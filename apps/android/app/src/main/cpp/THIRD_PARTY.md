# Third-party code in the native build

## NetGuard — GPL-3.0

`netguard/` is the userspace TCP/IP forwarder, vendored from
[M66B/NetGuard](https://github.com/M66B/NetGuard) (`app/src/main/jni/netguard/`).
**Copyright © 2015–2024 Marcel Bokhorst (M66B), licensed GPL-3.0-or-later.**

Changed from upstream: the JNI symbol prefix and the four Java class paths it looks up
(`Java_eu_faircode_netguard_ServiceSinkhole_*` → `Java_com_safeworld_app_vpn_NativeTunnel_*`,
`eu/faircode/netguard/*` → `com/safeworld/app/vpn/*`). The C is otherwise unmodified, so an
upstream fix can be re-applied by repeating that rename.

### Why this one

Full-tunnel mode routes `0.0.0.0/0` so a blocked app's packets can be dropped by owning UID.
Routes are per-tunnel and cannot be scoped to one app, so *every other app's* traffic arrives
too and has to be carried to its destination and back — which means terminating TCP in
userspace: handshake, sequence numbers, windows, retransmission, teardown.

NetGuard is the right vendor specifically because **it already filters per-UID.** That is the
feature, not a bolt-on. `hev-socks5-tunnel` (MIT, evaluated below) forwards to a SOCKS5 proxy
and has no concept of a UID, so choosing it would have meant writing the per-app half
ourselves — and a TCP implementation that is subtly wrong does not fail loudly. It makes some
connections hang some of the time, and the person debugging it is the user.

### What taking it costs, and what it does not

**It makes the Android app GPL-3.0.** `apps/android/LICENSE` says so. The obligation is
publishing this app's complete source, which was already public, and letting anyone fork and
redistribute it.

**It does not reach the blocklists.** They are data — not derived from NetGuard, not linked
against it, loaded at runtime by Kotlin from `:core`'s resources. GPL §5 calls that mere
aggregation and does not cover it. The rule that keeps this obviously true rather than merely
arguable: **never compile list data into this directory.** No generated headers, no domains in
C. They stay where they are.

**It stays inside `apps/android/`.** `packages/core`, `SafeWorldCore` (shared by iOS and macOS)
and the Windows app remain permissive. GPL in `SafeWorldCore` would reach iOS, where the App
Store terms and GPL-3.0 genuinely do conflict — that conflict is Apple's, not Google's, and
Play has no policy against GPL.

### Obligation

GPL-3.0 requires the licence and copyright to reach the user, not just the repository.
Settings ▸ Help carries both, along with a pointer to the upstream project.

## Evaluated and not used

Assessed 2026-07-31 by reading each `LICENSE`, not by trusting a badge.

| Component | Licence | Why not |
|---|---|---|
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | MIT | No per-UID filtering — the actual feature |
| [hev-task-system](https://github.com/heiher/hev-task-system) | MIT | Only needed by the above |
| [hev-socks5-core](https://github.com/heiher/hev-socks5-core) | MIT | Only needed by the above |
| [lwIP](https://github.com/heiher/lwip) | BSD-3-Clause | A TCP stack, but the UID glue would still be ours to write |

These stay recorded because they are the permissive route: if the forwarder ever has to be
shared with iOS or macOS, lwIP plus our own per-UID glue is how it would have to be built,
because GPL code cannot go there.

**GitHub reports lwIP as "NOASSERTION".** That is a detection artefact — the file is named
`LICENSE` with wording the classifier does not match. The text is the standard three-clause BSD
from the Swedish Institute of Computer Science. Check the file, not the badge.

## The rule this follows

Read the licence text before code lands here, and write down what accepting it costs. The
answer changed once already: NetGuard was turned down in an earlier pass on the grounds that
GPL blocks App Store distribution — true of Apple, not of Google, and the Android app does not
ship on Apple.
