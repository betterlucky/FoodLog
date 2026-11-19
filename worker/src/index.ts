import { z } from "zod";
import type { ZodType } from "zod";
import {
  CONTRACT_VERSION,
  estimateOutputSchema,
  estimateRequestSchema,
  labelOutputSchema,
  labelRequestSchema,
} from "./contract.ts";
import type { EstimateOutput, LabelOutput } from "./contract.ts";
import { ESTIMATE_SYSTEM_PROMPT, LABEL_SYSTEM_PROMPT, LABEL_USER_TEXT, buildEstimateUserText } from "./prompts.ts";
import type { Env } from "./providers/types.ts";
import { defaultResolver, parseRoute, runRoute } from "./router.ts";
import type { AdapterResolver, RouteResult } from "./router.ts";

export type { Env } from "./providers/types.ts";

const MAX_BODY_BYTES = 8 * 1024 * 1024;

export interface Deps {
  resolve?: AdapterResolver;
  log?: (line: string) => void;
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function errorResponse(status: number, code: string, message: string, extra: object = {}): Response {
  return json(status, { error: { code, message, ...extra } });
}

async function sha256(value: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
}

/** Constant-time comparison: hash both sides to equal length, then XOR-accumulate. */
async function tokenMatches(provided: string, expected: string): Promise<boolean> {
  const [a, b] = await Promise.all([sha256(provided), sha256(expected)]);
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i]! ^ b[i]!;
  return diff === 0;
}

async function authorised(request: Request, env: Env): Promise<boolean> {
  const expected = env.APP_TOKEN;
  const header = request.headers.get("authorization") ?? "";
  const match = /^Bearer (.+)$/.exec(header);
  // Always run the comparison so timing doesn't reveal whether a token was supplied.
  const ok = await tokenMatches(match?.[1] ?? "", expected ?? "");
  return Boolean(expected) && Boolean(match) && ok;
}

function issueSummary(error: z.ZodError): string {
  return error.issues
    .slice(0, 5)
    .map((i) => `${i.path.join(".") || "(body)"}: ${i.message}`)
    .join("; ");
}

async function readBody(request: Request): Promise<{ ok: true; text: string } | { ok: false; response: Response }> {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > MAX_BODY_BYTES) {
    return { ok: false, response: errorResponse(413, "payload_too_large", "request body exceeds 8 MB") };
  }
  // Count bytes as they arrive, so a body without (or with a false) content-length can't be
  // buffered past the limit before it is rejected.
  const chunks: Uint8Array[] = [];
  let total = 0;
  if (request.body) {
    const reader = request.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_BODY_BYTES) {
        await reader.cancel().catch(() => {});
        return { ok: false, response: errorResponse(413, "payload_too_large", "request body exceeds 8 MB") };
      }
      chunks.push(value);
    }
  }
  const buf = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    buf.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return { ok: true, text: new TextDecoder().decode(buf) };
}

function routesFor(env: Env, resolve: AdapterResolver) {
  const estimate = parseRoute(env.ESTIMATE_ROUTE, resolve);
  const label = parseRoute(env.LABEL_ROUTE, resolve);
  return { estimate, label };
}

export async function handleRequest(request: Request, env: Env, deps: Deps = {}): Promise<Response> {
  const resolve = deps.resolve ?? defaultResolver();
  const log = deps.log ?? ((line: string) => console.log(line));
  const url = new URL(request.url);
  const path = url.pathname;

  const known: Record<string, string> = {
    "/v1/health": "GET",
    "/v1/estimate": "POST",
    "/v1/label": "POST",
  };
  const method = known[path];
  if (!method) return errorResponse(404, "not_found", "unknown path");
  if (request.method !== method) {
    const res = errorResponse(405, "method_not_allowed", `use ${method}`);
    res.headers.set("allow", method);
    return res;
  }

  if (!(await authorised(request, env))) {
    return errorResponse(401, "unauthorized", "missing or invalid bearer token");
  }

  const routes = routesFor(env, resolve);

  if (path === "/v1/health") {
    return json(200, {
      contractVersion: CONTRACT_VERSION,
      routes: {
        estimate: routes.estimate.entries.map((e) => `${e.provider}:${e.model}`),
        label: routes.label.entries.map((e) => `${e.provider}:${e.model}`),
      },
      configErrors: [
        ...routes.estimate.errors.map((e) => `ESTIMATE_ROUTE: ${e}`),
        ...routes.label.errors.map((e) => `LABEL_ROUTE: ${e}`),
      ],
    });
  }

  const body = await readBody(request);
  if (!body.ok) return body.response;
  let parsedJson: unknown;
  try {
    parsedJson = JSON.parse(body.text);
  } catch {
    return errorResponse(400, "invalid_request", "body is not valid JSON");
  }

  const task = path === "/v1/estimate" ? "estimate" : "label";
  const compare = env.COMPARE === "1";

  if (task === "estimate") {
    const req = estimateRequestSchema.safeParse(parsedJson);
    if (!req.success) return errorResponse(400, "invalid_request", issueSummary(req.error));
    const result = await runRoute<EstimateOutput>({
      entries: routes.estimate.entries,
      request: {
        system: ESTIMATE_SYSTEM_PROMPT,
        text: buildEstimateUserText(req.data),
        image: req.data.image ?? undefined,
        schema: estimateOutputSchema as ZodType<EstimateOutput>,
        schemaName: "foodlog_estimate",
      },
      env,
      resolve,
      compare,
      kcalOf: (d) => d.items.reduce((sum, i) => sum + i.kcal, 0),
      log,
    });
    return respond(task, result, log);
  }

  const req = labelRequestSchema.safeParse(parsedJson);
  if (!req.success) return errorResponse(400, "invalid_request", issueSummary(req.error));
  const result = await runRoute<LabelOutput>({
    entries: routes.label.entries,
    request: {
      system: LABEL_SYSTEM_PROMPT,
      text: LABEL_USER_TEXT,
      image: req.data.image,
      schema: labelOutputSchema as ZodType<LabelOutput>,
      schemaName: "foodlog_label",
    },
    env,
    resolve,
    compare,
    kcalOf: (d) => d.per100g?.kcal ?? d.per100ml?.kcal ?? d.perServing?.kcal ?? null,
    log,
  });
  return respond(task, result, log);
}

function respond<T extends object>(task: string, result: RouteResult<T>, log: (line: string) => void): Response {
  if (result.ok) {
    const p = result.provenance;
    log(`task=${task} outcome=ok provider=${p.provider} model=${p.model} latencyMs=${p.latencyMs}`);
    return json(200, { contractVersion: CONTRACT_VERSION, ...result.data, provenance: p });
  }
  log(`task=${task} outcome=failed attempts=${result.attempts.length}`);
  for (const a of result.attempts) {
    log(`task=${task} attempt provider=${a.provider} model=${a.model} reason=${a.reason}`);
  }
  const message = result.attempts.length
    ? "all providers failed or returned invalid output"
    : "no usable providers configured for this task";
  return errorResponse(502, "estimate_failed", message, { attempts: result.attempts });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await handleRequest(request, env);
    } catch {
      // Never leak internals or request content.
      return errorResponse(500, "internal_error", "unexpected error");
    }
  },
};
