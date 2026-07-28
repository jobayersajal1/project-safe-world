package com.safeworld.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.safeworld.app.LocaleHelper
import com.safeworld.app.MainActivity
import com.safeworld.app.R
import com.safeworld.app.SettingsStore
import com.safeworld.core.Matcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
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

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var forwarders: ExecutorService? = null

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
        val dnsServers = systemDnsServers()

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
            .setMtu(MTU)
            .setBlocking(true)
            .setConfigureIntent(configureIntent())

        // Routing *only* the resolvers means every other packet on the device
        // takes its normal path and never enters this process.
        for (server in dnsServers) {
            builder.addDnsServer(server)
            server.hostAddress?.let { builder.addRoute(it, IPV4_HOST_PREFIX_LENGTH) }
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
        worker = Thread({ runTunnel(fd) }, "safe-world-dns").apply {
            isDaemon = true
            start()
        }
        _running.value = true
        // Records that protection has genuinely run at least once, which is what
        // makes a later "it stopped" claim true rather than just noticing that a
        // fresh install isn't running yet.
        store.markProtectionStarted()
    }

    private fun stop() {
        _running.value = false

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
        val datagram = Ipv4Udp.parse(buffer, length) ?: return
        if (datagram.destinationPort != DNS_PORT) return

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

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "protection"
        private const val ALERT_NOTIFICATION_ID = 2
        private const val ALERT_CHANNEL_ID = "protection_alerts"

        /** Link-local-ish address for the tun endpoint; nothing else uses it. */
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val TUN_PREFIX_LENGTH = 32
        private const val IPV4_HOST_PREFIX_LENGTH = 32
        private const val MTU = 1500
        private const val DNS_PORT = 53
        private const val MAX_DNS_RESPONSE = 4096
        private const val UPSTREAM_TIMEOUT_MS = 5_000
        private const val FORWARDER_THREADS = 4

        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")

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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SafeWorldVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SafeWorldVpnService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
