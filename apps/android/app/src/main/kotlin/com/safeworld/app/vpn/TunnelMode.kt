package com.safeworld.app.vpn

import com.safeworld.core.CategoryId
import com.safeworld.core.Settings

/**
 * Which shape of tunnel to establish.
 *
 * The two differ enormously in cost, and the point of having both is that almost nobody should pay
 * for the expensive one. Someone who wants scam, gambling and adult content blocked — the reason the
 * app exists — never leaves [DnsOnly], where the tunnel carries about a hundred bytes per lookup and
 * the battery cost is indistinguishable from zero. Only opting into app-level blocking moves them to
 * [FullTunnel], where every packet on the device crosses our code.
 *
 * Chosen at `establish()` because a tunnel's routes are fixed once created; `ACTION_REFRESH`
 * re-establishes in place when the answer changes.
 */
enum class TunnelMode {
    /**
     * Routes only DNS resolvers. Blocks by domain, which is what 5.4M entries are expressed in, and
     * everything else on the device takes its normal path and never enters this process.
     *
     * The ceiling: an app that avoids DNS — DNS-over-HTTPS, Private DNS, an address it already knows
     * — is not filtered, and for a native app that is a real gap rather than a theoretical one.
     */
    DnsOnly,

    /**
     * Routes everything, so a blocked app's packets can be dropped by owning UID no matter how it
     * found the address.
     *
     * The cost is that *every other app's* traffic now has to be forwarded by us, because routes are
     * per-tunnel and cannot be scoped to one app. That is the forwarder, and it is why this mode is
     * gated on [SafeWorldRelay.canForward] rather than on settings alone.
     */
    FullTunnel;

    companion object {
        /**
         * Full tunnel only when the user asked for app-level blocking *and* the relay can forward.
         *
         * The second condition is not belt-and-braces. Without a forwarder this mode routes
         * `0.0.0.0/0` into something that only drops, which takes the device offline entirely —
         * so no arrangement of toggles is allowed to reach it until forwarding is real.
         */
        fun select(settings: Settings, blockedApps: Boolean): TunnelMode {
            if (!blockedApps) return DnsOnly
            if (!SafeWorldRelay.canForward) return DnsOnly
            return FullTunnel
        }

        /**
         * True when the user has asked for something only a UID rule can deliver.
         *
         * Driven by the resolved package set rather than by the switches, so it is false when the
         * switches are on but none of those apps are installed. A phone with no Facebook, no
         * YouTube and no games has nothing for a full tunnel to do, and making it forward every
         * packet to discover that would be pure cost.
         */
        fun needsPerAppBlocking(blockedPackages: Set<String>): Boolean = blockedPackages.isNotEmpty()

        /**
         * The switches that put apps in scope: the two categories that have an app catalogue behind
         * them, plus Games, which has no category because it has no domain list.
         */
        fun perAppSwitchesOn(settings: Settings, gamesEnabled: Boolean): Boolean =
            gamesEnabled ||
                settings.categories[CategoryId.SOCIAL] == true ||
                settings.categories[CategoryId.ENTERTAINMENT] == true
    }
}
