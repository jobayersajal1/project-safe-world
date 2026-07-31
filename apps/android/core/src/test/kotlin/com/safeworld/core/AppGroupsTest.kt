package com.safeworld.core

import com.safeworld.core.AppGroups.AppGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue's failure mode is silence: a package name that matches nothing installed blocks
 * nothing, and looks identical to the feature working. Nothing here can prove a name is *correct*
 * — only a device can — so these check the properties that would make a name impossible.
 */
class AppGroupsTest {

    @Test
    fun `no package name appears twice`() {
        val duplicates = AppGroups.ALL
            .groupBy { it.packageName }
            .filterValues { it.size > 1 }
            .keys
        assertEquals("duplicate package names", emptySet<String>(), duplicates)
    }

    @Test
    fun `every package name is a syntactically valid application id`() {
        // Lower-cased ASCII labels separated by dots, each starting with a letter. A name that fails
        // this cannot be an installed package under any circumstances, so it is a typo.
        val valid = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
        val malformed = AppGroups.ALL.map { it.packageName }.filterNot { valid.matches(it) }
        assertEquals("malformed package names", emptyList<String>(), malformed)
    }

    @Test
    fun `every group has entries`() {
        for (group in AppGroup.entries) {
            assertTrue("$group has no apps", AppGroups.forGroup(group).isNotEmpty())
        }
    }

    @Test
    fun `group ids round-trip`() {
        for (group in AppGroup.entries) {
            assertEquals(group, AppGroup.fromId(group.id))
        }
        assertNull(AppGroup.fromId("apps_nonexistent"))
    }

    /**
     * Blocking someone's messaging is not what "block social media" means, and doing it silently
     * would be the app deciding something the user did not ask for. Pinned so a later addition to
     * the catalogue has to argue with a test rather than slip through.
     */
    @Test
    fun `messaging apps are never in the catalogue`() {
        val messaging = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.facebook.orca",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
            "com.viber.voip",
            "com.skype.raider",
            "com.google.android.apps.messaging",
        )
        val found = AppGroups.ALL.map { it.packageName }.filter { it in messaging }
        assertEquals("messaging must stay reachable", emptyList<String>(), found)
    }

    /**
     * Each group pairs with a category of the same subject for the UI's benefit, but **the category
     * does not switch it on.** Blocking social websites and stopping the Facebook app are different
     * decisions with different costs, and one must not silently imply the other.
     */
    @Test
    fun `every group names the category it pairs with`() {
        assertEquals(CategoryId.SOCIAL, AppGroup.SOCIAL.category)
        assertEquals(CategoryId.ENTERTAINMENT, AppGroup.ENTERTAINMENT.category)
        assertEquals(CategoryId.GAMES, AppGroup.GAMES.category)
    }

    // MARK: Which packages a given set of switches produces

    @Test
    fun `nothing is blocked when no group is on`() {
        assertEquals(emptySet<String>(), AppGroups.blockedPackages(emptySet(), emptySet()))
    }

    @Test
    fun `turning on a group blocks its apps and nothing else`() {
        val blocked = AppGroups.blockedPackages(setOf(AppGroup.SOCIAL), emptySet())
        assertTrue(blocked.contains("com.facebook.katana"))
        assertFalse(blocked.contains("com.google.android.youtube"))
        assertFalse(blocked.contains("com.dts.freefireth"))
    }

    @Test
    fun `groups combine`() {
        val blocked = AppGroups.blockedPackages(
            setOf(AppGroup.ENTERTAINMENT, AppGroup.GAMES),
            emptySet(),
        )
        assertTrue(blocked.contains("com.google.android.youtube"))
        assertTrue(blocked.contains("com.dts.freefireth"))
        assertFalse(blocked.contains("com.facebook.katana"))
    }

    /**
     * A hand-picked app was chosen individually, so there is no group to un-choose it. Turning a
     * group off must not quietly un-block something the user added themselves.
     */
    @Test
    fun `hand-picked packages survive every group being off`() {
        val blocked = AppGroups.blockedPackages(emptySet(), setOf("com.example.somegame"))
        assertEquals(setOf("com.example.somegame"), blocked)
    }

    @Test
    fun `a hand-picked app already in a group is not counted twice`() {
        val blocked = AppGroups.blockedPackages(setOf(AppGroup.GAMES), setOf("com.dts.freefireth"))
        assertEquals(1, blocked.count { it == "com.dts.freefireth" })
    }
}
