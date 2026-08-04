package com.safeworld.app.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.safeworld.core.FaceObservation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * The gender classifier, ported from face-api's `AgeGenderNet`.
 *
 * The weights are the identical MIT-licensed ones Chrome loads; only the runtime
 * differs. `scripts/port-gender-model.py` builds the `.tflite` and
 * `scripts/check-gender-parity.py` proves it — the two agree to 3.3e-07 on the
 * same face crops, which is float32 round-off rather than agreement by
 * approximation.
 *
 * **The model expects RGB in 0..255 and normalises internally.** Do not
 * subtract a mean or divide by 255 here: that is baked into the graph precisely
 * so this side cannot get it subtly wrong, and doing it twice degrades accuracy
 * without ever failing visibly.
 */
class GenderClassifier(context: Context) : AutoCloseable {

    private val interpreter: Interpreter? = try {
        Interpreter(
            context.assets.openFd(MODEL).use { fd ->
                fd.createInputStream().channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength,
                )
            },
            Interpreter.Options().apply { setNumThreads(2) },
        )
    } catch (e: Exception) {
        // A missing or corrupt model must not take the whole feature down. Every
        // caller reads a null classification as "unknown", and unknown blurs —
        // so the failure degrades to over-blurring rather than to showing
        // faces the user asked not to see.
        Log.e(TAG, "gender model unavailable; every face will read as unknown", e)
        null
    }

    private val input = ByteBuffer
        .allocateDirect(SIZE * SIZE * 3 * 4)
        .order(ByteOrder.nativeOrder())

    private val output = Array(1) { FloatArray(2) }

    /** The scratch bitmap every crop is scaled into, reused across calls. */
    private val scratch = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(SIZE * SIZE)

    /**
     * Classify the face at [box] within [frame], or null if it cannot be read.
     *
     * Null is not "no face" — it is "no answer", which the caller must treat as
     * a reason to blur.
     */
    @Synchronized
    fun classify(frame: Bitmap, box: Rect): FaceObservation? {
        val lite = interpreter ?: return null
        if (box.width() < MIN_FACE_PX || box.height() < MIN_FACE_PX) return null

        return try {
            Canvas(scratch).apply {
                drawColor(Color.BLACK)
                drawBitmap(frame, box, Rect(0, 0, SIZE, SIZE), null)
            }
            scratch.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)

            input.rewind()
            for (p in pixels) {
                input.putFloat(((p shr 16) and 0xFF).toFloat())
                input.putFloat(((p shr 8) and 0xFF).toFloat())
                input.putFloat((p and 0xFF).toFloat())
            }
            input.rewind()

            lite.run(input, output)

            // face-api reads index 0 as P(male) and derives female from it. The
            // reported probability is the winner's, not the male one — a 0.2
            // P(male) is an 0.8-confident female, and reading it the other way
            // would make every woman look uncertain and blur regardless of
            // target.
            val probMale = output[0][0]
            val isMale = probMale > 0.5f
            FaceObservation(
                isMale = isMale,
                genderProbability = if (isMale) probMale else 1f - probMale,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "classification failed", e)
            null
        }
    }

    override fun close() {
        runCatching { interpreter?.close() }
        runCatching { scratch.recycle() }
    }

    private companion object {
        const val TAG = "GenderClassifier"
        const val MODEL = "gender_classifier.tflite"
        const val SIZE = 112

        /**
         * Smaller than this and the crop is upscaled guesswork. The classifier
         * still returns a number, which is worse than returning nothing —
         * null blurs, a confident wrong answer does not.
         */
        const val MIN_FACE_PX = 24
    }
}
