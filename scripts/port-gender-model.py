#!/usr/bin/env python3
"""
Port face-api's AgeGenderNet to TFLite, for Android.

Android cannot run face-api: the weights ship as a tfjs weight manifest plus a
hand-written JavaScript forward pass, so there is no serialized graph to
convert. What there *is* is a completely specified architecture — 51 named
tensors and ~60 lines of TypeScript — and that is reimplemented here in
TensorFlow, loaded with the identical MIT-licensed weights, and exported as a
1.6MB float32 `.tflite`.

The alternative was a 12.5MB TFLite model trained on UTKFace, whose terms
restrict use to non-commercial research; `apps/android/` is GPL-3.0 and cannot
grant redistribution rights it does not have. Porting keeps one licence and, more
usefully, keeps Chrome and Android running the *same* classifier — a divergence
there would mean the two platforms disagreeing about the same photo.

Run:
    scripts/port-gender-model.py

Verify parity afterwards with `scripts/check-gender-parity.py`, which compares
this model's output against numbers captured from face-api in a browser. Do not
skip it: every layer here is a transcription, and a transposed filter or a
misplaced ReLU produces a model that runs, looks healthy, and is wrong.
"""

import json
import pathlib
import sys

import numpy as np
import tensorflow as tf

REPO = pathlib.Path(__file__).resolve().parent.parent
MODEL_DIR = REPO / "apps/chrome-extension/public/models"
OUT_DIR = REPO / "apps/android/app/src/main/assets"

# TinyXception's input normalisation, from src/xception/TinyXception.ts.
MEAN_RGB = [122.782, 117.001, 104.298]
INPUT_SIZE = 112
NUM_MAIN_BLOCKS = 2


def load_weights() -> dict[str, np.ndarray]:
    """Read the tfjs weight manifest and dequantise every tensor.

    Most tensors are uint8 with a per-tensor `scale`/`min` (`value = q * scale +
    min`); the biases are stored as plain float32. They are concatenated into the
    `.bin` in manifest order, so the offset has to be walked, not indexed.
    """
    manifest = json.loads((MODEL_DIR / "age_gender_model-weights_manifest.json").read_text())
    blob = (MODEL_DIR / "age_gender_model.bin").read_bytes()

    out: dict[str, np.ndarray] = {}
    offset = 0
    for group in manifest:
        for spec in group["weights"]:
            shape = tuple(spec["shape"])
            count = int(np.prod(shape)) if shape else 1
            quant = spec.get("quantization")

            if quant:
                assert quant["dtype"] == "uint8", quant["dtype"]
                raw = np.frombuffer(blob, dtype=np.uint8, count=count, offset=offset)
                offset += count
                values = raw.astype(np.float32) * quant["scale"] + quant["min"]
            else:
                raw = np.frombuffer(blob, dtype=np.float32, count=count, offset=offset)
                offset += count * 4
                values = raw.astype(np.float32)

            out[spec["name"]] = values.reshape(shape)

    if offset != len(blob):
        raise SystemExit(f"weight file not fully consumed: {offset} of {len(blob)} bytes")
    return out


