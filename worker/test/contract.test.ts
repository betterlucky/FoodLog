import assert from "node:assert/strict";
import { test } from "node:test";
import {
  estimateOutputSchema,
  estimateRequestSchema,
  estimateResponseSchema,
  labelOutputSchema,
  labelRequestSchema,
  labelResponseSchema,
} from "../src/contract.ts";
import { estimateExample, estimateModelOutput, labelExample, labelModelOutput, tinyJpegBase64 } from "./fixtures.ts";

const provenance = { provider: "anthropic", model: "m", latencyMs: 2140 };

test("accepts the contract's estimate example", () => {
  assert.ok(estimateOutputSchema.safeParse(estimateModelOutput).success);
  assert.ok(estimateResponseSchema.safeParse({ ...estimateExample, provenance }).success);
});

test("accepts the contract's label example", () => {
  assert.ok(labelOutputSchema.safeParse(labelModelOutput).success);
  assert.ok(labelResponseSchema.safeParse({ ...labelExample, provenance }).success);
});

test("estimate output rejects out-of-range values", () => {
  const withItem = (patch: object) => ({
    ...estimateModelOutput,
    items: [{ ...estimateModelOutput.items[0], ...patch }],
  });
  assert.ok(!estimateOutputSchema.safeParse(withItem({ kcal: 5001 })).success);
  assert.ok(!estimateOutputSchema.safeParse(withItem({ kcal: -1 })).success);
  assert.ok(!estimateOutputSchema.safeParse(withItem({ grams: 0 })).success);
  assert.ok(!estimateOutputSchema.safeParse(withItem({ grams: 5001 })).success);
  assert.ok(!estimateOutputSchema.safeParse(withItem({ amount: 0 })).success);
  assert.ok(!estimateOutputSchema.safeParse(withItem({ proteinG: 501 })).success);
  assert.ok(!estimateOutputSchema.safeParse(withItem({ unit: "x".repeat(25) })).success);
  assert.ok(!estimateOutputSchema.safeParse(withItem({ name: "" })).success);
  assert.ok(!estimateOutputSchema.safeParse(withItem({ name: "x".repeat(121) })).success);
  assert.ok(!estimateOutputSchema.safeParse(withItem({ notes: "x".repeat(301) })).success);
  assert.ok(!estimateOutputSchema.safeParse(withItem({ confidence: "CERTAIN" })).success);
  assert.ok(!estimateOutputSchema.safeParse({ ...estimateModelOutput, items: [] }).success);
  const many = Array.from({ length: 13 }, () => estimateModelOutput.items[0]);
  assert.ok(!estimateOutputSchema.safeParse({ ...estimateModelOutput, items: many }).success);
  assert.ok(estimateOutputSchema.safeParse({ ...estimateModelOutput, per100g: null, notes: null }).success);
});

test("label output rejects out-of-range values", () => {
  const per100g = labelModelOutput.per100g!;
  assert.ok(!labelOutputSchema.safeParse({ ...labelModelOutput, per100g: { ...per100g, kcal: 1001 } }).success);
  assert.ok(!labelOutputSchema.safeParse({ ...labelModelOutput, per100g: { ...per100g, fatG: 100.5 } }).success);
  assert.ok(!labelOutputSchema.safeParse({ ...labelModelOutput, per100g: { ...per100g, saltG: -0.1 } }).success);
  assert.ok(
    !labelOutputSchema.safeParse({
      ...labelModelOutput,
      perServing: { ...labelModelOutput.perServing!, kcal: 3001 },
    }).success,
  );
  assert.ok(!labelOutputSchema.safeParse({ ...labelModelOutput, confidence: "high" }).success);
  // All-null calories is valid (unreadable label).
  assert.ok(
    labelOutputSchema.safeParse({
      ...labelModelOutput,
      per100g: null,
      perServing: null,
      confidence: "LOW",
      notes: "unreadable",
    }).success,
  );
});

test("estimate request needs text or image and enforces limits", () => {
  const image = { mediaType: "image/jpeg", data: tinyJpegBase64 };
  assert.ok(estimateRequestSchema.safeParse({ contractVersion: 1, text: "tea" }).success);
  assert.ok(estimateRequestSchema.safeParse({ contractVersion: 1, image }).success);
  assert.ok(
    estimateRequestSchema.safeParse({
      contractVersion: 1,
      text: "chilli",
      image,
      hints: { portion: "1 bowl", batchWeightGrams: 1800, servings: 6 },
    }).success,
  );
  assert.ok(!estimateRequestSchema.safeParse({ contractVersion: 1 }).success);
  assert.ok(!estimateRequestSchema.safeParse({ contractVersion: 1, text: "" }).success);
  assert.ok(!estimateRequestSchema.safeParse({ contractVersion: 1, text: "x".repeat(2001) }).success);
  assert.ok(!estimateRequestSchema.safeParse({ contractVersion: 2, text: "tea" }).success);
  assert.ok(!estimateRequestSchema.safeParse({ contractVersion: 1, image: { mediaType: "image/gif", data: "AAAA" } }).success);
  assert.ok(!estimateRequestSchema.safeParse({ contractVersion: 1, image: { mediaType: "image/png", data: "AA\nAA" } }).success);
});

test("label request requires an image", () => {
  assert.ok(labelRequestSchema.safeParse({ contractVersion: 1, image: { mediaType: "image/png", data: "AAAA" } }).success);
  assert.ok(!labelRequestSchema.safeParse({ contractVersion: 1 }).success);
});
