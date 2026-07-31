package com.safeworld.app.vpn

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.util.Log
import com.safeworld.app.SettingsStore
import com.safeworld.core.Matcher
import java.net.InetSocketAddress

/**
 * The userspace TCP/IP forwarder, and the reason full-tunnel mode can exist at all.
 *
 * Under a full tunnel we route `0.0.0.0/0` so a blocked app's packets reach us to be dropped. The
 * cost is that *every other app's* packets reach us too, and each one has to be carried to its
 * destination and back — routes are per-tunnel and cannot be scoped to one app, so there is no
 * arrangement where we see only the traffic we care about. Doing that means terminating TCP in
 * userspace: handshake, sequence numbers, windows, retransmission, teardown.
 *
 * That is what `cpp/netguard/` is. It is NetGuard's native core, vendored rather than written,
 * because a TCP implementation that is subtly wrong does not fail loudly — it makes some
 * connections hang some of the time, and the person debugging it is the user. See
 * `cpp/THIRD_PARTY.md`; **it is GPL-3.0, and it is why the Android app is GPL-3.0.**
 *
 * ### The one decision we make
 *
 * [isAddressAllowed] is the whole filter: return null to drop a flow, an [Allowed] to forward it.
 * Everything else here is plumbing the native side requires. We answer purely on the owning UID,
 * because a UID is the one thing an app cannot change about itself.
 *
 * ### Names are contracts
 *
 * Every method the native code calls is looked up by string. Renaming one, or changing a parameter
 * type, compiles fine and then fails on the packet path at runtime. Do not tidy the names.
 */
class NativeTunnel(
    private val service: VpnService,
    private val blockedApps: BlockedApps,
    private val store: SettingsStore,
) {

    /** Opaque pointer to the native context. 0 when not initialised. */
    private var context: Long = 0

    @Volatile
    private var running = false

    /**
     * Starts forwarding on [tun].
     *
     * Blocks until [stop], so the caller owns a thread for it. `fwd53 = false`: DNS keeps going to
     * the resolver the app asked for, because our own DNS filtering already handles port 53 and
     * having both would mean two things answering the same query.
     */
    fun run(tun: Int) {
        if (context == 0L) context = jni_init(Build.VERSION.SDK_INT)
        running = true
        jni_start(context, LOG_LEVEL)
        try {
            jni_run(context, tun, false, RCODE_NXDOMAIN)
        } finally {
            running = false
        }
    }

    fun stop() {
        if (context == 0L) return
        running = false
        jni_stop(context)
    }

    /** Frees the native context. The instance is unusable afterwards. */
    fun close() {
        if (context == 0L) return
        jni_done(context)
        context = 0
    }

    // MARK: Called from native code — names and signatures are fixed by netguard.c

    /**
     * The filter. **Null drops the flow, an [Allowed] forwards it.**
     *
     * Answering on UID rather than on address is the entire point of the full tunnel: an app that
     * uses DNS-over-HTTPS, or an address it cached last week, is unchanged by anything we do at the
     * DNS layer, but it cannot send a packet that belongs to somebody else's UID.
     *
     * A flow we cannot attribute is allowed. That is deliberate and it is the safe direction here:
     * the alternative is dropping traffic belonging to no identifiable app, which on a phone means
     * breaking the system's own connections.
     */
    private fun isAddressAllowed(packet: Packet): Allowed? {
        packet.allowed = packet.uid == Process.INVALID_UID || !blockedApps.contains(packet.uid)
        // Default-constructed: forward to wherever the app was already going.
        return if (packet.allowed) Allowed() else null
    }

    /**
     * Keeps a forwarding socket out of our own tunnel.
     *
     * Without this the socket's packets would be routed straight back in and loop forever, which
     * is the classic way a tunnel like this takes the device offline rather than filtering it.
     */
    private fun protect(socket: Int): Boolean = service.protect(socket)

    /** Who owns this flow. The native side asks once per new session, not per packet. */
    private fun getUidQ(
        version: Int,
        protocol: Int,
        saddr: String,
        sport: Int,
        daddr: String,
        dport: Int,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Process.INVALID_UID
        if (protocol != PROTOCOL_TCP && protocol != PROTOCOL_UDP) return Process.INVALID_UID
        val manager = service.getSystemService(ConnectivityManager::class.java)
            ?: return Process.INVALID_UID
        return runCatching {
            manager.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(saddr, sport),
                InetSocketAddress(daddr, dport),
            )
        }.getOrDefault(Process.INVALID_UID)
    }

    /**
     * Domain blocking, against the same ~4.4M list and the same [Matcher.decide] precedence the
     * DNS-only tunnel uses. **Under a full tunnel this is the only thing asking that question** —
     * the native side owns the tun fd, so the Kotlin DNS loop is not running.
     *
     * The native side calls this from two places, and the second is a real gain over what the
     * DNS-only path can do:
     *
     * - on a DNS *response*, where a blocked name has its answer rewritten to NXDOMAIN;
     * - on the **TLS SNI** of a new connection, which catches a name the app resolved somewhere we
     *   could not see — over DNS-over-HTTPS, say — because it still has to say who it is asking
     *   for in the handshake.
     *
     * Settings are read per call rather than captured, so a category toggled while the tunnel is up
     * applies to the next lookup, exactly as it does on the DNS-only path.
     */
    private fun isDomainBlocked(name: String): Boolean =
        runCatching {
            Matcher.decide(name, store.settings.value, store.blocklists).blocked
        }.getOrDefault(false)

    // The native side requires these to exist. We record nothing: a per-connection log of every
    // site visited is precisely the data this app has no business keeping.
    private fun logPacket(packet: Packet) = Unit

    private fun dnsResolved(record: ResourceRecord) = Unit

    private fun accountUsage(usage: Usage) = Unit

    private fun nativeExit(reason: String?) {
        Log.w(TAG, "native forwarder exited: ${reason ?: "no reason given"}")
        running = false
    }

    private fun nativeError(error: Int, message: String?) {
        Log.e(TAG, "native forwarder error $error: $message")
    }

    // MARK: JNI

    private external fun jni_init(sdk: Int): Long
    private external fun jni_start(context: Long, loglevel: Int)
    private external fun jni_run(context: Long, tun: Int, fwd53: Boolean, rcode: Int)
    private external fun jni_stop(context: Long)
    private external fun jni_clear(context: Long)
    private external fun jni_done(context: Long)

    companion object {
        private const val TAG = "SafeWorldNative"

        private const val PROTOCOL_TCP = 6
        private const val PROTOCOL_UDP = 17

        /** `ANDROID_LOG_WARN`. Anything chattier logs a line per packet. */
        private const val LOG_LEVEL = 5

        /** What a dropped flow is answered with when the native side needs a DNS rcode. */
        private const val RCODE_NXDOMAIN = 3

        /**
         * True when the native library is present and loadable on this device's ABI.
         *
         * Everything degrades to the DNS-only tunnel when it is false — see `TunnelMode.select`.
         */
        @Volatile
        var available: Boolean = false
            private set

        init {
            available = runCatching {
                System.loadLibrary("safeworld_netguard")
                true
            }.onFailure { error ->
                // Wrapped for the same reason as in SafeWorldRelay: `android.util.Log` is a JVM
                // stub that throws under plain unit tests, and a throwing logger inside a failure
                // handler turns "library missing" into a class that cannot be touched at all.
                runCatching { Log.w(TAG, "native forwarder unavailable", error) }
            }.getOrDefault(false)
        }
    }
}
