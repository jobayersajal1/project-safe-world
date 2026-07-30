import Foundation
import SafeWorldCore

/// Installs, starts, and removes the root DNS daemon that does the uncapped blocking.
///
/// **Why a daemon at all.** The hosts file can only sinkhole exact names and is parsed linearly, so
/// it ships a capped 50,000 domains per category — about 150,000 in total. `safeworld-dnsd` puts our
/// own code in the resolution path, matching against memory-mapped fuse filters, which carries the
/// full **4,482,470**. Windows already works this way (`ProxyController`); this is the macOS
/// equivalent, and the reason the app had none is that installation lived only in
/// `apps/macos/dnsd/safeworld-dnsd.sh`.
///
/// **The ordering below is the whole safety story, and it is copied from that script deliberately.**
/// Each step exists because the alternative leaves a Mac with no name resolution:
///
/// 1. save the current resolver *before* changing anything, so a half-finished install is recoverable
/// 2. stage the binary and filters, write the LaunchDaemon, load it
/// 3. **prove the daemon answers on port 53 before touching DNS** — a failed install must not take
///    the machine's DNS with it
/// 4. only then point DNS at 127.0.0.1, keeping the real resolver as *secondary* so a dead daemon
///    degrades to unfiltered DNS rather than none
///
/// Uninstall reverses it: DNS first, daemon second.
@MainActor
final class DaemonController: ObservableObject {

    @Published private(set) var isInstalled = false
    @Published private(set) var isRunning = false
    /// How many domains the daemon covers, read from the staged filter headers.
    @Published private(set) var blockedDomainCount = 0
    /// Set when the last operation failed, so the menu can say why instead of silently reverting.
    @Published private(set) var lastError: String?
    @Published private(set) var isBusy = false

    private let prefix = "/Library/Application Support/SafeWorld"
    private let plistPath = "/Library/LaunchDaemons/com.safeworld.dnsd.plist"
    private let label = "com.safeworld.dnsd"

    init() { refresh() }

    // MARK: Status

    func refresh() {
        let fm = FileManager.default
        isInstalled = fm.fileExists(atPath: plistPath)
            && fm.fileExists(atPath: "\(prefix)/safeworld-dnsd")
        isRunning = isInstalled && launchctlLists()
        blockedDomainCount = stagedDomainCount()
    }

    private func launchctlLists() -> Bool {
        // `launchctl list <label>` exits non-zero when the job is not loaded, which is a cheaper and
        // more reliable signal than parsing the full list.
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        task.arguments = ["list", label]
        task.standardOutput = FileHandle.nullDevice
        task.standardError = FileHandle.nullDevice
        do { try task.run() } catch { return false }
        task.waitUntilExit()
        return task.terminationStatus == 0
    }

    /// Sums the staged filters' headers. Nine bytes each, so this is not worth caching.
    private func stagedDomainCount() -> Int {
        CategoryId.allCases.reduce(0) { total, id in
            let path = "\(prefix)/filters/\(id.rawValue).filter"
            return total + (FuseFilter.entryCount(atPath: path) ?? 0)
        }
    }

    // MARK: Install

    /// Stages everything into a user-writable temp directory first, then performs the privileged
    /// half in **one** authorization prompt. Asking repeatedly for the same install is how people
    /// learn to click through prompts without reading them.
    func install(settings: Settings) async {
        isBusy = true
        lastError = nil
        defer { isBusy = false }

        do {
            let staging = try stageForInstall(settings: settings)
            defer { try? FileManager.default.removeItem(at: staging) }

            let upstream = currentResolver() ?? "1.1.1.1"
            try runPrivileged(installScript(staging: staging.path, upstream: upstream))

            refresh()
            guard isRunning else {
                lastError = "The blocking service did not start. Your DNS settings were not changed."
                return
            }
        } catch {
            lastError = error.localizedDescription
        }
        refresh()
    }

    func uninstall() async {
        isBusy = true
        lastError = nil
        defer { isBusy = false }

        do {
            try runPrivileged(uninstallScript())
        } catch {
            lastError = error.localizedDescription
        }
        refresh()
    }

