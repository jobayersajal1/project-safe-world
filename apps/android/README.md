# Safe World — Android

> **Status:** Phase 3 working. App + local-VPN DNS sinkhole are implemented, compile clean, pass 23
> unit tests, and have been **verified blocking end-to-end on an Android 15 emulator** — see
> [Verification](#verification) for exactly what was and wasn't proven.

## Goal

Block scam/malware, gambling, and adult sites **device-wide** (all apps and browsers), not just in
one browser.

## Stack

- **Language / build:** Kotlin + Gradle, Jetpack Compose (Material 3) for the UI.
- **Min SDK:** 26 (Android 8.0). **Compile/target SDK:** 35.
- **Modules:**
  - `:app` — the Compose host app (master switch, per-category toggles, custom allow/block lists,
    "blocked today" counter) plus `SafeWorldVpnService`, the DNS sinkhole.
  - `:core` — a plain **Kotlin/JVM** module (not an Android library), the Android analogue of
    `packages/core` and of iOS's `SafeWorldCore`: category metadata, `Settings`/`DailyStats`,
    `Matcher.decide`, the bundled blocklists, and the remote-update payload type. Nothing in it
    touches the Android SDK, so `./gradlew :core:test` runs with no emulator.

## Blocking approach

Android won't let one app inspect another app's traffic without root, so the standard no-root
technique for a device-wide content blocker is a local `VpnService` — the same approach DNS66,
RethinkDNS, and Blokada take.

`SafeWorldVpnService` establishes a tunnel that goes **nowhere**: there is no remote server and
traffic never leaves the device. It then:

1. Routes **only** the system's IPv4 DNS resolvers into the tunnel (discovered via
   `ConnectivityManager.getLinkProperties(...).dnsServers`, falling back to `1.1.1.1`/`8.8.8.8`
   when the platform reports none). Every other packet on the device takes its normal path and
   never enters this process — that's what keeps the tunnel cheap.
2. Parses each captured IPv4/UDP datagram bound for port 53, reads the queried name, and runs it
   through `Matcher.decide` — the same precedence spec (master off → custom allow → custom block →
   first enabled category) the Chrome extension and the iOS content blocker implement.
3. **Blocked:** synthesizes an `NXDOMAIN` response, so the client fails fast with "host not found"
   rather than hanging on a connection to a dead address, and bumps the blocked-today counter.
   **Allowed:** forwards the query verbatim to the resolver the device was already using and
   relays the reply, on a small thread pool so one slow resolver can't stall every other lookup.

Forwarding sockets are `protect()`ed, and the app excludes itself from its own tunnel, so neither
the relayed queries nor the remote-list fetch loop back through the VPN.

Settings changes take effect immediately — the service reads `SettingsStore` on every query, so
there is no tunnel restart when a category is toggled.

