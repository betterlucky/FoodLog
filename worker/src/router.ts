import type { ZodType } from "zod";
import { z } from "zod";
import type { Provenance } from "./contract.ts";
import { createAnthropicAdapter } from "./providers/anthropic.ts";
import { compatAdapterFor } from "./providers/openai-compatible.ts";
import { AdapterError } from "./providers/types.ts";
import type { CompletionRequest, Env, FetchLike, ProviderAdapter } from "./providers/types.ts";

export interface Attempt {
  provider: string;
  model: string;
  reason: string;
}

export interface RouteEntry {
  provider: string;
  model: string;
}

export type AdapterResolver = (provider: string) => ProviderAdapter | undefined;

export function defaultResolver(fetchImpl?: FetchLike): AdapterResolver {
  return (provider) => {
    if (provider === "anthropic") return createAnthropicAdapter({ fetch: fetchImpl });
    return compatAdapterFor(provider, fetchImpl);
  };
}

export interface ParsedRoute {
  entries: RouteEntry[];
  errors: string[];
}

/** Parse `provider:model,provider:model`. Unknown providers and malformed entries become errors and are skipped. */
export function parseRoute(raw: string | undefined, resolve: AdapterResolver): ParsedRoute {
  const entries: RouteEntry[] = [];
  const errors: string[] = [];
  for (const part of (raw ?? "").split(",")) {
    const item = part.trim();
    if (!item) continue;
    const idx = item.indexOf(":");
    if (idx <= 0 || idx === item.length - 1) {
      errors.push(`malformed route entry "${item}" (expected provider:model)`);
      continue;
    }
    const provider = item.slice(0, idx);
    const model = item.slice(idx + 1);
    if (!resolve(provider)) {
      errors.push(`unknown provider "${provider}"`);
      continue;
    }
    entries.push({ provider, model });
  }
  return { entries, errors };
}

export type RouteResult<T> =
  | { ok: true; data: T; provenance: Provenance; attempts: Attempt[] }
  | { ok: false; attempts: Attempt[] };

export interface RouteOptions<T> {
  entries: RouteEntry[];
  request: CompletionRequest & { schema: ZodType<T> };
  env: Env;
  resolve: AdapterResolver;
  compare?: boolean;
  /** Extracts the kcal total for the COMPARE log line. */
  kcalOf?: (data: T) => number | null;
  log?: (line: string) => void;
}

function issueSummary(error: z.ZodError): string {
  // Paths and codes only: never echo values, which could contain request content.
  const paths = [...new Set(error.issues.map((i) => i.path.join(".") || "(root)"))].slice(0, 5);
  return `invalid_output at ${paths.join(", ")}`;
}

interface Outcome<T> {
  entry: RouteEntry;
  latencyMs: number;
  data?: T;
  reason?: string;
}

async function tryEntry<T>(entry: RouteEntry, opts: RouteOptions<T>): Promise<Outcome<T>> {
  const started = Date.now();
  const adapter = opts.resolve(entry.provider);
  if (!adapter) return { entry, latencyMs: 0, reason: "unknown_provider" };
  try {
    const raw = await adapter.complete(opts.request, entry.model, opts.env);
    const latencyMs = Date.now() - started;
    const parsed = opts.request.schema.safeParse(raw);
    if (!parsed.success) return { entry, latencyMs, reason: issueSummary(parsed.error) };
    return { entry, latencyMs, data: parsed.data };
  } catch (err) {
    const latencyMs = Date.now() - started;
    const reason = err instanceof AdapterError ? err.reason : "adapter_error";
    return { entry, latencyMs, reason };
  }
}

export async function runRoute<T>(opts: RouteOptions<T>): Promise<RouteResult<T>> {
  const log = opts.log ?? (() => {});
  const attempts: Attempt[] = [];

  const finish = (o: Outcome<T>): RouteResult<T> => ({
    ok: true,
    data: o.data as T,
    provenance: { provider: o.entry.provider, model: o.entry.model, latencyMs: o.latencyMs },
    attempts,
  });

  if (opts.compare) {
    const outcomes = await Promise.all(opts.entries.map((e) => tryEntry(e, opts)));
    for (const o of outcomes) {
      const kcal = o.data !== undefined && opts.kcalOf ? opts.kcalOf(o.data) : null;
      log(
        `compare provider=${o.entry.provider} model=${o.entry.model} latencyMs=${o.latencyMs} ` +
          (o.data !== undefined ? `kcal=${kcal ?? "n/a"}` : `failed=${o.reason}`),
      );
    }
    let winner: Outcome<T> | undefined;
    for (const o of outcomes) {
      if (o.data !== undefined) {
        winner ??= o;
      } else {
        attempts.push({ provider: o.entry.provider, model: o.entry.model, reason: o.reason ?? "failed" });
      }
    }
    return winner ? finish(winner) : { ok: false, attempts };
  }

  for (const entry of opts.entries) {
    const o = await tryEntry(entry, opts);
    if (o.data !== undefined) return finish(o);
    attempts.push({ provider: entry.provider, model: entry.model, reason: o.reason ?? "failed" });
  }
  return { ok: false, attempts };
}
