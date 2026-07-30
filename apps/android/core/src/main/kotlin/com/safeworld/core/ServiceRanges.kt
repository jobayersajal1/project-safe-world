package com.safeworld.core

/**
 * IP ranges belonging to services whose apps must be blocked outright, not just their websites.
 *
 * **Why this exists.** Everything else in this app blocks by domain, which means it blocks by DNS —
 * and DNS is exactly what a determined app can avoid. DNS-over-HTTPS is indistinguishable from
 * ordinary HTTPS, Android's Private DNS leaves on port 853 where nothing here can see it, and an app
 * that has cached or hardcoded an address never asks a resolver at all. For a *website* that hardly
 * matters, because a browser has to resolve what you typed. For a native app it matters completely.
 *
 * Routing a service's own address ranges into the tunnel and dropping them sidesteps all of that: the
 * packets are dropped on the way out regardless of how the address was obtained.
 *
 * **The costs, which are real and must be shown to the user before this is switched on:**
 *
 * - It is address-based, so it blocks *everything* the operator runs on those addresses. Meta's
 *   ranges carry WhatsApp and Messenger as well as Facebook and Instagram. That is not a bug to fix
 *   later; it is what "block by IP range" means, and someone who needs WhatsApp must not have it
 *   taken away silently.
 * - Ranges go stale. These are the published prefixes as of 2026-07; an operator adding a range
 *   means that traffic is missed until this list is updated. It degrades to the DNS-level blocking
 *   that is already in place, rather than failing loudly.
 * - **Some services cannot be done this way at all.** YouTube shares Google's edge with Search,
 *   Gmail and Play; Twitch runs on AWS. Blocking those prefixes would take the phone off the
 *   internet in every way that matters. Those stay DNS-only, deliberately — see the README.
 */
object ServiceRanges {

    /**
     * @param id stable identifier, used for the per-service opt-in.
     * @param category the category that must be enabled for this to apply.
     * @param alsoBlocks other products on the same addresses, named so the UI can warn. Empty when
     *   the operator runs nothing else the user is likely to care about.
     * @param cidrs IPv4 prefixes in `a.b.c.d/len` form.
     */
    data class Service(
        val id: String,
        val category: CategoryId,
        val alsoBlocks: List<String>,
        val cidrs: List<String>,
    )

    val ALL: List<Service> = listOf(
        Service(
            id = "meta",
            category = CategoryId.SOCIAL,
            // Same addresses. Anyone enabling this loses these too, so the UI says so up front.
            alsoBlocks = listOf("WhatsApp", "Messenger"),
            cidrs = listOf(
                "31.13.24.0/21", "31.13.64.0/18", "45.64.40.0/22", "66.220.144.0/20",
                "69.63.176.0/20", "69.171.224.0/19", "74.119.76.0/22", "102.132.96.0/20",
                "103.4.96.0/22", "129.134.0.0/17", "157.240.0.0/16", "173.252.64.0/18",
                "179.60.192.0/22", "185.60.216.0/22", "204.15.20.0/22",
            ),
        ),
        Service(
            id = "netflix",
            category = CategoryId.ENTERTAINMENT,
            alsoBlocks = emptyList(),
            cidrs = listOf(
                "23.246.0.0/18", "37.77.184.0/21", "45.57.0.0/17", "64.120.128.0/17",
                "66.197.128.0/17", "108.175.32.0/20", "185.2.220.0/22", "185.9.188.0/22",
                "192.173.64.0/18", "198.38.96.0/19", "198.45.48.0/20", "208.75.76.0/22",
            ),
        ),
    )

    fun forCategory(category: CategoryId): List<Service> = ALL.filter { it.category == category }

    fun byId(id: String): Service? = ALL.firstOrNull { it.id == id }

    /**
     * A compiled set of prefixes, matched against a packet's destination address.
     *
     * Held as parallel int arrays and checked with a mask compare, because this runs on the tunnel's
     * hot path for every packet that reaches it — not just DNS.
     */
    class Matcher(cidrs: List<String>) {
        private val networks: IntArray
        private val masks: IntArray

        init {
            val parsed = cidrs.mapNotNull { parse(it) }
            networks = IntArray(parsed.size) { parsed[it].first }
            masks = IntArray(parsed.size) { parsed[it].second }
        }

        val size: Int get() = networks.size

        fun contains(address: Int): Boolean {
            for (i in networks.indices) {
                if (address and masks[i] == networks[i]) return true
            }
            return false
        }

        companion object {
            val EMPTY = Matcher(emptyList())

            /** `a.b.c.d/len` to (network, mask), or null if malformed. */
            fun parse(cidr: String): Pair<Int, Int>? {
                val slash = cidr.indexOf('/')
                if (slash <= 0) return null
                val length = cidr.substring(slash + 1).toIntOrNull() ?: return null
                if (length !in 0..32) return null

                val octets = cidr.substring(0, slash).split('.')
                if (octets.size != 4) return null

                var address = 0
                for (octet in octets) {
                    val value = octet.toIntOrNull() ?: return null
                    if (value !in 0..255) return null
                    address = (address shl 8) or value
                }

                // A /0 mask must be 0, and `shl 32` is a no-op on Int rather than zero — the classic
                // way this goes wrong is a /0 quietly matching nothing instead of everything.
                val mask = if (length == 0) 0 else (-1 shl (32 - length))
                return (address and mask) to mask
            }

            /** Big-endian bytes at [offset] as an Int, for reading straight out of an IPv4 header. */
            fun addressAt(packet: ByteArray, offset: Int): Int =
                ((packet[offset].toInt() and 0xFF) shl 24) or
                    ((packet[offset + 1].toInt() and 0xFF) shl 16) or
                    ((packet[offset + 2].toInt() and 0xFF) shl 8) or
                    (packet[offset + 3].toInt() and 0xFF)
        }
    }
}
