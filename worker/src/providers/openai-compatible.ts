import { z } from "zod";
import { AdapterError } from "./types.ts";
import type { CompletionRequest, Env, FetchLike, ProviderAdapter } from "./types.ts";

export interface CompatConfig {
  /** Adapter name as used in routes, e.g. "openai" or "compat-gemini". */
  name: string;
  /** Env var holding the API key. */
  apiKeyVar: string;
  /** Env var holding the base URL (optional; falls back to defaultBaseUrl). */
  baseUrlVar: string;
  defaultBaseUrl?: string;
  fetch?: FetchLike;
}

export function createOpenAICompatibleAdapter(config: CompatConfig): ProviderAdapter {
  return {
    name: config.name,
    async complete(request: CompletionRequest, model: string, env: Env): Promise<unknown> {
      const apiKey = env[config.apiKeyVar];
      if (!apiKey) throw new AdapterError("not_configured", `${config.apiKeyVar} not set`);
      const baseUrl = (env[config.baseUrlVar] || config.defaultBaseUrl || "").replace(/\/+$/, "");
      if (!baseUrl) throw new AdapterError("not_configured", `${config.baseUrlVar} not set`);

      const userContent: unknown[] = [{ type: "text", text: request.text }];
      if (request.image) {
        userContent.push({
          type: "image_url",
          image_url: { url: `data:${request.image.mediaType};base64,${request.image.data}` },
        });
      }

      const jsonSchema = z.toJSONSchema(request.schema) as Record<string, unknown>;
      delete jsonSchema.$schema;

      const body = {
        model,
        messages: [
          { role: "system", content: request.system },
          { role: "user", content: userContent },
        ],
        response_format: {
          type: "json_schema",
          json_schema: { name: request.schemaName, strict: true, schema: jsonSchema },
        },
      };

      const doFetch: FetchLike = config.fetch ?? ((input, init) => fetch(input, init));
      let response: Response;
      try {
        response = await doFetch(`${baseUrl}/chat/completions`, {
          method: "POST",
          headers: { "content-type": "application/json", authorization: `Bearer ${apiKey}` },
          body: JSON.stringify(body),
          signal: AbortSignal.timeout(45_000),
        });
      } catch (err) {
        if (err instanceof Error && (err.name === "TimeoutError" || err.name === "AbortError")) {
          throw new AdapterError("timeout", "request timed out");
        }
        throw new AdapterError("network", "connection error");
      }

      if (response.status === 429) throw new AdapterError("rate_limited", "http_429 rate limited");
      if (response.status === 401 || response.status === 403) {
        throw new AdapterError("auth_failed", `http_${response.status} authentication failed`);
      }
      if (!response.ok) {
        throw new AdapterError("http_error", `http_${response.status}${await providerErrorDetail(response)}`);
      }

      let payload: any;
      try {
        payload = await response.json();
      } catch {
        throw new AdapterError("bad_response", "response was not valid JSON");
      }
      const choice = payload?.choices?.[0];
      if (!choice) throw new AdapterError("bad_response", "no choices in response");
      if (choice.message?.refusal || choice.finish_reason === "content_filter") {
        throw new AdapterError("refusal", "model refused");
      }
      if (choice.finish_reason === "length") {
        throw new AdapterError("max_tokens", "output truncated at max tokens");
      }
      const text = choice.message?.content;
      if (typeof text !== "string") throw new AdapterError("bad_response", "no text content in response");
      try {
        return JSON.parse(text);
      } catch {
        throw new AdapterError("bad_response", "response text was not valid JSON");
      }
    },
  };
}

/**
 * The provider's error status code (e.g. "UNAVAILABLE", "INVALID_ARGUMENT"), so failed attempts are
 * diagnosable. Only a short identifier-like code is kept: provider messages can echo request content.
 */
async function providerErrorDetail(response: Response): Promise<string> {
  try {
    const payload: any = await response.json();
    const error = Array.isArray(payload) ? payload[0]?.error : payload?.error;
    const code = [error?.status, error?.type, error?.code].find(
      (value) => typeof value === "string" && /^[A-Za-z0-9_.-]{1,40}$/.test(value),
    );
    return code ? ` ${code}` : "";
  } catch {
    return "";
  }
}

/** `openai` -> OPENAI_*, `compat-<name>` -> COMPAT_<NAME>_* (name upper-cased, non-alphanumerics to `_`). */
export function compatAdapterFor(provider: string, fetchImpl?: FetchLike): ProviderAdapter | undefined {
  if (provider === "openai") {
    return createOpenAICompatibleAdapter({
      name: "openai",
      apiKeyVar: "OPENAI_API_KEY",
      baseUrlVar: "OPENAI_BASE_URL",
      defaultBaseUrl: "https://api.openai.com/v1",
      fetch: fetchImpl,
    });
  }
  const m = /^compat-([a-z0-9][a-z0-9_-]*)$/.exec(provider);
  if (m) {
    const key = m[1]!.toUpperCase().replace(/[^A-Z0-9]/g, "_");
    return createOpenAICompatibleAdapter({
      name: provider,
      apiKeyVar: `COMPAT_${key}_API_KEY`,
      baseUrlVar: `COMPAT_${key}_BASE_URL`,
      fetch: fetchImpl,
    });
  }
  return undefined;
}
