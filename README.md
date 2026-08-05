# Safe World

Protect users from harmful websites — scam/phishing/malware, gambling, and adult/haram
content — by blocking known-bad domains at the network layer.

**Roadmap:** Chrome extension (this phase) → Android → iOS / macOS / Windows. The
platform-agnostic category and matching logic lives in `packages/core` so later platforms can
reuse it. A second, independent feature — [blurring people on screen](#blurring-people-on-screen-chrome--android-not-yet-in-a-release) —
is built for Chrome and Android.

**Website:** [`website/`](website) is the public landing page (self-contained `index.html`, no
build step, translated into English/Bengali/Spanish/Arabic) — see [website/README.md](website/README.md)
for local preview and deployment via GitHub Pages.

## Status

Phase 1 — Manifest V3 Chrome extension (shipping):

- Blocks by category (gambling, adult, scam) using `declarativeNetRequest`.
- Friendly block page instead of a raw connection error.
- Bundled offline blocklists **plus** optional periodic remote updates.
- Per-category toggles, custom allow/block lists, and a "blocked today" counter.
- Self-control model — no PIN lock; the user can toggle protection freely.

Phase 2 — iOS app (in progress, see [apps/ios](apps/ios)): SwiftUI app + Safari Content Blocker
extension scaffolded and building, with the same category toggles, custom allow/block lists, and
remote updates as the extension, backed by a `SafeWorldCore` Swift package that mirrors
`packages/core`. Screen Time and system-wide (Network Extension) blocking are not started yet.

Phase 3 — Android app (working, see [apps/android](apps/android)): Kotlin/Compose app plus a local
`VpnService` DNS sinkhole for **device-wide** blocking (all apps and browsers, not just one),
backed by a `:core` Kotlin module mirroring `packages/core`. Scam/gambling/adult are always on and
shown as a count; social media, entertainment, and the user's own domains are opt-in switches. PIN
gate with an attempt limit and a one-time recovery code, uninstall friction via device admin, and
UI in English/Bangla/Spanish/Arabic following the system language. Verified blocking end-to-end on
an Android 15 emulator; not yet tested on physical hardware.

Phase 4 — macOS app (MVP shipping, see [apps/macos](apps/macos)): a menu-bar app managing a
system-wide `/etc/hosts` sinkhole, sharing iOS's `SafeWorldCore` Swift package (which already
declares macOS support) rather than a third reimplementation. Master switch, per-category toggles,
and custom allow/block lists; the admin-privileged hosts-file write goes through one macOS
admin-password prompt per change. Network Extension content filtering is not started.

Phase 5 — Windows app (MVP shipping, see [apps/windows](apps/windows)): a WinForms tray app
managing a system-wide `hosts` sinkhole, backed by a `SafeWorld.Core` C# library — a fourth
from-scratch port of `decide()`, since Windows has neither a JS runtime nor Swift/Kotlin. Runs
elevated (one UAC prompt at launch) to write the hosts file directly. WFP-based filtering is not
started.

## Blurring people on screen (Chrome + Android, not yet in a release)

Everything above is all-or-nothing: a site or an app is reachable or it is not. This is the setting
in between — the page loads, and photos of people of the chosen gender are covered in place. All
on-device; no image, and no request about an image, ever leaves the machine.

- **Chrome** blurs `<img>`, `<video>` and CSS backgrounds. The stylesheet that hides every image
  arrives with the document and the content script only ever takes blur *away*, so nothing is ever
  briefly visible. Built and measured (first verdict 162 ms, 50 images in 3.1 s on software WebGL);
  **not yet checked by hand in real Chrome**, which unpacked MV3 requires.
- **Android** covers *people*, not faces — a screen is not a photograph, and covering face boxes
  leaves the body. It detects person boxes, finds faces inside them, and classifies only those,
  which also covers someone turned away from the camera. Verified on-device against the minified
  release build.

The two platforms run the **same** classifier: `scripts/port-gender-model.py` reimplements
face-api's `AgeGenderNet` in TensorFlow and loads its MIT weights, and
`scripts/check-gender-parity.py` holds the port to the original's own output — currently 3.3e-07
across 16 faces, which is float32 round-off. Run it after touching the port.

Recognising who is in a picture is imperfect, and least reliable on children, side profiles, small
thumbnails, and faces in hijab or niqab. The rule everywhere is that **uncertainty covers**: an
unsure answer blurs, because a missed blur is the failure the feature exists to prevent while an
over-blur is an inconvenience. The apps say so rather than implying more accuracy than they have.

## Repository layout

