# FoodLog AI Contract (version 1)

The iPhone app talks only to FoodLog's own Worker (`worker/`), never to an AI provider. The Worker turns these requests into provider calls through pluggable adapters and returns these responses, validated, with provenance. Changing providers or models is Worker configuration; the app does not change.

## Transport

- `POST https://<worker>/v1/estimate` and `POST https://<worker>/v1/label`, JSON bodies, `Content-Type: application/json`.
- `GET https://<worker>/v1/health` returns `{ "contractVersion": 1, "routes": { "estimate": ["<provider>:<model>", ...], "label": [...] } }`. No secrets.
- Every request carries `Authorization: Bearer <app token>`. The token is a Worker secret (`APP_TOKEN`) and is revocable by rotating it. Wrong or missing token: `401`.
- Request bodies over 8 MB: `413`. Invalid request: `400` with `{ "error": { "code": "invalid_request", "message": "..." } }`.
- All providers failed or returned invalid output: `502` with `{ "error": { "code": "estimate_failed", "message": "...", "attempts": [{ "provider": "...", "model": "...", "reason": "..." }] } }`. `attempts[].reason` never contains secrets or the request body.
- The Worker stores nothing. It logs only provider, model, latency and outcome; never food text or images.

## `POST /v1/estimate`

Estimate what was eaten from a description, a photo, or both. Used for vague text ("slice of friend's lasagne"), restaurant meals, plate photos, and home-made batches (ingredient list plus cooked batch weight).

Request:

```json
{
  "contractVersion": 1,
  "text": "homemade chilli, about a bowl",
  "image": { "mediaType": "image/jpeg", "data": "<base64, no newlines>" },
  "hints": { "portion": "1 bowl", "batchWeightGrams": 1800, "servings": 6 }
}
```

- At least one of `text` (1–2000 characters) or `image` is required.
- `image.mediaType` is one of `image/jpeg`, `image/png`, `image/webp`. The app sends JPEG, downscaled to at most 1600 px on the long edge.
- `hints` and each of its fields are optional. `batchWeightGrams` with `servings` (or a portion in grams) lets a recipe estimate return a per-portion item.

Response `200`:

```json
{
  "contractVersion": 1,
  "items": [
    {
      "name": "Beef chilli with kidney beans",
      "amount": 1,
      "unit": "bowl",
      "grams": 350,
      "kcal": 420,
      "proteinG": 32,
      "fibreG": 9,
      "confidence": "MEDIUM",
      "notes": "Assumed lean mince and no rice"
    }
  ],
  "per100g": { "kcal": 120, "proteinG": 9.1, "fibreG": 2.6 },
  "notes": "Estimate from description only",
  "provenance": { "provider": "anthropic", "model": "<model id>", "latencyMs": 2140 }
}
```

- `items`: 1–12 entries. Each needs `name` (1–120 chars), `kcal` (0–5000), and `confidence` (`HIGH` | `MEDIUM` | `LOW`). `amount` (>0), `unit` (≤24 chars), `grams` (>0, ≤5000), `proteinG` and `fibreG` (0–500) and `notes` (≤300 chars) are optional, and `null` means unknown.
- `per100g` is optional (`null` when not meaningful). It is returned when the estimate is for a single dish whose density is known well enough to save a gram-based shortcut, e.g. a home-made batch.
- `notes` is optional (≤300 chars).
- `provenance` is added by the Worker, never by the model.
- `confidence` guidance for providers: `HIGH` only when the input states quantities and the food is standard; `LOW` when portion or recipe is guessed.

## `POST /v1/label`

Read a nutrition label photo. Returns per-100 and per-serving values as printed. The app runs its own portion maths on them, the same way as for the on-device OCR path.

Request:

```json
{ "contractVersion": 1, "image": { "mediaType": "image/jpeg", "data": "<base64>" } }
```

Response `200`:

```json
{
  "contractVersion": 1,
  "productName": "Oat granola",
  "per100g": { "kcal": 420, "proteinG": 10, "fibreG": 6, "carbsG": 60, "fatG": 14, "sugarsG": 18, "saltG": 0.1 },
  "per100ml": null,
  "perServing": { "kcal": 168, "servingGrams": 40, "servingAmount": 1, "servingUnit": "serving" },
  "packageGrams": 500,
  "packageMilliliters": null,
  "packageItemCount": null,
  "packageItemUnit": null,
  "prepared": false,
  "confidence": "HIGH",
  "notes": null,
  "provenance": { "provider": "openai", "model": "<model id>", "latencyMs": 1830 }
}
```

- Every nutrition field is optional and `null` when not printed or unreadable. Providers must not infer values the label does not show. `per100g`/`per100ml` values: `kcal` 0–1000, the rest 0–100 g. `perServing.kcal` 0–3000.
- `confidence` reflects legibility. Use `LOW` when any calorie figure was hard to read.
- If no calorie value is readable at all, the response is still `200` with all calorie fields `null`, and the app falls back to manual entry.

## Rules for the app

- AI output is always a draft in a review screen. Nothing is logged without the user's confirmation.
- Logged rows from `/v1/estimate` use `source = ESTIMATE`, the returned confidence, `nutrientSource = ESTIMATE` when protein or fibre is present, and the provenance (contract version, provider, model).
- Label reads keep `source = SAVED_LABEL` once confirmed, with AI provenance recorded on the row.
- The app rejects responses whose `contractVersion` is not `1` and validates ranges again before showing them.
- Offline or failed calls show a clear message and leave the manual, label and shortcut flows available.

## Versioning

Breaking changes bump `contractVersion` and the path (`/v2/...`). The Worker keeps serving `v1` until the installed app no longer uses it.
