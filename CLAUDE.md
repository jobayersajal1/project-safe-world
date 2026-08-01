# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Safe World blocks harmful websites (scam/malware, gambling, adult content) by category. It is a
multi-platform product. **Phase 1 is a Manifest V3 Chrome extension** (shipping). **Phase 2, an iOS
app (`apps/ios`), is in progress**: SwiftUI app + Safari Content Blocker extension, backed by a
`SafeWorldCore` Swift package. **Phase 3, an Android app (`apps/android`), is in progress**:
Kotlin/Compose app + a local `VpnService` DNS sinkhole, backed by a `:core` Kotlin module. **Phase
4, a macOS app (`apps/macos`), and Phase 5, a Windows app (`apps/windows`), both ship**: menu-bar
and tray apps that run a **local DNS resolver** on `127.0.0.1:53` over memory-mapped fuse filters,
blocking the full ~4.48M domains in every app. The `hosts`-file sinkhole each started as is now only
the fallback when the resolver cannot start. macOS **shares iOS's
`SafeWorldCore` Swift package** rather than reimplementing `decide()` again — it's pure Foundation
and already declares macOS support in its own `Package.swift`, and both are Swift/Apple platforms.
Windows has neither a JS runtime nor Swift/Kotlin available, so `apps/windows/SafeWorld.Core` is a
fourth from-scratch port of categories/`Settings`/`decide()`, in C# — **keep all ports (Swift,
Kotlin, C#) in sync with `packages/core` by hand**. Network Extension (macOS) and WFP (Windows) content
filtering are not started; with the DNS resolvers shipping, those are now about tamper resistance
and about seeing DoH/direct-to-IP traffic, not about coverage.

**Per-platform reach, because it differs and the numbers are easy to get wrong:** Android 4,430,965
(VpnService); macOS 4,430,965 once the daemon is installed from the app, ~150,000 on the hosts
fallback; Windows 4,482,470 via the proxy, ~150,000 on the hosts fallback; **iOS 150,000** — its
Safari content blocker is capped by Safari's ~150k rule ceiling, and the `SafeWorldTunnel` packet
tunnel that would carry the full list needs a paid Apple Developer account and a real device, so it
cannot run here. Note that Chrome on iOS does **not** use Safari content blockers, so the tunnel is
the only thing that would cover it.

## The lists are not in this repo

**`packages/core/src/blocklists/` is gitignored and empty on a fresh clone.** The domain lists live
only in the private repo `jobayersajal1/safe-world-listed`, scrambled. Nothing in this repo contains
a domain in any encoding — not plaintext, not scrambled, not hashed — and no generated artifact that
does is ever tracked.

```bash
npm run lists:pull   # private repo -> decoded working files (needs read access)
npm run lists:push   # working files -> scrambled -> private repo
```

Every list-consuming script is gated behind `lists:check`, which fails with instructions rather than
letting a build quietly produce apps that block nothing. If you are adding a build output that
contains domains, **add its path to `.gitignore`** — that file is the enforcement point.

## Commands

```bash
npm install          # install all workspaces (npm workspaces monorepo)
npm run lists:pull   # REQUIRED FIRST — the lists are not in this repo
npm run build:lists  # regenerate apps/chrome-extension/src/rules/*.json from the core blocklists
npm run build        # build core (tsc) then extension (vite) -> apps/chrome-extension/dist
npm run dev          # vite watch-build of the extension
npm test             # run all vitest suites
npm run typecheck    # tsc -b across project references
npm run lint         # eslint
```

Run a single test file: `npx vitest run packages/core/test/matcher.test.ts`
(add `-t "<name>"` to filter by test name).

`build:lists` must run before the first `build`: the manifest references
`src/rules/{scam,gambling,adult}.json`, which are generated (git-tracked) outputs.

Load in Chrome: `chrome://extensions` → Developer mode → **Load unpacked** → pick
`apps/chrome-extension/dist`. Unpacked MV3 extensions cannot be loaded in a headless/in-app
browser, so end-to-end checks are manual in real Chrome.

iOS (`apps/ios`, not an npm workspace — its own Xcode/Swift toolchain):

