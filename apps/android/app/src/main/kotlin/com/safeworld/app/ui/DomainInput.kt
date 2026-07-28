package com.safeworld.app.ui

import com.safeworld.core.Matcher

/** Anything a person might reasonably type between two domain names. */
private val SEPARATORS = Regex("[,;\\s]+")

/**
 * Turns a free-text field into a domain list.
 *
 * Accepts commas, newlines, semicolons, or spaces as separators rather than
 * insisting on one, because the two places this is used ask for different
 * things ("separate with commas" on the home screen, one per line in settings)
 * and a user who does it the other way should still get what they meant.
 *
 * Entries are normalized on the way in — `Matcher.normalizeHost` strips the
 * scheme, path, port, and a leading `www.`, so pasting a full URL works — and
 * deduped, which also makes "did this edit remove anything?" a straight set
 * comparison at the call site.
 */
fun parseDomainList(text: String): List<String> =
    text.split(SEPARATORS)
        .map(Matcher::normalizeHost)
        .filter { it.isNotEmpty() }
        .distinct()
