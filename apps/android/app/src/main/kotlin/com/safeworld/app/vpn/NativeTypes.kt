package com.safeworld.app.vpn

/**
 * The objects the native forwarder passes across the JNI boundary.
 *
 * **Every name and type here is load-bearing.** `netguard.c` looks these fields up by string with
 * `GetFieldID`, so a rename, a different type, or a Kotlin property where a field was expected does
 * not fail to compile — it fails at runtime, on the packet path, the first time a session is
 * created. `@JvmField` is what keeps them real fields instead of getter/setter pairs.
 *
 * Kept in one file because they only exist for that boundary; nothing else in the app should hold
 * one.
 */

/** One flow, as the native side sees it. Mirrors `eu.faircode.netguard.Packet`. */
class Packet {
    @JvmField var time: Long = 0
    @JvmField var version: Int = 0
    @JvmField var protocol: Int = 0
    @JvmField var flags: String? = null
    @JvmField var saddr: String? = null
    @JvmField var sport: Int = 0
    @JvmField var daddr: String? = null
    @JvmField var dport: Int = 0
    @JvmField var data: String? = null
    @JvmField var uid: Int = 0
    @JvmField var allowed: Boolean = false
}

/**
 * The verdict for a flow. **Null means drop**; an instance means forward.
 *
 * A default-constructed one forwards to the address the app originally asked for, which is what we
 * always want — the fields exist so a flow can be redirected somewhere else, which we never do.
 */
class Allowed {
    @JvmField var raddr: String? = null
    @JvmField var rport: Int = 0
}

/** A DNS answer the forwarder observed. We do not record these; the callback is required. */
class ResourceRecord {
    @JvmField var Time: Long = 0
    @JvmField var QName: String? = null
    @JvmField var AName: String? = null
    @JvmField var Resource: String? = null
    @JvmField var TTL: Int = 0
    @JvmField var uid: Int = 0
}

/** Byte counts for a finished flow. Also unused by us, also required by the native side. */
class Usage {
    @JvmField var Time: Long = 0
    @JvmField var Version: Int = 0
    @JvmField var Protocol: Int = 0
    @JvmField var DAddr: String? = null
    @JvmField var DPort: Int = 0
    @JvmField var Uid: Int = 0
    @JvmField var Sent: Long = 0
    @JvmField var Received: Long = 0
}
