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

        val faceBoxes = try {
            faces.detect(image).detections().mapNotNull { it.boundingBox()?.toRect(frame) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "face detection failed", e)
            // People were found but their faces cannot be read. Every one of
            // them is unknown, and unknown blurs.
            return personBoxes
        }

        return personBoxes.filter { person ->
            val within = faceBoxes.filter { person.containsMostOf(it) }
            if (within.isEmpty()) {
                // A person with no readable face: turned away, too small, or
                // cropped. Not evidence that they are safe to show.
                return@filter true
            }
            val observed = within.mapNotNull { box -> gender.classify(frame, box) }
            if (observed.size < within.size) {
                // At least one crop could not be classified at all.
                return@filter true
            }
            BlurRules.verdict(observed, target) == BlurVerdict.BLUR
        }
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

/**
 * True if [inner] is mostly inside this box.
 *
 * Not strict containment: the person box and the face box come from different
 * models and their edges disagree by a few pixels, so a face on the boundary
 * would otherwise be assigned to nobody and its person covered as unknown.
 */
private fun Rect.containsMostOf(inner: Rect): Boolean {
    val overlap = Rect(this)
    if (!overlap.intersect(inner)) return false
    val area = inner.width().toLong() * inner.height()
    if (area == 0L) return false
    return overlap.width().toLong() * overlap.height() >= area / 2
}
