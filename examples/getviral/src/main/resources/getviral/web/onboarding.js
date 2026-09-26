(async () => {
  "use strict";
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];
  const ORDER = ["PROFILE", "CONNECT", "VOICE", "TOUR"];
  const PLATFORM = {
    instagram: { icon: "📸", cls: "pf-ig", blurb: "Learn from your captions · publish Reels (Business/Creator accounts)" },
    youtube: { icon: "▶", cls: "pf-yt", blurb: "Learn from your titles & descriptions · publishing coming soon" },
    x: { icon: "𝕏", cls: "pf-x", blurb: "Learn from your posts · publishing coming soon" },
  };

  let me = await GV.requireMe();
  const params = new URLSearchParams(location.search);
  $("#meChip").innerHTML = `${GV.avatar(me)}<span>${GV.esc(me.name || me.email)}</span>`;
  $("#firstName").textContent = (me.name || "creator").split(/[\s@]/)[0];

  if (params.get("connect")) {
    const label = { instagram: "Instagram", youtube: "YouTube", x: "X" }[params.get("connect")] || "Account";
    GV.toast(params.get("ok") ? `✓ ${label} connected — we're learning from your recent posts.` : params.get("error") || `${label} wasn't connected.`);
    history.replaceState(null, "", "/welcome?step=CONNECT");
  }

  // ── navigation ─────────────────────────────────────────────────────────
  function show(step) {
    $$(".step-panel").forEach((p) => (p.hidden = p.dataset.panel !== step));
    const reached = ORDER.indexOf(me.onboardingStep === "DONE" ? "TOUR" : me.onboardingStep);
    $$("#steps li").forEach((li, i) => {
      li.classList.toggle("is-active", li.dataset.step === step);
      li.classList.toggle("is-done", i < ORDER.indexOf(step) || i < reached);
    });
    if (step === "CONNECT") renderConnections();
    if (step === "VOICE") $("#voiceStatus").textContent = me.voiceSamples
      ? `GetViral already has ${me.voiceSamples} of your posts (from your connected accounts or earlier). Add a few favourites to sharpen it.`
      : "Paste a few posts you're proud of — captions, tweets, video descriptions. They're embedded privately so every agent writes like you.";
    if (step === "TOUR") tour(0);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }
  async function advance(to) {
    me = await GV.api("/api/me/onboarding", { method: "POST", body: { to } });
    show(to === "DONE" ? "TOUR" : to);
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
        <span class="pf ${p.cls}">${p.icon}</span><h3>${GV.esc(c.label)}</h3><p>${p.blurb || ""}</p>${action}</article>`;
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
      GV.toast(`🧠 Learned from ${res.added} post${res.added === 1 ? "" : "s"}.`);
      me = await GV.api("/api/me");
    }
    advance("TOUR");
  });
  $("#voiceSkip").addEventListener("click", () => advance("TOUR"));

  // ── 4 · the tour ───────────────────────────────────────────────────────
  const AGENTS = ["🎬 Showrunner", "🔬 Researcher", "📡 TrendScout", "🧭 Strategist", "𝕏 XWriter", "🎞️ ReelDirector", "▶ YouTubeProducer",
    "🔥 ViralityCritic", "🎨 ArtDirector", "📹 VideoEditor", "🚀 Publisher", "🛡️ SafetyCoach"];
  const SLIDES = [
    { kicker: "The promise", title: "One idea. <span class='grad-text'>Every feed.</span>",
      body: "You bring one idea. GetViral turns it into a complete pack — an X thread, an Instagram Reel (script, cover and a rendered MP4) and a YouTube video package — ready to post.",
      art: `<div class="tv-flow"><span class="tv-idea">💡 your idea</span><i></i><div class="tv-outs"><span class="pf pf-x">𝕏</span><span class="pf pf-ig">Reels</span><span class="pf pf-yt">YouTube</span></div></div>` },
    { kicker: "Meet the room", title: "A team, not a chatbot",
      body: "Twelve specialists work on every pack — a researcher, a trend scout, a strategist, three platform writers, an art director, a video editor, a critic, a publisher and a safety coach — orchestrated step by step.",
      art: `<div class="tv-agents">${AGENTS.map((a, i) => `<span style="animation-delay:${i * 60}ms">${a}</span>`).join("")}</div>` },
    { kicker: "Prompts written live", title: "Your brief, re-cast for every agent",
      body: "Nobody runs on a generic prompt. The Showrunner writes each specialist's brief for your idea, niche and voice — and rewrites the ones that fall short when the critic pushes back.",
      art: `<div class="tv-prompts"><div class="tv-card"><b>XWriter · v1</b><p>Write for @you, a fitness creator whose tone is bold…</p></div><div class="tv-card v2"><b>XWriter · v2</b><p>Critic said: “tweet 1 reads like a title.” Lead with a surprising claim in under 12 words…</p></div></div>` },
    { kicker: "Research first", title: "Researched, then written",
      body: "Before anyone writes, a Researcher searches the web, reads the best sources in full and hands the team a dossier where every fact carries its source. Trend signals time the post, and house rules forbid invented stats.",
      art: `<div class="tv-apis"><span>🌍 Web search</span><span>📖 Reads the sources</span><span>📰 News, last 30 days</span><span>📈 Wikipedia most-read</span><span>🗣️ Hacker News</span><span>#️⃣ Mastodon trends</span><span>🎵 Music charts</span><span>🔎 Fact check</span><span>📅 Cultural moments</span></div>` },
    { kicker: "You're the creative director", title: "Your call, every time it matters",
      body: "You pick the hook the whole pack is built on. And nothing is ever published without you approving that exact post — caption, media and account.",
      art: `<div class="tv-approve"><div class="tv-hook">“3 sunset workout mistakes I see every day”<span>your pick ✓</span></div><div class="tv-gate">✋ Approve &amp; publish?</div></div>` },
    { kicker: "It learns you", title: "Sounds more like you every time",
      body: "Your past posts teach it your voice, and every hook you pick and every 🔥 or 👎 is remembered. Your next pack is briefed with what worked last time.",
      art: `<div class="tv-memory"><span>🧠 “Loved the loopable Reel ending — do more like this.”</span><span>🧠 “Picked the contrarian hook for gym content.”</span><span>🧠 “Critic: open mid-motion, not with a title.”</span></div>` },
    { kicker: "Graded before you see it", title: "Quality you can see",
      body: "A critic sends weak work back for another round, and independent AI judges score the finished pack — hook strength, platform fit, honest packaging, groundedness and safety.",
      art: `<div class="tv-badges"><span><b>92</b>Scroll-stopping hook</span><span><b>88</b>Reel is platform-native</span><span><b>95</b>Grounded</span></div>` },
  ];
  let slide = 0;
  $("#tourDots").innerHTML = SLIDES.map((_, i) => `<i data-i="${i}"></i>`).join("");
  $$("#tourDots i").forEach((d) => d.addEventListener("click", () => tour(Number(d.dataset.i))));
  function tour(i) {
    slide = Math.max(0, Math.min(SLIDES.length - 1, i));
    const s = SLIDES[slide];
    $("#tourStage").innerHTML = `<div class="tour-slide"><div class="tour-copy"><p class="eyebrow">${slide + 1} / ${SLIDES.length} · ${s.kicker}</p><h1 class="display-sm">${s.title}</h1><p class="lede-sm">${s.body}</p></div><div class="tour-art">${s.art}</div></div>`;
    $$("#tourDots i").forEach((d, j) => d.classList.toggle("is-on", j === slide));
    $("#tourPrev").style.visibility = slide === 0 ? "hidden" : "visible";
    $("#tourNext span").textContent = slide === SLIDES.length - 1 ? "Create my first pack ✦" : "Next";
  }
  $("#tourPrev").addEventListener("click", () => tour(slide - 1));
  $("#tourNext").addEventListener("click", async () => {
    if (slide < SLIDES.length - 1) return tour(slide + 1);
    await GV.api("/api/me/onboarding", { method: "POST", body: { to: "DONE" } });
    location.href = "/studio?guided=1";
  });
  document.addEventListener("keydown", (e) => {
    if ($("[data-panel=TOUR]").hidden) return;
    if (e.key === "ArrowRight") tour(slide + 1);
    if (e.key === "ArrowLeft") tour(slide - 1);
  });

  const requested = (params.get("step") || "").toUpperCase();
  show(ORDER.includes(requested) && (me.handle || requested === "PROFILE") ? requested
    : me.onboardingStep === "DONE" ? "TOUR" : me.onboardingStep);
})();
