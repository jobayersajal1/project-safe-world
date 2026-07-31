package com.safeworld.app.ui

/**
 * Where a stuck user can reach a human.
 *
 * Kept in Kotlin rather than `strings.xml` on purpose: a phone number, an email
 * address, and a repository URL are the same in every language, and putting
 * them in the translatable resources invites a translator to "localize" one.
 */
object SupportLinks {
    const val WHATSAPP_NUMBER = "+84963885"

    const val EMAIL = "safeworldwebsiteblock@gmail.com"

    const val GITHUB = "https://github.com/jobayersajal1/project-safe-world"

    /**
     * The Facebook group doesn't exist yet. Held as null rather than as an
     * empty string so the UI has to decide what to show, instead of silently
     * opening a link to nowhere.
     */
    val FACEBOOK_GROUP: String? = null

    /** `wa.me` wants digits only — no `+`, spaces, or dashes. */
    val whatsappUrl: String
        get() = "https://wa.me/" + WHATSAPP_NUMBER.filter { it.isDigit() }

    val emailUrl: String
        get() = "mailto:$EMAIL"

    /**
     * The licence this app is under, and the project whose code put it there.
     *
     * GPL-3.0 requires both to reach the person running the software, so these are shown in
     * Settings ▸ Help rather than only living in the repository.
     */
    const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

    const val NETGUARD_URL = "https://github.com/M66B/NetGuard"
}
