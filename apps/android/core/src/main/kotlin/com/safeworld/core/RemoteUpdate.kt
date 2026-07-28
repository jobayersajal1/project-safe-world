package com.safeworld.core

import kotlinx.serialization.Serializable

/**
 * Mirrors `packages/core/src/types.ts` (`RemoteUpdatePayload`). Domains are
 * keyed by the category's raw string id (e.g. "gambling") so the JSON shape
 * matches what the JS side of the project produces/consumes, and unknown keys
 * are tolerated rather than failing the whole fetch.
 */
@Serializable
data class RemoteUpdatePayload(
    val version: Int,
    /** Identifies this exact set of domains, so a client can skip work it has already done. Absent on payloads published before the field existed. */
    val updateId: String? = null,
    val domains: Map<String, List<String>> = emptyMap(),
) {
    /** [domains] re-keyed by [CategoryId], dropping any unrecognized keys. */
    fun domainsByCategory(): Map<CategoryId, List<String>> =
        domains.mapNotNull { (key, value) -> CategoryId.fromId(key)?.let { it to value } }.toMap()
}
