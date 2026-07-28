import Foundation
import NetworkExtension
import SafeWorldCore

/// Installs, starts, and stops the system-wide packet tunnel.
///
/// The Safari content blocker and this tunnel are two independent mechanisms:
/// the blocker only affects Safari and needs no permission, while the tunnel
/// covers every app but requires the user to approve a VPN configuration. Both
/// can run at once, and the app treats the tunnel as the optional stronger
/// setting — mirroring how Android's protection switch works.
@MainActor
final class TunnelManager: ObservableObject {

    /// Whether the tunnel is currently carrying traffic.
    @Published private(set) var isRunning = false
    /// Set when the last operation failed, so the UI can say why.
    @Published private(set) var lastError: String?

    private var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?

    init() {
        Task { await reload() }
        observer = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.refreshStatus() }
        }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
    }

    /// Load the existing configuration, if the user has already approved one.
    func reload() async {
        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            manager = managers.first
            refreshStatus()
        } catch {
            lastError = error.localizedDescription
        }
    }

    private func refreshStatus() {
        isRunning = manager?.connection.status == .connected
            || manager?.connection.status == .connecting
    }

    /// Create the VPN configuration if needed and start the tunnel.
    ///
    /// The first call shows the system's "allow VPN configuration" prompt.
    /// Declining is a legitimate answer and surfaces as an error rather than
    /// leaving the UI claiming protection is on.
    func start() async {
        lastError = nil
        do {
            // Filters must be in the shared container before the extension
            // launches — it cannot read the host app's bundle.
            try FilterStaging.stageIfNeeded()

            let manager = try await loadOrCreate()
            manager.isEnabled = true
            try await manager.saveToPreferences()
            // Reload after saving: the saved object is what the system will
            // launch, and starting from a stale one silently does nothing.
            try await manager.loadFromPreferences()
            try manager.connection.startVPNTunnel()
            self.manager = manager
            refreshStatus()
        } catch {
            lastError = error.localizedDescription
            isRunning = false
        }
    }

    func stop() {
        manager?.connection.stopVPNTunnel()
        refreshStatus()
    }

    private func loadOrCreate() async throws -> NETunnelProviderManager {
        let existing = try await NETunnelProviderManager.loadAllFromPreferences()
        if let first = existing.first { return first }

        let manager = NETunnelProviderManager()
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = "com.safeworld.app.tunnel"
        // Required, but meaningless here: the tunnel terminates on the device
        // and connects to no server. Set to a placeholder so the system has
        // something to display.
        proto.serverAddress = "On this device"
        manager.protocolConfiguration = proto
        manager.localizedDescription = "Safe World"
        return manager
    }
}

/// Copies the bundled filters into the App Group container.
///
/// A Network Extension runs in its own process and cannot read the host app's
/// bundle, so the files have to be somewhere both can see. They are copied once
/// and then reused; at ~19 MB this is not something to redo on every launch.
enum FilterStaging {
    enum Failure: Error, LocalizedError {
        case noContainer
        case missingBundledFilter(String)

        var errorDescription: String? {
            switch self {
            case .noContainer:
                return "The shared app group is unavailable."
            case .missingBundledFilter(let name):
                return "The blocklist \(name) is missing from the app."
            }
        }
    }

    /// Copy any filter that isn't already staged, or whose size differs from
    /// the bundled one — which is how an app update ships a new list.
    static func stageIfNeeded() throws {
        guard let container = AppGroup.containerURL() else { throw Failure.noContainer }

        for id in CategoryId.allCases {
            let name = "\(id.rawValue).filter"
            guard let source = Blocklists.bundledFilterURL(for: id) else {
                throw Failure.missingBundledFilter(name)
            }
            let destination = container.appendingPathComponent(name)

            let sourceSize = try FileManager.default
                .attributesOfItem(atPath: source.path)[.size] as? Int ?? -1
            let stagedSize = (try? FileManager.default
                .attributesOfItem(atPath: destination.path)[.size] as? Int) ?? nil

            if stagedSize == sourceSize { continue }

            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.copyItem(at: source, to: destination)
        }
    }
}
