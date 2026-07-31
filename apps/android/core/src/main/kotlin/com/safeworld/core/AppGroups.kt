package com.safeworld.core

/**
 * Apps blocked by the UID that owns their traffic, rather than by the names they look up.
 *
 * **Why this exists.** Every other rule in this app is a domain rule, which means it is really a
 * DNS rule — and a native app does not have to use DNS. It can speak DNS-over-HTTPS, which is
 * indistinguishable from ordinary traffic; it can use an address it cached last week. A browser has
 * to resolve what you typed, so websites are covered. The Facebook app, the YouTube app and Free
 * Fire are not.
 *
 * [ServiceRanges] answers this for two services by dropping their published address ranges, and
 * that is as far as address blocking goes: YouTube shares Google's edge with Search, Gmail and Play,
 * so dropping those prefixes would take the phone off the internet. The only rule that survives
 * every evasion is "this UID sends nothing", and that is what this file feeds.
 *
 * **These are package names, not domains.** Nothing here is list data, so this file is ordinary
 * source and carries none of the constraints described in the repo's `.gitignore`.
 *
 * **A wrong package name fails silently** — it matches no installed app and blocks nothing, which
 * looks exactly like the feature working. So this list is deliberately short and only holds names
 * verified against a real device (`adb shell pm list packages`). Anything uncertain is left out on
 * purpose: the in-app picker enumerates what is actually installed, which covers the long tail far
 * more reliably than a guessed constant would.
 */
object AppGroups {

    /**
     * @param id stable identifier, used as the persisted key.
     * @param category the category whose switch turns this group on, or null when the group has its
     *   own switch. Social and entertainment apps ride on the category the user already toggles —
     *   asking twice ("block social sites?" then "block social apps?") is a distinction the user
     *   did not make and does not want to make.
     */
    enum class AppGroup(val id: String, val category: CategoryId?) {
        SOCIAL("apps_social", CategoryId.SOCIAL),
        ENTERTAINMENT("apps_entertainment", CategoryId.ENTERTAINMENT),

        /** Its own switch: there is no "games" domain category, and it does not need one. */
        GAMES("apps_games", null);

        companion object {
            fun fromId(raw: String): AppGroup? = entries.firstOrNull { it.id == raw }
        }
    }

    /**
     * @param label English only, same reason as [CategoryMeta.label] — this module is plain JVM and
     *   cannot reach Android resources. `ui/AppGroupStrings.kt` maps these to translated text.
     */
    data class AppEntry(val packageName: String, val label: String, val group: AppGroup)

