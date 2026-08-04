# Bundled detection models

These power "blur people on screen". They are committed rather than downloaded
so the build is hermetic and, more to the point, so **nothing about what is on
your screen ever leaves the device** — including the request that would fetch a
model to look at it with.

| File | Model | Size | Licence | Used for |
|---|---|---|---|---|
| `efficientdet_lite0.tflite` | EfficientDet-Lite0 (int8) | 4.4 MB | Apache-2.0 | Person boxes — what actually gets covered |
| `blaze_face_short_range.tflite` | BlazeFace short-range | 224 KB | Apache-2.0 | Faces inside those boxes |
| `gender_classifier.tflite` | face-api `AgeGenderNet`, ported | 1.6 MB | MIT | Which people to cover |

The first two come from Google's MediaPipe model repository, taken from
`storage.googleapis.com/mediapipe-models/…` and **not** `…/mediapipe-assets/…`:
the latter hosts the raw legacy models, which have no TFLite metadata, and
MediaPipe Tasks refuses them with

    Input tensor has type float32: it requires specifying NormalizationOptions
    metadata to preprocess input images.

The third is not a third-party download at all. It is
[`vladmandic/face-api`](https://github.com/vladmandic/face-api)'s MIT-licensed
`age_gender_model` weights, reimplemented and exported by
`scripts/port-gender-model.py` — Chrome loads those same weights directly, so
both platforms reach the same verdict about the same face by construction.
`scripts/check-gender-parity.py` is what proves it.

## Licensing is load-bearing here

`apps/android/` is **GPL-3.0**, which obliges us to grant every recipient the
right to redistribute. That rules out the obvious shortcut: the small pretrained
gender classifiers in circulation derive from **UTKFace** or **IMDB-WIKI**, whose
terms restrict use to non-commercial *research* — a restriction on purpose, not
price, so being a free app does not satisfy it, and we cannot grant rights we do
not have. Porting face-api's MIT weights avoids the question entirely.

Any replacement must be Apache-2.0 or MIT, with provenance that survives being
looked at.

## `noCompress`

`app/build.gradle.kts` lists `tflite` under `androidResources.noCompress`. These
files are memory-mapped straight out of the APK and a compressed asset cannot be
mapped; without it TFLite fails to load at runtime, which is a log line rather
than a build error — so it would ship.
