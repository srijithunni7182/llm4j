/* Shared by every GetViral page: API calls with the CSRF header, the signed-in creator, small helpers. */
(() => {
  "use strict";

  const cookie = (name) => document.cookie.split("; ").find((c) => c.startsWith(name + "="))?.split("=")[1];

  async function api(path, { method = "GET", body } = {}) {
    const headers = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET") {
      if (!cookie("XSRF-TOKEN")) await fetch("/api/public/info", { credentials: "same-origin" });
      headers["X-XSRF-TOKEN"] = decodeURIComponent(cookie("XSRF-TOKEN") || "");
    }
    const res = await fetch(path, { method, headers, credentials: "same-origin", body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = { error: text }; }
    if (!res.ok) {
      const err = new Error((data && data.error) || `Request failed (${res.status})`);
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  }

  /** The signed-in creator, or a redirect to the landing page. */
  async function requireMe() {
    try {
      return await api("/api/me");
    } catch (e) {
      if (e.status === 401) location.replace("/");
      throw e;
    }
  }

  const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  function toast(msg) {
    let t = document.getElementById("toast");
    if (!t) { t = document.createElement("div"); t.id = "toast"; t.className = "toast"; t.setAttribute("role", "status"); document.body.appendChild(t); }
    t.textContent = msg;
    t.classList.add("show");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => t.classList.remove("show"), 3200);
  }

  function avatar(me) {
    const initial = (me.name || me.email || "?").trim()[0].toUpperCase();
    return me.avatarUrl
      ? `<img class="avatar" src="${esc(me.avatarUrl)}" alt="" referrerpolicy="no-referrer">`
      : `<span class="avatar avatar-letter">${esc(initial)}</span>`;
  }

  async function signOut() {
    await api("/auth/logout", { method: "POST" }).catch(() => {});
    location.replace("/");
  }

  // ── Icons: Lucide line icons rendered as currentColor masks; emoji in any text are swapped for them. ──
  const LUCIDE = "https://unpkg.com/lucide-static@0.460.0/icons/";
  const ic = (name, cls = "") => `<i class="ic ${cls}" data-ic="${name}" style="--u:url(${LUCIDE}${name}.svg)" aria-hidden="true"></i>`;
  const EMOJI = {
    "🎬": "clapperboard", "📡": "radar", "🔬": "search", "🔎": "search", "🧭": "compass", "𝕏": "twitter", "🎞️": "film", "🎞": "film",
    "▶": "youtube", "🔥": "flame", "🎨": "palette", "📹": "video", "🎥": "video", "🚀": "send", "✦": "sparkles", "✎": "pen-line", "✍️": "pen-line", "✍": "pen-line",
    "💭": "message-circle", "🌐": "globe", "🌍": "globe", "🏁": "flag", "🎲": "shuffle", "🧬": "fingerprint", "📚": "book-open", "📖": "book-open",
    "🧠": "brain", "⚠️": "triangle-alert", "⚠": "triangle-alert", "👆": "hand", "✅": "circle-check", "✋": "hand", "🛡️": "shield-check", "🛡": "shield-check",
    "🎯": "target", "🔧": "wrench", "👎": "thumbs-down", "📈": "trending-up", "🗣️": "message-square", "🗣": "message-square", "#️⃣": "hash", "📅": "calendar",
    "♫": "music", "♥": "heart", "💬": "message-circle", "↗": "arrow-up-right", "✗": "x", "🎵": "music", "🔤": "type", "🖼️": "image", "🖼": "image", "📊": "chart-bar", "📤": "send", "🛠️": "wrench", "🛠": "wrench", "🌿": "leaf", "😄": "smile", "⚡": "zap", "🤓": "glasses",
  };
  const EMOJI_RE = new RegExp(Object.keys(EMOJI).sort((a, b) => b.length - a.length).map((k) => k.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|") + "|\\p{Extended_Pictographic}\\uFE0F?", "gu");
  function swapEmoji(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => (/^(SCRIPT|STYLE|TEXTAREA|INPUT|OPTION|TITLE)$/.test(n.parentNode.nodeName) ? NodeFilter.FILTER_REJECT : EMOJI_RE.test(n.nodeValue) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP),
    });
    const nodes = []; EMOJI_RE.lastIndex = 0;
    while (walker.nextNode()) { nodes.push(walker.currentNode); EMOJI_RE.lastIndex = 0; }
    for (const n of nodes) {
      const frag = document.createDocumentFragment(); let last = 0; const t = n.nodeValue; EMOJI_RE.lastIndex = 0;
      for (const m of t.matchAll(EMOJI_RE)) {
        frag.append(t.slice(last, m.index));
        const name = EMOJI[m[0]] || EMOJI[m[0].replace(/\uFE0F$/, "")];
        if (name) { const w = document.createElement("span"); w.innerHTML = ic(name); frag.append(w.firstChild); }
        last = m.index + m[0].length;
      }
      frag.append(t.slice(last));
      n.replaceWith(frag);
    }
  }
  function hydrateIcons(root) {
    root.querySelectorAll?.("i.ic[data-ic]:not([style])").forEach((el) => el.style.setProperty("--u", `url(${LUCIDE}${el.dataset.ic}.svg)`));
  }
  function iconPass(root = document.body) { hydrateIcons(root); swapEmoji(root); }
  let pending = false;
  const queue = () => { if (pending) return; pending = true; requestAnimationFrame(() => { pending = false; iconPass(); }); };
  document.addEventListener("DOMContentLoaded", () => {
    iconPass();
    new MutationObserver(queue).observe(document.body, { childList: true, subtree: true, characterData: true });
  });

  window.GV = { api, requireMe, esc, toast, avatar, signOut, ic };
})();
