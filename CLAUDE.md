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
blocking the full ~4.56M domains in every app. The `hosts`-file sinkhole each started as is now only
the fallback when the resolver cannot start. macOS **shares iOS's
`SafeWorldCore` Swift package** rather than reimplementing `decide()` again — it's pure Foundation
and already declares macOS support in its own `Package.swift`, and both are Swift/Apple platforms.
Windows has neither a JS runtime nor Swift/Kotlin available, so `apps/windows/SafeWorld.Core` is a
fourth from-scratch port of categories/`Settings`/`decide()`, in C# — **keep all ports (Swift,
Kotlin, C#) in sync with `packages/core` by hand**. Network Extension (macOS) and WFP (Windows) content
filtering are not started; with the DNS resolvers shipping, those are now about tamper resistance
and about seeing DoH/direct-to-IP traffic, not about coverage.

**Per-platform reach, because it differs and the numbers are easy to get wrong:** Android, macOS and
Windows all carry **4,558,995** — they build from the same fuse filters, so a figure that differs
between those three is a stale note rather than a real difference. Android gets it through the
VpnService; macOS once the daemon is installed from the app, ~172,000 on the hosts fallback; Windows
via the proxy, same hosts fallback. **iOS 172,407** — the cap that actually binds is
`IOS_MAX_PER_CATEGORY` (50,000, applied *per category* in `build-ios-blocklists.ts`), so the total
is the sum of the capped categories and not a single ceiling. That is why this number went *up*
when `list7` landed rather than staying pinned at 150,000: `BlockerListBuilder` emits one rule per
category with the domains in its `if-domain` array, so Safari's ~150k *rule* ceiling is nowhere
near binding — about seven rules are in play. The undocumented limit is the array length, which is
what the conservative 50,000 is guarding. The `SafeWorldTunnel` packet
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

**The stored lists are gzipped, and `lists:check` cannot catch the failure that made them so.**
Pretty-printed scrambled `list1` is ~103 MB and GitHub rejects anything over 100 MB, so `lists:push`
failed while `lists:pull` kept working — leaving the private repo holding an old 20,000-per-category
fetch. Pulling that overwrote a 4.5M-domain working tree with an 85k one, and a build straight after
produced apps that passed every test and blocked almost nothing. `lists:check` only asserts the
files exist. **After a pull, check the counts, not just that it succeeded**; `fetch:lists` caps at
`MAX_PER_CATEGORY` (default 20,000), so restoring the full corpus means
`MAX_PER_CATEGORY=10000000 npm run fetch:lists`.

## Commands

```bash
npm install          # install all workspaces (npm workspaces monorepo)
npm run lists:pull   # REQUIRED FIRST — the lists are not in this repo
npm run build:lists  # regenerate apps/chrome-extension/src/rules/*.json from the core blocklists
npm run build:model  # copy the exported advisory models into the extension's packaged files
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

Advisory model (Python, not an npm workspace — needs the venv from `scripts/port-gender-model.py`,
and `DYLD_LIBRARY_PATH=/opt/homebrew/opt/expat/lib` or Homebrew's broken `pyexpat` breaks pip):

```bash
# $WORK must be OUTSIDE this repo — everything below writes domain data.
python3 scripts/prepare-model-corpus.py --work $WORK --data $WORK/data  # needs majestic.csv,
                                    # top-1m.csv (Tranco), top10milliondomains.csv (DomCop),
                                    # public_suffix_list.dat