    /// Copies the daemon binary, the filters, and the current settings somewhere the privileged
    /// script can read them. Done unprivileged so a failure here costs nothing.
    private func stageForInstall(settings: Settings) throws -> URL {
        let fm = FileManager.default
        let staging = fm.temporaryDirectory
            .appendingPathComponent("safeworld-install-\(UUID().uuidString)")
        try fm.createDirectory(at: staging.appendingPathComponent("filters"), withIntermediateDirectories: true)

        guard let helper = Bundle.main.url(forAuxiliaryExecutable: "safeworld-dnsd")
            ?? bundledHelperURL()
        else {
            throw Failure.helperMissing
        }
        try fm.copyItem(at: helper, to: staging.appendingPathComponent("safeworld-dnsd"))

        var copied = 0
        for id in CategoryId.allCases {
            guard let source = Blocklists.bundledFilterURL(for: id) else { continue }
            try fm.copyItem(
                at: source,
                to: staging.appendingPathComponent("filters/\(id.rawValue).filter")
            )
            copied += 1
        }
        guard copied > 0 else { throw Failure.filtersMissing }

        // The daemon runs as root and cannot read this app's UserDefaults, so settings travel as a
        // file. Left owned by the installing user afterwards, which is what lets the unprivileged
        // app keep it current — see `SettingsStore.publishForDaemon`.
        let encoded = try JSONEncoder().encode(settings)
        try encoded.write(to: staging.appendingPathComponent("settings.json"))

        try Data(plistContents.utf8).write(to: staging.appendingPathComponent("com.safeworld.dnsd.plist"))
        return staging
    }

