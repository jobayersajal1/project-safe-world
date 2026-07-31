import Foundation

/// Which apps the user has chosen to block, shared between the app and the tunnel extension.
///
/// **Lives in `SafeWorldCore`, not in the app, because two processes need it.** The UI writes the
/// selection; `PacketTunnelProvider` — a separate process that the app cannot talk to directly —
/// reads it. The App Group container is the only thing they share, so it is where this goes.
///
/// The tunnel re-reads on every settings change rather than caching at startup: a category toggled
/// while the tunnel is up must apply to the next packet, exactly as it does on Android.
public struct BlockedAppStore: Sendable {

    private let defaults: UserDefaults

    private static let groupsKey = "blockedAppGroups"
    private static let bundlesKey = "blockedBundleIds"
    private static let acknowledgedKey = "fullTunnelAcknowledged"

    public init(defaults: UserDefaults? = nil) {
        self.defaults = defaults ?? UserDefaults(suiteName: AppGroup.identifier) ?? .standard
    }

    // MARK: Groups

    /// The app groups currently switched on.
    ///
    /// **Independent of the categories with the same names.** Blocking instagram.com in Safari and
    /// stopping the Instagram app from reaching the network are different decisions: the first is a
    /// content-blocker rule that costs nothing, the second needs the packet tunnel running. Tying
    /// them together would mean someone who wanted one had to accept the other.
    public var enabledGroups: Set<AppGroups.Group> {
        get {
            let raw = defaults.stringArray(forKey: Self.groupsKey) ?? []
            return Set(raw.compactMap(AppGroups.Group.init(rawValue:)))
        }
        nonmutating set {
            defaults.set(newValue.map(\.rawValue).sorted(), forKey: Self.groupsKey)
        }
    }

    public func setGroup(_ group: AppGroups.Group, blocked: Bool) {
        var next = enabledGroups
        if blocked { next.insert(group) } else { next.remove(group) }
        enabledGroups = next
    }

    // MARK: Hand-picked apps

    /// Bundle identifiers the user entered themselves, beyond the built-in catalogue.
    ///
    /// iOS gives an app no way to enumerate what else is installed — there is no equivalent of
    /// Android's `QUERY_ALL_PACKAGES`, by design. So where Android can show a picker over real
    /// installed apps, here the user supplies the identifier. The UI explains where to find it.
    public var pickedBundleIds: Set<String> {
        get { Set(defaults.stringArray(forKey: Self.bundlesKey) ?? []) }
        nonmutating set { defaults.set(newValue.sorted(), forKey: Self.bundlesKey) }
    }

    /// Whether the user has been shown what routing every packet through the app costs.
    public var fullTunnelAcknowledged: Bool {
        get { defaults.bool(forKey: Self.acknowledgedKey) }
        nonmutating set { defaults.set(newValue, forKey: Self.acknowledgedKey) }
    }

    // MARK: Resolved

    /// Everything the current selection adds up to. Read by the tunnel once per new flow.
    public var blockedBundleIds: Set<String> {
        AppGroups.blockedBundleIds(enabledGroups: enabledGroups, userPicked: pickedBundleIds)
    }

    /// Whether app blocking is on at all, so the tunnel can skip the mechanism entirely on the
    /// ordinary install where nobody asked for it.
    public var isEmpty: Bool {
        enabledGroups.isEmpty && pickedBundleIds.isEmpty
    }
}
