(async () => {
  "use strict";
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];
  const ORDER = ["PROFILE", "CONNECT", "VOICE"];
  const PLATFORM = {
    instagram: { icon: "instagram", cls: "pf-ig", blurb: "Learn from your captions · publish Reels (Business/Creator accounts)" },
    youtube: { icon: "youtube", cls: "pf-yt", blurb: "Learn from your titles & descriptions · publishing coming soon" },
    x: { icon: "twitter", cls: "pf-x", blurb: "Learn from your posts · publishing coming soon" },
  };

  let me = await GV.requireMe();
  const params = new URLSearchParams(location.search);
  $("#meChip").innerHTML = `${GV.avatar(me)}<span>${GV.esc(me.name || me.email)}</span>`;
  $("#firstName").textContent = (me.name || "creator").split(/[\s@]/)[0];

  if (params.get("connect")) {
    const label = { instagram: "Instagram", youtube: "YouTube", x: "X" }[params.get("connect")] || "Account";
    GV.toast(params.get("ok") ? `${label} connected — we're learning from your recent posts.` : params.get("error") || `${label} wasn't connected.`);
    history.replaceState(null, "", "/welcome?step=CONNECT");
  }

  // ── navigation ─────────────────────────────────────────────────────────
  function show(step) {
    $$(".step-panel").forEach((p) => (p.hidden = p.dataset.panel !== step));
    const reached = me.onboardingStep === "DONE" ? ORDER.length : Math.max(0, ORDER.indexOf(me.onboardingStep));
    $$("#steps li").forEach((li, i) => {
      li.classList.toggle("is-active", li.dataset.step === step);
      li.classList.toggle("is-done", i < ORDER.indexOf(step) || i < reached);
    });
    if (step === "CONNECT") renderConnections();
    if (step === "VOICE") $("#voiceStatus").textContent = me.voiceSamples
      ? `GetViral already has ${me.voiceSamples} of your posts (from your connected accounts or earlier). Add a few favourites to sharpen it.`
      : "Paste a few posts you're proud of — captions, tweets, video descriptions. They're embedded privately so every agent writes like you.";
    window.scrollTo({ top: 0, behavior: "smooth" });
  }
  async function advance(to) {
    me = await GV.api("/api/me/onboarding", { method: "POST", body: { to } });
    if (to === "DONE") { location.href = "/studio"; return; }
    show(to);
  }
  $$("[data-back]").forEach((b) => b.addEventListener("click", () => show(ORDER[Math.max(0, ORDER.indexOf(b.closest(".step-panel").dataset.panel) - 1)])));
  $$("#steps li").forEach((li) => li.addEventListener("click", () => {
    if (me.handle || li.dataset.step === "PROFILE") show(li.dataset.step);
  }));

  // ── 1 · profile ────────────────────────────────────────────────────────
  $("#pHandle").value = me.handle || (me.email || "").split("@")[0].replace(/[^A-Za-z0-9._]/g, "").slice(0, 30);
  $("#pRegion").value = me.region || "";
  $("#pNiche").value = me.niche || "";
  $("#pAudience").value = me.audience || "";
  let tone = me.tone || "warm and witty";
  const syncChips = () => {
    $$("#toneChips .chip").forEach((c) => c.classList.toggle("is-on", c.dataset.v === tone));
    $$("#nicheChips .chip").forEach((c) => c.classList.toggle("is-on", c.dataset.v === $("#pNiche").value.trim().toLowerCase()));
  };
  $$("#toneChips .chip").forEach((c) => c.addEventListener("click", () => { tone = c.dataset.v; syncChips(); }));
  $$("#nicheChips .chip").forEach((c) => c.addEventListener("click", () => { $("#pNiche").value = c.dataset.v; syncChips(); }));
  $("#pNiche").addEventListener("input", syncChips);
  syncChips();
  $("#profileForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    $("#profileError").textContent = "";
    try {
      me = await GV.api("/api/me/profile", { method: "PUT", body: {
        handle: $("#pHandle").value.trim(), niche: $("#pNiche").value.trim(), tone,
        audience: $("#pAudience").value.trim(), region: ($("#pRegion").value.trim() || "US").toUpperCase(),
      } });
      show("CONNECT");
    } catch (err) {
      $("#profileError").textContent = err.message;
    }
  });

  // ── 2 · connections ────────────────────────────────────────────────────
  function renderConnections() {
    $("#connectGrid").innerHTML = (me.connections || []).map((c) => {
      const p = PLATFORM[c.platform] || {};
      let action;
      if (c.connected) {
        action = `<div class="conn-who">${c.avatarUrl ? `<img src="${GV.esc(c.avatarUrl)}" alt="" referrerpolicy="no-referrer">` : ""}<span>@${GV.esc(c.username || "connected")}${c.expired ? " · expired" : ""}</span></div>
          <div class="conn-actions">${c.expired ? `<a class="btn small" href="/connect/${c.platform}?returnTo=/welcome">Reconnect</a>` : ""}<button class="btn ghost small" data-disconnect="${c.platform}">Disconnect</button></div>`;
      } else if (c.available) {
        action = `<a class="btn" href="/connect/${c.platform}?returnTo=/welcome">Connect ${GV.esc(c.label)}</a>`;
      } else {
        action = `<span class="conn-na">Not set up on this server yet</span>`;
      }
      return `<article class="conn-card ${c.connected ? "is-connected" : ""}">
        <span class="pf ${p.cls}">${GV.ic(p.icon)}</span><h3>${GV.esc(c.label)}</h3><p>${p.blurb || ""}</p>${action}</article>`;
    }).join("");
    $$("[data-disconnect]").forEach((b) => b.addEventListener("click", async () => {
      await GV.api(`/api/connections/${b.dataset.disconnect}`, { method: "DELETE" });
      me = await GV.api("/api/me");
      renderConnections();
      GV.toast("Disconnected.");
    }));
  }
  $("#connectNext").addEventListener("click", () => advance("VOICE"));

  // ── 3 · voice ──────────────────────────────────────────────────────────
  $("#voiceNext").addEventListener("click", async () => {
    const posts = $("#voicePosts").value.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean).slice(0, 20);
    if (posts.length) {
      const res = await GV.api("/api/me/voice", { method: "POST", body: { posts } });
      GV.toast(`Learned from ${res.added} post${res.added === 1 ? "" : "s"}.`);
      me = await GV.api("/api/me");
    }
    advance("DONE");
  });
  $("#voiceSkip").addEventListener("click", () => advance("DONE"));

  const requested = (params.get("step") || "").toUpperCase();
  show(ORDER.includes(requested) && (me.handle || requested === "PROFILE") ? requested
    : me.onboardingStep === "DONE" ? "PROFILE" : ORDER.includes(me.onboardingStep) ? me.onboardingStep : "PROFILE");
})();
