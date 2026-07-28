package com.safeworld.core

import kotlinx.serialization.Serializable

/**
 * Mirrors `packages/core/src/types.ts` (`Settings`) and `settings.ts`.
 *
 * Every field has a default so that a stored blob written by an older version
 * still decodes; [withDefaults] then fills in any category added since.
 */
@Serializable
data class Settings(
    /** Master switch. When false, nothing is blocked. */
    val enabled: Boolean = true,
    /** Per-category enable flags. */
    val categories: Map<CategoryId, Boolean> = defaultCategories(),
    /** Domains the user always allows, even if a category would block them. */
    val customAllow: List<String> = emptyList(),
    /** Domains the user always blocks, regardless of category. */
    val customBlock: List<String> = emptyList(),
    /** URL to fetch remote list updates from. Empty string disables remote updates. */
    val remoteUpdateUrl: String = "",
    /** How often (in hours) to fetch remote updates. */
    val remoteUpdateIntervalHours: Int = 24,
    /** Timestamp (ms since epoch) of the last successful remote update, or 0 if never. */
    val lastRemoteUpdate: Long = 0,
) {
    companion object {
        fun defaults(): Settings = Settings()

        /**
         * Merge stored (possibly partial/older) settings over the current
         * defaults, the same precedence as `withDefaults` in
         * `packages/core/src/settings.ts`.
         */
        fun withDefaults(stored: Settings?): Settings {
            if (stored == null) return defaults()
            return stored.copy(categories = defaultCategories() + stored.categories)
        }
    }
}

private fun defaultCategories(): Map<CategoryId, Boolean> =
    Categories.all.associate { it.id to it.defaultEnabled }

/** Mirrors `packages/core/src/types.ts` (`DailyStats`). */
@Serializable
data class DailyStats(
    /** Local date key, e.g. "2026-07-26". */
    val date: String,
    /** Number of navigations blocked on that date. */
    val blocked: Int,
)
