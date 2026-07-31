package com.safeworld.app.vpn

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

    @Test
    fun `full tunnel is refused while the relay cannot forward`() {
        // The gate. `canForward` is false until forwarding exists, and until then this must hold
        // even when the user has asked for app blocking.
        assertFalse("test is meaningless once forwarding lands", SafeWorldRelay.canForward)
        assertEquals(TunnelMode.DnsOnly, TunnelMode.select(settings(social = true), blockedApps = true))
        assertEquals(
            TunnelMode.DnsOnly,
            TunnelMode.select(settings(social = true, entertainment = true), blockedApps = true),
        )
    }

    // MARK: Which switches put apps in scope

    @Test
    fun `the opt-in categories ask for per-app blocking`() {
        assertFalse(TunnelMode.perAppSwitchesOn(settings(), gamesEnabled = false))
        assertTrue(TunnelMode.perAppSwitchesOn(settings(social = true), gamesEnabled = false))
        assertTrue(TunnelMode.perAppSwitchesOn(settings(entertainment = true), gamesEnabled = false))
    }

    /** Games has no category behind it, so it must reach the packet path on its own. */
    @Test
    fun `games asks for per-app blocking with every category off`() {
        assertTrue(TunnelMode.perAppSwitchesOn(settings(), gamesEnabled = true))
    }

    /**
     * The mandatory categories are always on and are blocked by domain, so they must never drag a
     * user onto the expensive path — that would make every install pay the packet cost.
     */
    @Test
    fun `mandatory categories alone stay cheap`() {
        val base = Settings.defaults()
        assertTrue(base.categories[CategoryId.SCAM] == true)
        assertFalse(TunnelMode.perAppSwitchesOn(base, gamesEnabled = false))
        assertEquals(TunnelMode.DnsOnly, TunnelMode.select(base, blockedApps = false))
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
