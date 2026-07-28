# Safe World website

A single self-contained `index.html` (no build step, no external requests — inline CSS/JS, system
fonts only) for the project's public landing page. It states the privacy positioning from the
architecture docs in `CLAUDE.md`: on-device filtering (Chrome `declarativeNetRequest`, Android
`VpnService` DNS sinkhole, iOS Safari Content Blocker) vs. cloud DNS filters like NextDNS.

## Preview locally

```bash
cd website
python3 -m http.server 8000
# open http://localhost:8000
```

(Opening `index.html` directly via `file://` also works — there's no fetch/XHR, everything is
inlined — but a local server matches how it'll actually be served.)

## Translations

UI strings live in the `translations` object in the inline `<script>` in `index.html`, keyed by
language code (`en`, `bn`, `es`, `ar`). To add a language: copy the `en` block, translate every
value, add it to `translations`, add an `<option>` to the `#lang` `<select>` in the nav, and — if
the script is RTL (like Arabic) — add the language code to the `rtlLangs` array.

## Deploying

`.github/workflows/deploy-website.yml` publishes this folder to GitHub Pages on every push to
`main` that touches `website/**`. One-time setup: repo **Settings → Pages → Source: GitHub
Actions**. After that, pushes deploy automatically — no separate build step needed since the page
is already static.
