package com.safeworld.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
private data class HashedBlocklistFile(
    val category: String,
    /** Identifies the digest scheme, so a future change is detectable rather than silent. */
    val algorithm: String,
    val salt: String,
    val hashes: List<String>,
)

/**
 * The bundled per-category lists, stored as **salted hashes** rather than
 * plaintext domains (see [DomainHasher]). Generated from
 * `packages/core/src/blocklists` by `npm run build:android`, so Android blocks
 * the same curated domains as the other platforms without shipping a readable
 * directory of them.
 *
 * Resources of a plain JVM module are packaged into the APK and are also on the
 * classpath under `:core:test`, so this reads them the same way in both.
 */
object Blocklists {
    private val json = Json { ignoreUnknownKeys = true }

    /** The scheme this build understands; a mismatch means stale generated files. */
    const val EXPECTED_ALGORITHM = "sha256-128-hex"

    private val cache: Map<CategoryId, Set<String>> by lazy {
        CategoryId.entries.associateWith { load(it) }
    }

    private fun load(category: CategoryId): Set<String> {
        val stream = Blocklists::class.java.getResourceAsStream("/blocklists/${category.id}.json")
            ?: return emptySet()
        return stream.use {
            val file = runCatching {
                json.decodeFromString<HashedBlocklistFile>(it.readBytes().decodeToString())
            }.getOrNull() ?: return@use emptySet()

            // Fail closed rather than silently matching nothing against digests
            // this build can't reproduce.
            if (file.algorithm != EXPECTED_ALGORITHM || file.salt != DomainHasher.SALT) {
                return@use emptySet()
            }
            file.hashes.toSet()
        }
    }

    /** Bundled digests for a category. */
    fun hashes(category: CategoryId): Set<String> = cache[category].orEmpty()

    /** Bundled lists for every category, ready for [Matcher.decide]. */
    fun all(): Map<CategoryId, DomainSet> = cache.mapValues { HashedDomainSet(it.value) }
}
