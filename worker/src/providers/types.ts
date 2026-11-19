import type { ZodType } from "zod";

/** Worker bindings. Named provider instances read extra keys, hence the index signature. */
export interface Env {
  APP_TOKEN?: string;
  ESTIMATE_ROUTE?: string;
  LABEL_ROUTE?: string;
  COMPARE?: string;
  ANTHROPIC_API_KEY?: string;
  OPENAI_API_KEY?: string;
  OPENAI_BASE_URL?: string;
  [key: string]: string | undefined;
}

export interface CompletionRequest {
  system: string;
  text: string;
  image?: { mediaType: string; data: string };
  schema: ZodType;
  schemaName: string;
}

export interface ProviderAdapter {
  name: string;
  /**
   * Returns parsed JSON from the model. The router validates it against the schema. Adapters call
   * `servedBy` with the model the provider says produced the answer (it can differ from the requested
   * model under provider-side fallback), so provenance names the real model.
   */
  complete(request: CompletionRequest, model: string, env: Env, servedBy?: (model: string) => void): Promise<unknown>;
}

export type AdapterErrorKind =
  | "refusal"
  | "max_tokens"
  | "rate_limited"
  | "auth_failed"
  | "timeout"
  | "network"
  | "http_error"
  | "bad_response"
  | "not_configured";

/**
 * Typed adapter failure. `reason` is short and safe to expose in `attempts[]`:
 * never put keys, request content or provider response bodies in it.
 */
export class AdapterError extends Error {
  kind: AdapterErrorKind;
  reason: string;
  constructor(kind: AdapterErrorKind, reason: string) {
    super(reason);
    this.name = "AdapterError";
    this.kind = kind;
    this.reason = reason;
  }
}

export type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
