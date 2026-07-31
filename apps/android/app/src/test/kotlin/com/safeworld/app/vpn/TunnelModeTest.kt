package com.safeworld.app.vpn

import com.safeworld.core.AppGroups.AppGroup
import com.safeworld.core.CategoryId
import com.safeworld.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety property here matters more than the feature: no arrangement of settings may select
 * full-tunnel mode while the relay cannot forward, because that routes every packet on the device
 * into something that only drops. The symptom would be a phone with no internet at all, reached by
 * flipping an ordinary-looking toggle.
 */
class TunnelModeTest {

    private fun settings(social: Boolean = false, entertainment: Boolean = false): Settings {
        val base = Settings.defaults()
        return base.copy(
            categories = base.categories +
                mapOf(CategoryId.SOCIAL to social, CategoryId.ENTERTAINMENT to entertainment),
        )
    }

    @Test
    fun `plain protection stays on the cheap DNS-only tunnel`() {
        assertEquals(TunnelMode.DnsOnly, TunnelMode.select(settings(), blockedApps = false))
    }

    /**
     * The gate, exercised in the state this test runs in: no native library can load under a plain
     * JVM, so `canForward` is false here, and no arrangement of settings may reach the full tunnel.
     *
     * That is the same state a device whose ABI we do not ship is in, which is why testing it on
     * the JVM is worth something rather than being an artefact — routing `0.0.0.0/0` with nothing
     * able to forward is a phone with no internet.
     */
    @Test
    fun `full tunnel is refused when the forwarder cannot load`() {
        assertFalse("no native library should load under a plain JVM", SafeWorldRelay.canForward)
        assertEquals(TunnelMode.DnsOnly, TunnelMode.select(settings(social = true), blockedApps = true))
        assertEquals(
            TunnelMode.DnsOnly,
            TunnelMode.select(settings(social = true, entertainment = true), blockedApps = true),
        )
    }

    // MARK: Which switches put apps in scope

    /**
     * App blocking is its own set of switches, **not derived from the categories.** Blocking social
     * websites is a DNS rule that costs nothing; stopping the Facebook app routes every packet on
     * the device through us. Someone who turned on the first has not asked for the second, and a
     * category switch must never move them onto the expensive tunnel.
     */
    @Test
    fun `category switches alone never ask for per-app blocking`() {
        assertFalse(TunnelMode.perAppSwitchesOn(emptySet()))
        // Every optional category on, no app group on: still the cheap tunnel.
        assertEquals(
            TunnelMode.DnsOnly,
            TunnelMode.select(settings(social = true, entertainment = true), blockedApps = false),
        )
    }

    @Test
    fun `any app group asks for per-app blocking`() {
        for (group in AppGroup.entries) {
            assertTrue("$group should ask for it", TunnelMode.perAppSwitchesOn(setOf(group)))
        }
    }

    // MARK: The resolved package set, not the switches, is what decides

    /**
     * A phone with none of these apps installed has nothing for a full tunnel to do. Forwarding
     * every packet on the device to discover that would be the whole cost for none of the benefit.
     */
    @Test
    fun `switches on but nothing installed stays cheap`() {
        assertFalse(TunnelMode.needsPerAppBlocking(emptySet()))
        assertEquals(TunnelMode.DnsOnly, TunnelMode.select(settings(social = true), blockedApps = false))
    }

    @Test
    fun `one installed blocked app is enough`() {
        assertTrue(TunnelMode.needsPerAppBlocking(setOf("com.dts.freefireth")))
    }
}