```bash
npm run build:ios                 # export packages/core blocklists into SafeWorldCore + the
                                   # extension's default rule list (run after editing blocklists)
cd apps/ios/SafeWorldCore && swift test   # run SafeWorldCore's unit tests, no Xcode/simulator needed
cd apps/ios && xcodegen generate  # regenerate SafeWorld.xcodeproj from project.yml (edit
                                   # project.yml, never the .xcodeproj, then regenerate)
```

Full app build needs an iOS Simulator platform installed (`xcodebuild -downloadPlatform iOS`) — see
`apps/ios/README.md` for the `xcodebuild` invocation and why plain `-scheme`/`-destination` builds
need it even just to compile. **The iOS 26.5 simulator platform is now installed here**, so the app
does build and run locally:

```bash
xcodebuild -project SafeWorld.xcodeproj -scheme SafeWorld -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO build
xcrun simctl install <device-udid> build/Build/Products/Debug-iphonesimulator/SafeWorld.app
```

Two things to know before verifying anything on it by hand. **Pass the device UDID, not `booted`** —
more than one simulator is usually booted and `booted` picks the wrong one silently. And **the
Simulator cannot be tapped**: driving it needs accessibility permission this environment can't grant,
so screens are reached with launch arguments instead. `-startTab settings` opens the Settings tab
(`RootView`), and any `UserDefaults` key can be forced the same way (`-darkTheme YES`) because
launch arguments land in `NSArgumentDomain`, which outranks stored values. Editing the preference
plist on disk does **not** work — `cfprefsd` caches it, and the copy `simctl spawn defaults` reads is
a different domain from the one inside the app sandbox.

Android (`apps/android`, not an npm workspace — its own Gradle/Kotlin toolchain):

```bash
npm run build:android             # export packages/core blocklists into :core's JVM resources
cd apps/android
./gradlew :core:test              # pure-JVM tests, no Android SDK or emulator needed
./gradlew :app:testDebugUnitTest  # DNS/IPv4 packet tests (needs the Android SDK)
./gradlew :app:assembleDebug
```

The toolchain is installed (`openjdk@17` + `gradle` formulae, `android-commandlinetools` cask, SDK
platform 35 at `/opt/homebrew/share/android-commandlinetools`) and an AVD named `safeworld`
(Android 15, arm64) exists. Set `JAVA_HOME=/opt/homebrew/opt/openjdk@17` and
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` — Gradle won't find a JDK otherwise,
since the system `java` is a stub. Install JDKs via brew **formulae**, never casks: casks need a
sudo password that isn't available here.

Launch the emulator with `run_in_background` — a `nohup ... &` emulator gets killed when its
spawning shell is cleaned up.

macOS (`apps/macos`, not an npm workspace — shares iOS's Xcode/Swift toolchain and `SafeWorldCore`
package):

```bash
npm run build:ios                 # macOS shares iOS's SafeWorldCore package + bundled blocklists,
                                   # so the iOS build script covers both
cd apps/macos && xcodegen generate  # regenerate SafeWorld.xcodeproj from project.yml
xcodebuild -project SafeWorld.xcodeproj -scheme SafeWorld -configuration Release \
  -derivedDataPath build CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=YES build
```

Unlike iOS, this needs no simulator platform — macOS builds run directly against the host. No
Apple Developer ID is configured here, so builds are ad-hoc signed (`CODE_SIGN_IDENTITY="-"`);
that's enough to run locally, but Gatekeeper will warn on a downloaded, unnotarized copy.

Windows (`apps/windows`, not an npm workspace — its own .NET toolchain):

```bash
npm run build:windows              # export packages/core blocklists into SafeWorld.Core's
                                    # embedded resources
