import assert from "node:assert/strict";
import { test } from "node:test";
import { handleRequest } from "../src/index.ts";
import { AdapterError } from "../src/providers/types.ts";
import type { CompletionRequest, Env, ProviderAdapter } from "../src/providers/types.ts";
import type { AdapterResolver } from "../src/router.ts";
import { estimateModelOutput, labelModelOutput, tinyJpegBase64 } from "./fixtures.ts";

const TOKEN = "app-token-for-tests";
const env: Env = {
  APP_TOKEN: TOKEN,
  ANTHROPIC_API_KEY: "sk-ant-should-never-appear",
  ESTIMATE_ROUTE: "fake:model-a,nope:model-z",
  LABEL_ROUTE: "fake:model-a",
};

const seenRequests: CompletionRequest[] = [];
const fake: ProviderAdapter = {
  name: "fake",
  async complete(req) {
    seenRequests.push(req);
    return req.schemaName === "foodlog_label" ? labelModelOutput : estimateModelOutput;
  },
};
const resolve: AdapterResolver = (p) => (p === "fake" ? fake : undefined);

async function readJson(res: Response): Promise<any> {
  return JSON.parse(await res.text());
}

function call(method: string, path: string, opts: { token?: string | null; body?: unknown; raw?: string; env?: Env; headers?: Record<string, string>; resolve?: AdapterResolver; log?: (l: string) => void } = {}) {
  const headers: Record<string, string> = { ...(opts.headers ?? {}) };
  if (opts.token !== null) headers.authorization = `Bearer ${opts.token ?? TOKEN}`;
  const body = method === "GET" ? undefined : (opts.raw ?? (opts.body !== undefined ? JSON.stringify(opts.body) : undefined));
  return handleRequest(
    new Request(`https://w.example${path}`, { method, headers, body }),
    opts.env ?? env,
    { resolve: opts.resolve ?? resolve, log: opts.log ?? (() => {}) },
  );
}

test("401 without or with wrong token, on every endpoint", async () => {
  for (const [m, p] of [["GET", "/v1/health"], ["POST", "/v1/estimate"], ["POST", "/v1/label"]] as const) {
    assert.equal((await call(m, p, { token: null, body: {} })).status, 401);
    assert.equal((await call(m, p, { token: "wrong", body: {} })).status, 401);
    assert.equal((await call(m, p, { token: TOKEN + "x", body: {} })).status, 401);
  }
  // No APP_TOKEN configured: nothing authorises, not even an empty bearer.
  assert.equal((await call("GET", "/v1/health", { env: { ...env, APP_TOKEN: undefined }, token: "" })).status, 401);
  assert.equal((await call("GET", "/v1/health", { headers: { authorization: "Basic abc" }, token: null })).status, 401);
});

test("404 and 405", async () => {
  assert.equal((await call("GET", "/nope")).status, 404);
  const res = await call("GET", "/v1/estimate");
  assert.equal(res.status, 405);
  assert.equal((await call("POST", "/v1/health", { body: {} })).status, 405);
});

test("health lists valid routes, config errors, and no secrets", async () => {
  const res = await call("GET", "/v1/health");
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type") ?? "", /application\/json/);
  const text = await res.text();
  const body = JSON.parse(text) as any;
  assert.equal(body.contractVersion, 1);
  assert.deepEqual(body.routes, { estimate: ["fake:model-a"], label: ["fake:model-a"] });
  assert.equal(body.configErrors.length, 1);
  assert.match(body.configErrors[0], /unknown provider "nope"/);
  assert.ok(!text.includes(TOKEN));
  assert.ok(!text.includes("sk-ant"));
});

test("400 on invalid JSON and invalid requests", async () => {
  const bad = await call("POST", "/v1/estimate", { raw: "{not json" });
  assert.equal(bad.status, 400);
  assert.equal((await readJson(bad)).error.code, "invalid_request");

  const neither = await call("POST", "/v1/estimate", { body: { contractVersion: 1 } });
  assert.equal(neither.status, 400);
  assert.match((await readJson(neither)).error.message, /text or image/);

  const long = await call("POST", "/v1/estimate", { body: { contractVersion: 1, text: "x".repeat(2001) } });
  assert.equal(long.status, 400);

  const wrongVersion = await call("POST", "/v1/estimate", { body: { contractVersion: 2, text: "tea" } });
  assert.equal(wrongVersion.status, 400);

  const noImage = await call("POST", "/v1/label", { body: { contractVersion: 1 } });
  assert.equal(noImage.status, 400);
});

