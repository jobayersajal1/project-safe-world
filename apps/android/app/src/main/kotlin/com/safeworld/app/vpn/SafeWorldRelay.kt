package com.safeworld.app.vpn

import android.util.Log

/**
 * The JVM side of the native packet relay.
 *
 * **Status: the classifier is real, the forwarder is not.** Full-tunnel mode routes `0.0.0.0/0`, so
 * every packet on the device would arrive here — and anything this code fails to forward is traffic
 * the phone simply loses. Until the forwarding path exists, [available] is what stops that mode from
 * being switched on, because routing everything into a tunnel that only drops is a phone with no
 * internet rather than a phone with YouTube blocked.
 *
 * Why native at all: today the tunnel routes only DNS and the JVM sees ~100 bytes per lookup. Under
 * a full tunnel it sees every byte the device transfers, where allocation per packet means GC pauses
 * in the middle of a call. NetGuard and Blokada are native for the same reason.
 */
object SafeWorldRelay {

    /** Mirrors the verdict constants in `relay.c`. */
    enum class Verdict {
        /** Unparseable, truncated, or a header length that pointed past the end. Never acted on. */
        MALFORMED,

        /** IPv4 UDP to port 53 — the existing filtering path handles it. */
        DNS,

        /** Anything else. Under a full tunnel this is what needs forwarding. */
        PASS,
    }

    /**
     * False when the library could not be loaded — a device whose ABI we do not ship, or a build
     * where the native step was skipped.
     *
     * Everything degrades to the current DNS-only tunnel, which is the whole point of checking:
     * a missing `.so` must cost the new mode, never the blocking that already works.
     */
    @Volatile
    var available: Boolean = false
        private set

    init {
        available = runCatching {
            System.loadLibrary("safeworld_relay")
            nativeAbiCheck() == 1
        }.onFailure { error ->
            // Wrapped because `android.util.Log` is a stub that throws under plain JVM unit tests,
            // and a logging call that fails inside a failure handler turns "the library is missing"
            // into ExceptionInInitializerError — the class then cannot be touched at all, including
            // by the gate that keeps full-tunnel mode off.
            runCatching { Log.w(TAG, "native relay unavailable; staying DNS-only", error) }
        }.getOrDefault(false)
    }

    /**
     * Whether full-tunnel mode may be used.
     *
     * Separate from [available] on purpose: this classifier could load and parse packets long
     * before anything could *forward* them, and those are two different questions. Routing
     * `0.0.0.0/0` without a forwarder pulls every packet on the device into something that can only
     * drop — a phone with no internet, not a phone with YouTube blocked.
     *
     * Now answered by whether `libsafeworld_netguard` loaded. It is a real check rather than a
     * constant: on a device whose ABI we do not ship, or a build where the native step was skipped,
     * the answer must be no and `TunnelMode.select` must fall back to the DNS-only tunnel — which
     * still blocks 4.4M domains, and still blackholes a blocked app's lookups.
     */
    val canForward: Boolean get() = NativeTunnel.available

    fun classify(packet: ByteArray, length: Int): Verdict = when (nativeClassify(packet, length)) {
        1 -> Verdict.DNS
        2 -> Verdict.PASS
        else -> Verdict.MALFORMED
    }

    /** IPv4 destination as a big-endian Int, or 0 when it could not be read. */
    fun destination(packet: ByteArray, length: Int): Int = nativeDestination(packet, length)

    private external fun nativeClassify(packet: ByteArray, length: Int): Int
    private external fun nativeDestination(packet: ByteArray, length: Int): Int
    private external fun nativeAbiCheck(): Int

    private const val TAG = "SafeWorldRelay"
}
