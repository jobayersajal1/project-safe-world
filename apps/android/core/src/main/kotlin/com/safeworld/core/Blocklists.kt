package com.safeworld.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
private data class FilterMeta(
    val category: String,
    /** Identifies the filter layout, so a future change is detectable rather than silent. */
    val algorithm: String,
    /** Identifies the digest the filter's keys were derived from. */
    val digest: String,
    val salt: String,
    /** How many domains went in. The filter itself cannot be counted. */
    val count: Int,
)

/**
 * The bundled per-category lists, stored as **binary fuse16 filters** over
 * salted digests of the domains (see [FuseFilter] and [DomainHasher]).
 * Generated from `packages/core/src/blocklists` by `npm run build:android`.
 *
 * Android is the only platform where our own code does the matching — Chrome's
 * declarativeNetRequest, Safari's content blocker, and the macOS/Windows hosts
 * files all need literal domains — so it is the only one that can store the
 * list approximately. That is what makes an uncapped list shippable: ~4.5M
 * domains in ~10 MB rather than ~82 MB of hex digests.
 *
 * Resources of a plain JVM module are packaged into the APK and are also on the
 * classpath under `:core:test`, so this reads them the same way in both.
 */
object Blocklists {
    private val json = Json { ignoreUnknownKeys = true }

    /** The layouts this build understands; a mismatch means stale generated files. */
    const val EXPECTED_ALGORITHM = "binary-fuse32-sha256-64"
    const val EXPECTED_DIGEST = "sha256-128-hex"

    private data class Loaded(val filter: FuseFilter?, val count: Int)

    private val cache: Map<CategoryId, Loaded> by lazy {
        CategoryId.entries.associateWith { load(it) }
    }

    private fun load(category: CategoryId): Loaded {
        val metaStream = Blocklists::class.java
            .getResourceAsStream("/blocklists/${category.id}.json")
            ?: return Loaded(null, 0)

        val meta = metaStream.use {
            runCatching { json.decodeFromString<FilterMeta>(it.readBytes().decodeToString()) }
                .getOrNull()
        } ?: return Loaded(null, 0)

        // Fail closed rather than silently matching nothing against a layout
        // this build can't parse or digests it can't reproduce.
        if (meta.algorithm != EXPECTED_ALGORITHM ||
            meta.digest != EXPECTED_DIGEST ||
            meta.salt != DomainHasher.SALT
        ) {
            return Loaded(null, 0)
        }

        val filterStream = Blocklists::class.java
            .getResourceAsStream("/blocklists/${category.id}.filter")
            ?: return Loaded(null, 0)

        val filter = runCatching { FuseFilter.parse(filterStream) }.getOrNull()
        return Loaded(filter, meta.count)
    }

    /** How many domains this category bundles — for the UI, not for matching. */
    fun count(category: CategoryId): Int = cache[category]?.count ?: 0

    /** The bundled filter for a category, or null when it failed to load. */
    fun filter(category: CategoryId): FuseFilter? = cache[category]?.filter

    /** Bundled lists for every category, ready for [Matcher.decide]. */
    fun all(): Map<CategoryId, DomainSet> =
        CategoryId.entries.associateWith { id ->
            cache[id]?.filter?.let { FuseDomainSet(it) } ?: EmptyDomainSet
        }
}