test("413 by content-length and by actual size", async () => {
  const byHeader = await call("POST", "/v1/estimate", {
    raw: "{}",
    headers: { "content-length": String(9 * 1024 * 1024) },
  }).catch(() => null);
  // Some runtimes refuse to construct a Request with a lying content-length; accept either.
  if (byHeader) assert.equal(byHeader.status, 413);

  const huge = JSON.stringify({ contractVersion: 1, text: "tea", pad: "x".repeat(8 * 1024 * 1024 + 10) });
  const res = await call("POST", "/v1/estimate", { raw: huge });
  assert.equal(res.status, 413);
});

test("estimate happy path end to end", async () => {
  seenRequests.length = 0;
  const lines: string[] = [];
  const res = await call("POST", "/v1/estimate", {
    log: (l) => lines.push(l),
    body: {
      contractVersion: 1,
      text: "homemade chilli, about a bowl",
      image: { mediaType: "image/jpeg", data: tinyJpegBase64 },
      hints: { portion: "1 bowl", batchWeightGrams: 1800, servings: 6 },
    },
  });
  assert.equal(res.status, 200);
  const body = await readJson(res);
  assert.equal(body.contractVersion, 1);
  assert.deepEqual(body.items, estimateModelOutput.items);
  assert.deepEqual(body.per100g, estimateModelOutput.per100g);
  assert.equal(body.provenance.provider, "fake");
  assert.equal(body.provenance.model, "model-a");
  assert.equal(typeof body.provenance.latencyMs, "number");

  const sent = seenRequests[0]!;
  assert.match(sent.text, /homemade chilli/);
  assert.match(sent.text, /1800 g/);
  assert.match(sent.text, /Servings hint: 6/);
  assert.equal(sent.image?.mediaType, "image/jpeg");

  const logs = lines.join("\n");
  assert.match(logs, /outcome=ok provider=fake model=model-a/);
  assert.ok(!logs.includes("chilli"));
  assert.ok(!logs.includes(tinyJpegBase64));
});

test("label happy path, image only", async () => {
  const res = await call("POST", "/v1/label", {
    body: { contractVersion: 1, image: { mediaType: "image/png", data: tinyJpegBase64 } },
  });
  assert.equal(res.status, 200);
  const body = await readJson(res);
  assert.equal(body.productName, "Oat granola");
  assert.equal(body.per100g.kcal, 420);
  assert.equal(body.provenance.provider, "fake");
});

test("image-only estimate gets a default prompt text", async () => {
  seenRequests.length = 0;
  const res = await call("POST", "/v1/estimate", {
    body: { contractVersion: 1, image: { mediaType: "image/jpeg", data: tinyJpegBase64 } },
  });
  assert.equal(res.status, 200);
  assert.match(seenRequests[0]!.text, /photo/);
});

test("all providers failing gives 502 with attempts and no body or secrets", async () => {
  const failing: ProviderAdapter = {
    name: "fake",
    async complete() {
      throw new AdapterError("rate_limited", "http_429 rate limited");
    },
  };
  const res = await call("POST", "/v1/estimate", {
    resolve: (p) => (p === "fake" ? failing : undefined),
    body: { contractVersion: 1, text: "very private text" },
  });
  assert.equal(res.status, 502);
  const text = await res.text();
  const body = JSON.parse(text) as any;
  assert.equal(body.error.code, "estimate_failed");
  assert.deepEqual(body.error.attempts, [{ provider: "fake", model: "model-a", reason: "http_429 rate limited" }]);
  assert.ok(!text.includes("very private text"));
  assert.ok(!text.includes("sk-ant"));
});

test("no usable routes gives 502 with empty attempts", async () => {
  const res = await call("POST", "/v1/label", {
    env: { ...env, LABEL_ROUTE: "nope:x" },
    body: { contractVersion: 1, image: { mediaType: "image/png", data: tinyJpegBase64 } },
  });
  assert.equal(res.status, 502);
  assert.deepEqual((await readJson(res)).error.attempts, []);
});
