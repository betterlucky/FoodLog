import assert from "node:assert/strict";
import { test } from "node:test";
import { estimateOutputSchema } from "../src/contract.ts";
import { compatAdapterFor } from "../src/providers/openai-compatible.ts";
import { AdapterError } from "../src/providers/types.ts";
import type { CompletionRequest } from "../src/providers/types.ts";
import { estimateModelOutput, tinyJpegBase64 } from "./fixtures.ts";

const request: CompletionRequest = {
  system: "system prompt",
  text: "user text",
  image: { mediaType: "image/jpeg", data: tinyJpegBase64 },
  schema: estimateOutputSchema,
  schemaName: "foodlog_estimate",
};

function completion(over: object = {}, finish = "stop") {
  return {
    choices: [{ finish_reason: finish, message: { role: "assistant", content: JSON.stringify(estimateModelOutput), ...over } }],
  };
}

function recorder(status: number, body: unknown) {
  const seen: { url: string; headers: Headers; body: any }[] = [];
  const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit) => {
    seen.push({ url: String(input), headers: new Headers(init?.headers as HeadersInit), body: JSON.parse(String(init?.body)) });
    return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
  };
  return { seen, fetchImpl };
}

test("openai: request shape and parsing", async () => {
  const { seen, fetchImpl } = recorder(200, completion());
  const adapter = compatAdapterFor("openai", fetchImpl)!;
  const result = await adapter.complete(request, "gpt-x", { OPENAI_API_KEY: "test-key" });
  assert.deepEqual(result, estimateModelOutput);

  const { url, headers, body } = seen[0]!;
  assert.equal(url, "https://api.openai.com/v1/chat/completions");
  assert.equal(headers.get("authorization"), "Bearer test-key");
  assert.equal(body.model, "gpt-x");
  assert.deepEqual(body.messages[0], { role: "system", content: "system prompt" });
  assert.equal(body.messages[1].role, "user");
  assert.deepEqual(body.messages[1].content[0], { type: "text", text: "user text" });
  assert.deepEqual(body.messages[1].content[1], {
    type: "image_url",
    image_url: { url: `data:image/jpeg;base64,${tinyJpegBase64}` },
  });
  assert.equal(body.response_format.type, "json_schema");
  assert.equal(body.response_format.json_schema.name, "foodlog_estimate");
  assert.equal(body.response_format.json_schema.strict, true);
  assert.equal(body.response_format.json_schema.schema.type, "object");
  assert.ok(body.response_format.json_schema.schema.properties.items);
});

test("openai: OPENAI_BASE_URL override, trailing slash trimmed", async () => {
  const { seen, fetchImpl } = recorder(200, completion());
  await compatAdapterFor("openai", fetchImpl)!.complete(request, "m", {
    OPENAI_API_KEY: "k",
    OPENAI_BASE_URL: "https://proxy.example/v1/",
  });
  assert.equal(seen[0]!.url, "https://proxy.example/v1/chat/completions");
});

test("compat-<name> reads COMPAT_<NAME>_* and needs a base URL", async () => {
  const { seen, fetchImpl } = recorder(200, completion());
  const adapter = compatAdapterFor("compat-open-router", fetchImpl)!;
  assert.equal(adapter.name, "compat-open-router");
  await adapter.complete({ ...request, image: undefined }, "google/gemini:free", {
    COMPAT_OPEN_ROUTER_API_KEY: "or-key",
    COMPAT_OPEN_ROUTER_BASE_URL: "https://openrouter.ai/api/v1",
  });
  assert.equal(seen[0]!.url, "https://openrouter.ai/api/v1/chat/completions");
  assert.equal(seen[0]!.headers.get("authorization"), "Bearer or-key");
  assert.equal(seen[0]!.body.messages[1].content.length, 1);

  await assert.rejects(
    adapter.complete(request, "m", { COMPAT_OPEN_ROUTER_API_KEY: "k" }),
    (e: unknown) => e instanceof AdapterError && e.kind === "not_configured",
  );
  await assert.rejects(
    adapter.complete(request, "m", {}),
    (e: unknown) => e instanceof AdapterError && e.kind === "not_configured",
  );
});

test("unknown provider names resolve to nothing", () => {
  assert.equal(compatAdapterFor("mystery"), undefined);
  assert.equal(compatAdapterFor("compat-"), undefined);
});

test("error statuses map to typed errors", async () => {
  const cases: [number, string][] = [[429, "rate_limited"], [401, "auth_failed"], [403, "auth_failed"], [500, "http_error"]];
  for (const [status, kind] of cases) {
    const { fetchImpl } = recorder(status, { error: { message: "echo user text sk-secret" } });
    await assert.rejects(
      compatAdapterFor("openai", fetchImpl)!.complete(request, "m", { OPENAI_API_KEY: "k" }),
      (e: unknown) => e instanceof AdapterError && e.kind === kind && !e.reason.includes("secret") && !e.reason.includes("user text"),
    );
  }
});

test("provider status codes are kept, messages are not", async () => {
  const { fetchImpl } = recorder(503, [{ error: { code: 503, status: "UNAVAILABLE", message: "overloaded: user text" } }]);
  await assert.rejects(
    compatAdapterFor("openai", fetchImpl)!.complete(request, "m", { OPENAI_API_KEY: "k" }),
    (e: unknown) => e instanceof AdapterError && e.reason === "http_503 UNAVAILABLE",
  );
  const spaced = recorder(400, { error: { type: "has spaces user text", message: "x" } });
  await assert.rejects(
    compatAdapterFor("openai", spaced.fetchImpl)!.complete(request, "m", { OPENAI_API_KEY: "k" }),
    (e: unknown) => e instanceof AdapterError && e.reason === "http_400",
  );
});

test("refusal, truncation and bad content", async () => {
  const run = (body: unknown) =>
    compatAdapterFor("openai", recorder(200, body).fetchImpl)!.complete(request, "m", { OPENAI_API_KEY: "k" });
  await assert.rejects(run(completion({ content: null, refusal: "no" })), (e: unknown) => e instanceof AdapterError && e.kind === "refusal");
  await assert.rejects(run(completion({}, "length")), (e: unknown) => e instanceof AdapterError && e.kind === "max_tokens");
  await assert.rejects(run(completion({ content: "nope" })), (e: unknown) => e instanceof AdapterError && e.kind === "bad_response");
  await assert.rejects(run({ choices: [] }), (e: unknown) => e instanceof AdapterError && e.kind === "bad_response");
});

test("network failure maps to network error", async () => {
  const adapter = compatAdapterFor("openai", async () => {
    throw new TypeError("fetch failed: secret-host");
  })!;
  await assert.rejects(
    adapter.complete(request, "m", { OPENAI_API_KEY: "k" }),
    (e: unknown) => e instanceof AdapterError && e.kind === "network" && !e.reason.includes("secret"),
  );
});
