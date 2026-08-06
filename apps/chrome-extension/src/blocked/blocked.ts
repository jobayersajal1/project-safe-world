import { CATEGORIES } from "@safe-world/core";

const params = new URLSearchParams(location.search);
const categoryParam = params.get("category") ?? "";

/**
 * Two pages in one, and the difference matters more than the layout.
 *
 * A listed site is a fact: it is on a blocklist, and "allow always" is the
 * dangerous button. An advisory is a guess made from the hostname alone by a
 * model that is right most of the time and not all of it, so continuing is the
 * ordinary answer rather than the reckless one. Dressing the second up as the
 * first is how a guess spends trust it has not earned — and once a user has
 * been wrongly stopped on a site they know, every later block reads as noise.
 */
const advisoryParam = params.get("advisory") ?? "";
const isAdvisory = advisoryParam === "1";
/**
 * A model block, not a listed one. Framed like an ordinary block because that is
 * what happened, but it still says the site is on no list — a user who is told
 * why can make sense of a wrong block instead of losing faith in all of them.
 */
const isStrictAdvisory = advisoryParam === "strict";

const categoryEl = document.getElementById("category")!;

const labels: Record<string, string> = { custom: "Your block list" };
for (const c of CATEGORIES) labels[c.id] = c.label;
categoryEl.textContent = labels[categoryParam] ?? "Blocked";

if (isStrictAdvisory) {
  document.getElementById("advisoryNote")!.hidden = false;
  document.getElementById("advisoryNote")!.textContent =
    "This site isn’t on any block list. Safe World blocked it because the address " +
    "looks almost certainly like one — if that’s wrong, allow it below.";
  categoryEl.textContent = `Looks like: ${labels[categoryParam] ?? "blocked"}`;
}

if (isAdvisory) {
  document.getElementById("shield")!.textContent = "🤔";
  document.getElementById("title")!.textContent = "This might be a site you block";
  document.getElementById("lede")!.innerHTML =
    'Safe World isn’t sure about <span class="host" id="host">this site</span>.';
  document.getElementById("advisoryNote")!.hidden = false;
  categoryEl.textContent = `Looks like: ${labels[categoryParam] ?? "blocked"}`;

  // "Go back" stops being the safe default when we are guessing, so the
  // emphasis moves to continuing.
  document.getElementById("back")!.classList.remove("primary");
  document.getElementById("allowOnce")!.classList.add("primary");
  document.getElementById("allowOnce")!.textContent = "Continue anyway";
  document.getElementById("allowAlways")!.classList.remove("danger");
  document.getElementById("allowAlways")!.textContent = "Continue, and don’t ask again";
}

interface BlockedInfo {
  host: string;
  url: string;
  blockedToday: number;
}

let info: BlockedInfo | undefined;
chrome.runtime.sendMessage({ type: "getBlockedInfo" }, (res: BlockedInfo) => {
  info = res;
  // Re-read the element: the advisory branch above replaces the paragraph, so
  // the node captured at load time is no longer in the document.
  const target = document.getElementById("host");
  if (res?.host && target) target.textContent = res.host;
});

document.getElementById("back")!.addEventListener("click", () => {
  if (history.length > 1) history.back();
  else location.href = "about:blank";
});

function reopen(): void {
  if (info?.url) location.href = info.url;
  else history.back();
}

document.getElementById("allowOnce")!.addEventListener("click", () => {
  chrome.runtime.sendMessage({ type: "allowOnce", host: info?.host ?? "" }, () => reopen());
});

document.getElementById("allowAlways")!.addEventListener("click", () => {
  chrome.runtime.sendMessage({ type: "allowAlways", host: info?.host ?? "" }, () => {
    // Give the background a moment to apply the dynamic allow rule before reloading.
    setTimeout(reopen, 150);
  });
});
