import Foundation
import Network

/// A DNS resolver that listens on loopback, answers blocked names locally, and forwards the rest.
///
/// This is what lets macOS block the full uncapped list. The hosts file needs literal domains and
/// is parsed linearly, which caps it; a proxy puts our own code in the path, so it can match
/// against a `FuseFilter` and carry millions of domains in ~19 MB.
///
/// Lives in SafeWorldCore rather than the macOS app so it can be exercised by `swift test` on a
/// high port, with no root and no system changes — the end-to-end check the Windows equivalent
/// never got.
///
/// **Fails open, never closed.** Every failure path forwards or stays silent rather than blocking:
/// if the upstream is unreachable or a packet is malformed, the user keeps working DNS. A blocker
/// that breaks the internet when it breaks is worse than one that stops blocking, because the
/// second is recoverable by the person affected.
public final class DnsProxy: @unchecked Sendable {

    private let engine: FilterEngine
    private let upstream: NWEndpoint
    private let queue = DispatchQueue(label: "com.safeworld.dnsproxy")
    private var listener: NWListener?

    /// Called with the queried name each time one is blocked, so a UI can count them.
    public var onBlocked: (@Sendable (String) -> Void)?

    /// The port actually bound. Useful when starting on port 0 in tests.
    public private(set) var boundPort: UInt16 = 0

    public init(engine: FilterEngine, upstream: String = "1.1.1.1") {
        self.engine = engine
        self.upstream = NWEndpoint.hostPort(host: .init(upstream), port: 53)
    }

    /// Bind and start serving. Port 53 requires root; tests pass a high port instead.
    public func start(port: UInt16) throws {
        let parameters = NWParameters.udp
        parameters.allowLocalEndpointReuse = true
        // Loopback only. This is never reachable off the machine.
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .init(rawValue: port)!)

        let listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }

        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { [weak self] state in
            if case .ready = state {
                self?.boundPort = listener.port?.rawValue ?? port
                ready.signal()
            }
            if case .failed = state { ready.signal() }
        }
        listener.start(queue: queue)
        _ = ready.wait(timeout: .now() + 5)

        self.listener = listener
    }

    public func stop() {
        listener?.cancel()
        listener = nil
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(on: connection)
    }

    /// Read one datagram, then immediately arm for the next.
    ///
    /// A UDP `NWListener` hands out one connection per *source endpoint*, not per datagram, so a
    /// client that reuses its socket — which stub resolvers do — sends every query down the same
    /// connection. Handling one and cancelling drops the rest: measured at 1 answer in 500 under
    /// load, while `dig` looked fine because each invocation uses a fresh source port.
    private func receive(on connection: NWConnection) {
        connection.receiveMessage { [weak self] data, _, _, error in
            guard let self else { return }

            // `isComplete` is deliberately ignored. On UDP it is true for *every* datagram —
            // each one is a complete message — so treating it as end-of-stream cancels the
            // connection before the reply has even flushed, and nothing is ever answered.
            // Only a real error ends the loop.
            if error != nil {
                connection.cancel()
                return
            }
            if let data, !data.isEmpty {
                self.handle(query: [UInt8](data), on: connection)
            }
            self.receive(on: connection)
        }
    }

    private func handle(query: [UInt8], on connection: NWConnection) {
        // The list first, then the model's second opinion for a name no list
        // covers. `isBlocked` stays the whole of `Matcher.decide`; the advisory
        // is a separate call so that equivalence keeps holding.
        if let name = DnsMessage.questionName(query),
           engine.isBlocked(name) || engine.advisoryBlocks(name) {
            if let nx = DnsMessage.nxDomainResponse(query) {
                // Not cancelled: the same connection carries this client's next query.
                connection.send(content: Data(nx), completion: .contentProcessed { _ in })
                onBlocked?(name)
                return
            }
        }
        forward(query: query, to: connection)
    }

    /// Send an allowed query upstream and relay the answer back to the client.
    private func forward(query: [UInt8], to client: NWConnection) {
        let upstreamConnection = NWConnection(to: upstream, using: .udp)
        upstreamConnection.start(queue: queue)

        // Anything that goes wrong here leaves the client without an answer, which it treats as a
        // timeout and retries or falls back — the behaviour it already expects from a flaky
        // network, and far better than a wrong answer.
        upstreamConnection.send(content: Data(query), completion: .contentProcessed { error in
            guard error == nil else {
                // Only the upstream is torn down. Cancelling the client would drop the queries it
                // has yet to send down the same socket.
                upstreamConnection.cancel()
                return
            }
            upstreamConnection.receiveMessage { data, _, _, _ in
                if let data {
                    client.send(content: data, completion: .contentProcessed { _ in })
                }
                upstreamConnection.cancel()
            }
        })
    }
}
