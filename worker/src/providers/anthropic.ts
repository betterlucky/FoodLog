import Anthropic from "@anthropic-ai/sdk";
import { zodOutputFormat } from "@anthropic-ai/sdk/helpers/zod";
import type { z } from "zod";
import { AdapterError } from "./types.ts";
import type { CompletionRequest, Env, FetchLike, ProviderAdapter } from "./types.ts";

const FALLBACK_BETA = "server-side-fallback-2026-07-01";

export function createAnthropicAdapter(options: { fetch?: FetchLike } = {}): ProviderAdapter {
  return {
    name: "anthropic",
    async complete(request: CompletionRequest, model: string, env: Env, servedBy?: (model: string) => void): Promise<unknown> {
      const apiKey = env.ANTHROPIC_API_KEY;
      if (!apiKey) throw new AdapterError("not_configured", "ANTHROPIC_API_KEY not set");

      // The router falls back across providers, so the SDK does not retry on its own.
      const client = new Anthropic({
        apiKey,
        maxRetries: 0,
        timeout: 30_000,
        ...(options.fetch ? { fetch: options.fetch as unknown as typeof fetch } : {}),
      });

      const content: Anthropic.Beta.Messages.BetaContentBlockParam[] = [];
      if (request.image) {
        content.push({
          type: "image",
          source: {
            type: "base64",
            media_type: request.image.mediaType as "image/jpeg" | "image/png" | "image/webp",
            data: request.image.data,
          },
        });
      }
      content.push({ type: "text", text: request.text });

      let message: Anthropic.Beta.Messages.BetaMessage;
      try {
        message = await client.beta.messages.create({
          model,
          max_tokens: 4096,
          betas: [FALLBACK_BETA],
          fallbacks: "default",
          system: request.system,
          messages: [{ role: "user", content }],
          output_config: { format: zodOutputFormat(request.schema as z.ZodType) },
        });
      } catch (err) {
        throw mapSdkError(err);
      }

      if (typeof message.model === "string" && message.model) servedBy?.(message.model);
      if (message.stop_reason === "refusal") throw new AdapterError("refusal", "model refused");
      if (message.stop_reason === "max_tokens") {
        throw new AdapterError("max_tokens", "output truncated at max_tokens");
      }
      const block = message.content.find((b) => b.type === "text");
      if (!block || block.type !== "text") {
        throw new AdapterError("bad_response", "no text block in response");
      }
      try {
        return JSON.parse(block.text);
      } catch {
        throw new AdapterError("bad_response", "response text was not valid JSON");
      }
    },
  };
}

function mapSdkError(err: unknown): AdapterError {
  if (err instanceof AdapterError) return err;
  // Most specific classes first; they all extend Anthropic.APIError.
  if (err instanceof Anthropic.RateLimitError) return new AdapterError("rate_limited", "http_429 rate limited");
  if (err instanceof Anthropic.AuthenticationError) return new AdapterError("auth_failed", "http_401 authentication failed");
  if (err instanceof Anthropic.APIConnectionTimeoutError) return new AdapterError("timeout", "request timed out");
  if (err instanceof Anthropic.APIConnectionError) return new AdapterError("network", "connection error");
  if (err instanceof Anthropic.APIError) {
    return new AdapterError("http_error", `http_${err.status ?? "unknown"}`);
  }
  return new AdapterError("network", "unexpected adapter error");
}
