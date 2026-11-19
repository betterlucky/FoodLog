import assert from "node:assert/strict";
import { test } from "node:test";
import { estimateOutputSchema } from "../src/contract.ts";
import { AdapterError } from "../src/providers/types.ts";
import type { Env, ProviderAdapter } from "../src/providers/types.ts";
import { parseRoute, runRoute } from "../src/router.ts";
import type { AdapterResolver } from "../src/router.ts";
import { estimateModelOutput } from "./fixtures.ts";

const SECRET = "sk-super-secret";
const BODY_TEXT = "my private lunch";

function adapter(name: string, impl: () => Promise<unknown>): ProviderAdapter {
  return { name, complete: impl };
}

function resolverOf(adapters: ProviderAdapter[]): AdapterResolver {
  return (p) => adapters.find((a) => a.name === p);
}

const env: Env = { ANTHROPIC_API_KEY: SECRET };
const request = {
  system: "sys",
  text: BODY_TEXT,
  schema: estimateOutputSchema,
  schemaName: "x",
};

test("uses first provider that succeeds, with provenance", async () => {
  const calls: string[] = [];
  const a = adapter("a", async () => {
    calls.push("a");
    return estimateModelOutput;
  });
  const b = adapter("b", async () => {
    calls.push("b");
    return estimateModelOutput;
  });
  const res = await runRoute({
    entries: parseRoute("a:m1,b:m2", resolverOf([a, b])).entries,
    request,
    env,
    resolve: resolverOf([a, b]),
  });
  assert.equal(res.ok, true);
  assert.deepEqual(calls, ["a"]);
  if (res.ok) {
    assert.equal(res.provenance.provider, "a");
    assert.equal(res.provenance.model, "m1");
    assert.equal(typeof res.provenance.latencyMs, "number");
  }
});

test("falls through on adapter error and on invalid output", async () => {
  const a = adapter("a", async () => {
    throw new AdapterError("rate_limited", "http_429 rate limited");
  });
  const b = adapter("b", async () => ({ items: [{ name: BODY_TEXT, kcal: 99999 }] }));
  const c = adapter("c", async () => estimateModelOutput);
  const resolve = resolverOf([a, b, c]);
  const res = await runRoute({ entries: parseRoute("a:m1,b:m2,c:m3", resolve).entries, request, env, resolve });
  assert.equal(res.ok, true);
  if (res.ok) {
    assert.equal(res.provenance.provider, "c");
    assert.equal(res.attempts.length, 2);
    assert.equal(res.attempts[0]!.reason, "http_429 rate limited");
    assert.match(res.attempts[1]!.reason, /^invalid_output/);
  }
});

test("all failing gives ok:false with attempts free of secrets and body", async () => {
  const a = adapter("a", async () => {
    throw new Error(`boom ${SECRET} ${BODY_TEXT}`); // non-AdapterError must not leak its message
  });
  const b = adapter("b", async () => ({ items: [{ name: BODY_TEXT, kcal: -5 }] }));
  const resolve = resolverOf([a, b]);
  const res = await runRoute({ entries: parseRoute("a:m1,b:m2", resolve).entries, request, env, resolve });
  assert.equal(res.ok, false);
  assert.equal(res.attempts.length, 2);
  const dump = JSON.stringify(res.attempts);
  assert.ok(!dump.includes(SECRET));
  assert.ok(!dump.includes(BODY_TEXT));
  assert.deepEqual(
    res.attempts.map((x) => [x.provider, x.model]),
    [["a", "m1"], ["b", "m2"]],
  );
});

test("unknown providers and malformed entries are skipped and reported", () => {
  const a = adapter("a", async () => estimateModelOutput);
  const parsed = parseRoute("nope:m, a:m1 ,broken,a:", resolverOf([a]));
  assert.deepEqual(parsed.entries, [{ provider: "a", model: "m1" }]);
  assert.equal(parsed.errors.length, 3);
  assert.match(parsed.errors[0]!, /unknown provider "nope"/);
});

test("model ids containing colons keep everything after the first colon", () => {
  const a = adapter("a", async () => null);
  assert.deepEqual(parseRoute("a:org/model:free", resolverOf([a])).entries, [{ provider: "a", model: "org/model:free" }]);
});

test("COMPARE calls every entry, returns first valid in route order, logs one line each", async () => {
  const lines: string[] = [];
  const a = adapter("a", async () => {
    throw new AdapterError("timeout", "request timed out");
  });
  const b = adapter("b", async () => estimateModelOutput);
  const c = adapter("c", async () => estimateModelOutput);
  const resolve = resolverOf([a, b, c]);
  const res = await runRoute({
    entries: parseRoute("a:m1,b:m2,c:m3", resolve).entries,
    request,
    env,
    resolve,
    compare: true,
    kcalOf: (d) => d.items.reduce((s, i) => s + i.kcal, 0),
    log: (l) => lines.push(l),
  });
  assert.equal(res.ok, true);
  if (res.ok) assert.equal(res.provenance.provider, "b");
  assert.equal(lines.length, 3);
  assert.match(lines[0]!, /provider=a .*failed=request timed out/);
  assert.match(lines[1]!, /provider=b model=m2 latencyMs=\d+ kcal=420/);
  assert.ok(lines.every((l) => !l.includes(BODY_TEXT) && !l.includes(SECRET)));
});

test("no entries gives ok:false", async () => {
  const res = await runRoute({ entries: [], request, env, resolve: resolverOf([]) });
  assert.equal(res.ok, false);
});
