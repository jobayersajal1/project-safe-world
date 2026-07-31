package com.safeworld.app.ui

import androidx.annotation.StringRes
import com.safeworld.app.R
import com.safeworld.core.AppGroups.AppGroup
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
 * The app groups' labels, kept beside the categories' for the same reason: `:core` is a plain JVM
 * module and cannot hold Android resources.
 *
 * These read "…apps" where the category above reads the bare noun, because the two rows sit near
 * each other and the whole point is that they are different switches.
 */
@StringRes
fun appGroupLabelFor(group: AppGroup): Int = when (group) {
    AppGroup.SOCIAL -> R.string.appblock_social_label
    AppGroup.ENTERTAINMENT -> R.string.appblock_entertainment_label
    AppGroup.GAMES -> R.string.appblock_games_label
}

@StringRes
fun appGroupDescriptionFor(group: AppGroup): Int = when (group) {
    AppGroup.SOCIAL -> R.string.appblock_social_description
    AppGroup.ENTERTAINMENT -> R.string.appblock_entertainment_description
    AppGroup.GAMES -> R.string.appblock_games_description
}

@StringRes
fun appGroupOffPinTitleFor(group: AppGroup): Int = when (group) {
    AppGroup.SOCIAL -> R.string.appblock_social_off_pin_title
    AppGroup.ENTERTAINMENT -> R.string.appblock_entertainment_off_pin_title
    AppGroup.GAMES -> R.string.appblock_games_off_pin_title
}