class Net:
    """The forward pass, transcribed from TinyXception.ts and AgeGenderNet.ts.

    tfjs and TensorFlow agree on filter layout — conv `[kh, kw, in, out]`,
    depthwise `[kh, kw, in, multiplier]` — so the weights need no transposing.
    That is worth stating because it is the one thing that would silently
    produce a plausible-looking wrong answer.
    """

    def __init__(self, w: dict[str, np.ndarray]):
        self.w = {k: tf.constant(v, dtype=tf.float32) for k, v in w.items()}

    def _conv(self, x, prefix, stride):
        y = tf.nn.conv2d(x, self.w[f"{prefix}/filters"], strides=[1, stride, stride, 1], padding="SAME")
        return tf.nn.bias_add(y, self.w[f"{prefix}/bias"])

    def _separable(self, x, prefix, stride=1):
        y = tf.nn.separable_conv2d(
            x,
            self.w[f"{prefix}/depthwise_filter"],
            self.w[f"{prefix}/pointwise_filter"],
            strides=[1, stride, stride, 1],
            padding="SAME",
        )
        return tf.nn.bias_add(y, self.w[f"{prefix}/bias"])

    def _reduction_block(self, x, prefix, activate_input=True):
        out = tf.nn.relu(x) if activate_input else x
        out = self._separable(out, f"{prefix}/separable_conv0")
        out = self._separable(tf.nn.relu(out), f"{prefix}/separable_conv1")
        out = tf.nn.max_pool2d(out, ksize=3, strides=2, padding="SAME")
        # The shortcut convolves the block's *input*, before the activation
        # above — not `out`. Reading it the other way is the easiest mistake to
        # make here and the hardest to see afterwards.
        return out + self._conv(x, f"{prefix}/expansion_conv", stride=2)

    def _main_block(self, x, prefix):
        out = self._separable(tf.nn.relu(x), f"{prefix}/separable_conv0")
        out = self._separable(tf.nn.relu(out), f"{prefix}/separable_conv1")
        out = self._separable(tf.nn.relu(out), f"{prefix}/separable_conv2")
        return out + x

    def __call__(self, rgb_0_255):
        # Normalisation is baked into the graph rather than left to the caller:
        # Android would otherwise have to reproduce a mean-subtract and a /255
        # exactly, and getting it subtly wrong degrades accuracy without ever
        # failing.
        x = (rgb_0_255 - tf.constant(MEAN_RGB, dtype=tf.float32)) / 255.0

        out = tf.nn.relu(self._conv(x, "entry_flow/conv_in", stride=2))
        out = self._reduction_block(out, "entry_flow/reduction_block_0", activate_input=False)
        out = self._reduction_block(out, "entry_flow/reduction_block_1")
        for i in range(NUM_MAIN_BLOCKS):
            out = self._main_block(out, f"middle_flow/main_block_{i}")
        out = self._reduction_block(out, "exit_flow/reduction_block")
        features = tf.nn.relu(self._separable(out, "exit_flow/separable_conv"))

        pooled = tf.nn.avg_pool2d(features, ksize=7, strides=2, padding="VALID")
        pooled = tf.reshape(pooled, [tf.shape(pooled)[0], -1])

        gender = tf.matmul(pooled, self.w["fc/gender/weights"]) + self.w["fc/gender/bias"]
        # face-api reads index 0 as P(male) and derives female from it, so the
        # softmax stays a 2-vector rather than being reduced here.
        #
        # The age head is deliberately not exported. It costs nothing to run,
        # but TFLite conversion drops output *names* — two outputs arrive as
        # `Identity` and `Identity_1`, told apart only by shape, and a caller
        # that picks the wrong one gets a confident number that means nothing.
        # One output cannot be got wrong. Age can come back if it is ever used.
        return tf.nn.softmax(gender)


def main() -> int:
    weights = load_weights()
    print(f"loaded {len(weights)} tensors")

    net = Net(weights)

    @tf.function(input_signature=[tf.TensorSpec([1, INPUT_SIZE, INPUT_SIZE, 3], tf.float32, name="face")])
    def serve(face):
        return net(face)

    concrete = serve.get_concrete_function()

    # Sanity: mid-grey in, something finite out. Catches a shape error before
    # the converter buries it in a stack trace.
    probe = np.full((1, INPUT_SIZE, INPUT_SIZE, 3), 128.0, dtype=np.float32)
    print("probe gender:", serve(tf.constant(probe)).numpy().tolist())

    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete], serve)
    # Deliberately *not* `converter.optimizations = [tf.lite.Optimize.DEFAULT]`.
    #
    # These weights are already uint8-quantised in the source manifest and
    # dequantised above; letting TFLite quantise them a second time compounds
    # the error. Measured against face-api on the same 16 face crops, it moved
    # P(male) by up to 1.8e-2 — never far enough to flip a label in that sample,
    # but the verdict reads this against a 0.75 confidence threshold, so it is
    # exactly the kind of drift that decides whether a face gets blurred.
    # Without it the worst deviation is 3.3e-07, which is float32 round-off.
    # The model goes from 490KB to 1.6MB; in a 21MB APK that is the cheap side
    # of the trade.
    tflite = converter.convert()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    dest = OUT_DIR / "gender_classifier.tflite"
    dest.write_bytes(tflite)
    print(f"wrote {dest} ({len(tflite) / 1024:.0f}KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
