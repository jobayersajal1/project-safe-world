# Safe World — macOS

> **Status:** Shipping. The menu-bar app installs a root DNS daemon that blocks **4,430,965**
> domains in every app; the `/etc/hosts` sinkhole (capped at ~150,000) remains as the fallback when
> the daemon isn't installed. The Network Extension content filter described below is not started
> and is no longer needed for coverage — only for tamper resistance.

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
repo. Package a `.dmg` with:

```bash
mkdir -p dmg-staging && cp -R build/Build/Products/Release/SafeWorld.app dmg-staging/ \
  && ln -s /Applications dmg-staging/Applications
hdiutil create -volname "Safe World" -srcfolder dmg-staging -ov -format UDZO dist/SafeWorld.dmg
```

### Gatekeeper blocks the result, and "right-click → Open" will not save you

An ad-hoc signature is *valid* but carries no Team ID, so it can't be notarized, and Gatekeeper
refuses it outright once the file is quarantined. Reproduced on macOS 26.2 against the published
0.2.0 `.dmg`:

```
codesign --verify --deep --strict   -> valid on disk, satisfies its Designated Requirement
spctl --assess --type execute       -> rejected
```

What the user sees is "Apple could not verify …is free of malware", with **Move to Trash** as the
prominent button — so the app appears to delete itself on install.

**Do not document "right-click and choose Open".** That bypass was removed in macOS 15; repeating it
sends people in a circle. The two things that actually work on macOS 15+:

1. Press **Done** (not Move to Trash), then **System Settings → Privacy & Security → Open Anyway**.
2. Or strip the quarantine flag, which is what Gatekeeper actually keys on:
   ```bash
   xattr -dr com.apple.quarantine /Applications/SafeWorld.app
   ```

Both are workarounds for a missing signature, not fixes. **The fix is a Developer ID certificate
(paid Apple Developer Program) plus notarization**, after which the app opens with no warning and no
steps. That also requires turning on `ENABLE_HARDENED_RUNTIME`, which notarization mandates and this
project currently has off.

## System-wide blocking (uncapped)

The hosts file caps macOS at 50k per category: it needs literal domains and is parsed linearly.
`safeworld-dnsd` removes that ceiling by putting a resolver on `127.0.0.1:53`, matching against a
memory-mapped fuse filter, and carrying the full **4.48M domains** — the same filters Android,
iOS and Windows now ship, byte for byte.

A GUI app in `/Applications` runs as the user and cannot bind a port below 1024, so the resolver
is a separate binary that `launchd` starts as root. That is the only reason the daemon exists.

**Installed from the app, not from a terminal.** `DaemonController.swift` does what the shell script
below does, in one `osascript … with administrator privileges` prompt, and the daemon binary is
embedded at `Contents/Helpers/safeworld-dnsd` by a `postBuildScripts` phase in `project.yml`. Before
that phase existed the `.dmg` contained no daemon at all, which is why installation was
terminal-only and the shipping app blocked the capped list.

The ordering in both the app and the script is the whole safety story, and neither may be reordered:
save the current resolver first, load the daemon, **prove it answers on port 53 before touching
DNS**, and only then point DNS at `127.0.0.1` with the real resolver kept as secondary. Uninstall
reverses it — DNS first, daemon second.

The script remains for development and recovery:

```bash
npm run build:ios                      # generates the filters
sudo apps/macos/dnsd/safeworld-dnsd.sh install
dig +short bet365.com                  # expect nothing (NXDOMAIN)
dig +short wikipedia.org               # expect an address
sudo apps/macos/dnsd/safeworld-dnsd.sh uninstall
```

`status` needs no root and reports what is loaded and what DNS is set.

> **What is verified.** The embedded daemon has been run from the built `.app` and reported
> `serving on 127.0.0.1, 4430965 domains`, blocking `bet365.com` while `wikipedia.org` resolved. The
> shell-script install path was tested end to end under `sudo`, including fail-open and full DNS
> restoration. **The app's own install button has not been clicked** — the authorization prompt needs
> a human at the keyboard — so the `osascript` plumbing around that verified script is the one
> untested link.

### Deleting the app does not stop the blocking

