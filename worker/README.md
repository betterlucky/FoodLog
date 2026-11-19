# FoodLog AI Worker

Cloudflare Worker implementing `docs/ai-contract.md` (v1): `GET /v1/health`, `POST /v1/estimate`, `POST /v1/label`. The app talks only to this Worker; providers are pluggable adapters chosen by config. The Worker stores nothing and logs only provider, model, latency and outcome.

## Setup

```sh
cd worker
npm install
npx wrangler@4.165.0 login          # once, yourself
for s in APP_TOKEN ANTHROPIC_API_KEY OPENAI_API_KEY; do npx wrangler@4.165.0 secret put $s; done
npm run deploy
```

`APP_TOKEN` is the bearer token the app sends; rotate it to revoke. Secrets never go in `wrangler.toml`. For local dev copy the same names into `worker/.dev.vars` (gitignored) and run `npx wrangler@4.165.0 dev`.

## Routes

`ESTIMATE_ROUTE` and `LABEL_ROUTE` (in `wrangler.toml` `[vars]`) are comma-separated `provider:model` lists, tried in order. A provider that errors, refuses, truncates or returns output that fails the contract schema falls through to the next. If all fail the Worker returns `502` with `attempts[]` (provider, model, short reason). Unknown providers are skipped and listed under `configErrors` in `/v1/health`.

```
ESTIMATE_ROUTE = "anthropic:claude-opus-5-5,openai:gpt-5.1"
```

Set `COMPARE = "1"` to call every entry on each request, return the first valid one, and log one comparison line per entry (provider, model, latency, kcal). Evaluation only: it multiplies cost.

## Providers

| Route name | Secrets / vars | Notes |
| --- | --- | --- |
| `anthropic:<model>` | `ANTHROPIC_API_KEY` | Official SDK, structured outputs, server-side fallback beta. |
| `openai:<model>` | `OPENAI_API_KEY`, optional `OPENAI_BASE_URL` (default `https://api.openai.com/v1`) | OpenAI-compatible adapter. |
| `compat-<name>:<model>` | `COMPAT_<NAME>_API_KEY`, `COMPAT_<NAME>_BASE_URL` | Same adapter, any endpoint. `<NAME>` is upper-cased, non-alphanumerics become `_`. |

Point a route at other services with the compat adapter (the model must support vision and `json_schema` structured output for `/v1/label` and photo estimates):

- OpenRouter: route `compat-openrouter:<vendor/model>`, `COMPAT_OPENROUTER_BASE_URL=https://openrouter.ai/api/v1`, `COMPAT_OPENROUTER_API_KEY=<key>`.
- Gemini: route `compat-gemini:<model>`, base URL `https://generativelanguage.googleapis.com/v1beta/openai`.
- Local server (llama.cpp, Ollama, LM Studio): route `compat-local:<model>`, base URL of the server's `/v1`. It must be reachable from Cloudflare (e.g. via a tunnel); the key can be any non-empty string. Base URLs can be plain vars in `wrangler.toml`; keys must be secrets.

Model ids may contain colons (`compat-openrouter:vendor/model:free`); only the first colon splits provider from model.

## Adding a provider adapter

1. Create `src/providers/<name>.ts` exporting a `ProviderAdapter` (`src/providers/types.ts`): `complete({system, text, image?, schema, schemaName}, model, env)` returns the parsed JSON the model produced. Throw `AdapterError(kind, shortReason)` for failures; keep reasons free of keys, bodies and request content. Do not validate: the router validates with the contract's zod schema.
2. Register it in `defaultResolver` in `src/router.ts`.
3. Add a test with an injected `fetch` next to `test/anthropic.test.ts`.

## Tests

```sh
npm test            # node:test, no network, no keys
npm run typecheck
```

## Example

```sh
curl -s https://foodlog-ai.<your-subdomain>.workers.dev/v1/estimate \
  -H "authorization: Bearer $APP_TOKEN" -H "content-type: application/json" \
  -d '{"contractVersion":1,"text":"homemade chilli, about a bowl","hints":{"portion":"1 bowl"}}'

curl -s https://foodlog-ai.<your-subdomain>.workers.dev/v1/health -H "authorization: Bearer $APP_TOKEN"
```
