package com.safeworld.app.ui

import androidx.annotation.StringRes
import com.safeworld.app.R
import com.safeworld.core.CategoryId

/**
 * Localized labels for the categories the UI actually shows.
 *
 * `:core` is a plain JVM module (so its matching logic can be unit-tested
 * without an emulator), which means it can't hold Android resources — its
 * `CategoryMeta.label` is English-only. This is where ids become translated
 * text.
 *
 * Only the opt-in categories appear here: the mandatory three are never
 * rendered as a row, just counted into the headline. The app groups follow
 * below — separate switches, separate strings.
 */
@StringRes
fun labelResFor(id: CategoryId): Int? = when (id) {
    CategoryId.SOCIAL -> R.string.category_list4_label
    CategoryId.ENTERTAINMENT -> R.string.category_list5_label
    CategoryId.GAMES -> R.string.category_list6_label
    else -> null
}

@StringRes
fun descriptionResFor(id: CategoryId): Int? = when (id) {
    CategoryId.SOCIAL -> R.string.category_list4_description
    CategoryId.ENTERTAINMENT -> R.string.category_list5_description
    CategoryId.GAMES -> R.string.category_list6_description
    else -> null
}

/**
 * The bare noun — "social media", not "Block social media".
 *
 * The row labels lead with the verb because that is what the switch does; the PIN prompt supplies
 * its own ("Stop blocking %1$s?") and interpolating the label there produced *"Stop blocking Block
 * social media?"*. Two forms because two sentences need them, rather than one form bent to fit
 * both.
 */
@StringRes
fun subjectResFor(id: CategoryId): Int? = when (id) {
    CategoryId.SOCIAL -> R.string.category_list4_subject
    CategoryId.ENTERTAINMENT -> R.string.category_list5_subject
    CategoryId.GAMES -> R.string.category_list6_subject
    else -> null
}

// The app groups had their own labels here, back when they were their own switches. They share a
// row with the category now — one subject, one switch, one set of words — so the strings above are
// written to cover both halves and there is nothing separate left to name.
