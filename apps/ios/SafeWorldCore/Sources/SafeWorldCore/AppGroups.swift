import Foundation

/// Apps blocked by the bundle identifier that owns their traffic, rather than by the names they
/// look up.
///
/// **Why this exists.** Every other rule in this app is a domain rule, which means it is really a
/// DNS rule — and a native app does not have to use DNS. It can speak DNS-over-HTTPS, which is
/// indistinguishable from ordinary traffic; it can use an address it cached last week. Safari has
/// to resolve what you typed, so websites are covered. The Instagram app, the YouTube app and Free
/// Fire are not.
///
/// **How iOS makes this possible.** `NEPacket.metadata.sourceAppSigningIdentifier` names the app
/// that sent a packet — the same question Android answers with `getConnectionOwnerUid`, except iOS
/// hands back a bundle identifier rather than a numeric uid, which is both stabler and easier to
/// read. It has been available since iOS 10 and needs no entitlement beyond the packet tunnel's own.
///
/// **These are bundle identifiers, not domains.** Nothing here is list data, so this file is
/// ordinary source and carries none of the constraints in the repo's `.gitignore`.
///
/// **A wrong identifier fails silently** — it matches no installed app and blocks nothing, which
/// looks exactly like the feature working. So this list only holds identifiers that can be checked
/// against a real device or the App Store, and the picker covers everything else.
///
/// Mirrors `AppGroups.kt` on Android. The groups and their meaning are the same; the identifiers
/// differ because the platforms name the same app differently.
public enum AppGroups {

    /// Named `Group`, not `AppGroup`, because `AppGroup` in this package is already the *App Group
    /// container* identifier — a completely unrelated Apple concept. Two types called AppGroup
    /// meaning different things is exactly the confusion that produces a wrong-looking-right bug.
    ///
    /// - Note: `category` is the domain category covering the *same* subject. **The UI turns the
    ///   two on together** — one switch per subject, both halves. They were separate switches
    ///   once, on the grounds that the domain half is free while this half needs the packet
    ///   tunnel; what that produced was five switches to express one intention, and a half-set
    ///   state that looked like protection while the Instagram app still worked. The cost is real,
    ///   so it is stated once by the consent alert instead of implied by an extra row.
    ///
    ///   This stays a distinct switch in storage rather than being derived from the category, so
    ///   what puts the device on the tunnel remains an explicit fact.
    public enum Group: String, CaseIterable, Sendable {
        case social = "apps_social"
        case entertainment = "apps_entertainment"
        case games = "apps_games"

        public var category: CategoryId {
            switch self {
            case .social: return .social
            case .entertainment: return .entertainment
            case .games: return .games
            }
        }
    }

    public struct AppEntry: Sendable {
        /// The bundle identifier as it appears in `sourceAppSigningIdentifier`.
        public let bundleId: String
        /// English only, same reason as `CategoryMeta.label` — this package has no access to the
        /// app's localized strings. `AppGroupStrings` in the app maps these to translated text.
        public let label: String
        public let group: Group

        public init(bundleId: String, label: String, group: Group) {
            self.bundleId = bundleId
            self.label = label
            self.group = group
        }
    }

