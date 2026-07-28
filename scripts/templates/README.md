# Publishing the remote blocklist

The apps fetch additive blocklist updates from
[`safe-world-block-list-update`](https://github.com/jobayersajal1/safe-world-block-list-update), served over GitHub Pages.

## Only deltas are published, never the full list

The complete list lives in this repo and ships **inside the app**. The public repo carries only the
domains added *since* the snapshot a given release was built with. Right after a release that delta
is empty; it grows slowly as upstream feeds add entries. So what's publicly exposed is a small,
recent slice rather than the whole corpus.

This works because remote updates are additive — bundled lists are the offline baseline and remote
never replaces them, so `bundled ∪ delta` is the complete list on device.

Cutting a release:

```bash
npm run fetch:lists          # refresh from upstream
npm run baseline:snapshot    # record what this build will ship with -> prints a baseline id
# paste that id into RemoteConfig.kt (LIST_BASELINE) and RemoteConfig.swift (listBaseline)
npm run build:lists && npm run build:android && npm run build:ios
# ...then build and ship the apps
```

Between releases, `npm run build:remote` publishes the delta against that baseline. **Never run
`baseline:snapshot` on the update schedule** — it would move the baseline to the current lists,
making the delta empty and silently cutting off every already-installed app.

## Two files per baseline, because the platforms differ

| File | Fetched by | Format | Why |
|---|---|---|---|
| `delta-<baseline>-android.json` | Android | salted SHA-256 digests | Android matches in Kotlin, so it never needs the plaintext back — a **one-way** digest works. |
| `delta-<baseline>.json` | iOS, Chrome | XOR-scrambled, base64 | Both are declarative: the OS/browser matches from a rule file of literal domains, so the app must recover the real names at runtime. That needs something **reversible**. |

The baseline id is in the file name so an app only ever fetches a delta computed against the list
it actually shipped with. An app whose baseline has no published delta gets a 404, which degrades
to "no new domains" rather than to wrong ones.

Neither file is readable at a glance, and the category keys are opaque (`list1`/`list2`/`list3`)
so the payload doesn't announce what it blocks either.

**Old app versions drift.** Only the current baseline's delta is published, so a user on an older
build stops receiving additions until they update. They keep everything they shipped with — it
degrades to a stale list, not a broken one. Publishing deltas for several baselines at once would
fix that, at the cost of exposing more.

**Be clear about what each is worth.** The digests are genuinely one-way, though someone with a
large corpus of domains can hash them all and find which are on the list. The scrambled file is
*obfuscation*: the key ships in every app, so anyone who unpacks one can reverse it, and the
unscrambled domains land in the generated rule files on the device anyway. Both stop casual reading
of a public URL. Neither is secrecy — and none of it can be, because the app has to read the list,
so whoever controls a device with the app can too.

## Why a separate repo

The apps fetch with **no credentials**, so the endpoint has to be publicly readable. Don't point an
app at a private repo with an embedded token — anyone can pull a token back out of an APK or app
bundle, and it would then be a live credential for your private repo rather than a static file.

Keep authoring the lists wherever you like (private is fine); publish only the built files.

## One-time setup

Already done for `safe-world-block-list-update`. Kept here for reference, or for a second lists repo.

1. In the lists repo, copy [`lists-repo-pages.yml`](lists-repo-pages.yml) to
   `.github/workflows/deploy.yml`.
2. **Enable Pages before the first push:** Settings ▸ Pages ▸ Source ▸ **GitHub Actions**.
   Order matters — the workflow runs on push, and `actions/configure-pages` fails with
   `Get Pages site failed … Not Found` if Pages isn't enabled yet. If you hit that, nothing is
   broken: enable Pages, then re-run the failed job (`gh run rerun <id>`).
3. Push both JSON files to the repo root.

Served at:

```
https://jobayersajal1.github.io/safe-world-block-list-update/delta-<baseline>.json
https://jobayersajal1.github.io/safe-world-block-list-update/delta-<baseline>-android.json
```

## Each time the lists change

Regenerate the encoded files, copy them into the lists repo, and push. The push is what
republishes — Pages redeploys automatically, usually within about 30 seconds.

```bash
cd "/Users/jobayershajal/Git/projects/project safe world"
npm run build:remote

git clone https://github.com/jobayersajal1/safe-world-block-list-update   # once, keep it
cd safe-world-block-list-update
cp "/Users/jobayershajal/Git/projects/project safe world/dist/remote/"delta-*.json .
git add -A && git commit -m "Update lists" && git push
```

Verify it went live:

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  "https://jobayersajal1.github.io/safe-world-block-list-update/delta-$(jq -r .id baseline/baseline.json).json"
```

Both apps already point at these URLs (`RemoteConfig.kt`, `RemoteConfig.swift`), but only builds
made since those constants were set will pick the lists up.

## Automatic updates

`.github/workflows/update-blocklists.yml` in the main repo refreshes the lists from upstream feeds
every Monday, regenerates every platform's files, and pushes the deltas here — so this repo
normally updates itself and the manual steps above are only for one-off edits.

**It needs one secret to publish.** The workflow's default token can't push to another repository:

1. Create a fine-grained PAT: **Settings ▸ Developer settings ▸ Personal access tokens ▸
   Fine-grained tokens**, scoped to `jobayersajal1/safe-world-block-list-update` only, with
   **Repository permissions ▸ Contents: Read and write**.
2. Add it to the main repo as **Settings ▸ Secrets and variables ▸ Actions ▸ New repository
   secret**, named `LISTS_REPO_TOKEN`.

Without the secret the workflow still refreshes the lists in the main repo and just skips
publishing, rather than failing.

Run it by hand from the Actions tab ("Update blocklists" ▸ Run workflow), where you can also
override how many domains to keep per category.

## Payload shape

```json
{ "version": 1, "baseline": "aeda9c56a4d2", "format": "sha256-128-hex",
  "domains": { "list1": ["..."], "list2": ["..."], "list3": ["..."] } }
```

Unknown category keys are ignored, so adding a category before the apps know about it is safe.
Updates are **additive**: the bundled lists in `packages/core` stay the offline baseline and are
never replaced, so a failed fetch degrades to "no new domains", not "no blocking".

Changing the salt or the scramble key invalidates every published entry — bump the `format` string
so clients reject what they can't read instead of silently matching nothing.
