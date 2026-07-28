import NetworkExtension
import OSLog
import SafeWorldCore

/// System-wide DNS filtering for iOS — the counterpart to Android's
/// `SafeWorldVpnService`, and the reason blocking works outside Safari.
///
/// ## Why a packet tunnel
///
/// The Safari content blocker only ever sees Safari. Everything else on the
/// device — other browsers, apps with embedded web views, anything resolving a
/// hostname — bypasses it entirely. A `NEPacketTunnelProvider` is the only
/// mechanism available to a normal App Store app that sees traffic
/// system-wide: `NEDNSProxyProvider` and `NEFilterDataProvider` both require a
/// supervised (MDM-managed) device, which rules them out for consumer use.
///
/// Like the Android tunnel, this one goes **nowhere**. There is no remote VPN
/// server and no traffic leaves the device through it. It claims the DNS
/// resolvers, answers blocked names locally, and forwards the rest to a real
/// resolver.
///
/// ## Memory
///
/// Network Extensions run in a separate process under a hard memory cap well
/// below the size of the blocklist. That is why `FuseFilter` memory-maps its
/// file instead of reading it into an array: the pages stay file-backed and
/// evictable, and a lookup touches three of them. Loading 19 MB onto the heap
/// here would get the process killed.
final class PacketTunnelProvider: NEPacketTunnelProvider {

    private let log = Logger(subsystem: "com.safeworld.app", category: "tunnel")

    /// Addresses inside the tunnel. Private range, never routed anywhere.
    private let tunnelAddress = "10.111.222.1"
    private let tunnelMask = "255.255.255.0"

    /// The resolver queries are forwarded to when they are allowed through.
    ///
    /// Unlike Android — which can read the system's real resolvers and forward
    /// to exactly those — iOS gives an extension no way to see them. A fixed
    /// public resolver is the only option, so this is a genuine behavioural
    /// difference: on iOS an allowed lookup goes to Cloudflare rather than to
    /// whatever DHCP handed the device.
    private let upstreamResolver = "1.1.1.1"

    private var engine: FilterEngine?
    private var session: NWUDPSession?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        do {
            engine = try FilterEngine()
        } catch {
            // Fail loudly rather than starting a tunnel that forwards
            // everything: a filter that silently matches nothing is the worst
            // outcome, because the app still looks like it is protecting.
            log.error("refusing to start: \(error.localizedDescription, privacy: .public)")
            completionHandler(error)
            return
        }

        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: tunnelAddress)

        let ipv4 = NEIPv4Settings(addresses: [tunnelAddress], subnetMasks: [tunnelMask])
        // Route nothing by default. Only the DNS settings below pull traffic
        // in, so ordinary connections take their normal path and this cannot
        // become a bottleneck for the whole device.
        ipv4.includedRoutes = []
        settings.ipv4Settings = ipv4

        let dns = NEDNSSettings(servers: [upstreamResolver])
        // Claim every domain, which is what makes this system-wide.
        dns.matchDomains = [""]
        settings.dnsSettings = dns
        settings.mtu = 1500

        setTunnelNetworkSettings(settings) { [weak self] error in
            if let error {
                self?.log.error("tunnel settings rejected: \(error.localizedDescription, privacy: .public)")
                completionHandler(error)
                return
            }
            self?.readPackets()
            completionHandler(nil)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        log.info("stopping: \(reason.rawValue, privacy: .public)")
        session?.cancel()
        session = nil
        engine = nil
        completionHandler()
    }

    /// Reads continuously; `readPackets(completionHandler:)` delivers one batch
    /// and must be re-armed each time.
    private func readPackets() {
        packetFlow.readPackets { [weak self] packets, _ in
            guard let self else { return }
            for packet in packets {
                self.handle(packet: [UInt8](packet))
            }
            self.readPackets()
        }
    }

    private func handle(packet: [UInt8]) {
        guard
            let datagram = Ipv4Udp.parse(packet),
            datagram.destinationPort == 53,
            let name = DnsMessage.questionName(datagram.payload)
        else {
            // Not a DNS query we understand. Dropped rather than guessed at —
            // the tunnel only claims DNS, so this should be rare.
            return
        }

        if engine?.isBlocked(name) == true {
            guard let nx = DnsMessage.nxDomainResponse(datagram.payload) else { return }
            let reply = Ipv4Udp.buildResponse(to: datagram, payload: nx)
            packetFlow.writePackets([Data(reply)], withProtocols: [NSNumber(value: AF_INET)])
            return
        }

        forward(datagram)
    }

    /// Send an allowed query upstream and write the answer back into the tunnel.
    private func forward(_ datagram: UdpDatagram) {
        let endpoint = NWHostEndpoint(hostname: upstreamResolver, port: "53")
        // A fresh session per query. Wasteful compared with Android's pooled
        // sockets, but NWUDPSession has no multiplexing and the extension's
        // memory budget rules out holding many alive.
        let session = createUDPSession(to: endpoint, from: nil)

        session.setReadHandler({ [weak self] datagrams, error in
            guard let self, error == nil, let answer = datagrams?.first else {
                session.cancel()
                return
            }
            let reply = Ipv4Udp.buildResponse(to: datagram, payload: [UInt8](answer))
            self.packetFlow.writePackets([Data(reply)], withProtocols: [NSNumber(value: AF_INET)])
            session.cancel()
        }, maxDatagrams: 1)

        // Writes before the session is ready are queued by NWUDPSession, so
        // there is no need to observe state first.
        session.writeDatagram(Data(datagram.payload)) { error in
            if error != nil { session.cancel() }
        }
    }
}