python3 scripts/train-domain-model.py --work $WORK
python3 scripts/eval-domain-model.py --work $WORK --dump-top 60   # the gate; hand-review the dumps
python3 scripts/export-domain-model.py --work $WORK               # -> packages/core/src/model/
npm run build:model
```

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

There are seven, split by `optional`. `list1`/`list2`/`list3`/`list7` (scam, gambling, adult, drugs
& illegal) are the protection promise — on by default, and **forced on** by
`enforceMandatoryCategories` on all four app platforms so a stale stored value can't leave one off.
`list4`/`list5`/`list6` (social, entertainment, games) are opt-in, off until the user asks. Chrome
is the exception by design: it's the self-control build with no PIN, where everything toggles
freely.

**`list7`'s `ruleIdBase` is `7 * RULE_ID_STRIDE`, and it sits before `list6` in the array.** The
bases are keyed to the list *number*, not to array position, precisely so a category inserted out
of order cannot silently collide with an existing range.

**`list6` (games) and the dating half of `list4` come from UT1, not from the DNS-blocklist world.**
The usual maintainers categorise by *harm* — malware, phishing, porn, gambling — so they publish
nothing for "games" or "dating"; `blocklistproject`'s `fortnite.txt` is down to a handful of
entries. The Université Toulouse 1 Capitole blacklist categorises by *subject* and therefore does,
which took `list6` from 117 hand-curated domains to ~33,700. It is **CC BY-SA 4.0**, the only
share-alike source in `sources.ts` — attribution rides in each list file's `sources` block, and the
ShareAlike term attaches to the published lists, not to this repo's code. **`list7` draws on UT1
too** (`ut1-drugs`, `ut1-warez`, alongside two public-domain `blocklistproject` feeds), so the same
ShareAlike term rides with it.

Real-money play (teen patti, rummy, fantasy cricket, betting) goes in **`list2`, not `list6`**.
Paying to play is gambling, `list2` is mandatory so it is blocked unconditionally rather than only
for someone who opted into blocking games, and the headline count sums the categories — a domain in
two of them would be counted twice.

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

**The advisory model is a third feature, and the only one that guesses.** Everything else is
exact: `decide()` answers from a list. `packages/core/src/advisory.ts` scores the *hostname itself*
— hashed character 3/4/5-grams plus structural tokens (TLD, label count, length, digit and hyphen
ratios) into a per-category linear model — and warns about a domain nobody has listed yet. It runs
only where `decide()` already returned allowed, and **the strongest thing it can say is warn**.

**Two categories ship, because two cleared the gate.** Measured on 1,790,722 held-out negatives:
gambling at ~24% recall with genuine false positives under 1 per million, adult at ~24% at roughly
5 per million. Scam, social, games and drugs did not, and carry no model — enablement is per
category for that reason. Thresholds are the score at a *hand-reviewed rank* (list2 1200, list3
400), baked in by `export-domain-model.py`; a round number would be an operating point nobody
measured.

Four things here are counter-intuitive and each cost real time:

- **The false-positive rate measures list coverage, not model error, until adjudicated.** Negatives
  are top-sites minus our lists, so every gambling site we miss is labelled allowed. 11% of DomCop's
  10M was dropped for already being on a list, and what survives is not clean: list2's forty
  highest-scoring "false positives" were `jojobet-casino.top`, `222.casino`, `1xbet-48.com` and
  thirty-seven more of the same. `eval-domain-model.py --dump-top` writes the top-scoring negatives
  for hand review; **the reviewed files hold domains and stay in the work directory**, and they are
  tied to a model version — re-rank and the review must be redone.
- **Negatives must include the long tail.** Majestic's top million alone taught the model "looks
  obscure", because every negative it had seen was a short brandy name. The domains it must never
  flag are `1752solutions.com` and `247lightheartedcaregivers.com`. DomCop Open PageRank 10M
  supplies them. Raw recall *drops* when they are added; that is the honest direction.
- **There is no temporal split available.** `baseline/baseline.json` is the 20,000-per-category
  truncation from the stale-fetch incident, not a snapshot in time, and the private list repo's
  only full-corpus commits are two days and ~70 domains apart. The split is grouped by coarse
  registrable domain instead — 19.4% of list1 shares a parent, and shared hosting accounts for tens
  of thousands each, so a random split would measure memory.
- **int8 quantisation was never the blocker it looked like.** A max logit delta of 0.16–0.67 sounds
  alarming and means nothing: positives and negatives shift together so ranking survives, and recall
  moves 0.23 points. Clipping outliers to tighten the scale made it *worse*. Measure quantisation by
  its effect on recall.

**`advise()` refuses to score a shared publishing platform, and that guard is load-bearing.** The
adult model learned `*.blogspot.com` is adult — adult Blogger blogs are heavily represented in
list3 — and then flagged Blogger's own image CDN (`1.bp.blogspot.com`, `2.`, `3.`), which serves
the pictures on every Blogger blog there is, plus `videoseriesbiblicas.blogspot.com`. A subdomain
on shared hosting says something about that one blog and nothing about the platform.

**Two thresholds, because "possibly" and "almost certainly" are different claims.** `threshold`
warns; `blockThreshold` may block outright and is held far stricter than the hand review requires —
the bar is "nothing near the boundary", not "no false positive found". It costs most of the recall
(gambling 23.8% → 7.6%, adult 24.5% → 16.3%) and everything between the two still warns. A model
file lacking `blockThreshold` defaults to **infinity** in all four ports, never zero: the
difference between "warn only" and "block everything".

**Chrome warns; Android blocks, because a DNS answer cannot warn.** DNR cannot express this (a rule is a
fixed list of domains; the point is a host on no list), so the check rides on
`webNavigation.onBeforeNavigate` and redirects to the blocked page with `advisory=1`, which is a
visibly different page — "Continue anyway" is the emphasised button and it says outright that the
site is on no list. MV3 has no blocking navigation hook, so the page has begun loading when we
redirect; accepted, because a *guess* does not get to charge for a blocking handler on every
navigation, and listed categories never take this path. Blocking is a **second** opt-in there on top
of warning: someone who asked to be warned has not agreed to have sites taken away.

`SafeWorldVpnService` passes `allowBlocking = true` and takes only the strict tier — there is
nowhere in an NXDOMAIN to put "continue anyway", which is precisely why that tier sits where it
does. Verdicts are cached per host and the cache is **keyed on the settings**, so turning a category
off applies on the next query rather than whenever the cache next fills. iOS/macOS/Windows have
`DomainModel.{swift,cs}` ported and pinned but wired to nothing: a content-blocker rule list has no
interstitial, and the resolver path is the same yes-or-no as Android's.

**`list5` is not a category.** 24,241 of its 24,360 entries are `*.googlevideo.com` hostnames, so a
model trained on it learns "short CDN-shaped name" and flags `bayer.com` and `mail.google.com`. It
needs repairing as a list, independently of any of this.

**Blurring people is a second, independent feature — Chrome only so far.** Everything above is
all-or-nothing: a site is reachable or it is not. `BlurSettings` (`packages/core/src/blur.ts`) adds
the setting in between — the page loads, and photos of the chosen gender are blurred in place.
It is **stored under its own key, never merged into `Settings`**, because adding a non-optional
field to the Swift `Settings` silently wipes every stored setting (`Codable` makes it required,
`loadSettings` decodes with `try?`, so an existing blob falls back to defaults). Android and Safari
are planned; macOS and Windows will never have it.

Four things hold it up, and each has already failed the obvious way once:

- **Blur first, reveal after.** `public/blur.css` blurs every `img`/`video` at `document_start`;
  `src/content/blur.ts` only ever takes blur *away*. Measuring first shows every image for a few
  frames, which is the whole failure the feature exists to prevent.
- **The stylesheet is registered dynamically, not declared in the manifest.** It lives in `public/`
  (so its path is stable — crxjs renames what it processes) and the worker registers it with
  `chrome.scripting.registerContentScripts` only while the feature is on. A manifest content-style
  is injected unconditionally, and this feature ships *off*: every user who never enabled it would
  watch the web flash blurred while an async storage read came back.
- **Pixels are never read off the page element.** Drawing a third-party `<img>` to a canvas taints
  it and every read throws `SecurityError` (verified, not assumed). The offscreen document fetches
  the URL itself under `<all_urls>` and `createImageBitmap`s the blob — with `cache: "force-cache"`,
  or every analysed image is paid for twice on the network.
- **The detector runs twice per image, over differently inset copies, and the results are unioned.**
  TinyFaceDetector is anchor-based: a face filling the frame — every cropped avatar — falls outside
  its scale band and is **missed entirely**, and a missed face reads as a photo of nobody, so it is
  not blurred. Measured: inset 0.3 alone missed tight crops, 0.5 alone cut a six-face group to
  three, both together found every face either did. Collapsing it back to one pass reintroduces the
  miss silently.

`verdictForFaces` is the tested spec, and its asymmetry is the point: **uncertainty blurs.** The
unit is the whole image, not the face box — bodies and hair are the bulk of what needs covering and
no face box contains them. Models are MIT (face-api), committed under `public/models/`, and never
fetched at runtime.

**Android covers *people*, not faces, and is not finished.** A screen is not a photograph: covering
face boxes on a captured frame leaves the body, which is most of what needs covering. So
`PersonScanner` detects person boxes (EfficientDet-Lite0, Apache-2.0), finds faces inside them
(BlazeFace, Apache-2.0), and classifies those — which also covers someone turned away from the
camera, who yields no face and is therefore uncertain. `BlurService` captures via `MediaProjection`
and `BlurOverlay` paints panels over the result.

The gender classifier is **face-api's, ported to TFLite** by `scripts/port-gender-model.py`: there
is no serialized graph to convert, only a weight manifest and a hand-written JS forward pass, so the
architecture is reimplemented in TensorFlow and loaded with the same MIT weights.
`scripts/check-gender-parity.py` replays face crops captured from face-api in a browser and requires
agreement — currently 3.3e-07 over 16 faces, which is float32 round-off. **Run it after any change
to the port**; every layer there is a transcription, and a transposed filter produces a model that
runs and is wrong. Do *not* re-enable `Optimize.DEFAULT`: the weights are already uint8-quantised in
the manifest, and quantising again moved P(male) by up to 1.8e-2 against a 0.75 threshold.

Three release-only R8 failures are already fixed in `proguard-rules.pro` and will come back if the
rules are trimmed: **Flogger** (MediaPipe logs through it and it finds its call site by walking the
stack, so obfuscation kills `Graph`'s static initializer and the process with it), **protobuf-lite**
(looks fields up by name; renaming them fails graph construction), and MediaPipe/TFLite themselves.
All three are invisible in debug builds.

**The panels must never carry `FLAG_NOT_TOUCHABLE`, and this is the single most breakable thing
here.** Android silently clamps the alpha of an untrusted `TYPE_APPLICATION_OVERLAY` window that
passes touches through, down to `maximum_obscuring_opacity_for_touch` — **0.8** by default. It is a
tapjacking defence: obscure the screen completely, or let touches reach the app underneath, but not
both. `LayoutParams.alpha = 1f` is accepted and then overwritten, and the only place it shows is
`dumpsys window` (`mAttrs … alpha=0.8`, `mShownAlpha=0.8`). At 0.8 a face is dimmed and perfectly
readable — the feature failing while appearing to work. No drawing beats it: an opaque fill and
`PorterDuff.Mode.SRC` both changed nothing, because the alpha applies to the surface at composition,
not to the pixels.

So `BlurOverlay` puts **each panel in its own touchable window** sized to that panel, with
`FLAG_NOT_TOUCH_MODAL` so every touch outside its bounds still reaches the app behind. Verified both
ways on device: panels fully opaque, and a tap on Chrome's toolbar still opened a new tab. A tap
*on* a panel is swallowed, which is the price and the right way round.

Two related traps that follow from the capture including our own overlay:

- **`FLAG_SECURE` is not the answer.** It would exclude the panels from capture, but it blanks every
  screenshot and screen recording on the device while active — verified, the capture came back solid
  black.
- **`BlurService.hold` must not decide by looking at what it covered.** Its first version released a
  region once the pixels under it stopped looking flat, which is self-fulfilling — an opaque panel
  *is* flat, so nothing was ever released and coverage grew until it swallowed the screen. It now
  fingerprints the *uncovered* part of the frame and drops every held region the moment that moves.
- **Do not merge overlapping person boxes.** Worth doing while the panels were translucent, where
  overlaps double-darkened; with opaque panels overlaps are invisible, and in a group photo every
  box touches its neighbour, so merging produced one panel over the whole group — covering the men
  when the answer was women.

Verified on device: all three models load and run in the minified release build,
`app/src/androidTest/.../BlurPipelineTest.kt` passes 16 tests, and panels are opaque and land on the
target. **Coverage is coarse in a tight group photo** — person boxes overlap heavily and a box with
no matched face is covered by design, so more of the frame is covered than strictly needed. That is
the safe direction and is not treated as a defect.

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
