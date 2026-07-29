import Foundation
import SafeWorldCore

/// The privileged half of macOS blocking: a resolver on 127.0.0.1:53.
///
/// A GUI app in /Applications runs as the user and cannot bind a port below 1024, so the actual
/// matching lives here and `launchd` starts it as root. The app installs a LaunchDaemon pointing
/// at this binary and then only has to write settings — it never needs to hold root itself.
///
/// Deliberately small and dependency-free. Everything it does — reading the filters, deciding,
/// answering — is `SafeWorldCore` code already covered by `swift test`, including an end-to-end
/// test of this exact proxy on a high port.
///
///     safeworld-dnsd --filters <dir> [--settings <file>] [--port 53] [--upstream 1.1.1.1]
///
/// Settings are re-read periodically so toggling a category in the app takes effect without
/// restarting the daemon, matching how the Android service reads settings per query.

struct Options {
    var filters = "/Library/Application Support/SafeWorld/filters"
    var settings = "/Library/Application Support/SafeWorld/settings.json"
    var port: UInt16 = 53
    var upstream = "1.1.1.1"
}

func parseOptions() -> Options {
    var options = Options()
    var arguments = Array(CommandLine.arguments.dropFirst())
    while let flag = arguments.first {
        arguments.removeFirst()
        guard let value = arguments.first else { break }
        arguments.removeFirst()
        switch flag {
        case "--filters": options.filters = value
        case "--settings": options.settings = value
        case "--port": options.port = UInt16(value) ?? options.port
        case "--upstream": options.upstream = value
        default: break
        }
    }
    return options
}

/// Read settings written by the app. Falls back to defaults rather than failing: the daemon must
/// still answer every query, and the defaults block the three protection categories — the safe
/// direction when the app has not written yet.
func loadSettings(_ path: String) -> Settings {
    guard
        let data = FileManager.default.contents(atPath: path),
        let decoded = try? JSONDecoder().decode(Settings.self, from: data)
    else {
        return .defaults()
    }
    return decoded
}

let options = parseOptions()

let engine: FilterEngine
do {
    engine = try FilterEngine(
        directory: URL(fileURLWithPath: options.filters),
        settings: loadSettings(options.settings)
    )
} catch {
    // Exit rather than serve without a filter. A resolver that forwards everything looks exactly
    // like a healthy one while blocking nothing, and launchd will report the failure.
    FileHandle.standardError.write(Data("safeworld-dnsd: \(error)\n".utf8))
    exit(1)
}

let proxy = DnsProxy(engine: engine, upstream: options.upstream)
do {
    try proxy.start(port: options.port)
} catch {
    FileHandle.standardError.write(Data("safeworld-dnsd: cannot bind port \(options.port): \(error)\n".utf8))
    exit(1)
}

FileHandle.standardError.write(Data(
    "safeworld-dnsd: serving on 127.0.0.1:\(proxy.boundPort), \(engine.blockedDomainCount) domains\n".utf8
))

// Pick up settings changes without a restart, so toggling a category in the app applies to the
// next query rather than the next launch.
let reload = DispatchSource.makeTimerSource(queue: .global())
reload.schedule(deadline: .now() + 2, repeating: 2)
reload.setEventHandler {
    engine.update(settings: loadSettings(options.settings))
}
reload.resume()

// launchd owns the lifecycle; SIGTERM is how it asks us to stop.
signal(SIGTERM) { _ in exit(0) }
dispatchMain()
