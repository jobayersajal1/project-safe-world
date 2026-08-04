package com.safeworld.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Kotlin half of `packages/core/test/blur.test.ts`. The cases are
 * deliberately the same ones: the two platforms run the *same* classifier, so
 * they must also reach the same verdict from the same numbers.
 */
class BlurSettingsTest {

    private fun sure(male: Boolean) = FaceObservation(isMale = male, genderProbability = 0.98f)
    private fun unsure(male: Boolean) = FaceObservation(isMale = male, genderProbability = 0.55f)

    @Test
    fun `no faces is left alone`() {
        assertEquals(BlurVerdict.CLEAR, BlurRules.verdict(emptyList(), BlurTarget.WOMEN))
        assertEquals(BlurVerdict.CLEAR, BlurRules.verdict(emptyList(), BlurTarget.EVERYONE))
    }

    @Test
    fun `blurs the target gender and clears the other`() {
        assertEquals(BlurVerdict.BLUR, BlurRules.verdict(listOf(sure(false)), BlurTarget.WOMEN))
        assertEquals(BlurVerdict.CLEAR, BlurRules.verdict(listOf(sure(true)), BlurTarget.WOMEN))
        assertEquals(BlurVerdict.BLUR, BlurRules.verdict(listOf(sure(true)), BlurTarget.MEN))
        assertEquals(BlurVerdict.CLEAR, BlurRules.verdict(listOf(sure(false)), BlurTarget.MEN))
    }

    @Test
    fun `one match takes the whole region`() {
        val mixed = listOf(sure(true), sure(false))
        assertEquals(BlurVerdict.BLUR, BlurRules.verdict(mixed, BlurTarget.WOMEN))
        assertEquals(BlurVerdict.BLUR, BlurRules.verdict(mixed, BlurTarget.MEN))
    }

    @Test
    fun `an uncertain face blurs whichever way it was called`() {
        // The rule the whole design leans on: a classifier that is unsure — a
        // side profile, a small face, a hijab — must not be able to clear a
        // region by guessing.
        assertEquals(BlurVerdict.BLUR, BlurRules.verdict(listOf(unsure(true)), BlurTarget.WOMEN))
        assertEquals(BlurVerdict.BLUR, BlurRules.verdict(listOf(unsure(false)), BlurTarget.MEN))
    }

    @Test
    fun `the confidence threshold is exclusive`() {
        val at = FaceObservation(isMale = true, genderProbability = BlurRules.GENDER_CONFIDENCE)
        assertEquals(BlurVerdict.CLEAR, BlurRules.verdict(listOf(at), BlurTarget.WOMEN))
        assertEquals(
            BlurVerdict.BLUR,
            BlurRules.verdict(listOf(at.copy(genderProbability = at.genderProbability - 0.001f)), BlurTarget.WOMEN),
        )
    }

    @Test
    fun `everyone blurs every face however unsure`() {
        assertEquals(BlurVerdict.BLUR, BlurRules.verdict(listOf(sure(true)), BlurTarget.EVERYONE))
        assertEquals(BlurVerdict.BLUR, BlurRules.verdict(listOf(unsure(false)), BlurTarget.EVERYONE))
    }

    @Test
    fun `the confidence threshold matches the TypeScript`() {
        // Pinned, not derived. Both platforms run the same ported classifier, so
        // a drift here would have them disagree about an identical photo.
        assertEquals(0.75f, BlurRules.GENDER_CONFIDENCE, 0.0f)
    }

    @Test
    fun `ships off and never reveals until asked`() {
        assertEquals(false, BlurSettings.defaults().enabled)
        assertEquals(BlurRevealMode.NEVER, BlurSettings.defaults().revealMode)
        assertEquals(BlurTarget.WOMEN, BlurSettings.defaults().target)
    }

    @Test
    fun `target ids match the wire form the other ports use`() {
        assertEquals("women", BlurTarget.WOMEN.id)
        assertEquals("men", BlurTarget.MEN.id)
        assertEquals("everyone", BlurTarget.EVERYONE.id)
        assertEquals(BlurTarget.MEN, BlurTarget.fromId("men"))
        assertEquals(BlurTarget.EVERYONE, BlurTarget.fromId("EVERYONE"))
        // An unreadable value falls back to the default rather than throwing:
        // a settings blob from a newer build must not crash an older one.
        assertEquals(BlurTarget.WOMEN, BlurTarget.fromId("nonsense"))
    }
}
