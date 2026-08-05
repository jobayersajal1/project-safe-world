package com.safeworld.app.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.safeworld.core.BlurRules
import com.safeworld.core.BlurTarget
import com.safeworld.core.BlurVerdict
import com.safeworld.core.FaceObservation

/**
 * Decides which parts of a captured frame to cover.
 *
 * **The unit is a person, not a face.** Chrome gets body coverage for free — it
 * blurs the whole `<img>`, and a photograph contains its subject. A screen does
 * not: covering the face boxes on a captured frame leaves the body, which is
 * most of what needs covering, in plain view. So this detects *people* and
 * covers those boxes, and uses faces only to decide which ones.
 *
 * That ordering also fixes the case face detection cannot see at all: someone
 * photographed from behind, or with their face out of frame, produces no face
 * and would otherwise be left uncovered. Here they are a person box with no
 * readable face — which is uncertainty, and uncertainty blurs.
 *
 * Three models, all on-device, nothing uploaded:
 *  - EfficientDet-Lite0 (Apache-2.0) for person boxes.
 *  - BlazeFace short-range (Apache-2.0) for faces.
 *  - The gender classifier ported from face-api (MIT) — see
 *    `scripts/port-gender-model.py`. Chrome runs the identical weights, so the
 *    two platforms agree about the same face by construction rather than by
 *    coincidence.
 */
class PersonScanner(context: Context) : AutoCloseable {

    private val gender = GenderClassifier(context)

    private val people: ObjectDetector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(PERSON_MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            // Low, on purpose. A weak person detection still gets covered, and
            // the cost of a false positive is a blurred rectangle over nothing.
            .setScoreThreshold(PERSON_SCORE)
            .setCategoryAllowlist(listOf("person"))
            .setMaxResults(MAX_PEOPLE)
            .build(),
    )

    private val faces: FaceDetector = FaceDetector.createFromOptions(
        context,
        FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACE_MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setMinDetectionConfidence(FACE_SCORE)
            .build(),
    )

    /**
     * Regions of [frame] to cover, in frame coordinates.
     *
     * Returns an empty list when there is nobody to cover — which the caller
     * must not confuse with "not scanned yet". A frame that failed to scan has
     * to keep its previous cover, not lose it.
     */
    fun scan(frame: Bitmap, target: BlurTarget): List<Rect> {
        val image = BitmapImageBuilder(frame).build()

        val personBoxes = try {
            people.detect(image).detections().mapNotNull { it.boundingBox()?.toRect(frame) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "person detection failed", e)
            return emptyList()
        }
        if (personBoxes.isEmpty()) return emptyList()

        // Everyone gets covered regardless of who they are, so the face pass —
        // by far the more expensive half — is skipped entirely.
        if (target == BlurTarget.EVERYONE) return personBoxes

        return personBoxes.filter { person -> shouldCover(frame, person, target) }
    }

    /**
     * Decide one person, by looking only at them.
     *
     * **Face detection runs on the person's own crop, upscaled — not on the
     * whole frame.** The frame is captured at [BlurService] resolution, well
     * below the screen's, so a photo occupying half the width leaves a face a
     * few dozen pixels across, and BlazeFace short-range — built for a face
     * filling a selfie — simply does not find it. Every person then came back
     * with no readable face, which the rule below treats as unknown, so
     * *everyone* was covered whatever the target was. The gender choice was
     * still being made correctly and then never consulted.
     *
     * Cropping to the person and scaling up puts the face back into the range
     * the detector works in, and costs one detector pass per person instead of
     * one per frame — at a handful of people, a fair trade for the setting
     * actually meaning something.
     */
    private fun shouldCover(frame: Bitmap, person: Rect, target: BlurTarget): Boolean {
        val crop = cropUpscaled(frame, person) ?: return true
        try {
            val faceBoxes = try {
                faces.detect(BitmapImageBuilder(crop).build())
                    .detections().mapNotNull { it.boundingBox()?.toRect(crop) }
            } catch (e: RuntimeException) {
                Log.w(TAG, "face detection failed", e)
                return true
            }

            // Turned away, too small, or cropped out of shot. Not evidence that
            // they are safe to show.
            if (faceBoxes.isEmpty()) return true

            val observed = faceBoxes.mapNotNull { gender.classify(crop, it) }
            // At least one face could not be classified at all.
            if (observed.size < faceBoxes.size) return true

            return BlurRules.verdict(observed, target) == BlurVerdict.BLUR
        } finally {
            if (crop !== frame) crop.recycle()
        }
    }

    /** The person's own pixels, enlarged so a small face becomes a findable one. */
    private fun cropUpscaled(frame: Bitmap, person: Rect): Bitmap? {
        val r = Rect(person)
        if (!r.intersect(0, 0, frame.width, frame.height)) return null
        if (r.width() < 8 || r.height() < 8) return null

        val crop = runCatching {
            Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        }.getOrNull() ?: return null

        val longest = maxOf(crop.width, crop.height)
        if (longest >= FACE_INPUT_EDGE) return crop

        val factor = FACE_INPUT_EDGE.toFloat() / longest
        return runCatching {
            Bitmap.createScaledBitmap(
                crop,
                (crop.width * factor).toInt().coerceAtLeast(1),
                (crop.height * factor).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== crop) crop.recycle() }
        }.getOrElse { crop }
    }

    override fun close() {
        runCatching { people.close() }
        runCatching { faces.close() }
        runCatching { gender.close() }
    }

    private companion object {
        const val TAG = "PersonScanner"
        const val PERSON_MODEL = "efficientdet_lite0.tflite"
        const val FACE_MODEL = "blaze_face_short_range.tflite"
        const val PERSON_SCORE = 0.35f
        const val FACE_SCORE = 0.4f

        /**
         * A person crop is enlarged until its long edge reaches this before the
         * face detector sees it. Below roughly this size BlazeFace short-range
         * stops finding faces at all.
         */
        const val FACE_INPUT_EDGE = 256

        /**
         * A crowd scene past this is covered wholesale anyway, and the per-face
         * classification cost grows with it while the screen is still being
         * captured many times a second.
         */
        const val MAX_PEOPLE = 12
    }
}

/** MediaPipe reports float bounds; clamp into the frame so crops stay legal. */
private fun RectF.toRect(frame: Bitmap): Rect? {
    val r = Rect(
        left.toInt().coerceIn(0, frame.width),
        top.toInt().coerceIn(0, frame.height),
        right.toInt().coerceIn(0, frame.width),
        bottom.toInt().coerceIn(0, frame.height),
    )
    return if (r.width() > 1 && r.height() > 1) r else null
}