cd apps/windows
dotnet test SafeWorld.Core.Tests   # pure C#, runs on any OS — no Windows or WinForms needed
dotnet publish SafeWorld.App -r win-x64 --self-contained true -p:PublishSingleFile=true -c Release
```

The .NET SDK doesn't need to be pre-installed system-wide or via a sudo-gated brew cask — the
[dotnet-install script](https://dot.net/v1/dotnet-install.sh) installs a working copy under
`~/.dotnet` for the current user only. `dotnet publish -r win-x64` cross-compiles a real Windows
`.exe` from macOS/Linux (WinForms' reference assemblies restore cross-platform); only *running* the
result needs Windows. No Authenticode certificate is configured here, so the published `.exe` is
unsigned — Windows SmartScreen will warn on first run.

## Architecture

**Monorepo split (`packages/*` + `apps/*`).** `packages/core` (`@safe-world/core`) is
platform-agnostic TypeScript — categories, blocklists, the matching decision, and DNR rule
generation — with **no Chrome APIs**. `apps/chrome-extension` is the only Chrome-specific code and
imports core. Later platforms are meant to depend on core the same way, so keep browser/DOM APIs
out of `packages/core`.

**Categories are data.** `packages/core/src/categories.ts` is the single source of truth: adding a
category is one `CATEGORIES` entry (plus a blocklist JSON and a `rule_resources` entry in the
manifest). Each category owns a disjoint rule-id range via `ruleIdBase`/`RULE_ID_STRIDE` so
generated rule ids never collide.

There are six, split by `optional`. `list1`/`list2`/`list3` (scam, gambling, adult) are the
protection promise — on by default, and **forced on** by `enforceMandatoryCategories` on both
Android and iOS so a stale stored value can't leave one off. `list4`/`list5`/`list6` (social,
entertainment, games) are opt-in, off until the user asks. Chrome is the exception by design: it's
the self-control build with no PIN, where everything toggles freely.

`list6` is much smaller than the others (~117 domains) and mostly hand-curated: gaming is not a
standard blocklist category the way porn and gambling are, and `blocklistproject`'s `fortnite.txt`
is the only feed any of the usual maintainers publish for it. It covers storefronts, launchers and
browser-game portals. Treat its size as expected rather than as a build that went wrong.

**One subject, one switch — the websites and the apps together.** A row named "Block social media"
sets both `Settings.categories[.social]` and `AppGroup.SOCIAL`; `AppGroup.category` is what pairs
them. They were two switches once, on the reasoning that the domain half is a DNS rule costing
nothing while the app half routes every packet on the device through us (`TunnelMode.FullTunnel`).
What that produced was five switches to express one intention, and a half-set state that read as
protection while the Instagram app still worked. The cost is real, so it is stated once by the
consent dialog rather than implied by an extra row — and **the dialog is owned by the screen**
(`HomeScreen`/`HomeView`), not by either control, because both the subject rows and the app picker
lead to the tunnel and one answer should cover both.

Two invariants hold this together. The group stays a **separate stored value**, never derived:
`TunnelMode.perAppSwitchesOn` reads the enabled groups and never `Settings.categories`, so what
puts a device on the expensive tunnel is an explicit fact. And a row is **on only when both halves
are** — an install left half-on by an older build shows off and self-heals on one tap, because a
row reading "on" over a working Instagram app is the one thing this product must never say.

The three rows are ordered games, social, entertainment (`subjectOrder`), written out rather than
taken from `Categories.optional` so the order is a UI decision. Anything the rows can't reach —
one specific app, a site nobody lists — lives under "Want to block further?".

**iOS and Android are the same product; only the enforcement mechanism differs.** Both have the
PIN, the recovery code, the four languages, the help section, and the blocked-count home screen —
`apps/ios/SafeWorldCore`'s `PinHasher`/`PinLockout`/`RecoveryCode` are parameter-for-parameter
ports of the Kotlin ones in `apps/android/app/.../security/`. The one deliberate gap is uninstall
protection: Android registers a device-admin receiver, and iOS has no equivalent outside MDM. iOS
compensates where it can by keeping the PIN in the **Keychain** rather than in app storage, so
reinstalling to shed it lands back at the same prompt.

**One rule decides every PIN gate: strengthening is free, weakening costs the PIN.** On iOS that
rule is `SafeWorldCore.ProtectionChange.weakens(from:to:)` — a single tested function rather than a
condition per switch, so a setting added later gets its gate by construction. Note the asymmetry it
exists to capture: *removing* a custom-block entry weakens, but *adding* a custom-allow entry
weakens too, because allow outranks every category in `decide`.

**Blocking is `declarativeNetRequest`, not runtime interception.** Blocking happens declaratively:
- **Static rulesets** (one per category) are generated by `scripts/build-blocklists.ts` from the
  bundled domain lists into `apps/chrome-extension/src/rules/*.json`. Domains are packed into a
  rule's `requestDomains` (up to `DOMAINS_PER_RULE`) rather than one-rule-per-domain, to stay far
  below MV3 rule-count limits — preserve this packing when changing rule generation.
- `main_frame` hits **redirect** to `/src/blocked/blocked.html` (a friendly page); sub-resources
  are plain `block`. The redirect path is root-relative and must match the manifest's
  `web_accessible_resources` / crxjs output path — if you move the blocked page, update both.
- The background worker toggles category rulesets on/off with `updateEnabledRulesets` from the
  user's settings, encodes custom allow/block lists as **dynamic** rules, and applies **remote**
  updates as **session** rules. These three DNR surfaces (static/dynamic/session) are used
  deliberately for different lifetimes.

**Android's UI states the mandatory lists, and only offers switches for adding.** The home screen
shows a count of what's blocked rather than per-category rows, then offers the opt-in categories and
a comma-separated field for the user's own domains. One rule decides every PIN gate there:
strengthening is free, weakening costs the PIN — turning a category on goes straight through, turning
one off prompts; saving a block list that keeps everything it had goes through, saving one that drops
an entry prompts.

**A forgotten PIN needs a local answer.** No account, no server, so only a secret the user already
holds can authorize a reset: `RecoveryCode.kt` issues one 100-bit code at setup, shows it once, and
stores it hashed like the PIN — so it can never be shown again, only replaced. It is deliberately
exempt from `PinLockout`, since being locked out is the usual reason to need it.

**Android UI strings are localized (en/bn/es/ar) and follow the system language by default.**
`LocaleHelper` wraps the context in `attachBaseContext` — `AppCompatDelegate.setApplicationLocales`
only works for `AppCompatActivity`, and this is pure Compose. The VPN service wraps its context too,
or its notification lands in a different language from the app. `:core` is a plain JVM module with no
access to resources, so its `CategoryMeta.label` stays English and `ui/CategoryStrings.kt` maps ids to
translated strings. Numbers go through `CountFormat` (CLDR **LONG** compact style — SHORT truncates
Bengali হাজার to "হা" mid-sentence).

**Settings & precedence.** `Settings` lives in `chrome.storage.local`; `packages/core/src/matcher.ts`
`decide()` encodes precedence: master-off → allow; custom-allow → allow; custom-block → block;
else first enabled category listing the host. `decide()` is the tested spec of blocking behavior —
the extension's DNR rules must stay consistent with it. Host comparison is via `normalizeHost` +
subdomain match (`hostMatchesDomain`); reuse these rather than comparing raw strings.

**Block page needs the host from the background.** A static DNR redirect can't carry the original
URL, so the background worker records the last top-level navigation per tab
(`webNavigation.onBeforeNavigate`) and the blocked page requests it via the `getBlockedInfo`
message (which also increments the daily blocked counter). Message types between UI and background:
`getBlockedInfo`, `runRemoteUpdate`, `allowOnce`, `allowAlways`.

**Remote updates** are additive and optional: `background.ts` fetches a `RemoteUpdatePayload` JSON
from the user-configured URL on a `chrome.alarms` schedule (and on demand). Bundled lists are the
offline baseline; remote never replaces them.

**Blocklist updates and *app* updates are different things.** The above ships new domains to an
installed build. Separately, each app checks whether a newer *build* of itself exists, because the
apps are distributed as downloaded files rather than through a store that would update them. Both
halves read the **GitHub Releases API** (`/releases/latest`) rather than a hand-maintained version
file: publishing a release is already the act that makes a build available, so there is nothing
extra to bump and nothing that can drift out of step with the actual download. The unauthenticated
rate limit (60/hr per IP) is generous for a once-a-day check, and hitting it degrades to "no update
found" rather than to a wrong answer. Version ordering lives in `Version.kt` (Android `:core`) and
`Version.swift` (`SafeWorldCore`, shared by iOS/macOS), which **pin the same vectors in their
tests** — the two sides disagreeing would mean one platform nagging about an update that isn't one,
or staying quiet about one that is. Asset selection is `UpdateChecker.select` / `ReleaseChecker
.select`, tested against a captured real payload because one release carries the `.apk`, `.dmg`,
`.exe`, and Chrome `.zip` together and each platform must pick out its own.

**What each platform can actually do with an update differs, and the UI says so rather than
pretending.** Android downloads the APK and commits a `PackageInstaller` session — a real
end-to-end install, gated on `REQUEST_INSTALL_PACKAGES` (asked for *before* downloading, not
after) and on the APK being signed with the same key, so a debug install can check but never
apply. macOS downloads the `.dmg` to ~/Downloads and opens it, stopping short of replacing the
running bundle — that is Sparkle's job and needs a signed, notarized build to be verifiable.
**iOS cannot self-update at all**: there is no API to install an `.ipa` from inside an app, so it
checks and then opens the release page, and its footer tells the user that's what the button
does. When the app reaches the App Store the honest change is to point that link at the listing.

**`CFBundleShortVersionString` must be declared in `project.yml`'s `info.properties`, not only in
`settings`.** XcodeGen rewrites `Info.plist` wholesale and fills unlisted keys with its own
defaults — for that key, `"1.0"`. The update check compares it against the newest release tag, so
the default made a 0.1.0 build claim to be 1.0 and quietly conclude it was newer than every
release ever published. Both Apple `project.yml`s now set it to `$(MARKETING_VERSION)`.

**Only deltas are published — never the full list.** The complete list lives in this repo and ships
inside each app. `baseline/baseline.json` records what the current release bundles (written by
`npm run baseline:snapshot`, id pasted into both `RemoteConfig`s); `npm run build:remote` publishes
only what's been added since, as `delta-<baseline>[-android].json`. **Never run
`baseline:snapshot` on the update schedule** — moving the baseline to the current lists empties the
delta and silently cuts off every installed app. Old app versions stop receiving additions until
they update, which degrades to a stale list rather than a broken one.

**Every app ships its lists encoded, and decodes at runtime.** Android bundles salted SHA-256
digests it matches one-way (`DomainHasher`). Chrome, iOS, macOS, and Windows bundle XOR-scrambled
domains and reverse them in memory on first use — `background.ts`'s `loadCategoryDomains`,
`Blocklists.swift`, `Blocklists.cs`. Chrome therefore builds **dynamic** DNR rules at runtime rather
than declaring static rulesets: a static ruleset is a file of literal domains inside the extension,
so it would publish the whole list to anyone who unzips the `.zip`. `DOMAINS_PER_RULE = 1000` keeps
the whole corpus at ~85 rules, far under the 30,000 dynamic-rule ceiling.

**The published deltas are encoded, in two formats, because the platforms differ.** They are hosted
at `github.com/jobayersajal1/safe-world-block-list-update` (GitHub Pages) so the public URL isn't a readable
directory of blocked sites. Android fetches the `-android` delta — salted SHA-256 digests it
matches **one-way** via `DomainHasher`/`HashedDomainSet`, never reversing them; its bundled
resources are hashed too, so unzipping the APK yields no domains. iOS and Chrome fetch
the plain delta — XOR-scrambled domains they **reverse** at runtime (`packages/core/src/scramble.ts`,
`Scramble.swift`), because `declarativeNetRequest` and Safari's content blocker match from rule
files containing literal domains, so those bundled lists stay plaintext by platform constraint.
`plainDomains()`/`domainsByCategory()` **throw** on a format the platform can't use rather than
returning nothing — a silent empty result would leave the app looking healthy while blocking
nothing. The encodings must stay byte-identical across TS/Swift/Kotlin; each side pins shared
vectors in its tests (`scramble.test.ts`, `ScrambleTests.swift`, `DomainHasherTest.kt`) for exactly
that reason. Neither encoding is secrecy — the key and salt ship in the apps — they only stop
casual reading.

**iOS blocks via a single generated Safari Content Blocker list, not toggleable rulesets.** Safari
only loads one active rule list per content blocker extension (unlike Chrome's per-category DNR
rulesets), so `SafeWorldCore.BlockerListBuilder` (`apps/ios/SafeWorldCore/Sources/SafeWorldCore/BlockerListBuilder.swift`)
compiles the user's settings into one combined list on every settings change: a `block` trigger per
enabled category, then custom-block, then an `ignore-previous-rules` trigger for custom-allow last
so it always wins — reproducing `decide()`'s precedence with Safari's more limited primitives. The
`SafeWorld` app writes this list as JSON to an App Group shared container and calls
`SFContentBlockerManager.reloadContentBlocker`; the `SafeWorldBlocker` extension's
`ContentBlockerRequestHandler` just serves whatever is in that file (falling back to a bundled
default before the app has ever run). See `apps/ios/README.md` for the full design and status.

**Android blocks device-wide at the DNS layer via a local VpnService, not per-browser.** Android
won't let an app inspect another app's traffic without root, so `SafeWorldVpnService`
(`apps/android/app/src/main/kotlin/com/safeworld/app/vpn/`) establishes a tunnel that goes nowhere
(no remote server) and routes **only** the system's IPv4 DNS resolvers into it — everything else on
the device takes its normal path. Each captured query is run through the Kotlin `Matcher.decide`;
blocked names get a synthesized `NXDOMAIN`, allowed ones are forwarded verbatim to the resolver the
device already used and the reply relayed. Forwarding sockets must stay `protect()`ed or they loop
back into the tunnel. `Ipv4Udp`/`DnsMessage` are deliberately pure Kotlin (no Android APIs) so the
wire-format handling is unit-testable; keep them that way. The service reads `SettingsStore` per
query, so settings changes apply without restarting the tunnel. DNS-layer filtering can't see
DoH/DoT, hardcoded resolvers, or direct-to-IP connections — see `apps/android/README.md`.

**macOS and Windows block via a local DNS resolver; the hosts file is the fallback.** Both run our
own code in the resolution path — macOS through the root `safeworld-dnsd` LaunchDaemon
(`apps/macos/SafeWorld/DaemonController.swift` installs it; the binary is embedded at
`Contents/Helpers/` by a `postBuildScripts` phase), Windows through `ProxyController` +
`DnsProxy` — matching against memory-mapped fuse filters and carrying the full ~4.48M rather than
the hosts file's ~150,000. **Neither may reorder its install sequence:** save the current resolver,
start the resolver, *prove it answers on port 53*, and only then repoint DNS, keeping the real
resolver as secondary so a dead daemon degrades to unfiltered DNS rather than none. Windows
additionally persists the previous DNS config to disk before changing it and calls
`RecoverFromPreviousRun()` at startup, because the failure being guarded against is a machine left
unable to resolve anything.

The hosts path below remains, and is still written, so that a resolver which cannot start (port 53
taken, no admin rights) degrades to the smaller list instead of to nothing. Both platforms'
`HostsFileBuilder` (Swift: shared with iOS in `SafeWorldCore`;
C#: `apps/windows/SafeWorld.Core/HostsFileBuilder.cs`) computes the set of domains `decide()` would
block and renders a marker-delimited (`# BEGIN SAFEWORLD` / `# END SAFEWORLD`) block mapping each
to `0.0.0.0`, spliced into `/etc/hosts` or `C:\Windows\System32\drivers\etc\hosts`. Every candidate
domain is already a literal member of an enabled category's bundled list, so building the blocked
set unions those lists directly and only checks candidates against the (small, user-entered)
custom-allow list — **do not** re-derive membership by calling `Matcher.decide`/`Matcher.Decide`
once per bundled domain the way a naive port of the per-host `decide()` API would suggest;
`decide()` does an O(n) linear scan per category to support subdomain matching, so calling it ~45k
times (once per bundled domain, to build the sinkhole list) is O(candidates × total bundled
domains) — it pegs a CPU core rather than returning. A hosts file can only sinkhole exact names, so
each blocked domain is emitted both bare and with a `www.` prefix — coarser than the resolver path,
which is why the resolver is preferred and this is only the fallback.
Writing the file needs admin rights: macOS stages the new content in a user-writable temp file and
asks once via `osascript … with administrator privileges` to copy it into place (never
interpolating user-supplied domains into the privileged command line); Windows instead runs the
whole tray app elevated from launch (`app.manifest`'s `requireAdministrator`) and writes directly.
Both no-op (no prompt/write) when the computed contents already match what's on disk.

## Conventions

- ESM everywhere; core is consumed via `@safe-world/core` (built) or its `src` path alias in the
  extension's tsconfig. Import paths use `.js` extensions (TS ESM/NodeNext resolution).
- `tsconfig.base.json` sets `strict` + `noUnusedLocals`/`noUnusedParameters`; unused imports fail
  the typecheck build.
