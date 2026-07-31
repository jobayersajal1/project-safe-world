package com.safeworld.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import com.safeworld.app.LocaleHelper
import com.safeworld.app.MainActivity
import com.safeworld.app.R
import com.safeworld.app.SettingsStore
import com.safeworld.app.SubscriptionStore
import com.safeworld.core.Matcher
import com.safeworld.core.ServiceRanges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A local `VpnService` that filters DNS.
 *
 * Android won't let one app inspect another app's traffic without root, so the
 * standard no-root technique for a device-wide content blocker is a local VPN:
 * we register a tunnel that goes nowhere (no remote server — traffic never
 * leaves the device), route only the system's DNS resolvers into it, and answer
 * lookups ourselves.
 *
 * For each query we run the host through [Matcher.decide] — the same precedence
 * spec the Chrome extension and the iOS content blocker implement — and either
 * synthesize an NXDOMAIN answer (blocked) or forward the query verbatim to the
 * resolver the device already uses and relay its reply (allowed).
 *
 * Only IPv4 DNS is routed in; everything else on the device is untouched, which
 * keeps this cheap. See apps/android/README.md for the limitations that follow
 * from filtering at the DNS layer (DoH, hardcoded resolvers, direct-to-IP).
 */
class SafeWorldVpnService : VpnService() {

    /**
     * Same language override the UI uses — without it the persistent
     * notification would come up in the system language while the app it points
     * at is in another.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var store: SettingsStore

    /**
     * Address ranges to drop outright, rebuilt whenever settings change.
     *
     * Empty unless the user has switched on full blocking for a service, so the packet loop pays
     * nothing for a feature that is off.
     */
    @Volatile
    private var blockedRanges: ServiceRanges.Matcher = ServiceRanges.Matcher.EMPTY

    /**
     * UIDs to blackhole, resolved at [start] and refreshed by `ACTION_REFRESH`.
     *
     * Null until the first tunnel comes up, so the packet path can skip the whole mechanism on the
     * ordinary install where no app blocking is on.
     */
    @Volatile
    private var blockedApps: BlockedApps? = null

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var forwarders: ExecutorService? = null

    /** The userspace TCP/IP forwarder. Non-null only under a full tunnel. */
    private var native: NativeTunnel? = null