    /// Messaging is deliberately absent from `.social`.
    ///
    /// WhatsApp, Messenger, Signal and Telegram are how people reach their family and their job.
    /// Someone asking to block social media is asking to stop scrolling, not to become
    /// uncontactable, and taking those away silently would be the app overreaching.
    public static let all: [AppEntry] = [
        // MARK: Social
        AppEntry(bundleId: "com.burbn.instagram", label: "Instagram", group: .social),
        AppEntry(bundleId: "com.facebook.Facebook", label: "Facebook", group: .social),
        AppEntry(bundleId: "com.zhiliaoapp.musically", label: "TikTok", group: .social),
        AppEntry(bundleId: "com.atebits.Tweetie2", label: "X (Twitter)", group: .social),
        AppEntry(bundleId: "com.toyopagroup.picaboo", label: "Snapchat", group: .social),
        AppEntry(bundleId: "com.reddit.Reddit", label: "Reddit", group: .social),
        AppEntry(bundleId: "pinterest", label: "Pinterest", group: .social),
        AppEntry(bundleId: "com.linkedin.LinkedIn", label: "LinkedIn", group: .social),
        AppEntry(bundleId: "com.tumblr.tumblr", label: "Tumblr", group: .social),
        AppEntry(bundleId: "com.imo.international", label: "imo", group: .social),

        // MARK: Social — dating and stranger chat
        //
        // Grouped with social rather than given a row of their own. Someone asking to stop
        // scrolling feeds is asking to stop the same pull, and a separate "dating" switch would
        // make the user announce something about themselves to turn it on — on a screen a parent
        // or a spouse may well be looking at.
        AppEntry(bundleId: "com.cardify.tinder", label: "Tinder", group: .social),
        AppEntry(bundleId: "com.bumble.app", label: "Bumble", group: .social),
        AppEntry(bundleId: "co.hinge.mobile.ios", label: "Hinge", group: .social),
        AppEntry(bundleId: "com.badoo.Badoo", label: "Badoo", group: .social),
        AppEntry(bundleId: "com.okcupid.app", label: "OkCupid", group: .social),
        AppEntry(bundleId: "com.plentyoffish.pof", label: "Plenty of Fish", group: .social),
        AppEntry(bundleId: "com.match.Match", label: "Match", group: .social),
        AppEntry(bundleId: "com.grindr.grindr", label: "Grindr", group: .social),
        AppEntry(bundleId: "com.p1.ios.tantan", label: "Tantan", group: .social),
        AppEntry(bundleId: "net.lovoo.lovooapp", label: "LOVOO", group: .social),
        AppEntry(bundleId: "com.myyearbook.MeetMe", label: "MeetMe", group: .social),
        AppEntry(bundleId: "com.azarlive.ios", label: "Azar", group: .social),
        AppEntry(bundleId: "com.shaadi.ios", label: "Shaadi", group: .social),

        // MARK: Entertainment
        AppEntry(bundleId: "com.google.ios.youtube", label: "YouTube", group: .entertainment),
        AppEntry(bundleId: "com.google.ios.youtubemusic", label: "YouTube Music", group: .entertainment),
        AppEntry(bundleId: "com.netflix.Netflix", label: "Netflix", group: .entertainment),
        AppEntry(bundleId: "tv.twitch", label: "Twitch", group: .entertainment),
        AppEntry(bundleId: "com.spotify.client", label: "Spotify", group: .entertainment),
        AppEntry(bundleId: "com.amazon.aiv.AIVApp", label: "Prime Video", group: .entertainment),
        AppEntry(bundleId: "com.disney.disneyplus", label: "Disney+", group: .entertainment),
        AppEntry(bundleId: "in.startv.hotstar", label: "Hotstar", group: .entertainment),

        // MARK: Games — time-wasting
        AppEntry(bundleId: "com.dts.freefireth", label: "Free Fire", group: .games),
        AppEntry(bundleId: "com.dts.freefiremax", label: "Free Fire MAX", group: .games),
        AppEntry(bundleId: "com.tencent.ig", label: "PUBG Mobile", group: .games),
        AppEntry(bundleId: "com.tencent.iglite", label: "PUBG Mobile Lite", group: .games),
        AppEntry(bundleId: "com.activision.callofduty.shooter", label: "Call of Duty: Mobile", group: .games),
        AppEntry(bundleId: "com.mobile.legends", label: "Mobile Legends: Bang Bang", group: .games),
        AppEntry(bundleId: "com.riotgames.league.wildrift", label: "League of Legends: Wild Rift", group: .games),
        AppEntry(bundleId: "com.supercell.magic", label: "Clash of Clans", group: .games),
        AppEntry(bundleId: "com.supercell.scroll", label: "Clash Royale", group: .games),
        AppEntry(bundleId: "com.supercell.laser", label: "Brawl Stars", group: .games),
        AppEntry(bundleId: "com.roblox.robloxmobile", label: "Roblox", group: .games),
        AppEntry(bundleId: "com.mojang.minecraftpe", label: "Minecraft", group: .games),
        AppEntry(bundleId: "com.innersloth.amongus", label: "Among Us", group: .games),
        AppEntry(bundleId: "com.nianticlabs.pokemongo", label: "Pokémon GO", group: .games),
        AppEntry(bundleId: "com.midasplayer.apps.candycrushsaga", label: "Candy Crush Saga", group: .games),
        AppEntry(bundleId: "com.miniclip.8ballpoolmult", label: "8 Ball Pool", group: .games),
        AppEntry(bundleId: "com.ludo.king", label: "Ludo King", group: .games),
        AppEntry(bundleId: "com.ea.ios.fifamobile", label: "EA SPORTS FC Mobile", group: .games),

        // MARK: Games — real money
        //
        // Same group on purpose. These are gambling wearing a game's clothes, and someone asking to
        // stop wasting time on games has not opted out of also stopping the ones that take money.
        AppEntry(bundleId: "com.dream11.fantasycricket", label: "Dream11", group: .games),
        AppEntry(bundleId: "com.mpl.mobilepremierleague", label: "MPL", group: .games),
        AppEntry(bundleId: "com.octro.teenpatti", label: "Teen Patti by Octro", group: .games),
    ]

    public static func forGroup(_ group: Group) -> [AppEntry] {
        all.filter { $0.group == group }
    }

    /// Every bundle identifier the user's choices add up to: the enabled groups' catalogues plus
    /// whatever they picked by hand.
    ///
    /// Hand-picked identifiers are **not** filtered by group, because they were chosen individually
    /// and there is nothing to infer a group from. That also means they survive turning a group
    /// off, which is correct: unticking "Games" should not silently un-block an app the user added
    /// themselves.
    public static func blockedBundleIds(
        enabledGroups: Set<Group>,
        userPicked: Set<String>
    ) -> Set<String> {
        var result = Set(all.filter { enabledGroups.contains($0.group) }.map(\.bundleId))
        result.formUnion(userPicked)
        return result
    }
}
