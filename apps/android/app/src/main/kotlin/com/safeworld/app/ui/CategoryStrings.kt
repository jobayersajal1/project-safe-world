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
 * rendered as a row, just counted into the headline.
 */
@StringRes
fun labelResFor(id: CategoryId): Int? = when (id) {
    CategoryId.SOCIAL -> R.string.category_list4_label
    CategoryId.ENTERTAINMENT -> R.string.category_list5_label
    else -> null
}

@StringRes
fun descriptionResFor(id: CategoryId): Int? = when (id) {
    CategoryId.SOCIAL -> R.string.category_list4_description
    CategoryId.ENTERTAINMENT -> R.string.category_list5_description
    else -> null
}
