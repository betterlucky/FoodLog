// Examples copied from docs/ai-contract.md (minus provenance).
export const estimateExample = {
  contractVersion: 1,
  items: [
    {
      name: "Beef chilli with kidney beans",
      amount: 1,
      unit: "bowl",
      grams: 350,
      kcal: 420,
      proteinG: 32,
      fibreG: 9,
      confidence: "MEDIUM",
      notes: "Assumed lean mince and no rice",
    },
  ],
  per100g: { kcal: 120, proteinG: 9.1, fibreG: 2.6 },
  notes: "Estimate from description only",
};

export const labelExample = {
  contractVersion: 1,
  productName: "Oat granola",
  per100g: { kcal: 420, proteinG: 10, fibreG: 6, carbsG: 60, fatG: 14, sugarsG: 18, saltG: 0.1 },
  per100ml: null,
  perServing: { kcal: 168, servingGrams: 40, servingAmount: 1, servingUnit: "serving" },
  packageGrams: 500,
  packageMilliliters: null,
  packageItemCount: null,
  packageItemUnit: null,
  prepared: false,
  confidence: "HIGH",
  notes: null,
};

function stripVersion<T extends { contractVersion: number }>(v: T): Omit<T, "contractVersion"> {
  const { contractVersion: _ignored, ...rest } = v;
  return rest;
}

export const estimateModelOutput = stripVersion(estimateExample);
export const labelModelOutput = stripVersion(labelExample);

export const tinyJpegBase64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/";
