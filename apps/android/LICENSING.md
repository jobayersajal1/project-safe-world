# The Android app is GPL-3.0. The rest of this repo is not.

`apps/android/` is licensed **GPL-3.0-or-later** (`LICENSE`). Nothing else in the repository
is affected: `packages/core`, `apps/chrome-extension`, `apps/ios`, `apps/macos` and
`apps/windows` keep their own terms.

## Why only here

The full-tunnel forwarder is NetGuard's native TCP/IP stack, which is GPL-3.0 and had no
permissive equivalent that also filtered per-UID — see `app/src/main/cpp/THIRD_PARTY.md`.
Linking it makes the program that links it GPL, and that program is this app.

Google Play has no policy against GPL. **Apple's App Store terms do conflict with GPL-3.0**,
which is why this stops at the Android boundary: `SafeWorldCore` is shared by iOS and macOS,
so a single GPL file in it would make the iOS app undistributable.

## What it means in practice

- This app's complete source must be available to anyone who receives a build. It already is.
- Anyone may fork it, rebrand it, and redistribute it, provided they keep it GPL.
- The licence and NetGuard's copyright must reach the *user*, not just the repo — Settings ▸
  Help carries both.

## What it does not mean

**The blocklists are not covered.** They are data, not code: not derived from NetGuard, not
linked against it, loaded at runtime from `:core`'s resources. GPL §5 calls this mere
aggregation. They remain in the private `safe-world-listed` repo under their own terms, and no
GPL obligation reaches them.

That argument depends on the separation being real, so keep it real: **never compile list data
into `app/src/main/cpp/`.** No generated headers, no domains in C, no build step that bakes a
list into the native library. The lists stay Kotlin-side resources.