    /**
     * Messaging is deliberately absent from [AppGroup.SOCIAL].
     *
     * WhatsApp, Messenger, Signal and Telegram are how people reach their family and their job.
     * Someone asking to block social media is asking to stop scrolling, not to become
     * uncontactable, and taking those away silently would be the app overreaching. `ServiceRanges`
     * already warns that Meta's *address* ranges carry WhatsApp and Messenger whether we like it or
     * not; that is a limit of address blocking, not a licence to do the same here where we have the
     * precision to be correct.
     */
    val ALL: List<AppEntry> = listOf(
        // MARK: Social
        AppEntry("com.facebook.katana", "Facebook", AppGroup.SOCIAL),
        AppEntry("com.facebook.lite", "Facebook Lite", AppGroup.SOCIAL),
        AppEntry("com.instagram.android", "Instagram", AppGroup.SOCIAL),
        AppEntry("com.instagram.lite", "Instagram Lite", AppGroup.SOCIAL),
        AppEntry("com.zhiliaoapp.musically", "TikTok", AppGroup.SOCIAL),
        AppEntry("com.ss.android.ugc.trill", "TikTok Lite", AppGroup.SOCIAL),
        AppEntry("com.twitter.android", "X (Twitter)", AppGroup.SOCIAL),
        AppEntry("com.snapchat.android", "Snapchat", AppGroup.SOCIAL),
        AppEntry("com.reddit.frontpage", "Reddit", AppGroup.SOCIAL),
        AppEntry("com.pinterest", "Pinterest", AppGroup.SOCIAL),
        AppEntry("com.linkedin.android", "LinkedIn", AppGroup.SOCIAL),
        AppEntry("com.tumblr", "Tumblr", AppGroup.SOCIAL),
        AppEntry("com.imo.android.imoim", "imo", AppGroup.SOCIAL),
        AppEntry("com.likeme.app", "Likee", AppGroup.SOCIAL),

        // MARK: Entertainment
        AppEntry("com.google.android.youtube", "YouTube", AppGroup.ENTERTAINMENT),
        AppEntry("com.google.android.apps.youtube.music", "YouTube Music", AppGroup.ENTERTAINMENT),
        AppEntry("com.google.android.apps.youtube.kids", "YouTube Kids", AppGroup.ENTERTAINMENT),
        AppEntry("com.netflix.mediaclient", "Netflix", AppGroup.ENTERTAINMENT),
        AppEntry("tv.twitch.android.app", "Twitch", AppGroup.ENTERTAINMENT),
        AppEntry("com.spotify.music", "Spotify", AppGroup.ENTERTAINMENT),
        AppEntry("com.amazon.avod.thirdpartyclient", "Prime Video", AppGroup.ENTERTAINMENT),
        AppEntry("com.disney.disneyplus", "Disney+", AppGroup.ENTERTAINMENT),
        AppEntry("in.startv.hotstar", "Hotstar", AppGroup.ENTERTAINMENT),
        AppEntry("com.mxtech.videoplayer.ad", "MX Player", AppGroup.ENTERTAINMENT),
        AppEntry("com.bongobd.bongobd", "Bongo", AppGroup.ENTERTAINMENT),

        // MARK: Games — time-wasting
        AppEntry("com.dts.freefireth", "Free Fire", AppGroup.GAMES),
        AppEntry("com.dts.freefiremax", "Free Fire MAX", AppGroup.GAMES),
        AppEntry("com.tencent.ig", "PUBG Mobile", AppGroup.GAMES),
        AppEntry("com.pubg.imobile", "Battlegrounds Mobile India", AppGroup.GAMES),
        AppEntry("com.pubg.krmobile", "PUBG Mobile KR", AppGroup.GAMES),
        AppEntry("com.activision.callofduty.shooter", "Call of Duty: Mobile", AppGroup.GAMES),
        AppEntry("com.mobile.legends", "Mobile Legends: Bang Bang", AppGroup.GAMES),
        AppEntry("com.riotgames.league.wildrift", "League of Legends: Wild Rift", AppGroup.GAMES),
        AppEntry("com.supercell.clashofclans", "Clash of Clans", AppGroup.GAMES),
        AppEntry("com.supercell.clashroyale", "Clash Royale", AppGroup.GAMES),
        AppEntry("com.supercell.brawlstars", "Brawl Stars", AppGroup.GAMES),
        AppEntry("com.roblox.client", "Roblox", AppGroup.GAMES),
        AppEntry("com.innersloth.spacemafia", "Among Us", AppGroup.GAMES),
        AppEntry("com.nianticlabs.pokemongo", "Pokémon GO", AppGroup.GAMES),
        AppEntry("com.king.candycrushsaga", "Candy Crush Saga", AppGroup.GAMES),
        AppEntry("com.miniclip.eightballpool", "8 Ball Pool", AppGroup.GAMES),
        AppEntry("com.ludo.king", "Ludo King", AppGroup.GAMES),
        AppEntry("com.ea.gp.fifamobile", "EA SPORTS FC Mobile", AppGroup.GAMES),

        // MARK: Games — real money
        //
        // Same group on purpose. These are gambling wearing a game's clothes, and someone asking to
        // stop wasting time on games has not opted out of also stopping the ones that take money.
        // Their *websites* are already blocked by the mandatory gambling list; this is the app half.
        AppEntry("com.app.dream11Pro", "Dream11", AppGroup.GAMES),
        AppEntry("com.mpl.androidapp", "MPL", AppGroup.GAMES),
        AppEntry("com.winzo.gold", "WinZO", AppGroup.GAMES),
        AppEntry("com.octro.teenpatti", "Teen Patti by Octro", AppGroup.GAMES),
        AppEntry("com.moonfrog.teenpatti.gold", "Teen Patti Gold", AppGroup.GAMES),
        AppEntry("com.zupee.ludo", "Zupee", AppGroup.GAMES),
    )

    fun forGroup(group: AppGroup): List<AppEntry> = ALL.filter { it.group == group }

    /**
     * The groups currently switched on.
     *
     * @param gamesEnabled [AppGroup.GAMES] has no category behind it, so its switch is passed in.
     */
    fun enabledGroups(settings: Settings, gamesEnabled: Boolean): Set<AppGroup> =
        AppGroup.entries.filterTo(mutableSetOf()) { group ->
            val category = group.category
            if (category == null) gamesEnabled else settings.categories[category] == true
        }

    /**
     * Every package the user's choices add up to: the enabled groups' catalogues plus whatever they
     * picked by hand.
     *
     * Hand-picked packages are **not** filtered by group, because they were chosen individually and
     * there is nothing to infer a group from. That also means they survive turning a group off,
     * which is correct: unticking "Games" should not silently un-block an app the user added
     * themselves.
     */
    fun blockedPackages(
        settings: Settings,
        gamesEnabled: Boolean,
        userPicked: Set<String>,
    ): Set<String> {
        val groups = enabledGroups(settings, gamesEnabled)
        val packages = ALL.filterTo(mutableSetOf()) { it.group in groups }.mapTo(mutableSetOf()) {
            it.packageName
        }
        packages.addAll(userPicked)
        return packages
    }
}
