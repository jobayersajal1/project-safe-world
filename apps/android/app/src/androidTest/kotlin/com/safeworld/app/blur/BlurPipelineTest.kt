package com.safeworld.app.blur

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.safeworld.core.BlurTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blur pipeline, on a device, end to end.
 *
 * Everything this exercises can only fail where the real thing runs: an asset
 * that `aapt` compressed so TFLite cannot memory-map it, a MediaPipe native
 * library missing for the ABI, a model that loads but reads its input in the
 * wrong channel order. All of those produce a *log line* and an app that quietly
 * covers nothing — which is indistinguishable from a screen with nobody on it.
 *
 * The fixtures are face-api's own MIT-licensed test images, the same ones the
 * Chrome side was measured against, so a disagreement between the platforms
 * shows up here rather than in somebody's hand.
 */
class BlurPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun asset(name: String): Bitmap {
        // The *test* APK's assets, not the app's — hence the instrumentation
        // context rather than the target one.
        val stream = InstrumentationRegistry.getInstrumentation().context.assets.open(name)
        return stream.use { BitmapFactory.decodeStream(it) }
            .copy(Bitmap.Config.ARGB_8888, false)
    }

    @Test
    fun models_load_and_a_group_photo_is_covered() {
        PersonScanner(context).use { scanner ->
            val frame = asset("mixed_group.jpg")

            // Three men and four women. Whoever is asked for, somebody matches,
            // so both targets must cover something.
            assertTrue(
                "expected women to be covered in a mixed group",
                scanner.scan(frame, BlurTarget.WOMEN).isNotEmpty(),
            )
            assertTrue(
                "expected men to be covered in a mixed group",
                scanner.scan(frame, BlurTarget.MEN).isNotEmpty(),
            )
            assertTrue(
                "expected everyone to be covered",
                scanner.scan(frame, BlurTarget.EVERYONE).isNotEmpty(),
            )
        }
    }

    @Test
    fun a_picture_of_nobody_is_left_alone() {
        PersonScanner(context).use { scanner ->
            val frame = asset("no_people.png")
            // The one case that must come back empty. If this covers something,
            // the person detector is firing on texture and the feature would
            // blur the whole screen.
            assertEquals(emptyList<Any>(), scanner.scan(frame, BlurTarget.EVERYONE))
        }
    }

    /**
     * The face box for the largest face in [image], found the way production
     * finds it.
     *
     * Handing the classifier a whole photograph instead of a face crop is not a
     * gentler version of the same test — it is a different question, and it
     * answers wrong. face-api reached 0.94 female on the cropped face of the
     * same picture and this returned male on the uncropped one.
     */
    private fun largestFace(image: Bitmap): android.graphics.Rect {
        val detector = com.google.mediapipe.tasks.vision.facedetector.FaceDetector.createFromOptions(
            context,
            com.google.mediapipe.tasks.vision.facedetector.FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(
                    com.google.mediapipe.tasks.core.BaseOptions.builder()
                        .setModelAssetPath("blaze_face_short_range.tflite").build(),
                )
                .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
                .setMinDetectionConfidence(0.3f)
                .build(),
        )
        detector.use {
            val found = it.detect(
                com.google.mediapipe.framework.image.BitmapImageBuilder(image).build(),
            ).detections()
            assertTrue("no face detected in fixture", found.isNotEmpty())
            val box = found.maxBy { d -> d.boundingBox().width() * d.boundingBox().height() }.boundingBox()
            return android.graphics.Rect(
                box.left.toInt().coerceIn(0, image.width),
                box.top.toInt().coerceIn(0, image.height),
                box.right.toInt().coerceIn(0, image.width),
                box.bottom.toInt().coerceIn(0, image.height),
            )
        }
    }

    @Test
    fun the_gender_classifier_tells_a_man_from_a_woman() {
        // Straight at the classifier, with only the face detector in front of
        // it: this is what proves the ported .tflite loaded and is reading its
        // input correctly, rather than returning a confident constant.
        GenderClassifier(context).use { classifier ->
            val man = asset("one_man.jpg")
            val woman = asset("one_woman.jpg")

            val onMan = classifier.classify(man, largestFace(man))
            val onWoman = classifier.classify(woman, largestFace(woman))

            assertTrue("classifier returned nothing for the man", onMan != null)
            assertTrue("classifier returned nothing for the woman", onWoman != null)
            assertTrue("expected male, got ${onMan!!}", onMan.isMale)
            assertTrue("expected female, got ${onWoman!!}", !onWoman.isMale)

            // A model that failed to load would still be *consistent*; what it
            // could not be is confident in opposite directions.
            assertTrue(onMan.genderProbability > 0.7f)
            assertTrue(onWoman.genderProbability > 0.7f)
        }
    }

    @Test
    fun a_face_too_small_to_read_returns_nothing_rather_than_a_guess() {
        GenderClassifier(context).use { classifier ->
            val frame = asset("one_man.jpg")
            // Null is not "no face" — every caller reads it as a reason to
            // blur, which is the whole point of returning it.
            assertEquals(null, classifier.classify(frame, android.graphics.Rect(0, 0, 8, 8)))
        }
    }
}
