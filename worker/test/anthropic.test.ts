import assert from "node:assert/strict";
import { test } from "node:test";
import { estimateOutputSchema } from "../src/contract.ts";
import { createAnthropicAdapter } from "../src/providers/anthropic.ts";
import { AdapterError } from "../src/providers/types.ts";
import type { CompletionRequest } from "../src/providers/types.ts";
import { estimateModelOutput, tinyJpegBase64 } from "./fixtures.ts";

const env = { ANTHROPIC_API_KEY: "test-key-not-real" };
const request: CompletionRequest = {
  system: "system prompt",
  text: "user text",
  image: { mediaType: "image/jpeg", data: tinyJpegBase64 },
  schema: estimateOutputSchema,
  schemaName: "foodlog_estimate",
};

function messageResponse(over: object = {}) {
  return {
    id: "msg_test",
    type: "message",
    role: "assistant",
    model: "claude-opus-5-5",
    content: [{ type: "text", text: JSON.stringify(estimateModelOutput) }],
    stop_reason: "end_turn",
    stop_sequence: null,
    usage: { input_tokens: 10, output_tokens: 20 },
    ...over,
  };
}

function recorder(status: number, body: unknown, headers: Record<string, string> = {}) {
  const seen: { url: string; headers: Headers; body: any }[] = [];
  const fetchImpl = async (input: RequestInfo | URL, init?: RequestInit) => {
    seen.push({
      url: String(input),
      headers: new Headers(init?.headers as HeadersInit),
      body: JSON.parse(String(init?.body)),
    });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "content-type": "application/json", ...headers },
    });
  };
  return { seen, fetchImpl };
}

test("sends the expected request and parses the JSON text block", async () => {
  const { seen, fetchImpl } = recorder(200, messageResponse());
  const result = await createAnthropicAdapter({ fetch: fetchImpl }).complete(request, "claude-opus-5-5", env);
  assert.deepEqual(result, estimateModelOutput);

  assert.equal(seen.length, 1);
  const { url, headers, body } = seen[0]!;
  assert.match(url, /\/v1\/messages/);
  assert.equal(headers.get("authorization"), "Bearer test-key-not-real");
  assert.match(headers.get("anthropic-beta") ?? "", /server-side-fallback-2026-07-01/);
  assert.equal(body.model, "claude-opus-5-5");
  assert.equal(body.max_tokens, 4096);
  assert.equal(body.fallbacks, "default");
  assert.equal(body.system, "system prompt");
  assert.equal(body.messages.length, 1);
  assert.equal(body.messages[0].role, "user");
  const content = body.messages[0].content;
  assert.equal(content[0].type, "image");
  assert.deepEqual(content[0].source, { type: "base64", media_type: "image/jpeg", data: tinyJpegBase64 });
  assert.equal(content[1].type, "text");
  assert.equal(content[1].text, "user text");
  assert.equal(body.output_config.format.type, "json_schema");
  assert.ok(body.output_config.format.schema);
  for (const forbidden of ["temperature", "top_p", "top_k", "thinking"]) {
    assert.ok(!(forbidden in body), `${forbidden} must not be sent`);
  }
  // No assistant prefill.
  assert.ok(body.messages.every((m: any) => m.role === "user"));
});

test("text-only request has just the text block", async () => {
  const { seen, fetchImpl } = recorder(200, messageResponse());
  await createAnthropicAdapter({ fetch: fetchImpl }).complete({ ...request, image: undefined }, "m", env);
  assert.deepEqual(seen[0]!.body.messages[0].content, [{ type: "text", text: "user text" }]);
});

test("refusal and max_tokens stop reasons throw typed errors", async () => {
  for (const [stop, kind] of [["refusal", "refusal"], ["max_tokens", "max_tokens"]] as const) {
    const { fetchImpl } = recorder(200, messageResponse({ stop_reason: stop }));
    await assert.rejects(
      createAnthropicAdapter({ fetch: fetchImpl }).complete(request, "m", env),
      (err: unknown) => err instanceof AdapterError && err.kind === kind,
    );
  }
});

test("429 maps to rate_limited without leaking details", async () => {
  const { fetchImpl } = recorder(
    429,
    { type: "error", error: { type: "rate_limit_error", message: "slow down user text" } },
    { "retry-after": "0" },
  );
  await assert.rejects(
    createAnthropicAdapter({ fetch: fetchImpl }).complete(request, "m", env),
    (err: unknown) => err instanceof AdapterError && err.kind === "rate_limited" && !err.reason.includes("user text"),
  );
});

test("401 maps to auth_failed, 500 to http_error", async () => {
  const auth = recorder(401, { type: "error", error: { type: "authentication_error", message: "bad key" } });
  await assert.rejects(
    createAnthropicAdapter({ fetch: auth.fetchImpl }).complete(request, "m", env),
    (err: unknown) => err instanceof AdapterError && err.kind === "auth_failed",
  );
  const boom = recorder(500, { type: "error", error: { type: "api_error", message: "oops" } });
  await assert.rejects(
    createAnthropicAdapter({ fetch: boom.fetchImpl }).complete(request, "m", env),
    (err: unknown) => err instanceof AdapterError && err.kind === "http_error" && err.reason === "http_500",
  );
});

test("non-JSON text and missing key are bad_response / not_configured", async () => {
  const bad = recorder(200, messageResponse({ content: [{ type: "text", text: "not json" }] }));
  await assert.rejects(
    createAnthropicAdapter({ fetch: bad.fetchImpl }).complete(request, "m", env),
    (err: unknown) => err instanceof AdapterError && err.kind === "bad_response",
  );
  await assert.rejects(
    createAnthropicAdapter({ fetch: bad.fetchImpl }).complete(request, "m", {}),
    (err: unknown) => err instanceof AdapterError && err.kind === "not_configured",
  );
});
