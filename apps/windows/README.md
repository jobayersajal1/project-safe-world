# Safe World — Windows

> **Status:** Shipping. The tray app runs a local DNS proxy blocking **4,482,470** domains in every
> app; the `hosts` sinkhole (capped at ~150,000) remains the automatic fallback when the proxy cannot
> start. WFP-based filtering described below is not started, and is now only about tamper resistance
> rather than coverage.

## Goal

Block scam/malware, gambling, and adult sites system-wide on Windows.

## Stack

- **Language / build:** C# on .NET, WinForms tray app (`SafeWorld.App`, `net10.0-windows`), plus a
  plain, cross-platform `SafeWorld.Core` class library (`net10.0`) — the Windows analogue of
  `packages/core`/iOS's `SafeWorldCore`: `CategoryId`/`Categories`, `Settings`, `Matcher.Decide`,
  and `HostsFileBuilder`. Neither Android/iOS's native language nor a JS runtime is available here,
  so this is a fourth from-scratch port of `decide()` — keep it in sync with the other three by
  hand, same as CLAUDE.md already asks for the existing three.
- **Min OS:** Windows 10/11 x64.
- **Solution:** `SafeWorld.slnx` — `SafeWorld.Core` (library), `SafeWorld.Core.Tests` (xUnit, ports
  `packages/core/test/matcher.test.ts`), `SafeWorld.App` (the tray app).

## Blocking approach

Implemented: **hosts-file sinkhole**, the MVP path this README previously called out. On every
settings change, `SafeWorldCore.HostsFileBuilder` computes the set of domains that should be
blocked, mirroring `Matcher.Decide`'s precedence (custom-allow beats everything, then custom-block,
then enabled categories), and renders them into a marker-delimited (`# BEGIN SAFEWORLD` /
`# END SAFEWORLD`) block spliced into `C:\Windows\System32\drivers\etc\hosts`, mapping each to
`0.0.0.0`, then flushes the DNS resolver cache (`ipconfig /flushdns`).

Writing the hosts file needs admin rights, so `SafeWorld.App` runs elevated from launch
(`app.manifest`'s `requestedExecutionLevel level="requireAdministrator"`) rather than running a
separate elevated helper process — Windows shows one UAC prompt at startup instead of one per
change. `HostsManager` no-ops (no write) when the computed contents already match what's on disk.

A hosts file can only sinkhole exact names, not "this domain and every subdomain" the way DNR rules
can, so `HostsFileBuilder` emits both the bare domain and a `www.` variant for each blocked entry —
coarser than the browser extension, and the acknowledged limitation of this MVP path.

Also implemented, and now the **default** path: **local DNS filtering**. `ProxyController` extracts
the embedded fuse filters, checks port 53 is free *before* touching anything, points the adapters at
`127.0.0.1` via `DnsSettingsManager`, and runs `DnsProxy` against a `FilterEngine`. That carries the
full **4,482,470** domains rather than the hosts file's ~150,000, and matches subdomains properly
instead of needing a `www.` variant per entry. `SettingsStore` starts it with the master switch and
keeps its settings current; the hosts file is still written so that a proxy which cannot start
degrades to the smaller list rather than to nothing.

`DnsSettingsManager` writes the previous DNS configuration to disk *before* changing it and calls
`RecoverFromPreviousRun()` at startup, because the failure mode being guarded against is a machine
left with no name resolution after a crash.

### Starting with Windows

The proxy runs **inside this process** — there is no service — so "does the app start with Windows"
and "is the machine protected after a reboot" are the same question. Until `StartupManager` existed
the app registered no startup entry at all, and a restart silently ended protection.

It was worse than simply unprotected. A reboot is not a clean exit, so the adapters are still pointed
at `127.0.0.1` with the real resolver as secondary and nothing listening on the primary — every
lookup waited for the dead primary to time out before falling back. Unfiltered *and* slow.
`RecoverFromPreviousRun()` fixes that, but only runs when the app starts.

**A scheduled task, not the `Run` key.** `app.manifest` requests `requireAdministrator`, and Windows
will not elevate anything launched from `HKCU\...\CurrentVersion\Run` — it silently does nothing.
`schtasks /Create ... /SC ONLOGON /RL HIGHEST` is the supported way to start an elevated app at logon
without a UAC prompt every time. The tray menu shows the state and can turn it off.

Registration is re-checked on every launch rather than recorded as a one-time "first run" flag: the
executable can be moved and the task can be removed by cleanup tools, and a stale task has exactly
the same effect as no task.

Not implemented yet:

1. **WFP (Windows Filtering Platform)** — a background service filtering connections by domain for
   the whole machine. Needs a signed driver (in practice an EV code-signing certificate and Microsoft
   attestation signing). With the DNS proxy shipping, the remaining argument for WFP is that DNS
   filtering cannot see DoH, hardcoded resolvers, or direct-to-IP connections.

**Remote list updates** are implemented: `SafeWorld.Core/Scramble.cs` and `RemoteUpdate.cs` port
`packages/core/src/scramble.ts`/`types.ts` (`RemoteUpdatePayload`) to C#, pinning the same shared
test vectors as the TS/Swift/Kotlin decoders. `SafeWorld.App/RemoteConfig.cs` fetches the same
scrambled delta iOS/macOS do from the public
[block-list-update repo](https://github.com/jobayersajal1/safe-world-block-list-update), silently,
on a fixed non-user-configurable endpoint. `TrayAppContext` runs the check on an hourly
`System.Windows.Forms.Timer` (a UI-thread timer, so the `await` continuation touching the
`NotifyIcon` afterward needs no extra marshaling); `RefreshRemoteIfDueAsync()` itself still only
fetches once every `UpdateIntervalHours`.

Still not implemented: a "blocked today" counter — a hosts-file sinkhole has no visibility into
individual blocked requests the way a WFP filter or VPN-based resolver does, so there's nothing to
count in real time.

## UI

Right-click the tray icon for: a **Protection** master switch, one checkable item per category
(disabled while Protection is off, matching the "self-control, no PIN" model of the browser
extension), **Edit custom lists...** (a small always-allow / always-block editor), and **Quit**.
Settings persist as JSON under `%AppData%\SafeWorld\settings.json`.

## Building

```bash
npm run build:windows              # copies packages/core/src/blocklists/*.json into
                                    # SafeWorld.Core/Resources as embedded resources
cd apps/windows
dotnet test SafeWorld.Core.Tests   # pure C#, runs on any OS (no Windows or WinForms needed)
dotnet publish SafeWorld.App -r win-x64 --self-contained true -p:PublishSingleFile=true -c Release
```

`--self-contained` bundles the .NET runtime into one `SafeWorld.exe` (~110 MB) so a Windows user
doesn't need the .NET runtime pre-installed — the tradeoff for a single-file download. This
cross-compiles cleanly from macOS/Linux; only *running* the result needs Windows.

There is no Windows code-signing certificate configured in this repo, so the published `.exe` is
unsigned — Windows SmartScreen will warn on first run until it's signed with a real Authenticode
certificate.

## Next steps

- [x] Scaffold the .NET solution here (tray app + core library).
- [x] Implement hosts-file sinkhole MVP with a settings UI mirroring the extension.
- [x] Remote list updates, reusing the `RemoteUpdate`/`Scramble` approach from iOS/Android.
- [x] Tray/exe icon (`Resources/SafeWorld.ico`) — same glyph as macOS's app icon, programmatically
      rendered since no design assets exist yet; swap for real artwork when there is any.
- [ ] Sign with a real Authenticode certificate.
- [ ] Evaluate a WFP-based filter for app-wide coverage without touching the hosts file.