Permissions: `BIND_VPN_SERVICE` (the system shows a VPN consent dialog on first start),
`INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, and a foreground service of type
`specialUse` — a local filtering VPN has no better-fitting type, and Android 14+ requires one.

### What DNS-layer blocking does and doesn't catch

Worth being honest about, since it's the main trade-off versus the Chrome extension:

- **Catches** any app or browser resolving a blocked domain through the system resolver — the
  device-wide coverage that Safari content blockers and the Chrome extension can't give.
- **Misses** DNS-over-HTTPS/TLS (Chrome and Android Private DNS both do this), apps shipping a
  hardcoded resolver, and connections made straight to an IP address.
- **Blocks a whole domain, not a URL** — DNS has no path, so per-page rules aren't expressible.

## Self-control friction

This is a self-control app, so the design assumption is that the adversary is the user's own
future impulse — not an attacker with the device. Everything here is friction, and the README says
so plainly rather than implying a lock that doesn't exist.

- **PIN.** Set on first run, before anything can be configured. Stored as a salted PBKDF2-HMAC-SHA256
  hash (120k iterations, `security/PinHasher.kt`), never in plaintext — a short PIN is likely reused
  elsewhere and SharedPreferences ends up in device backups. Required to **turn protection off** and
  to **save allow/block list changes**. Deliberately *not* required to turn protection on: friction
  belongs on the way out.
- **Attempt limit.** Five wrong entries start a 15-minute cooldown (`security/PinLockout.kt`), which
  turns a 4-digit PIN from ~30 minutes of patient guessing into weeks of it. The counter and deadline
  are **persisted**, so force-stopping or swiping the app away doesn't reset them — otherwise the
  limit would be bypassable in two taps. Winding the device clock *backwards* is detected and keeps
  the full cooldown; winding it *forwards* still skips it, which is a deliberate act well past the
  impulse this is meant to slow down. `SettingsStore.verifyPin` is the single entry point, so no
  caller can skip the check.
- **Uninstall protection.** Registering as a device admin makes Android refuse to uninstall the app;
  deactivating it in-app costs the PIN. It requests **no policies at all** — it cannot lock, wipe, or
  monitor anything. It is *not* a real block: admin can still be revoked from Android's settings, and
  the app's data can be cleared. A true block needs Device Owner, which requires provisioning from a
  factory-reset device.
- **Takeover detection.** Android allows one active VPN, so another VPN app silently displaces this
  one. `onRevoke()` catches that while the process is alive; `MainActivity.onResume` catches the case
  where the process was already gone ("enabled, but not running, and consent is gone"). Either way the
  user gets a high-priority notification and an error-coloured banner that costs the PIN to dismiss,
  so a silent takeover can't leave them believing they're covered.
- **Recovery code.** There is no account and no server, so nothing can authorize a PIN reset except a
  secret the user already holds. One 100-bit code (`security/RecoveryCode.kt`) is issued right after
  the PIN is set, shown **once**, and stored hashed with the same PBKDF2 — so "show it to me again"
  is impossible by construction and re-issuing is the only option. Using it consumes it and a
  replacement is issued immediately. Without this, a forgotten PIN would mean clearing the app's
  data, which throws away every setting *and* the uninstall friction. Recovery is deliberately
  **exempt from the attempt cooldown**: the cooldown exists to stop guessing at four digits, and being
  locked out is the usual reason to reach for the code in the first place.
- **No toggles for the protection categories.** Installing this app is the decision to block scam,
  gambling, and adult content; a switch that re-enables gambling in a weak moment is the exact failure
  the PIN exists to prevent. Those three are forced on in `SettingsStore.enforceMandatoryCategories`
  and never rendered as a switch — the home screen states them as a count instead.

### The one direction that is free

The opt-in categories (social media, entertainment) and the user's own domain list *do* have
switches, because they only ever **add** to what's blocked. That gives one rule that decides every
gate on the home screen: **strengthening protection is free, weakening it costs the PIN.** Turning a
category on, or saving a block list that keeps everything it had, goes straight through; turning one
off, or saving a list that drops an entry, prompts. Collapsing the "add websites" section clears the
list, so that prompts too, rather than leaving entries that look blocked but aren't.

## Languages

English, Bangla, Spanish, and Arabic, defaulting to **whatever the system is set to** — so a fresh
install already comes up in the phone's language with nothing to configure. Settings has an explicit
picker, each language named in its own script, so someone who lands in one they can't read can still
find their way out.

Applied by wrapping the context in `attachBaseContext` (`LocaleHelper.kt`) rather than with
`AppCompatDelegate.setApplicationLocales`, which only takes effect for activities extending
`AppCompatActivity` — this app is pure Compose on `ComponentActivity`. The VPN service wraps its
context the same way, or the persistent notification would come up in a different language from the
app it points at. Arabic gets RTL for free from `supportsRtl` plus the configuration's layout
direction, which `LocaleHelper` sets explicitly.

Numbers go through `CountFormat`, not string concatenation, so Bangla renders its own numerals and
Arabic its own. The headline count uses CLDR's **LONG** compact style ("85 thousand", "৮৫ হাজার",
"٨٥ ألف") rather than SHORT: short forms are meant for constrained space, and in Bengali SHORT
truncates হাজার to "হা", which reads like a cut-off word mid-sentence.

## Hashed blocklists

Android's lists are stored as **salted SHA-256 digests**, not plaintext domains — in the bundled
resources and in the payload fetched from the lists repo. Before this, `unzip app-debug.apk` handed
over a readable directory of every blocked gambling and adult site; now it yields only digests.

This is possible on Android and *not* on the other platforms because Android does its own matching
in Kotlin. Chrome's `declarativeNetRequest` and Safari's content blocker are declarative — the
browser or OS matches from a rule file containing literal domains — so those need something
reversible, and get a scrambled list instead.

Hashing destroys the suffix relationship `hostMatchesDomain` relies on, so subdomain matching is
recovered by enumerating candidates: for `a.b.example.com`, hash `a.b.example.com`, `b.example.com`,
`example.com`, `com` and look each up. Bounded by label count, so a handful of digests per lookup.
`DomainHasherTest` asserts hashed matching agrees with plaintext matching host-for-host.

`Matcher.decide` is unchanged as the tested spec: it takes a `DomainSet`, with `PlainDomainSet` and
`HashedDomainSet` implementations, so precedence logic is identical either way and
`MatcherTest`'s cross-platform cases still run against plaintext.

**What it's worth:** the digests are genuinely one-way — the app never reverses them. But someone
with a large corpus of domains can hash them all and find which are on the list, and the salt ships
in the app. This stops the published file and the APK being casually readable; it isn't secrecy, and
it can't be, since the app must read the list.

The generator (`scripts/build-android-blocklists.ts`) and `DomainHasher.kt` must produce identical
digests. `DomainHasherTest` pins shared vectors so drift fails loudly rather than silently matching
nothing — which would leave the app blocking nothing while looking healthy.

## Reusing shared logic

- `npm run build:android` hashes the per-category domain lists from `packages/core/src/blocklists/`
  into `apps/android/core/src/main/resources/blocklists/`. JVM resources of a plain Kotlin module
  are packaged into the APK *and* on the classpath under `:core:test`, so `Blocklists.kt` reads
  them the same way in both. Run it whenever the core blocklists change.
- Matching precedence follows the spec in `packages/core/src/matcher.ts` (`decide`), reimplemented
  in `core/src/main/kotlin/com/safeworld/core/Matcher.kt`. The two are kept in sync by hand (no
  codegen between TS and Kotlin); `core/src/test/kotlin/com/safeworld/core/MatcherTest.kt` mirrors
  `packages/core/test/matcher.test.ts` case-for-case, as `MatcherTests.swift` does on iOS.

## Building

```bash
npm run build:android            # export the blocklists into :core's resources
cd apps/android
./gradlew :core:test             # pure-JVM tests, no Android SDK or emulator needed
./gradlew :app:testDebugUnitTest # DNS/IPv4 packet tests (needs the Android SDK)
./gradlew :app:assembleDebug     # build the APK
```

The Gradle wrapper is checked in, so `./gradlew` works directly. Building `:app` needs an Android
SDK with platform 35; `local.properties` (gitignored) points at it, or Android Studio writes it.

A no-sudo toolchain on macOS — note the JDK comes from the **formula**, not the `temurin` cask,
because casks shell out to the macOS installer and need a sudo password:

```bash
brew install openjdk@17 gradle
brew install --cask android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" \
           "system-images;android-35;google_apis;arm64-v8a"