    /**
     * Re-resolves UIDs when an app is installed, replaced or removed.
     *
     * **A reinstall gives an app a new UID.** Without this the service would keep dropping traffic
     * for a UID nobody owns any more while the reinstalled app ran unblocked — and reinstalling is
     * the obvious thing to try when an app stops working, so it is the first bypass anyone would
     * find by accident.
     *
     * Registered at runtime rather than in the manifest: `ACTION_PACKAGE_ADDED` is an implicit
     * broadcast, and manifest receivers stopped getting those in Android 8. A runtime registration
     * is exempt, and the service is running for exactly as long as this matters.
     */
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val apps = blockedApps ?: return
            apps.refresh(store)
            Log.i(TAG, "package change (${intent.action}); ${apps.installedCount} uids blocked")
        }
    }

    private var packageChangesRegistered = false

    /** Serializes writes back into the tun fd across the worker and forwarders. */
    private val writeLock = Any()

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Tear the tunnel down *here* rather than leaving it to onDestroy():
            // while a tunnel is established the VPN framework holds a binding to
            // this service, and stopSelf() does not destroy a service that still
            // has bindings. Relying on onDestroy() left the tun fd open, the
            // worker threads running, and the app still intercepting every DNS
            // query on the device — with the UI reporting protection was off.
            // Closing the fd is also what makes the framework drop its binding.
            stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (intent?.action == ACTION_REFRESH) {
            // Re-establish in place. A tunnel's routes are fixed by `establish()` and cannot be
            // edited, so changing which address ranges are blocked needs a new one.
            //
            // Done here rather than by toggling protection off and on from the UI: that briefly
            // disables protection, goes through the consent path, and — as this got wrong once —
            // can leave it off entirely, so switching a *stronger* block on silently turned
            // blocking off. Settings are never touched by this path.
            if (tunnel != null) {
                stop()
                start()
            }
            return START_STICKY
        }

        if (tunnel == null) start()
        return START_STICKY
    }

    /**
     * Android calls this when our VPN consent is withdrawn — the user revoked
     * it in system settings, or another VPN app took over (only one VPN can be
     * active at a time, so installing and starting one silently displaces us).
     *
     * Protection is now off through no in-app action, which is exactly the case
     * where the user must not be left assuming they're still covered: flag it
     * so the UI shows an unmissable banner that costs the PIN to dismiss, and
     * post a high-priority notification in case the app is never reopened.
     */
    override fun onRevoke() {
        store.flagProtectionInterrupted()
        postInterruptedNotification()
        stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    // MARK: Tunnel lifecycle

    private fun start() {
        val settings = store.settings.value

        // Resolved before the mode is chosen: what decides is whether any blocked app is actually
        // installed, not whether the switch is on. A phone with no Facebook and no games gives a
        // full tunnel nothing to do, and it should not pay for one.
        val apps = BlockedApps.get(this).also { it.refresh(store) }
        blockedApps = apps
        val mode = TunnelMode.select(settings, TunnelMode.needsPerAppBlocking(apps.packages))
        Log.i(TAG, "establishing tunnel in $mode mode, ${apps.installedCount} app uids blocked")

        val dnsServers = systemDnsServers()

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
            .setMtu(MTU)
            .setBlocking(true)
            .setConfigureIntent(configureIntent())

        // Routing *only* resolvers means every other packet on the device takes its normal path
        // and never enters this process.
        for (server in dnsServers) {
            builder.addDnsServer(server)
            server.hostAddress?.let { builder.addRoute(it, IPV4_HOST_PREFIX_LENGTH) }
        }

        // The system's resolvers are not the only ones apps use. Routing just those left an obvious
        // hole: an app that hardcodes 8.8.8.8 — which plenty do, including several that are the whole
        // point of the social and entertainment categories — never entered the tunnel at all, so its
        // lookups were neither seen nor filtered. Their queries are routed here too and forwarded to
        // the resolver the app actually asked for, so nothing about the answer changes except that it
        // is now filtered.
        for (resolver in PUBLIC_RESOLVERS) {
            if (dnsServers.any { it.hostAddress == resolver }) continue
            runCatching { builder.addRoute(resolver, IPV4_HOST_PREFIX_LENGTH) }
        }

        if (mode == TunnelMode.FullTunnel) {
            // Everything, so a blocked app's packets arrive here whatever it did to find the
            // address. Only reachable when the relay can forward — see `TunnelMode.select`.
            builder.addRoute("0.0.0.0", 0)

            // IPv6 as well, and this is not optional. Routing only v4 would leave a blocked app
            // free to reach every v6 address on the internet while the UI reported it blocked —
            // and modern mobile networks are v6-first, so that is the common case, not the corner
            // one. Needs an address on the interface too, or the route has no source to use.
            runCatching {
                builder.addAddress(TUN_ADDRESS_V6, TUN_PREFIX_LENGTH_V6)
                builder.addRoute("::", 0)
            }.onFailure { Log.w(TAG, "no IPv6 on this tunnel", it) }
        }

        // Services the user chose to block outright. Their prefixes are routed in so the packets
        // arrive here to be dropped; without the route they would never enter the tunnel at all.
        val services = SettingsStore.get(this).fullyBlockedServices()
        val cidrs = services.flatMap { it.cidrs }
        blockedRanges = ServiceRanges.Matcher(cidrs)
        for (cidr in cidrs) {
            val slash = cidr.indexOf('/')
            if (slash <= 0) continue
            val prefix = cidr.substring(0, slash)
            val length = cidr.substring(slash + 1).toIntOrNull() ?: continue
            runCatching { builder.addRoute(prefix, length) }
        }

        // Keep our own remote-list fetches out of the tunnel we just created.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Could not exclude self from the tunnel", e)
        }

        val fd = try {
            builder.establish()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to establish the tunnel", e)
            null
        }

        if (fd == null) {
            // establish() returns null when permission was never granted or was
            // revoked between the consent dialog and here.
            Log.e(TAG, "VPN permission unavailable; not starting")
            stopSelf()
            return
        }

        tunnel = fd
        forwarders = Executors.newFixedThreadPool(FORWARDER_THREADS)

        // Exactly one of these owns the tun fd. Running both would have two readers competing for
        // the same packets, each seeing half of every connection.
        if (mode == TunnelMode.FullTunnel) {
            val forwarder = NativeTunnel(this, apps, store)
            native = forwarder
            worker = Thread({
                // Blocks until stop(). Domain filtering moves into the forwarder's
                // `isDomainBlocked` here — it covers DNS answers and TLS SNI both.
                runCatching { forwarder.run(fd.fd) }
                    .onFailure { Log.e(TAG, "native forwarder stopped", it) }
            }, "safe-world-forwarder").apply {
                isDaemon = true
                start()
            }
        } else {
            worker = Thread({ runTunnel(fd) }, "safe-world-dns").apply {
                isDaemon = true
                start()
            }
        }
        registerPackageChanges()
        _running.value = true
        // Records that protection has genuinely run at least once, which is what
        // makes a later "it stopped" claim true rather than just noticing that a
        // fresh install isn't running yet.
        store.markProtectionStarted()
        loadSubscriptions()
    }

    /**
     * Loads the user's subscribed blocklists into [store] so this tunnel matches against them too.
     *
     * **Done here rather than only in `MainActivity`, because the tunnel can start without the
     * activity ever running** — `BootReceiver` does exactly that. Testing a reboot caught it: the
     * bundled 4.4M blocked correctly while a domain that exists only in a subscribed feed resolved
     * fine. Partial protection, reported as full, which is the worst shape a bug here can take.
     *
     * On its own thread: reading the keys is megabytes of I/O plus sorting close to a million longs,
     * and this runs while the tunnel is already up. Until it finishes the bundled lists block as
     * normal, so nothing is worse off for the wait.
     */
    private fun loadSubscriptions() {
        Thread({
            val subscriptions = SubscriptionStore.get(applicationContext)
            runBlocking { subscriptions.load() }
            store.setSubscriptionSets(subscriptions.setsByCategory())
        }, "safe-world-subscriptions").apply {
            isDaemon = true
            start()
        }
    }

    private fun registerPackageChanges() {
        if (packageChangesRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            // Package broadcasts carry the package in the data URI, and a filter without this
            // scheme matches none of them.
            addDataScheme("package")
        }
        runCatching { registerReceiver(packageChanges, filter) }
            .onSuccess { packageChangesRegistered = true }
            .onFailure { Log.w(TAG, "could not watch package changes", it) }
    }

    private fun unregisterPackageChanges() {
        if (!packageChangesRegistered) return
        runCatching { unregisterReceiver(packageChanges) }
        packageChangesRegistered = false
    }

    private fun stop() {
        _running.value = false
        unregisterPackageChanges()

        // Before the fd closes: the native loop is inside epoll on it, and it has to be told to
        // come out rather than discovering a closed descriptor underneath itself.
        native?.let {
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        native = null

        worker?.interrupt()
        worker = null

        // Closing the fd is what actually unblocks the worker's read().
        runCatching { tunnel?.close() }
        tunnel = null

        forwarders?.shutdownNow()
        forwarders?.awaitTermination(1, TimeUnit.SECONDS)
        forwarders = null
    }

    /**
     * The resolvers the active network already uses, so forwarded queries keep
     * going wherever they were going. Falls back to public resolvers only when
     * the platform reports none (e.g. mid network change).
     */
    private fun systemDnsServers(): List<Inet4Address> {
        val manager = getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork
        val discovered = active
            ?.let { manager.getLinkProperties(it) }
            ?.dnsServers
            .orEmpty()
            .filterIsInstance<Inet4Address>()

        if (discovered.isNotEmpty()) return discovered
        return FALLBACK_DNS.mapNotNull {
            runCatching { InetAddress.getByName(it) as Inet4Address }.getOrNull()
        }
    }

    // MARK: Packet loop

    private fun runTunnel(fd: ParcelFileDescriptor) {
        try {
            FileInputStream(fd.fileDescriptor).use { input ->
                FileOutputStream(fd.fileDescriptor).use { output ->
                    val buffer = ByteArray(MTU)
                    while (!Thread.currentThread().isInterrupted) {
                        val read = input.read(buffer)
                        if (read <= 0) continue
                        handlePacket(buffer, read, output)
                    }
                }
            }
        } catch (e: IOException) {
            // Expected on stop(): closing the fd fails the in-flight read().
            Log.d(TAG, "Tunnel closed", e)
        }
    }

    private fun handlePacket(buffer: ByteArray, length: Int, output: FileOutputStream) {
        // Address-level blocking, before anything DNS-shaped is looked for.
        //
        // This is the only rule here that a native app cannot get around. Everything else in this
        // service works by reading the question out of a DNS packet, which an app avoids simply by
        // not asking us — DNS-over-HTTPS, Private DNS, or an address it already knows. These packets
        // are on their way to the service's own addresses, so dropping them ends the connection
        // whatever the app did to find it.
        //
        // Applies to every protocol, TCP and QUIC included, because it never looks past the IPv4
        // header. Dropping means writing nothing: the app sees a connection that goes nowhere.
        val ranges = blockedRanges
        if (ranges.size > 0 && length >= IPV4_HEADER_MIN && (buffer[0].toInt() shr 4 and 0xF) == 4) {
            if (ranges.contains(ServiceRanges.Matcher.addressAt(buffer, IPV4_DESTINATION_OFFSET))) {
                return
            }
        }

        val datagram = Ipv4Udp.parse(buffer, length) ?: return
        if (datagram.destinationPort != DNS_PORT) return

        // Whose query is this? If it belongs to a blocked app, the answer is NXDOMAIN whatever it
        // asked for — the app is what is blocked, not any particular name.
        //
        // This is the *fallback*, not the mechanism. When the relay can forward, the full tunnel
        // drops these packets outright and never reaches here, which also covers the app that uses
        // DNS-over-HTTPS or an address it already knows. This path only covers apps that ask us,
        // and it exists so a device that cannot run the forwarder gets partial blocking rather than
        // none.
        val apps = blockedApps
        if (apps != null && !apps.isEmpty && ownerIsBlocked(datagram, apps)) {
            val refusal = DnsMessage.nxDomainResponse(datagram.payload) ?: return
            writePacket(output, Ipv4Udp.buildResponse(datagram, refusal))
            store.incrementBlockedToday()
            return
        }

        val host = DnsMessage.questionName(datagram.payload)
        if (host.isNullOrEmpty()) {
            // Unparseable or non-standard query — forward it rather than break it.
            forward(datagram, output)
            return
        }

        val decision = Matcher.decide(host, store.settings.value, store.blocklists)
        if (!decision.blocked) {
            forward(datagram, output)
            return
        }

        val response = DnsMessage.nxDomainResponse(datagram.payload) ?: return
        writePacket(output, Ipv4Udp.buildResponse(datagram, response))
        store.incrementBlockedToday()
    }

    /**
     * Whether the app that sent [datagram] is one the user blocked.
     *
     * `getConnectionOwnerUid` is the only sanctioned way to ask — `/proc/net/udp` stopped being
     * readable for other apps' sockets in Android 10, which is the same release that added this.
     * Below API 29 there is no answer available here at all, so those devices get app blocking only
     * once the full tunnel lands (the native forwarder does its own lookup).
     *
     * Returns false whenever the platform declines to say, which is the safe direction: an
     * unattributed query falls through to ordinary domain filtering rather than being refused.
     */
    private fun ownerIsBlocked(datagram: UdpDatagram, apps: BlockedApps): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val uid = runCatching {
            manager.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(
                    InetAddress.getByAddress(datagram.sourceAddress),
                    datagram.sourcePort,
                ),
                InetSocketAddress(
                    InetAddress.getByAddress(datagram.destinationAddress),
                    datagram.destinationPort,
                ),
            )
        }.getOrDefault(Process.INVALID_UID)

        if (uid == Process.INVALID_UID) return false
        return apps.contains(uid)
    }

    /**
     * Relay an allowed query to its original destination and write the reply
     * back into the tunnel. Runs off the read loop so one slow resolver can't
     * stall every other lookup on the device.
     */
    private fun forward(datagram: UdpDatagram, output: FileOutputStream) {
        val pool = forwarders ?: return
        try {
            pool.execute {
                try {
                    DatagramSocket().use { socket ->
                        // Without protect() the socket's own packets would be
                        // routed back into this tunnel and loop forever.
                        if (!protect(socket)) return@use
                        socket.soTimeout = UPSTREAM_TIMEOUT_MS

                        val upstream = InetAddress.getByAddress(datagram.destinationAddress)
                        socket.send(
                            DatagramPacket(
                                datagram.payload,
                                datagram.payload.size,
                                upstream,
                                datagram.destinationPort,
                            ),
                        )

                        val buffer = ByteArray(MAX_DNS_RESPONSE)
                        val reply = DatagramPacket(buffer, buffer.size)
                        socket.receive(reply)
                        writePacket(
                            output,
                            Ipv4Udp.buildResponse(datagram, buffer.copyOf(reply.length)),
                        )
                    }
                } catch (e: IOException) {
                    // Timeout or upstream failure: drop it. The client's own
                    // resolver retries, which is the same as any lost datagram.
                    Log.d(TAG, "Upstream DNS forward failed", e)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.d(TAG, "Dropped a query during shutdown", e)
        }
    }

    private fun writePacket(output: FileOutputStream, packet: ByteArray) {
        try {
            synchronized(writeLock) { output.write(packet) }
        } catch (e: IOException) {
            Log.d(TAG, "Failed to write into the tunnel", e)
        }
    }

    // MARK: Notification

    private fun configureIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Separate high-importance channel from the ongoing status notification:
     * the status one is deliberately silent, but a takeover is the one event
     * here that should actually interrupt the user.
     */
    private fun postInterruptedNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = getString(R.string.alert_channel_description) },
        )

        val notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(getString(R.string.alert_interrupted_title))
            .setContentText(getString(R.string.alert_interrupted_text))
            .setStyle(
                Notification.BigTextStyle().bigText(getString(R.string.alert_interrupted_text)),
            )
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(configureIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()

        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SafeWorldVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(configureIntent())
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_stop),
                    stop,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val TAG = "SafeWorldVpn"

        const val ACTION_STOP = "com.safeworld.app.action.STOP"

        /** Rebuild the tunnel with current routes, without changing whether protection is on. */
        const val ACTION_REFRESH = "com.safeworld.app.action.REFRESH"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "protection"
        private const val ALERT_NOTIFICATION_ID = 2
        private const val ALERT_CHANNEL_ID = "protection_alerts"

        /** Link-local-ish address for the tun endpoint; nothing else uses it. */
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val TUN_PREFIX_LENGTH = 32

        /** Unique-local (fc00::/7) endpoint, added only under a full tunnel. */
        private const val TUN_ADDRESS_V6 = "fd00:6f77:7361:6665::1"
        private const val TUN_PREFIX_LENGTH_V6 = 128
        private const val IPV4_HOST_PREFIX_LENGTH = 32
        private const val MTU = 1500
        private const val DNS_PORT = 53
        private const val IPV4_HEADER_MIN = 20
        private const val IPV4_DESTINATION_OFFSET = 16
        private const val MAX_DNS_RESPONSE = 4096
        private const val UPSTREAM_TIMEOUT_MS = 5_000
        private const val FORWARDER_THREADS = 4

        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")

        /**
         * Well-known public resolvers, routed into the tunnel so an app that hardcodes one is still
         * filtered.
         *
         * This closes the most common DNS bypass, not all of them: an app using DNS-over-HTTPS is
         * indistinguishable from ordinary HTTPS at this layer, and DNS-over-TLS (Android's "Private
         * DNS") leaves on port 853 to a resolver of the system's choosing. Neither is visible here —
         * see `isPrivateDnsActive`, which at least makes the second one say so out loud.
         */
        private val PUBLIC_RESOLVERS = listOf(
            "8.8.8.8", "8.8.4.4",             // Google
            "1.1.1.1", "1.0.0.1",             // Cloudflare
            "9.9.9.9", "149.112.112.112",     // Quad9
            "208.67.222.222", "208.67.220.220", // OpenDNS
            "94.140.14.14", "94.140.15.15",   // AdGuard
            "76.76.2.0", "76.76.10.0",        // Control D
            "4.2.2.1", "4.2.2.2",             // Level3
        )

        private val _running = MutableStateFlow(false)

        /** Whether the tunnel is currently established. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /**
         * True when some *other* app's VPN is up. Android allows only one
         * active VPN, so this is how a takeover looks from our side once we've
         * been displaced — it lets the UI say "another VPN app is active"
         * rather than a vague "protection is off".
         */
        fun anotherVpnActive(context: Context): Boolean {
            if (running.value) return false
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

        /**
         * True when Android's "Private DNS" (DNS-over-TLS) is on.
         *
         * That sends every lookup encrypted to a resolver on port 853, so nothing reaches the port-53
         * path this tunnel filters — blocking silently stops working while the app still reports
         * itself as protecting the device. There is no way for an app to intercept or disable it, so
         * the only honest response is to say so and let the user turn it off.
         */
        fun isPrivateDnsActive(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val active = manager.activeNetwork ?: return false
            val properties = manager.getLinkProperties(active) ?: return false
            // Strict mode (a named server) always defeats us. Opportunistic mode also uses port 853
            // when the resolver supports it, and `isPrivateDnsActive` covers both.
            return properties.isPrivateDnsActive
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SafeWorldVpnService::class.java))
        }

        /** No-op when the tunnel is not up, so callers need not check first. */
        fun refresh(context: Context) {
            if (!running.value) return
            context.startForegroundService(
                Intent(context, SafeWorldVpnService::class.java).setAction(ACTION_REFRESH),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SafeWorldVpnService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
