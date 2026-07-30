package com.safeworld.core

/**
 * Top-level domains it is safe — and intended — to block whole.
 *
 * Normally a single-label name is neither stored nor looked up. Storing `com`
 * would make [Matcher.hostMatchesDomain] match every host under it, and *looking
 * it up* against a probabilistic filter is worse still: one collision on `com`
 * would block every .com domain there is. [DomainHasher.candidates] stops short
 * of bare names for exactly that reason.
 *
 * But the upstream feeds deliberately publish whole-TLD blocks for adult
 * content — `||www.xxx^`, `*.porn`, `*.sex`, `*.adult` — and for an adult-content
 * blocker each of those lines is worth more than any single domain in the file.
 * So the rule gains an explicit exception rather than being dropped.
 *
 * **Both halves have to agree.** [FeedParser] uses this to decide what to store;
 * [DomainHasher.candidates] uses it to decide what to look up. An entry stored
 * but never looked up is a domain the app claims to block and doesn't — the
 * silent failure this file exists to prevent. Must match `BLOCKABLE_TLDS` in
 * `packages/core/src/feed.ts`.
 *
 * Adding to this list blocks an entire TLD. Do it only for one with no
 * legitimate use for our users, and note the collision trade: allowing a lookup
 * of `xxx` means a filter collision could block all of .xxx — which is what the
 * user asked for anyway, so for these TLDs the risk is not a risk.
 */
object BlockableTlds {
    val ALL: Set<String> = setOf(
        "xxx", "porn", "sex", "adult", "sexy", "cam", "tube",
        "casino", "bet", "poker", "bingo",
    )

    operator fun contains(label: String): Boolean = label in ALL
}