avdmanager create avd -n safeworld -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_7
$ANDROID_HOME/emulator/emulator -avd safeworld     # the iOS-Simulator equivalent
```

## Verification

Compiles clean; **29 unit tests pass** (13 in `:core`, 16 in `:app` — packet parsing and PIN
hashing). `MatcherTest`'s 11 cases match `packages/core/test/matcher.test.ts` one-for-one, so the
Kotlin `decide()` port agrees with the TS spec.

Verified by hand on an Android 15 (API 35, arm64) emulator, with the tunnel established
(`Vpn: Established by com.safeworld.app on tun0`, `inet 10.111.222.1/32`):

| Check | Result |
|---|---|
| `bet365.com`, `sports.bet365.com`, `pornhub.com` | NXDOMAIN (`unknown host`) — subdomain match works |
| `wikipedia.org`, `example.com` | resolve normally — the forwarding path works |
| "Blocked today" counter | incremented to exactly 3 for 3 blocked lookups |
| Persisted settings JSON | field-for-field identical to the TS/Swift `Settings` shape |
| First run | Set-PIN screen blocks all configuration until a PIN exists; stored salted+hashed, no plaintext |
| Turning protection **on** | No PIN asked (by design); tunnel establishes |
| Turning protection **off**, wrong PIN | Rejected — tunnel stayed up and domains stayed blocked |
| Turning protection **off**, correct PIN | `tun0` gone, service record count 0, `enabled:false`, domains resolve |
| Five wrong PINs | Cooldown engaged at exactly 5; dialog hides the input and counts down |
| Cooldown vs. force-stop | Survives `am force-stop` + relaunch (counter and deadline are persisted) |
| Protection after force-stop | Auto-restored by `restoreProtectionIfLeftOn`, interruption flag correctly stayed false |
| WorkManager | Periodic job registered with the system JobScheduler |
| Uninstall protection | With admin active, `adb shell pm uninstall` fails with `DELETE_FAILED_DEVICE_POLICY_MANAGER` |
| Hashed lists | Blocking unchanged (domains + subdomains blocked, others resolve); grepping the unzipped APK for blocked domains returns nothing |

**Not proven:** real hardware. The emulator resolves DNS through its own NAT layer (`10.0.2.3`),
so the forwarding path exercised here isn't quite a physical device's. Behaviour under network
changes (Wi-Fi ↔ cellular), doze, and reboot is also untested — see next steps.

## In-app updates

The app is distributed as a downloaded APK, so no store updates it — which is why it updates
itself. Settings shows the installed `versionName`, checks the
[GitHub Releases API](https://api.github.com/repos/jobayersajal1/project-safe-world/releases/latest)
for a newer tag (throttled to once a day, `force` on the button), and offers to download and
install it. See [`update/`](app/src/main/kotlin/com/safeworld/app/update).

- **Ordering** is `:core`'s `Version.kt`, mirrored by `Version.swift` for iOS/macOS with the same
  pinned test vectors. Tolerant, not strict semver — a malformed tag reads as 0 rather than
  throwing, so it can only fail to look newer.
- **Asset selection** picks the `.apk` out of a release that also carries the `.dmg`, `.exe`, and
  Chrome `.zip`. `UpdateChecker.select` is split from the fetch so both are tested against a
  captured real payload.
- **Installing** commits a `PackageInstaller` session; Android then shows its own confirmation
  dialog, which `UpdateInstallReceiver` launches. `REQUEST_INSTALL_PACKAGES` is checked *before*
  downloading, so a user who hasn't granted it is sent to the system screen instead of being
  refused after 20 MB.
- **The downloaded APK must be signed with the same key as the installed one** or Android refuses
  the update outright. A locally-built debug install can therefore check for updates but never
  apply one — expected, not a bug.
- Drafts and pre-releases are skipped, and the check is not PIN-gated: updating only ever
  strengthens protection, and gating it would cut a user who forgot their PIN off from fixes.
- `UpdateManager` is a **process-wide singleton**, like `SettingsStore`, not something the Activity
  owns. Activity-scoped was the obvious first shape and it was wrong: a rotation cancelled an
  in-flight download mid-stream and reset the state to Idle, while the throttle survived as a
  static and suppressed the automatic re-check for another 24 hours — so a "0.2.0 is available"
  the user had just been shown silently became a bare "Check for updates" button.

**0.1.0 cannot be auto-updated.** The feature shipped in 0.2.0, so every 0.1.0 install has no
updater in it and can never be offered one — those users have to install 0.2.0 by hand, once.
Every release after that updates in place. This is the unavoidable bootstrap cost of adding an
updater to an already-distributed app.

Verified end-to-end on the emulator against the live API: a build faked to 0.0.9 found 0.1.0,
reported its real 20 MB size, gated on the install permission, downloaded all 20,243,764 bytes, and
brought up Android's "Do you want to update this app?" dialog. Separately, the signed 0.2.0 release
build installed **over** an existing 0.1.0 without an uninstall (same signing key, `versionCode`
1 → 2), showed "Installed version 0.2.0" from the minified R8 build, and kept its check result
across a rotation.

## Next steps

- [x] Scaffold the Gradle project (`:app` + `:core`).
- [x] Implement the `VpnService` DNS sinkhole.
- [x] Bundle + parse exported blocklists; settings UI mirroring the extension.
- [x] Compile, unit-test, and verify blocking end-to-end on an emulator.
- [x] PIN gate, uninstall friction, takeover detection, background list refresh.
- [ ] Test on physical hardware over `adb` — the emulator's NAT'd DNS isn't representative.
- [ ] Point `RemoteConfig.UPDATE_URL` at a published lists endpoint. Note the URL is extractable
      from the APK, so an unlisted endpoint is obscurity, not access control — don't put a token in
      the app to read a private repo.
- [x] Rate-limit PIN attempts (5 tries, then a 15-minute cooldown).
- [ ] Restart the tunnel on network changes (`ConnectivityManager.NetworkCallback`) — DNS servers
      discovered at establish time go stale when the device switches Wi-Fi ↔ cellular.
- [ ] Start on boot (`RECEIVE_BOOT_COMPLETED`) when protection was left on.
- [ ] Background refresh for remote updates (`WorkManager`) — currently fetched on app launch only.
- [ ] A blocked-page equivalent: NXDOMAIN gives a browser error, not the extension's friendly page.
      Needs a local responder, not just a sinkhole.
- [ ] Optional `AccessibilityService` to block specific installed apps (e.g. gambling apps), which
      DNS filtering can't reach.
- [ ] App icon polish (currently a generated adaptive vector).
