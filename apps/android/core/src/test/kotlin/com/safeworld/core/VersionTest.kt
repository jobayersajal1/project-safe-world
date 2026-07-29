package com.safeworld.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cases are duplicated in the Swift port
 * (apps/ios/SafeWorldCore/Tests/.../VersionTests.swift). They are the contract
 * between the two update checkers: if one side orders a pair differently, that
 * platform either nags about an update that isn't one or stays silent about one
 * that is.
 */
class VersionTest {
    @Test
    fun `orders by numeric components`() {
        assertTrue(Version.isNewer("0.2.0", "0.1.0"))
        assertTrue(Version.isNewer("1.0.0", "0.9.9"))
        assertTrue(Version.isNewer("0.1.1", "0.1.0"))
        assertFalse(Version.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun `the same version is not an update`() {
        assertFalse(Version.isNewer("0.1.0", "0.1.0"))
    }

    @Test
    fun `ignores a leading v, so a git tag compares against a versionName`() {
        assertFalse(Version.isNewer("v0.1.0", "0.1.0"))
        assertTrue(Version.isNewer("v0.2.0", "0.1.0"))
    }

    @Test
    fun `treats missing trailing components as zero`() {
        assertFalse(Version.isNewer("1.2", "1.2.0"))
        assertTrue(Version.isNewer("1.3", "1.2.9"))
    }

    @Test
    fun `compares components numerically, not as text`() {
        // The bug this pins: "10" sorts before "9" as a string.
        assertTrue(Version.isNewer("0.10.0", "0.9.0"))
        assertTrue(Version.isNewer("0.1.10", "0.1.9"))
    }

    @Test
    fun `a pre-release precedes the release it leads up to`() {
        assertTrue(Version.isNewer("1.0.0", "1.0.0-beta"))
        assertFalse(Version.isNewer("1.0.0-beta", "1.0.0"))
        // Still newer than the previous full release.
        assertTrue(Version.isNewer("1.0.0-beta", "0.9.0"))
    }

    @Test
    fun `build metadata does not affect precedence`() {
        assertFalse(Version.isNewer("1.0.0+build9", "1.0.0"))
        assertFalse(Version.isNewer("1.0.0", "1.0.0+build9"))
    }

    @Test
    fun `malformed input reads as zero rather than throwing`() {
        // A garbage tag must not crash the update check, only fail to look new.
        assertFalse(Version.isNewer("", "0.1.0"))
        assertFalse(Version.isNewer("not-a-version", "0.1.0"))
        assertTrue(Version.isNewer("0.1.0", "garbage"))
    }
}
