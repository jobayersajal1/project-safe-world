# Bundled detection models

These weights power the "blur photos of people" feature. They are committed
rather than downloaded at build time so the build is hermetic and, more to the
point, so **nothing about an image ever leaves the machine** — including the
request that would fetch a model to look at it with.

| File | Model | Size | Used for |
|---|---|---|---|
| `tiny_face_detector_model.bin` + manifest | TinyFaceDetector | 193 KB | Finding faces |
| `age_gender_model.bin` + manifest | AgeGenderNet | 430 KB | Classifying each face |

Both come from [`vladmandic/face-api`](https://github.com/vladmandic/face-api)
(`model/`), the maintained fork of
[`justadudewhohacks/face-api.js`](https://github.com/justadudewhohacks/face-api.js)
that the weights originate from.

**Licence: MIT** — both repositories, code and weights. That matters beyond the
usual reason: this project keeps GPL confined to `apps/android/` so that
`packages/core`, `SafeWorldCore` and the Windows port stay distributable through
Apple's and Microsoft's stores. A share-alike model here would undo that. Any
replacement must be MIT or Apache-2.0.

## Updating them

Fetch the same four files from the fork's `model/` directory and rebuild. The
loading code in `src/offscreen/detect.ts` asks face-api for
`tiny_face_detector` and `age_gender`, which resolves the `-weights_manifest.json`
first and follows the shard paths inside it — so the manifests must be updated
alongside the `.bin` files, never separately.

## What they can and cannot do

TinyFaceDetector is anchor-based and only sees faces within a band of scales.
A face that fills the frame — which is to say every cropped avatar — falls off
the top of that band and is missed entirely, so `detect.ts` runs it twice over
differently inset copies and unions the results. Removing that and keeping a
single pass silently reintroduces the miss, and a missed face is not blurred.

AgeGenderNet is least reliable on children, side profiles, low-resolution
thumbnails, and faces in hijab or niqab. The verdict treats anything below
`GENDER_CONFIDENCE` as a reason to blur for exactly that reason.
