import { CATEGORIES } from "@safe-world/core";

const params = new URLSearchParams(location.search);
const categoryParam = params.get("category") ?? "";

const categoryEl = document.getElementById("category")!;
const hostEl = document.getElementById("host")!;

const labels: Record<string, string> = { custom: "Your block list" };
for (const c of CATEGORIES) labels[c.id] = c.label;
categoryEl.textContent = labels[categoryParam] ?? "Blocked";

interface BlockedInfo {
  host: string;
  url: string;
  blockedToday: number;
}

let info: BlockedInfo | undefined;
chrome.runtime.sendMessage({ type: "getBlockedInfo" }, (res: BlockedInfo) => {
  info = res;
  if (res?.host) hostEl.textContent = res.host;
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
