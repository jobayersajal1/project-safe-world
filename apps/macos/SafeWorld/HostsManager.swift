import Foundation

enum HostsManagerError: Error, LocalizedError {
    case readFailed
    case writeFailed(String)

    var errorDescription: String? {
        switch self {
        case .readFailed: return "Could not read /etc/hosts."
        case .writeFailed(let detail): return "Could not update /etc/hosts: \(detail)"
        }
    }
}

/// Applies the SafeWorldCore-managed block to /etc/hosts. Writing requires
/// admin privileges — macOS gives no unprivileged way to edit /etc/hosts —
/// so this stages the new content in a plain temp file the current user can
/// write, then asks the user once via the standard macOS admin-password
/// prompt to copy that staged file into place. The privileged command line
/// only ever contains the fixed staging path and `/etc/hosts`, never
/// user-supplied domains, so there's nothing there for a hostile custom
/// domain entry to break out of.
enum HostsManager {
    static let path = "/etc/hosts"

    static func currentContents() throws -> String {
        guard let contents = try? String(contentsOfFile: path, encoding: .utf8) else {
            throw HostsManagerError.readFailed
        }
        return contents
    }

    /// Writes `newContents` to /etc/hosts if it differs from what's on disk,
    /// then flushes the DNS cache so the change takes effect immediately.
    /// No-ops (no prompt) when nothing changed.
    static func apply(newContents: String) throws {
        let existing = (try? currentContents()) ?? ""
        guard existing != newContents else { return }

        let stagingURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("safeworld-hosts-\(UUID().uuidString).tmp")
        try newContents.write(to: stagingURL, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(at: stagingURL) }

        let command = "cp '\(stagingURL.path)' '\(path)' && dscacheutil -flushcache; killall -HUP mDNSResponder"
        let script = "do shell script \"\(command)\" with administrator privileges " +
            "with prompt \"Safe World needs to update your Mac's block list.\""

        var errorDict: NSDictionary?
        guard let appleScript = NSAppleScript(source: script) else {
            throw HostsManagerError.writeFailed("could not build AppleScript")
        }
        appleScript.executeAndReturnError(&errorDict)
        if let errorDict {
            let message = errorDict[NSAppleScript.errorMessage] as? String ?? "unknown error"
            throw HostsManagerError.writeFailed(message)
        }
    }
}
