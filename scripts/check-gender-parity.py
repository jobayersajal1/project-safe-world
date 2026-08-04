#!/usr/bin/env python3
"""
Check the ported TFLite gender classifier against face-api itself.

Every layer in `port-gender-model.py` is a transcription from TypeScript. A
transposed filter, a ReLU on the wrong side of a shortcut, or a stride read from
the wrong argument all produce a model that loads, runs, and returns confident
nonsense — so the port is not finished until its output matches the original on
the same pixels.

The cases are captured by `blurtest/dump.html` running face-api in a real
browser: for each detected face it records the exact 112x112 RGB crop the
network saw and the gender vector it produced. This replays those crops through
the `.tflite` and compares.

Usage:  check-gender-parity.py <cases-dir>
"""

import base64
import json
import pathlib
import sys

import numpy as np
import tensorflow as tf

REPO = pathlib.Path(__file__).resolve().parent.parent
MODEL = REPO / "apps/android/app/src/main/assets/gender_classifier.tflite"

# Tolerances. The probability bound is what actually matters: the verdict reads
# P(male) against a 0.75 confidence threshold, so anything that could move a
# face across that line is a failure. 1e-3 is far tighter than that and still
# leaves room for float differences between WebGL and TFLite's CPU kernels.
PROB_TOL = 1e-3
LABEL_MUST_MATCH = True


def main() -> int:
    cases_dir = pathlib.Path(sys.argv[1])
    cases = sorted(cases_dir.glob("*.json"))
    if not cases:
        raise SystemExit(f"no cases in {cases_dir}")

    interpreter = tf.lite.Interpreter(model_path=str(MODEL))
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]
    # Exactly one output, by design — see the note in port-gender-model.py about
    # TFLite dropping output names.
    (gender_out,) = interpreter.get_output_details()

    worst = 0.0
    failures = 0

    for path in cases:
        case = json.loads(path.read_text())
        rgb = np.frombuffer(base64.b64decode(case["rgb"]), dtype=np.uint8)
        face = rgb.reshape(1, 112, 112, 3).astype(np.float32)

        interpreter.set_tensor(inp["index"], face)
        interpreter.invoke()
        got = interpreter.get_tensor(gender_out["index"])[0]
        want = np.array(case["gender"], dtype=np.float32)

        delta = float(np.max(np.abs(got - want)))
        worst = max(worst, delta)

        # face-api reads index 0 as P(male); the label is what the product
        # actually acts on, so a probability that drifts but keeps the label is
        # survivable while a flipped label is not.
        same_label = (got[0] > 0.5) == (want[0] > 0.5)
        ok = delta <= PROB_TOL and (same_label or not LABEL_MUST_MATCH)
        if not ok:
            failures += 1

        print(
            f"{'ok  ' if ok else 'FAIL'} {case['id']:<20} "
            f"face-api P(male)={want[0]:.4f}  tflite P(male)={got[0]:.4f}  d={delta:.2e}"
        )

    print(f"\n{len(cases)} cases, worst delta {worst:.2e}, {failures} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