    /// `Contents/Helpers/safeworld-dnsd`, for builds where the copy phase put it there rather than
    /// in the location `forAuxiliaryExecutable` searches.
    private func bundledHelperURL() -> URL? {
        let url = Bundle.main.bundleURL
            .appendingPathComponent("Contents/Helpers/safeworld-dnsd")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    // MARK: Scripts

    /// The resolver actually in use, which on DHCP is not visible through `networksetup`.
    private func currentResolver() -> String? {
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/bin/sh")
        task.arguments = ["-c", "scutil --dns | awk '/nameserver\\[0\\]/ {print $3; exit}'"]
        let pipe = Pipe()
        task.standardOutput = pipe
        task.standardError = FileHandle.nullDevice
        guard (try? task.run()) != nil else { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        task.waitUntilExit()
        let value = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        // Never hand back our own address as the fallback: that would make the daemon its own
        // upstream and every allowed lookup would loop.
        return (value.isEmpty || value == "127.0.0.1") ? nil : value
    }

    private func installScript(staging: String, upstream: String) -> String {
        let owner = NSUserName()
        return """
        set -e
        mkdir -p '\(prefix)/filters'
        cp '\(staging)/safeworld-dnsd' '\(prefix)/safeworld-dnsd'
        chmod 755 '\(prefix)/safeworld-dnsd'
        cp '\(staging)/filters/'*.filter '\(prefix)/filters/'
        if [ ! -f '\(prefix)/settings.json' ]; then
          cp '\(staging)/settings.json' '\(prefix)/settings.json'
        fi
        chown '\(owner)' '\(prefix)' '\(prefix)/settings.json'
        printf '%s\\n' '\(upstream)' > '\(prefix)/dns-restore.txt'

        # An uninstaller that outlives the app.
        #
        # The daemon is a root LaunchDaemon with KeepAlive and does not depend on the app existing —
        # dragging Safe World to the Trash leaves it running and blocking, with no UI left to turn it
        # off. That is deliberate (deleting an app must not be a one-step bypass) but it must not be a
        # trap, so the way out is written next to the daemon itself:
        #
        #   sudo '\(prefix)/uninstall.sh'
        cat > '\(prefix)/uninstall.sh' <<'SAFEWORLD_UNINSTALL'
        #!/bin/bash
        # Removes Safe World's system-wide blocking. Run with: sudo "$0"
        # Safe to run even if the app has been deleted, and safe to run twice.
        if [ "$(id -u)" -ne 0 ]; then echo "Run with: sudo $0" >&2; exit 1; fi
        # DNS first, always: removing the daemon while the system still points at it would leave a
        # window with no resolver at all.
        networksetup -listallnetworkservices | tail -n +2 | while IFS= read -r svc; do
          networksetup -setdnsservers "$svc" empty 2>/dev/null || true
        done
        dscacheutil -flushcache 2>/dev/null || true
        killall -HUP mDNSResponder 2>/dev/null || true
        launchctl unload '\(plistPath)' 2>/dev/null || true
        rm -f '\(plistPath)'
        rm -rf '\(prefix)'
        echo "Safe World blocking removed. DNS is back to DHCP."
        SAFEWORLD_UNINSTALL
        chmod 755 '\(prefix)/uninstall.sh'

        cp '\(staging)/com.safeworld.dnsd.plist' '\(plistPath)'
        chown root:wheel '\(plistPath)'
        chmod 644 '\(plistPath)'
        launchctl unload '\(plistPath)' 2>/dev/null || true
        launchctl load '\(plistPath)'

        sleep 2
        # Nothing below this line runs unless the daemon is actually answering, so a failed install
        # leaves DNS exactly as it was.
        if ! dig +short +time=3 +tries=1 @127.0.0.1 wikipedia.org >/dev/null 2>&1; then
          launchctl unload '\(plistPath)' 2>/dev/null || true
          rm -f '\(plistPath)'
          exit 1
        fi

        # The real resolver stays as secondary. macOS only falls back on timeout, and NXDOMAIN is a
        # valid answer rather than a failure — so blocking still works, but if the daemon dies the
        # machine keeps resolving instead of losing DNS entirely.
        networksetup -listallnetworkservices | tail -n +2 | while IFS= read -r svc; do
          networksetup -setdnsservers "$svc" 127.0.0.1 '\(upstream)' 2>/dev/null || true
        done
        dscacheutil -flushcache 2>/dev/null || true
        killall -HUP mDNSResponder 2>/dev/null || true
        """
    }

    private func uninstallScript() -> String {
        """
        # DNS first, always. Removing the daemon while the system still points at it would leave a
        # window with no resolver at all.
        networksetup -listallnetworkservices | tail -n +2 | while IFS= read -r svc; do
          networksetup -setdnsservers "$svc" empty 2>/dev/null || true
        done
        dscacheutil -flushcache 2>/dev/null || true
        killall -HUP mDNSResponder 2>/dev/null || true

        launchctl unload '\(plistPath)' 2>/dev/null || true
        rm -f '\(plistPath)'
        rm -rf '\(prefix)'
        """
    }

    private var plistContents: String {
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <key>Label</key><string>\(label)</string>
          <key>ProgramArguments</key>
          <array>
            <string>\(prefix)/safeworld-dnsd</string>
            <string>--filters</string><string>\(prefix)/filters</string>
            <string>--settings</string><string>\(prefix)/settings.json</string>
            <string>--port</string><string>53</string>
          </array>
          <key>RunAtLoad</key><true/>
          <!-- If the daemon dies while DNS points at 127.0.0.1, resolution depends on the secondary
               resolver until launchd brings it back. KeepAlive holds that window to about a second. -->
          <key>KeepAlive</key><true/>
          <key>StandardErrorPath</key><string>/var/log/safeworld-dnsd.log</string>
          <key>StandardOutPath</key><string>/var/log/safeworld-dnsd.log</string>
        </dict>
        </plist>
        """
    }

    /// Runs `script` as root via one authorization prompt.
    ///
    /// **No user-supplied text is ever interpolated into this.** Everything substituted above is a
    /// constant path, a temp directory we created, the login name, or an IP read from `scutil` and
    /// checked. Custom allow/block domains reach the daemon through `settings.json`, never through a
    /// command line — the shell script this is ported from is careful about the same thing.
    private func runPrivileged(_ script: String) throws {
        let source = """
        do shell script "\(script.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\""))" with administrator privileges
        """

        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        task.arguments = ["-e", source]
        let errors = Pipe()
        task.standardError = errors
        task.standardOutput = FileHandle.nullDevice

        try task.run()
        let errorData = errors.fileHandleForReading.readDataToEndOfFile()
        task.waitUntilExit()

        guard task.terminationStatus == 0 else {
            let message = String(decoding: errorData, as: UTF8.self)
            if message.contains("-128") {
                // The user cancelled the password prompt. Not an error worth shouting about.
                throw Failure.cancelled
            }
            throw Failure.privilegedFailed(message.trimmingCharacters(in: .whitespacesAndNewlines))
        }
    }

    enum Failure: LocalizedError {
        case helperMissing
        case filtersMissing
        case cancelled
        case privilegedFailed(String)

        var errorDescription: String? {
            switch self {
            case .helperMissing:
                return "This build is missing its blocking service, so only the smaller list can be used."
            case .filtersMissing:
                return "This build is missing its blocklists."
            case .cancelled:
                return "Cancelled."
            case .privilegedFailed(let message):
                return message.isEmpty
                    ? "The blocking service could not be installed."
                    : "The blocking service could not be installed: \(message)"
            }
        }
    }
}
