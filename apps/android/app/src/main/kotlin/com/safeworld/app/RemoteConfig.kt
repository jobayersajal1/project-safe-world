package com.safeworld.app

/**
 * Server-driven blocklist updates. Deliberately **not** exposed anywhere in the
 * app's UI or editable via `Settings` — end users can't see or change this
 * endpoint from the app itself; it ships baked into the binary and only changes
 * via an app update. Mirrors `RemoteConfig.swift` on iOS.
 *
 * This hides the endpoint from the UI, not from the world: anyone inspecting
 * the APK (e.g. `strings`) can still read this string, same as any other
 * client-embedded config. Don't rely on it for secrecy.
 */
object RemoteConfig {
    /**
     * Published from https://github.com/jobayersajal1/safe-world-block-list-update via
     * GitHub Pages. Android fetches the **hashed** variant — it matches
     * one-way digests and never needs the plaintext back, unlike iOS and
     * Chrome, which fetch `blocklist.json` (scrambled) because their rule
     * files need literal domains.
     *
     * Empty disables automatic fetching. Generate both files with
     * `npm run build:remote`; setup is in scripts/templates/README.md.
     */
    /**
     * Which bundled list this build shipped with, from
     * `npm run baseline:snapshot`. **Update it whenever the bundled lists
     * change**, or the app will fetch a delta computed against a list it
     * doesn't have and miss whatever fell between the two.
     */
    const val LIST_BASELINE = "3a7b028a5695"

    /**
     * Only the domains added *since* [LIST_BASELINE] — the full list ships
     * inside the app and is never published. Android fetches the hashed
     * variant, which it matches one-way; iOS and Chrome fetch the scrambled
     * one because their rule files need literal domains.
     *
     * A 404 here means no delta has been published for this baseline, which
     * degrades to "no new domains" rather than to wrong ones.
     */
    const val UPDATE_URL =
        "https://jobayersajal1.github.io/safe-world-block-list-update/delta-$LIST_BASELINE-android.json"

    /** Minimum time between automatic fetches. */
    const val UPDATE_INTERVAL_HOURS = 24L
}
