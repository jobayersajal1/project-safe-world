# Safe World — macOS

> **Status:** MVP shipping. A menu-bar app manages a system-wide `/etc/hosts` sinkhole. The
> stronger Network Extension content filter described below is not started.

## Goal

Block scam/malware, gambling, and adult sites across the whole Mac, not just one browser.

## Stack

- **Language / build:** Swift + SwiftUI, a menu-bar-only app (`LSUIElement`, no Dock icon or
  window). The Xcode project is generated from [`project.yml`](project.yml) via
  [XcodeGen](https://github.com/yonaskolb/XcodeGen) — edit `project.yml`, not
  `SafeWorld.xcodeproj` by hand, then re-run `xcodegen generate`.
- **Min macOS:** 13.0.
- **Shares [iOS](../ios)'s `SafeWorldCore` Swift package** rather than reimplementing categories,
  `Settings`, and `Matcher.decide` a third time in Swift — it's pure Foundation, already declares
  macOS as a supported platform in its own `Package.swift`, and both apps are Swift/Apple
  platforms, unlike the Android/Chrome ports which have no such option. `project.yml` references
  it by relative path (`../ios/SafeWorldCore`). Regenerating its bundled per-category domain lists
  (`npm run build:ios` at the repo root) updates both apps at once.

## Blocking approach

Implemented: **`/etc/hosts` sinkhole**, the MVP path this README previously called out. On every
settings change, `SafeWorldCore.HostsFileBuilder` (new — the macOS/Windows analogue of `BlockerListBuilder`)
computes the set of domains that should be blocked, mirroring `Matcher.decide`'s precedence
(custom-allow beats everything, then custom-block, then enabled categories), and renders them into
a marker-delimited (`# BEGIN SAFEWORLD` / `# END SAFEWORLD`) block spliced into `/etc/hosts`,
mapping each to `0.0.0.0`. `HostsManager.swift` stages the new file contents somewhere the current
user can write, then asks the user once via the standard macOS admin-password prompt
(`osascript … with administrator privileges`) to copy it into place and flush the DNS cache — the
privileged command line itself never contains user-supplied domains, so there's nothing there for a
hostile custom-domain entry to break out of. It no-ops (no prompt) when the computed contents
already match what's on disk.

A hosts file can only sinkhole exact names, not "this domain and every subdomain" the way DNR/Safari
content-blocker rules can, so `HostsFileBuilder` emits both the bare domain and a `www.` variant for
each blocked entry — coarser than the other platforms, and the acknowledged limitation of this MVP
path (see [`HostsFileBuilder.swift`](../ios/SafeWorldCore/Sources/SafeWorldCore/HostsFileBuilder.swift)).

Not implemented yet, in order of robustness:

1. **Network Extension content filter** (`NEFilterDataProvider`) — a system extension that filters
   traffic for all apps by domain. Requires a signed system extension and the Network Extension
   entitlement (Apple must grant it — not self-serve); this is the robust, app-wide solution and
   the long-term direction.
2. **Safari Content Blocker / Web Extension** — for the browser case; can reuse the same rule
   list as [iOS](../ios) (`BlockerListBuilder`, already shared via the same package).

**Remote list updates** are implemented, reusing `SafeWorldCore.RemoteUpdate`/`Scramble` (already
shared via the iOS package) plus new app-layer `RemoteConfig.swift`/`RemoteUpdateService.swift`.
Fetches the same scrambled delta iOS does from the public
[block-list-update repo](https://github.com/jobayersajal1/safe-world-block-list-update), silently,
on a fixed non-user-configurable endpoint — see iOS's `RemoteConfig.swift` doc comment for why. A
menu-bar app has no foreground/background transition to key a refresh off (unlike iOS's
`scenePhase`), so `SettingsStore` runs its own hourly `Timer` instead; `refreshRemoteIfDue()` itself
still only fetches once every `updateIntervalHours`.

Still not implemented: a "blocked today" counter — a hosts-file sinkhole has no visibility into
individual blocked requests the way a DNR ruleset or VPN-based resolver does, so there's nothing to
count in real time.

## Building

```bash
npm run build:ios                     # populates SafeWorldCore's bundled blocklists (shared)
cd apps/macos && xcodegen generate     # regenerate SafeWorld.xcodeproj from project.yml
xcodebuild -project SafeWorld.xcodeproj -scheme SafeWorld -configuration Release \
  -derivedDataPath build CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=YES build
```

The build ad-hoc signs (`CODE_SIGN_IDENTITY="-"`) since no Apple Developer ID is configured in this
repo — the app runs fine locally, but macOS Gatekeeper will warn on a downloaded, unnotarized copy
until it's signed with a real Developer ID and notarized. Package a `.dmg` with:

```bash
mkdir -p dmg-staging && cp -R build/Build/Products/Release/SafeWorld.app dmg-staging/ \
  && ln -s /Applications dmg-staging/Applications
hdiutil create -volname "Safe World" -srcfolder dmg-staging -ov -format UDZO dist/SafeWorld.dmg
```

## Next steps

- [x] Scaffold the Xcode menu-bar app project.
- [x] Implement `/etc/hosts` sinkhole MVP with a settings UI mirroring the extension.
- [x] Remote list updates, reusing `SafeWorldCore.RemoteUpdate`/`Scramble` as iOS does.
- [x] App icon (`Assets.xcassets/AppIcon.appiconset`) — same glyph as the menu-bar icon
      (`shield.checkerboard`), programmatically rendered since no design assets exist yet; swap for
      real artwork when there is any.
- [ ] Sign with a real Apple Developer ID and notarize releases.
- [ ] Add a Network Extension content-filter target.
