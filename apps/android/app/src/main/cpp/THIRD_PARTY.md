# Third-party code for the packet relay

Verified 2026-07-31 by reading each `LICENSE`, not by trusting a badge.

| Component | Licence | Why it's here |
|---|---|---|
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | MIT | tun ↔ socket forwarding |
| [hev-task-system](https://github.com/heiher/hev-task-system) | MIT | its coroutine scheduler |
| [hev-socks5-core](https://github.com/heiher/hev-socks5-core) | MIT | its protocol core |
| [yaml](https://github.com/heiher/yaml) | MIT | its config parser |
| [lwIP](https://github.com/heiher/lwip) | **BSD-3-Clause** | the userspace TCP/IP stack |

All permissive. Nothing here is copyleft, so none of it forces Safe World's own source
open or blocks App Store distribution.

**GitHub reports lwIP as "NOASSERTION".** That is a detection artefact — its file is
named `LICENSE` with wording the classifier doesn't match. The text is the standard
three-clause BSD from the Swedish Institute of Computer Science. Check the file, not the
badge.

## The rule this follows

**NetGuard was rejected despite being the best-proven stack for this, because it is
GPL-3.0.** Linking it would relicense all of Safe World: full source disclosure, anyone
free to rebrand and ship it, and App Store distribution blocked by the terms conflict.

This repo already turns down hagezi's and oisd's blocklists on the same grounds — users
fetch those themselves precisely so we never redistribute them. Taking GPL code into the
app while refusing GPL data would be incoherent.

Any future addition here gets the same treatment: read the licence text before it lands.

## Obligation

All five require their copyright notice and licence text to be preserved in
distributions. When the relay ships, surface them in the app (Settings ▸ Help) — a
notice file inside the repo is not the same as one the user can reach.
