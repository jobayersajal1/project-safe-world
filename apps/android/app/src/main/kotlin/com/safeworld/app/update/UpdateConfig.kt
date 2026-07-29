package com.safeworld.app.update

/**
 * Where the app looks for a newer build of itself.
 *
 * This reads the GitHub Releases API rather than a hand-maintained version
 * file, deliberately: publishing a release is already the act that makes a
 * build available, so there is no second thing to remember to bump and nothing
 * that can drift out of step with the actual download. The tradeoff is the
 * unauthenticated rate limit (60/hour per IP) — generous for a check that runs
 * at most once a day per install, and a limit hit degrades to "no update
 * found", never to a wrong answer.
 *
 * Distribution here is a downloaded APK, not Play. That is what makes an
 * in-app updater necessary at all: a sideloaded app gets no store to update it.
 */
object UpdateConfig {
    /** Returns the newest published release, including its assets. */
    const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/jobayersajal1/project-safe-world/releases/latest"

    /**
     * Picks this platform's asset out of a release that also carries the .dmg,
     * .exe, and Chrome .zip. Matched case-insensitively on the extension alone
     * so renaming the file (`SafeWorld-0.2.0.apk` → `safe-world-0.2.0.apk`)
     * doesn't quietly stop finding it.
     */
    const val ASSET_EXTENSION = ".apk"

    /** Shown when there is an update but no APK attached to it — see [UpdateManager]. */
    const val RELEASES_PAGE_URL =
        "https://github.com/jobayersajal1/project-safe-world/releases/latest"

    /** Minimum time between automatic checks, so opening Settings repeatedly doesn't spend the rate limit. */
    const val CHECK_INTERVAL_HOURS = 24L
}
