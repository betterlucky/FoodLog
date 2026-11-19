// FoodLog AI contract v1 (see docs/ai-contract.md). Schemas here are the single source of truth
// for request validation, model-output validation (shared by every adapter) and responses.
import { z } from "zod";

export const CONTRACT_VERSION = 1;

const confidence = z.enum(["HIGH", "MEDIUM", "LOW"]);

// ---- Requests -------------------------------------------------------------------------------

export const imageSchema = z.object({
  mediaType: z.enum(["image/jpeg", "image/png", "image/webp"]),
  // base64 without newlines
  data: z.string().min(1).regex(/^[A-Za-z0-9+/]+={0,2}$/, "must be base64 without newlines"),
});

export const hintsSchema = z.object({
  portion: z.string().min(1).max(200).nullish(),
  batchWeightGrams: z.number().gt(0).max(100000).nullish(),
  servings: z.number().gt(0).max(1000).nullish(),
});

export const estimateRequestSchema = z
  .object({
    contractVersion: z.literal(CONTRACT_VERSION),
    text: z.string().min(1).max(2000).nullish(),
    image: imageSchema.nullish(),
    hints: hintsSchema.nullish(),
  })
  .refine((v) => v.text != null || v.image != null, {
    message: "at least one of text or image is required",
    path: ["text"],
  });

export const labelRequestSchema = z.object({
  contractVersion: z.literal(CONTRACT_VERSION),
  image: imageSchema,
});

// ---- Model output (response minus contractVersion/provenance) --------------------------------
// Optional contract fields are `nullable` (required key, null = unknown) so the schema also works
// with strict structured-output modes that demand every key be present.

export const estimateItemSchema = z.object({
  name: z.string().min(1).max(120),
  amount: z.number().gt(0).nullable(),
  unit: z.string().max(24).nullable(),
  grams: z.number().gt(0).max(5000).nullable(),
  kcal: z.number().min(0).max(5000),
  proteinG: z.number().min(0).max(500).nullable(),
  fibreG: z.number().min(0).max(500).nullable(),
  confidence,
  notes: z.string().max(300).nullable(),
});

export const estimatePer100gSchema = z.object({
  kcal: z.number().min(0).max(1000),
  proteinG: z.number().min(0).max(100).nullable(),
  fibreG: z.number().min(0).max(100).nullable(),
});

export const estimateOutputSchema = z.object({
  items: z.array(estimateItemSchema).min(1).max(12),
  per100g: estimatePer100gSchema.nullable(),
  notes: z.string().max(300).nullable(),
});

const gramField = z.number().min(0).max(100).nullable();
const per100Schema = z.object({
  kcal: z.number().min(0).max(1000).nullable(),
  proteinG: gramField,
  fibreG: gramField,
  carbsG: gramField,
  fatG: gramField,
  sugarsG: gramField,
  saltG: gramField,
});

export const labelPerServingSchema = z.object({
  kcal: z.number().min(0).max(3000).nullable(),
  servingGrams: z.number().gt(0).max(5000).nullable(),
  servingAmount: z.number().gt(0).max(1000).nullable(),
  servingUnit: z.string().max(24).nullable(),
});

export const labelOutputSchema = z.object({
  productName: z.string().max(120).nullable(),
  per100g: per100Schema.nullable(),
  per100ml: per100Schema.nullable(),
  perServing: labelPerServingSchema.nullable(),
  packageGrams: z.number().gt(0).max(50000).nullable(),
  packageMilliliters: z.number().gt(0).max(50000).nullable(),
  packageItemCount: z.number().int().gt(0).max(1000).nullable(),
  packageItemUnit: z.string().max(24).nullable(),
  prepared: z.boolean(),
  confidence,
  notes: z.string().max(300).nullable(),
});

// ---- Responses ------------------------------------------------------------------------------

export const provenanceSchema = z.object({
  provider: z.string().min(1),
  model: z.string().min(1),
  latencyMs: z.number().int().min(0),
});

export const estimateResponseSchema = estimateOutputSchema.extend({
  contractVersion: z.literal(CONTRACT_VERSION),
  provenance: provenanceSchema,
});

export const labelResponseSchema = labelOutputSchema.extend({
  contractVersion: z.literal(CONTRACT_VERSION),
  provenance: provenanceSchema,
});

export type EstimateRequest = z.infer<typeof estimateRequestSchema>;
export type LabelRequest = z.infer<typeof labelRequestSchema>;
export type EstimateOutput = z.infer<typeof estimateOutputSchema>;
export type LabelOutput = z.infer<typeof labelOutputSchema>;
export type Provenance = z.infer<typeof provenanceSchema>;
