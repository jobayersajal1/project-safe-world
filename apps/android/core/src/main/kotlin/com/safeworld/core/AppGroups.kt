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
     * @param category the domain category covering the *same* subject. **The UI turns the two on
     *   together** — one switch per subject, both halves. They were separate switches once, on the
     *   grounds that the domain half is free while this half routes every packet on the device
     *   through us; what that produced was five switches to express one intention, and a half-set
     *   state that looked like protection while the Instagram app still worked. The cost is real,
     *   so it is now stated once by the consent dialog instead of implied by an extra row.
     *
     *   This stays a distinct switch in storage — `TunnelMode.perAppSwitchesOn` reads the enabled
     *   groups and never `Settings.categories` — so what drives the expensive tunnel is still an
     *   explicit fact rather than something derived.
     */
    enum class AppGroup(val id: String, val category: CategoryId) {
        SOCIAL("apps_social", CategoryId.SOCIAL),
        ENTERTAINMENT("apps_entertainment", CategoryId.ENTERTAINMENT),
        GAMES("apps_games", CategoryId.GAMES);

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

        // MARK: Social — dating and stranger chat
        //
        // Grouped with social rather than given a row of their own. Someone asking to stop
        // scrolling feeds is asking to stop the same pull, and a separate "dating" switch would
        // make the user announce something about themselves to turn it on — on a screen a parent
        // or spouse may be looking at. The random-video-chat apps sit here too: they are the same
        // behaviour with the matching step removed, and they are where the worst of it happens.
        AppEntry("com.tinder", "Tinder", AppGroup.SOCIAL),
        AppEntry("com.bumble.app", "Bumble", AppGroup.SOCIAL),
        AppEntry("co.hinge.app", "Hinge", AppGroup.SOCIAL),
        AppEntry("com.badoo.mobile", "Badoo", AppGroup.SOCIAL),
        AppEntry("com.okcupid.okcupid", "OkCupid", AppGroup.SOCIAL),
        AppEntry("com.pof.android", "Plenty of Fish", AppGroup.SOCIAL),
        AppEntry("com.match.android.matchmobile", "Match", AppGroup.SOCIAL),
        AppEntry("com.zoosk.zoosk", "Zoosk", AppGroup.SOCIAL),
        AppEntry("com.ftw_and_co.happn", "Happn", AppGroup.SOCIAL),
        AppEntry("com.grindrapp.android", "Grindr", AppGroup.SOCIAL),
        AppEntry("com.p1.mobile.putong", "Tantan", AppGroup.SOCIAL),
        AppEntry("com.lovoo.android", "LOVOO", AppGroup.SOCIAL),
        AppEntry("com.waplog.social", "Waplog", AppGroup.SOCIAL),
        AppEntry("com.myyearbook.m", "MeetMe", AppGroup.SOCIAL),
        AppEntry("com.skout.android", "Skout", AppGroup.SOCIAL),
        AppEntry("com.taggedapp", "Tagged", AppGroup.SOCIAL),
        AppEntry("com.azarlive.android", "Azar", AppGroup.SOCIAL),
        AppEntry("litmatch.chat.meet.friends", "Litmatch", AppGroup.SOCIAL),
        AppEntry("com.holla.datchat", "Holla", AppGroup.SOCIAL),
        AppEntry("com.yubo.app", "Yubo", AppGroup.SOCIAL),
        AppEntry("com.quackquack", "QuackQuack", AppGroup.SOCIAL),
        AppEntry("com.aisle", "Aisle", AppGroup.SOCIAL),
        AppEntry("com.shaadi.android", "Shaadi", AppGroup.SOCIAL),
        AppEntry("com.bharatmatrimony", "BharatMatrimony", AppGroup.SOCIAL),
        AppEntry("com.jeevansathi", "Jeevansathi", AppGroup.SOCIAL),

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
     * Every package the user's choices add up to: the enabled groups' catalogues plus whatever they
     * picked by hand.
     *
     * Takes the enabled groups directly rather than reading them out of [Settings], because app
     * blocking is not a category setting — `Settings` is the cross-platform shape, and Android is
     * the only platform that can block an app at all.
     *
     * Hand-picked packages are **not** filtered by group, because they were chosen individually and
     * there is nothing to infer a group from. That also means they survive turning a group off,
     * which is correct: unticking "Games" should not silently un-block an app the user added
     * themselves.
     */
    fun blockedPackages(
        enabledGroups: Set<AppGroup>,
        userPicked: Set<String>,
    ): Set<String> {
        val packages = ALL.asSequence()
            .filter { it.group in enabledGroups }
            .mapTo(mutableSetOf()) { it.packageName }
        packages.addAll(userPicked)
        return packages
    }
}