```
packages/core/               Platform-agnostic TS: categories, matching, bundled blocklists
apps/chrome-extension/        Manifest V3 extension (Vite + @crxjs/vite-plugin)  [shipping]
apps/android/                 Android app — VpnService DNS sinkhole              [working]
apps/ios/                     iOS app — Safari Content Blocker + Screen Time  [in progress]
apps/macos/                   macOS app — hosts sinkhole → Network Extension  [MVP shipping]
apps/windows/                 Windows app — hosts sinkhole → WFP              [MVP shipping]
scripts/build-blocklists.ts   Regenerates blocklists + DNR rulesets from source lists
```

Each `apps/<platform>/README.md` documents that platform's stack, blocking mechanism, and how it
reuses the category definitions and domain lists from `packages/core`. Only `chrome-extension` is
an npm workspace; the native apps have their own toolchains.

## The blocklists live elsewhere

This repo contains **no domain data** — not plaintext, not scrambled, not hashed. The lists live in
a private repo and are pulled in at build time:

```bash
npm run lists:pull     # required before any build; needs access to the private list repo
```

Without it every build fails with instructions rather than silently producing an app that blocks
nothing. Each app ships its lists encoded (hashed on Android, scrambled elsewhere) and decodes them
at runtime, so an unpacked `.apk`, `.zip`, `.dmg`, or `.exe` yields no readable list either.

That encoding is obfuscation, not secrecy: the key ships in every app, so anyone who unpacks one can
reverse it. The privacy comes from the source repo being private; this only stops casual reading.

## Development

```bash
npm install            # install workspace deps
npm run lists:pull     # fetch the lists (see above) — required first
npm run build:lists    # generate the extension's scrambled lists
npm run build          # build core + extension -> apps/chrome-extension/dist
npm run dev            # watch-build the extension
npm test               # run unit tests (vitest)
npm run typecheck      # tsc project references
```

### Load the extension in Chrome

1. `npm run build`
2. Open `chrome://extensions`, enable **Developer mode**.
3. **Load unpacked** → select `apps/chrome-extension/dist`.

### Build the iOS app

```bash
brew install xcodegen             # once
npm run build:ios                 # export blocklists into the SafeWorldCore Swift package
cd apps/ios && xcodegen generate  # regenerate SafeWorld.xcodeproj from project.yml
open SafeWorld.xcodeproj
```

See [apps/ios/README.md](apps/ios/README.md) for the blocking architecture and current status.

### Build the Android app

```bash
npm run build:android                  # export blocklists into the :core Kotlin module
cd apps/android
gradle wrapper --gradle-version 8.9    # once, if ./gradlew isn't there yet
./gradlew :core:test                   # pure-JVM tests, no Android SDK needed
./gradlew :app:assembleDebug
```

See [apps/android/README.md](apps/android/README.md) for the DNS-sinkhole architecture, what it
does and doesn't catch, and current status.

### Build the macOS app

```bash
brew install xcodegen              # once
npm run build:ios                  # macOS shares iOS's SafeWorldCore package + bundled lists
cd apps/macos && xcodegen generate # regenerate SafeWorld.xcodeproj from project.yml
xcodebuild -project SafeWorld.xcodeproj -scheme SafeWorld -configuration Release \
  -derivedDataPath build CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=YES build
```

See [apps/macos/README.md](apps/macos/README.md) for the hosts-file sinkhole architecture and how
to package a `.dmg`.

### Build the Windows app

```bash
npm run build:windows              # export blocklists into SafeWorld.Core's embedded resources
cd apps/windows
dotnet test SafeWorld.Core.Tests   # pure C#, runs on any OS
dotnet publish SafeWorld.App -r win-x64 --self-contained true -p:PublishSingleFile=true -c Release
```

Cross-compiles cleanly from macOS/Linux (only *running* the result needs Windows). See
[apps/windows/README.md](apps/windows/README.md) for the hosts-file sinkhole architecture.

### Publish updated blocklists

The full list ships inside each app. The public repo
[`safe-world-block-list-update`](https://github.com/jobayersajal1/safe-world-block-list-update)
carries only the domains added *since* the baseline a release was built with, in two encoded
formats — one-way digests for Android, scrambled domains for iOS/Chrome. After editing the lists:

```bash
npm run build:remote                                  # -> dist/remote/*.json
cd ../safe-world-block-list-update                               # clone it once, keep it
cp "../project safe world/dist/remote/"delta-*.json .
git add -A && git commit -m "Update lists" && git push
```

See [scripts/templates/README.md](scripts/templates/README.md) for what the formats are, what the
encoding is and isn't worth, and the one-time Pages setup.

## License

MIT