The daemon is a root LaunchDaemon with `KeepAlive` and does not depend on the app existing, so
dragging Safe World to the Trash leaves it running. That is deliberate — a one-step bypass would
defeat the point of the whole thing — but it must not be a trap. So the install writes its own
uninstaller next to the daemon, which works whether or not the app is still there:

```bash
sudo '/Library/Application Support/SafeWorld/uninstall.sh'
```

It restores DNS *before* removing the daemon, refuses to run without root, and is safe to run twice.
The menu shows that command next to the "Turn off" button rather than leaving someone to discover the
situation after deleting the app.

### Fails open, deliberately

The real resolver stays configured as the **secondary**. macOS only falls back on a timeout, and
NXDOMAIN is a valid answer rather than a failure — so blocking works exactly as intended, but if
the daemon dies the machine keeps resolving instead of losing DNS entirely.

The cost is honest: killing the daemon disables blocking, which the hosts file could not be
bypassed that way. It is still the right trade, because the alternative failure leaves someone
with no working internet and no way to look up how to fix it. `launchd` restarts the daemon
immediately (`KeepAlive`), keeping that window to about a second.

Two more guards: `install` verifies the daemon actually answers on port 53 **before** touching
DNS, and backs out if it doesn't; `uninstall` restores DNS **before** removing the daemon, so
there is never a moment pointing at nothing.

### What is verified

The resolver itself is tested end to end by `swift test` — a real UDP query, through the real
filter, over a real socket, on a high port needing no root. `DnsProxyRealFilterTests` runs the
same proxy against the actual 4.48M filters and confirms `bet365.com` and `pornhub.com` return
NXDOMAIN while `wikipedia.org`, `github.com` and `kernel.org` resolve. Verified separately with
`dig` against the built daemon on port 15353:

```
safeworld-dnsd: serving on 127.0.0.1:15353, 4430965 domains
bet365.com      status: NXDOMAIN
wikipedia.org   status: NOERROR   103.102.166.224
```

**Not verified:** the privileged half — `launchctl load` on port 53, and the `networksetup`
switch. Both need a root password, so they have never run. That is what the `install` script is
for, and why it checks before it commits and can undo itself.

## In-app updates

The menu shows the installed `CFBundleShortVersionString` and checks the
[GitHub Releases API](https://api.github.com/repos/jobayersajal1/project-safe-world/releases/latest)
for a newer tag, throttled to once a day. `Version` and `ReleaseChecker` come from `SafeWorldCore`
and are shared with iOS; the app-side wiring is [`UpdateService.swift`](SafeWorld/UpdateService.swift).

Picking the `.dmg` out of a release that also carries the `.apk`, `.exe`, and Chrome `.zip`, it
downloads to ~/Downloads with a progress bar and then opens the image — **stopping there on
purpose**. Replacing a running app bundle in place is what Sparkle exists to do, and doing it
safely means a signed, notarized build so the replacement can be verified; this app is ad-hoc
signed, so a self-replacing updater would be swapping the binary with nothing checking what it
swapped in. Mounting the image and letting the user drag it to Applications keeps Gatekeeper in the
loop and is one step for them.

> **Gotcha:** `CFBundleShortVersionString` has to be listed in `project.yml`'s `info.properties`,
> not just as a build setting. XcodeGen rewrites `Info.plist` wholesale and defaults that key to
> `"1.0"` — which compares as *newer* than the real 0.1.0 release, so the check silently reported
> "up to date" forever. It is now `$(MARKETING_VERSION)`.

## Next steps

- [x] Scaffold the Xcode menu-bar app project.
- [x] Implement `/etc/hosts` sinkhole MVP with a settings UI mirroring the extension.
- [x] In-app update check, downloading the `.dmg` and opening it.
- [x] Remote list updates, reusing `SafeWorldCore.RemoteUpdate`/`Scramble` as iOS does.
- [x] App icon (`Assets.xcassets/AppIcon.appiconset`) — same glyph as the menu-bar icon
      (`shield.checkerboard`), programmatically rendered since no design assets exist yet; swap for
      real artwork when there is any.
- [ ] Sign with a real Apple Developer ID and notarize releases.
- [ ] Add a Network Extension content-filter target.
