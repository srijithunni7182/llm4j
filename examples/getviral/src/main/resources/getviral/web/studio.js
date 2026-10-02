/* GetViral Studio — streams a Loom workflow run over SSE and turns it into a live show. */
(() => {
  "use strict";

  const AGENTS = [
    { id: "Showrunner", icon: "🎬", role: "Casts the team, deals your angle" },
    { id: "SafetyCoach", icon: "🛡️", role: "Keeps personal data out", onCall: true },
    { id: "TrendScout", icon: "📡", role: "Reads what's rising today" },
    { id: "Researcher", icon: "🔬", role: "Reads the web, keeps sources" },
    { id: "Strategist", icon: "🧭", role: "Finds the angle, writes hooks" },
    { id: "XWriter", icon: "𝕏", role: "Threads that get quoted" },
    { id: "ReelDirector", icon: "🎞️", role: "Beat-by-beat Reels" },
    { id: "YouTubeProducer", icon: "▶", role: "Titles, thumbnail, chapters" },
    { id: "ArtDirector", icon: "🎨", role: "Paints every visual" },
    { id: "VideoEditor", icon: "📹", role: "Cuts the Reel" },
    { id: "ViralityCritic", icon: "🔥", role: "Sends weak work back" },
    { id: "Publisher", icon: "🚀", role: "Posts only when you approve", onCall: true },
  ];
  const STAGE_OF = { Showrunner: "cast", TrendScout: "scout", Researcher: "scout", Strategist: "strategy", XWriter: "create", ReelDirector: "create", YouTubeProducer: "create", ViralityCritic: "critique", ArtDirector: "visuals", VideoEditor: "visuals", Publisher: "ship" };
  const STAGES = ["cast", "scout", "strategy", "hook", "create", "critique", "visuals", "verify", "ship"];
  const MEDIA_ORDER = ["reel", "youtube_thumbnail", "reel_cover", "x_card", "broll_1", "broll_2", "ai_clip"];
  const IDEAS = [
    "a 2-minute morning routine for busy students",
    "why walking meetings beat Zoom calls",
    "budget travel hacks for a weekend in Lisbon",
    "the one pasta trick restaurants don't tell you",
    "using AI tools to run a small bakery",
    "keeping houseplants alive in a dark apartment",
  ];

  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];
  const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  // The Reel ships as an H.264 MP4 (the master: Instagram/YouTube-ready) plus a WebM copy for browsers
  // without H.264. Every <video> lists both; the browser plays the first it can decode.
  const webmOf = (m, all) => (all || []).find((x) => x.purpose === "reel_webm" && x.url === String(m.url).replace(/\.mp4$/, "-preview.webm"));
  const videoTag = (m, all, attrs) => {
    const w = webmOf(m, all);
    return `<video ${attrs}><source src="${esc(m.url)}" type="video/mp4">${w ? `<source src="${esc(w.url)}" type="video/webm">` : ""}</video>`;
  };
  /** A download link that saves the file (not opens it) under a readable name. */
  const downloadUrl = (m, name) => `${m.url}?download=${encodeURIComponent(name || "getviral-" + String(m.purpose).replace(/_/g, "-") + (String(m.url).match(/\.[a-z0-9]+$/i) || [""])[0])}`;
  const MEDIA_ERRORS = { 1: "loading was aborted", 2: "a network error", 3: "the video couldn't be decoded", 4: "the format isn't supported" };
  /** Calls {@code onFail} once the browser has given up on every source of a video (see the handler below). */
  const whenUnplayable = (video, onFail) => video.addEventListener("gv-unplayable", onFail, { once: true });
  // Every <video> lists the MP4 and then the WebM. Browsers only move on to the next <source> when a file
  // can't be *selected*; if the MP4 is picked but then fails to decode, they stop. So on any failure we
  // switch to the next untried file ourselves, and when none is left we say why and offer the download.
  document.addEventListener("error", (e) => {
    const el = e.target;
    const video = el && el.tagName === "VIDEO" ? el : el && el.tagName === "SOURCE" ? el.parentElement : null;
    if (!video || video.tagName !== "VIDEO" || video.dataset.gaveUp) return;
    const urls = [...video.querySelectorAll("source")].map((x) => x.src);
    if (!urls.length) return;
    if (el.tagName === "SOURCE" && el.src !== urls[urls.length - 1]) return; // the browser tries the next one itself
    const failed = new Set((video.dataset.failed || "").split(" ").filter(Boolean));
    failed.add(el.tagName === "SOURCE" ? el.src : video.currentSrc);
    video.dataset.failed = [...failed].join(" ");
    const next = urls.find((u) => !failed.has(u));
    if (next && el.tagName === "VIDEO") {
      video.src = next;
      video.load();
      if (video.autoplay) video.play().catch(() => {});
      return;
    }
    video.dataset.gaveUp = "1";
    const why = MEDIA_ERRORS[video.error && video.error.code] || "none of its formats are supported here";
    const mp4 = urls.find((u) => /\.mp4(\?|$)/.test(u)) || urls[0];
    if (!video.parentElement.querySelector(".video-note")) {
      video.insertAdjacentHTML("afterend", `<p class="video-note">This browser couldn't play the video (${esc(why)}). The file itself is fine: <a href="${esc(mp4)}?download=getviral-reel.mp4">download the MP4</a> and it plays in any video player and uploads to Instagram and YouTube as it is.</p>`);
    }
    video.dispatchEvent(new CustomEvent("gv-unplayable", { detail: { why } }));
  }, true);
  const store = {
    get(k) { try { return localStorage.getItem("gv." + k) || ""; } catch { return ""; } },
    set(k, v) { try { localStorage.setItem("gv." + k, v); } catch { /* private mode */ } },
  };

  const state = {
    runId: null, es: null, brief: null, started: 0, timer: null,
    values: {}, prompts: {}, stats: { llm: 0, api: 0, live: 0 }, events: 0, media: [],
    pendingApproval: null, revealed: false, stage: null, reelTimer: null, info: {},
    memory: { recalled: 0, learned: 0 }, sentBack: 0,
    art: {}, gate: { round: 0, areas: {}, log: [] }, now: "",
  };

  // ── Boot ────────────────────────────────────────────────────────────────
  async function boot() {
    state.me = await GV.requireMe();
    renderAccount();
    bindComposer();
    $("#againBtn").addEventListener("click", () => { location.href = "/studio"; });
    $("#ownHook").addEventListener("submit", (e) => { e.preventDefault(); const v = $("#ownHookInput").value.trim(); if (v) answerHook(v); });
    $("#approveBtn").addEventListener("click", () => answerApproval("approve"));
    $("#rejectBtn").addEventListener("click", () => answerApproval("reject"));
    $("#publishForm").addEventListener("submit", publishSubmit);
    $("#skipPublish").addEventListener("click", () => answerPublish("skip"));
    document.addEventListener("keydown", hookKeys);
    $$(".rate").forEach(bindRating);
    $$("#resTabs button").forEach((b) => b.addEventListener("click", () => resultsTab(b.dataset.pf)));
    resultsTab("pfX");
    $("#postBtn").addEventListener("click", () => {
      if (state.published) return toast("Already posted to @" + (state.me.handle || "you"));
      if (!$("#publishPanel").hidden) $("#publishPanel").scrollIntoView({ behavior: "smooth" });
      else toast("Your Reel is still being finished — posting opens when it's ready.");
    });
    $$("[data-copy]").forEach((b) => b.addEventListener("click", () => copy($("#" + b.dataset.copy).innerText + "\n\n" + $("#reelTags").innerText)));
    $$(".tabs button").forEach((b) => b.addEventListener("click", () => libraryTab(b.dataset.tab)));
    window.addEventListener("popstate", () => location.reload());
    route();
  }

  function route() {
    const params = new URLSearchParams(location.search);
    const runId = params.get("run");
    $("#navStudio").classList.toggle("is-on", location.pathname !== "/library");
    $("#navLibrary").classList.toggle("is-on", location.pathname === "/library");
    if (location.pathname === "/library") return showLibrary();
    if (runId) return openRun(runId);
    if (state.me.activeRun) {
      GV.toast("Picking up your pack in progress…");
      return openRun(state.me.activeRun);
    }
    $("#composer").hidden = false;
    if (params.get("guided")) setTimeout(guideComposer, 700);
  }

  function renderAccount() {
    const me = state.me;
    $("#menuBtn").innerHTML = `${GV.avatar(me)}<span>${esc(me.handle ? "@" + me.handle : me.name || "")}</span>`;
    $("#menuEmail").textContent = me.email;
    $("#quotaPill").textContent = `${me.quota.used} of ${me.quota.limit} packs this month`;
    $("#menuBtn").addEventListener("click", (e) => {
      e.stopPropagation();
      const open = $("#menuList").hidden;
      $("#menuList").hidden = !open;
      $("#menuBtn").setAttribute("aria-expanded", String(open));
    });
    document.addEventListener("click", () => ($("#menuList").hidden = true));
    $("#signOutBtn").addEventListener("click", GV.signOut);
    $("#deleteBtn").addEventListener("click", async () => {
      if (!confirm("Delete your GetViral account? This permanently removes every pack, image, video, memory and connected account.")) return;
      await GV.api("/api/me", { method: "DELETE" });
      location.replace("/");
    });
  }

  function bindComposer() {
    const me = state.me;
    $("#handleLabel").textContent = me.handle || "set up your profile";
    $("#niche").value = me.niche || "";
    $("#region").value = me.region || "";
    if (me.tone) $$(".tone-row .chip").forEach((c) => c.classList.toggle("is-on", c.dataset.tone === me.tone));
    $$(".tone-row .chip").forEach((chip) => chip.addEventListener("click", () => {
      $$(".tone-row .chip").forEach((c) => c.classList.remove("is-on"));
      chip.classList.add("is-on");
    }));
    $("#setupBanner").hidden = me.onboardingStep === "DONE";
    let i = 0;
    setInterval(() => { if (!$("#idea").value) $("#idea").placeholder = IDEAS[++i % IDEAS.length]; }, 3200);
    peekMemory();
    $("#briefForm").addEventListener("submit", start);
    $("#idea").addEventListener("keydown", (e) => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) start(e); });
  }

  async function peekMemory() {
    try {
      const data = await GV.api("/api/memory");
      const active = (data.memories || []).filter((m) => !m.shadow);
      $("#memoryPeek").hidden = active.length === 0;
      $("#peekHandle").textContent = state.me.handle ? "@" + state.me.handle : "you";
      $("#peekList").innerHTML = active.slice(-4).reverse().map((m) => `<li>${esc(m.content)}</li>`).join("");
    } catch { /* ignore */ }
  }

  async function start(e) {
    e.preventDefault();
    if (!state.me.handle) { location.href = "/welcome?step=PROFILE"; return; }
    const idea = $("#idea").value.trim() || $("#idea").placeholder;
    const brief = {
      idea,
      niche: $("#niche").value.trim() || state.me.niche || "lifestyle",
      region: ($("#region").value.trim() || state.me.region || "US").toUpperCase(),
      tone: ($(".tone-row .chip.is-on") || {}).dataset?.tone || state.me.tone || "warm and witty",
    };
    const voice = $("#voice").value.split("\n").map((v) => v.trim()).filter(Boolean);
    $("#goBtn").disabled = true;
    $("#formError").textContent = "";
    clearCoach();
    try {
      if (voice.length) await GV.api("/api/me/voice", { method: "POST", body: { posts: voice } });
      const res = await GV.api("/api/runs", { method: "POST", body: brief });
      state.brief = { ...brief, handle: state.me.handle };
      history.pushState(null, "", `/studio?run=${res.id}`);
      openStudio(res.id);
    } catch (err) {
      $("#formError").textContent = err.status === 409 && state.me.activeRun ? err.message + " Opening it…" : err.message;
      if (err.status === 409) setTimeout(() => location.reload(), 1200);
      $("#goBtn").disabled = false;
    }
  }

  /** Opens an existing run: live if it's still going, an instant replay if it's finished. */
  async function openRun(id) {
    try {
      const run = await GV.api(`/api/runs/${id}`);
      state.brief = { ...run.brief, idea: run.idea };
      state.replay = ["DONE", "FAILED", "BLOCKED"].includes(run.status);
      openStudio(id);
    } catch (err) {
      GV.toast(err.status === 404 ? "That pack doesn't exist (or isn't yours)." : err.message);
      history.replaceState(null, "", "/studio");
      $("#composer").hidden = false;
    }
  }

  // ── Studio ──────────────────────────────────────────────────────────────
  function openStudio(id) {
    state.runId = id;
    $("#composer").hidden = true;
    $("#library").hidden = true;
    $("#studio").hidden = false;
    $("#studioIdea").textContent = state.brief.idea;
    $("#agents").innerHTML = AGENTS.map((a) => `
      <div class="agent ${a.onCall ? "on-call" : ""}" id="ag-${a.id}" data-agent="${a.id}">
        <div class="av">${a.icon}</div><h4>${a.id}</h4><p>${a.role}</p>
        <span class="state">${a.onCall ? "On call" : "Waiting"}</span><span class="pv" hidden></span>
      </div>`).join("");
    state.started = performance.now();
    
    window.scrollTo({ top: 0, behavior: "smooth" });
    loadMemory();
    if (new URLSearchParams(location.search).get("guided") || sessionStorageGet("gv.guided")) setTimeout(guideStudio, 1500);
    state.es = new EventSource(`/api/runs/${id}/events`);
    state.es.onmessage = (m) => handle(JSON.parse(m.data));
    state.es.onerror = () => { if (["DONE", "BLOCKED", "FAILED"].includes(state.final)) state.es.close(); };
  }

  // ── Library ─────────────────────────────────────────────────────────────
  async function showLibrary() {
    $("#composer").hidden = true;
    $("#studio").hidden = true;
    $("#results").hidden = true;
    $("#library").hidden = false;
    const runs = await GV.api("/api/runs?limit=100");
    const done = runs.filter((r) => r.status === "DONE").length;
    const month = new Date().getMonth();
    $("#libStats").innerHTML = [
      [runs.length, "packs made"], [done, "ready to post"], [runs.reduce((n, r) => n + (r.mediaCount || 0), 0), "media files"],
      [runs.filter((r) => new Date(r.createdAt).getMonth() === month).length, "made this month"],
    ].map(([n, l]) => `<div><b>${n}</b><span>${l}</span></div>`).join("");
    $("#libPacks").innerHTML = runs.length ? runs.map((r, i) => `
      <button type="button" class="pack-card" data-run="${esc(r.id)}" style="animation-delay:${Math.min(i, 12) * 40}ms">
        <div class="pack-cover">${r.cover ? `<img src="${esc(r.cover)}" alt="" loading="lazy">` : ""}
          <span class="ptag">${new Date(r.createdAt).toLocaleDateString(undefined, { month: "short", day: "numeric" })}</span>${r.hook ? `<span class="cap">${esc(trim(r.hook, 70))}</span>` : ""}
        </div>
        <div class="pack-body"><h3>${esc(r.idea)}</h3><span class="tag ${r.status === "DONE" ? "tag-accent" : "tag-neutral"}">${esc(statusLabel(r.status))}</span></div>
      </button>`).join("")
      : `<div class="empty"><h3>No packs yet</h3><p>Your packs — copy, images and Reels — will live here.</p><a class="btn" href="/studio"><span>Make your first pack</span><i class="ic" data-ic="arrow-right"></i></a></div>`;
    $$("#libPacks [data-run]").forEach((b) => b.addEventListener("click", () => { location.href = `/studio?run=${b.dataset.run}`; }));
  }

  async function libraryTab(tab) {
    $$(".tabs button").forEach((b) => b.classList.toggle("is-on", b.dataset.tab === tab));
    $("#libPacks").hidden = tab !== "packs";
    $("#libMedia").hidden = tab !== "media";
    if (tab !== "media" || $("#libMedia").dataset.loaded) return;
    const media = await GV.api("/api/library/media");
    $("#libMedia").dataset.loaded = "1";
    const shown = media.filter((m) => m.purpose !== "reel_webm");
    $("#libMedia").innerHTML = shown.length ? shown.map((m) => `
      <figure class="tile">${m.kind === "video"
        ? videoTag(m, media, `muted loop playsinline controls preload="metadata"`)
        : `<img src="${esc(m.url)}" alt="${esc(m.purpose)}" loading="lazy" width="${m.width}" height="${m.height}">`}
        <figcaption><span>${esc(String(m.purpose).replace(/_/g, " "))}</span><a href="${esc(downloadUrl(m))}" download title="Download">↓</a></figcaption>
      </figure>`).join("")
      : `<div class="empty"><h3>No media yet</h3><p>Thumbnails, covers, B-roll and rendered Reels from every pack collect here.</p></div>`;
  }

  function statusLabel(status) {
    return { DONE: "ready", FAILED: "failed", BLOCKED: "blocked", QUEUED: "queued", RUNNING: "in progress", WAITING_FOR_HUMAN: "needs you" }[status] || status;
  }

  // ── Guided first pack (coach marks) ─────────────────────────────────────
  function sessionStorageGet(k) { try { return sessionStorage.getItem(k); } catch { return null; } }
  function sessionStorageSet(k, v) { try { sessionStorage.setItem(k, v); } catch { /* ignore */ } }

  function coach(target, title, text, step, total, next) {
    clearCoach();
    const el = typeof target === "string" ? $(target) : target;
    if (!el || el.offsetParent === null) return;
    el.classList.add("coach-target");
    const r = el.getBoundingClientRect();
    const box = document.createElement("div");
    box.className = "coach";
    box.innerHTML = `<b>${esc(title)}</b><p>${esc(text)}</p><div class="coach-foot"><span>${step}/${total}</span><button type="button" class="btn small">${next ? "Next" : "Got it"}</button></div>`;
    document.body.appendChild(box);
    const left = Math.min(Math.max(12, r.left), innerWidth - box.offsetWidth - 12);
    box.style.left = left + "px";
    box.style.top = Math.min(r.bottom + 14, innerHeight - box.offsetHeight - 12) + "px";
    box.style.setProperty("--arrow", Math.max(16, Math.min(r.left + 30 - left, box.offsetWidth - 30)) + "px");
    $("button", box).addEventListener("click", () => { clearCoach(); if (next) next(); });
    state.coachEl = box;
  }
  function clearCoach() {
    state.coachEl?.remove();
    state.coachEl = null;
    $$(".coach-target").forEach((e) => e.classList.remove("coach-target"));
  }
  function guideComposer() {
    sessionStorageSet("gv.guided", "1");
    $("#idea").scrollIntoView({ behavior: "smooth", block: "center" });
    coach("#briefForm", "Start with one idea", "Anything you'd post about — rough is fine. Try one of the rotating suggestions, or your own.", 1, 3, () =>
      coach(".tone-row", "Set the vibe", "Your profile defaults are pre-filled. Change the tone or niche just for this pack if you like.", 2, 3, () =>
        coach("#goBtn", "Make it viral", "The team takes it from here. You'll pick the hook in about 20 seconds.", 3, 3)));
  }
  function guideStudio() {
    if (sessionStorageGet("gv.guided.studio")) return;
    sessionStorageSet("gv.guided.studio", "1");
    coach(".room", "This is the room", "Each card is an agent. Tap one to read the brief the Showrunner wrote for it — live.", 1, 2, () =>
      coach(".wire", "The live wire", "Every trend check, fact, tool call and memory, as it happens. In a moment it'll ask you to pick the hook.", 2, 2));
  }

  function handle(ev) {
    const d = ev.data || {};
    queueMicrotask(() => trackEvent(ev)); // after this handler has updated the shared state
    state.events++;
    $("#wireCount").textContent = state.events + " events";
    switch (ev.type) {
      case "run_started":
        $("#engineList").innerHTML = [
          ["Model", d.model], ["Embeddings", d.embeddings], ["Vectors", d.vectorStore],
          ["Public APIs", d.publicApis], ["Web search", d.webSearch], ["Images", d.images], ["Video", d.video],
          ["Instagram", String(d.instagram).startsWith("connected") ? d.instagram : "not connected — publishing is a dry run"],
        ].filter(([, v]) => v).map(([k, v]) => `<dt>${k}</dt><dd>${esc(v)}</dd>`).join("");
        state.info.instagram = d.instagram;
        feed("✦", "GetViral", "Brief received — assembling the team", "", "", ev.t);
        break;
      case "agent_start": {
        agentState(d.agent, "working");
        if (d.review) setStage("verify");
        else if (STAGE_OF[d.agent]) setStage(STAGE_OF[d.agent]);
        const task = String(d.task || "");
        const recast = task.startsWith("RECAST"), revise = task.startsWith("REVISE");
        const label = d.review ? `is checking every file · round ${d.review}` : recast ? "is rethinking the approach after feedback" : revise ? "is revising" : d.agent === "ViralityCritic" ? `is reviewing the pack` : "started";
        feed(icon(d.agent), d.agent, label, "", "", ev.t);
        nowBanner(`${d.agent} ${label === "started" ? "is working…" : label}`);
        if (d.agent === "ViralityCritic") $("#criticToast").hidden = true;
        break;
      }
      case "prompt_injected":
        
        break;
      case "prompt": {
        (state.prompts[d.agent] ||= []).push(d);
        const pv = $(`#ag-${d.agent} .pv`);
        if (pv) { pv.hidden = false; pv.textContent = "prompt v" + d.version; $(`#ag-${d.agent}`).classList.remove("flash"); void pv.offsetWidth; $(`#ag-${d.agent}`).classList.add("flash"); }
        $("#statPrompts").textContent = Object.values(state.prompts).reduce((n, v) => n + v.length, 0);
        
        break;
      }
      case "thought":
        if (d.text && !/^I have everything/.test(d.text)) 
        break;
      case "action":
        feed(toolIcon(d.tool), d.agent, toolVerb(d.tool), esc(trim(d.input, 110)), "", ev.t);
        break;
      case "api":
        state.stats.api++;
        if (d.live) state.stats.live++;
        $("#statApi").textContent = state.stats.api;
        $("#statLive").textContent = Math.round((state.stats.live / state.stats.api) * 100) + "%";
        break;
      case "build_review": {
        state.build = d;
        if (!d.complete) {
          state.sentBack += Object.values(d.areas || {}).filter((a) => !a.pass).length;
          $("#statFixes").textContent = state.sentBack;
        }
        const names = { x: "𝕏", reel: "Instagram", youtube: "YouTube", visuals: "images", video: "Reel video" };
        const areas = Object.entries(d.areas || {});
        const failing = areas.filter(([, a]) => !a.pass);
        feed("🏁", "Quality gate", d.complete ? `round ${d.round}: every artifact passes ✓`
            : `round ${d.round}: ${failing.map(([k]) => names[k] || k).join(", ")} sent back`,
          esc(areas.map(([k, a]) => `${a.pass ? "✓" : "✕"} ${names[k] || k}`).join("  ")
            + (failing.length ? " — " + trim(failing.map(([k, a]) => `${names[k] || k}: ${(a.problems || []).join("; ")}`).join(" · "), 220) : "")),
          d.complete ? "memory" : "err", ev.t);
        if (state.revealed) { renderBuild(); renderWhy(); }
        break;
      }
      case "creative_brief":
        state.originality = { past: d.pastCastings, checks: [] };
        feed("🎲", "Showrunner", d.pastCastings ? "dealt an angle you haven't used" : "dealt your first angle",
          "", "", ev.t);
        break;
      case "originality": {
        (state.originality ||= { past: 0, checks: [] }).checks.push(d);
        if (state.revealed) renderWhy();
        const what = d.stage === "youtube" ? "YouTube package" : "casting";
        const pct = Math.round((d.closest_similarity || 0) * 100);
        if (d.novelty === "REPEAT") {
          feed("🧬", "Originality", `${what} too close to earlier work${d.stage === "casting" && !d.retry ? " — re-casting" : ""}`, esc(trim((d.reasons || []).join(" · "), 220)), "err", ev.t);
        } else {
          feed("🧬", "Originality", `${what} is fresh`, d.compared_with ? `closest past pack ${pct}% similar${d.closest_idea ? " — “" + esc(trim(d.closest_idea, 50)) + "”" : ""}` : "first pack — this sets the baseline", "memory", ev.t);
        }
        break;
      }
      case "rag":
        feed("📚", "Memory", `looked up your ${esc(d.scope)}`, "", "", ev.t);
        break;
      case "llm":
        state.stats.llm++;
        $("#statLlm").textContent = state.stats.llm;
        break;
      case "memory_recall":
        if (d.recalled) { state.memory.recalled++; $("#statMemory").textContent = state.memory.recalled + state.memory.learned; }
        if (d.recalled) feed("🧠", "Memory", `reminded ${esc(d.agent)} what works for you`, "", "", ev.t);
        break;
      case "memory_store":
        if (d.learned > 0) { state.memory.learned += d.learned; $("#statMemory").textContent = state.memory.recalled + state.memory.learned; }
        if (d.learned > 0) { feed("🧠", "Memory", `learned ${d.learned} new thing${d.learned > 1 ? "s" : ""} about you`, esc(d.content || ""), "", ev.t); loadMemory(); }
        break;
      case "agent_done":
        agentState(d.agent, "done");
        state.values[d.variable] = d.value;
        if (state.revealed && (d.agent === "VideoEditor" || d.agent === "ArtDirector")) renderMedia();
        onAgentDone(d, ev.t);
        break;
      case "agent_error":
        feed("⚠️", d.agent, "hit a snag — trying again", "", "err", ev.t);
        break;
      case "human":
        if (d.kind === "hook") showHooks(d);
        else if (d.kind === "publish") showPublish(d);
        else if (d.kind === "approval") showApproval(d);
        break;
      case "human_answer":
        // Questions answered earlier (another tab, a replay, a timeout) must not stay open here.
        if (d.kind === "hook") { $("#hookModal").hidden = true; state.values.hookChoice = d.answer; }
        if (d.kind === "approval") $("#approvalModal").hidden = true;
        if (d.kind === "publish") { state.publishQuestion = null; $("#publishForm").style.display = "none"; }
        if (d.kind === "hook") feed("👆", d.by === "timeout" ? "Timeout" : "You", d.by === "timeout" ? "picked the first hook for you" : "picked the hook", esc(d.answer), "", ev.t);
        break;
      case "approval_request":
        state.pendingApproval = d;
        agentState("Publisher", "working");
        break;
      case "approval_answer":
        feed(d.approved ? "✅" : "✋", "You", d.approved ? "approved posting" : "held the post back", "", "", ev.t);
        plog(d.approved ? "ok" : "dry", d.approved ? "APPROVED" : "REJECTED", d.approved ? "You approved the post." : "Not posted.");
        break;
      case "publish":
        onPublish(d);
        break;
      case "media": {
        state.media.push(d);
        if (d.purpose === "reel_webm") break;
        $("#statMedia").textContent = state.media.length;
        const portrait = d.height > d.width;
        const label = d.kind === "video" ? "cut the Reel" : `painted the ${esc(String(d.purpose).replace(/_/g, " "))}`;
        const body = d.kind === "video"
          ? videoTag(d, state.media, `class="shot portrait" muted autoplay loop playsinline`)
          : `<img class="shot ${portrait ? "portrait" : ""}" src="${esc(d.url)}" alt="${esc(d.purpose)}">`;
        feed(d.kind === "video" ? "📹" : "🎨", d.kind === "video" ? "VideoEditor" : "ArtDirector", label, body, "media", ev.t);
        if (state.revealed) renderMedia();
        break;
      }
      case "quality":
        renderBadges(d.badges || []);
        break;
      case "pack":
        state.values.pack = d;
        if (!state.revealed) reveal();
        break;
      case "blocked":
        setStage("cast");
        feed("🛡️", "SafetyCoach", "found personal details — nothing was made", esc(d.notice), "err", ev.t);
        toast("Your idea has personal details in it. Remove them and try again.");
        setTimeout(() => { $("#studioIdea").insertAdjacentHTML("afterend", `<p class="muted" style="margin-top:10px">${esc(d.notice)} <a href="/" style="color:var(--color-accent)">Try again →</a></p>`); }, 50);
        break;
      case "error":
        feed("✗", "GetViral", "hit a problem", esc(d.message), "err", ev.t);
        toast("Something went wrong: " + d.message);
        break;
      case "status":
        state.final = d.status;
        if (["DONE", "BLOCKED", "FAILED"].includes(d.status)) {
          clearInterval(state.timer);
          state.es && state.es.close();
          if (state.revealed) renderMedia();
          GV.api("/api/me").then((me) => { state.me = me; $("#quotaPill").textContent = `${me.quota.used} of ${me.quota.limit} packs this month`; }).catch(() => {});
          if (d.status === "DONE") { setStage("ship", true); agentState("Publisher", "done"); if (state.build) renderBuild(); else $("#qualityHint").textContent = "graded"; }
          if (d.status === "FAILED" && state.build && state.revealed) renderBuild();
        }
        break;
    }
  }

  function onAgentDone(d, t) {
    const v = d.value;
    if (d.agent === "Showrunner" && v && typeof v === "object") {
      
      feed("🎬", "Showrunner", "cast the team", esc(trim(v.creative_direction, 200)), "", t);
      updatePack();
    } else if (d.agent === "Researcher" && v && typeof v === "object") {
      const n = (v.findings || []).length;
      feed("🔬", "Researcher", `brought back ${n} sourced finding${n === 1 ? "" : "s"}`, esc(trim(v.summary, 200)), "", t);
    } else if (d.agent === "ViralityCritic" && v && typeof v === "object") {
      criticMoment(v);
      $("#statRounds").textContent = v.round || 1;
      feed("🔥", "ViralityCritic", v.verdict === "SHIP" ? "approved the pack" : "sent weak work back to the team", esc(v.headline || ""), "critic", t);
      if (v.verdict === "SHIP" && !state.revealed) setTimeout(reveal, 1600);
    } else {
      feed("✓", d.agent, "finished", "", "", t);
    }
  }

  function agentState(agent, s) {
    const el = $("#ag-" + agent);
    if (!el) return;
    el.classList.toggle("is-working", s === "working");
    el.classList.toggle("is-done", s === "done");
    $(".state", el).textContent = s === "working" ? "Working" : s === "done" ? "Done" : el.classList.contains("on-call") ? "On call" : "Waiting";
    if (s === "working") state.working = agent;
  }

  const CHAPTER_OF = { cast: 0, scout: 0, strategy: 0, hook: 1, create: 2, visuals: 2, critique: 3, verify: 3, ship: 3 };
  const CHAPTERS = ["Research", "Your pick", "Make", "Review"];
  function setStage(stage, finished = false) {
    if (STAGES.indexOf(stage) < STAGES.indexOf(state.stage || "cast") && !finished) return;
    state.stage = stage;
    const idx = CHAPTER_OF[stage] ?? 0;
    $$("#rail li").forEach((li, i) => {
      li.classList.toggle("is-done", i < idx || finished);
      li.classList.toggle("is-active", i === idx && !finished);
    });
    $("#runTitle").textContent = finished ? "Your pack is ready" : "Pack in progress · " + CHAPTERS[idx];
  }

  function feed(ic, who, html, sub, cls, t) {
    const li = document.createElement("li");
    if (cls) li.className = cls;
    li.innerHTML = `<span class="ic">${ic}</span><div>${who ? `<span class="who">${esc(who)}</span> ` : ""}${html}${sub ? `<span class="sub">${sub}</span>` : ""}</div><span class="t">${((t || 0) / 1000).toFixed(1)}s</span>`;
    const list = $("#feed");
    const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 80;
    list.appendChild(li);
    if (list.children.length > 400) list.firstChild.remove();
    if (nearBottom) list.scrollTop = list.scrollHeight;
  }

  function criticMoment(v) {
    const score = Number(v.score) || 0;
    $("#criticToast").hidden = false;
    $("#dialScore").textContent = score.toFixed(1);
    $("#criticVerdict").textContent = v.verdict === "SHIP" ? `Critic · round ${v.round || 1} · ship it 🚀` : `Critic · round ${v.round || 1} · sending it back`;
    $("#criticHeadline").textContent = v.budget_note || v.headline || "";
    const fill = $(".dial-fill");
    fill.style.stroke = score >= 8 ? "var(--ok)" : "var(--amber)";
    requestAnimationFrame(() => (fill.style.strokeDashoffset = 327 - (327 * Math.min(score, 10)) / 10));
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(() => ($("#criticToast").hidden = true), 5200);
  }

  async function loadMemory() {
    const handle = state.brief?.handle || state.me?.handle || "you";
    try {
      const data = await GV.api("/api/memory");
      const list = (data.memories || []).slice(-8).reverse();
      $("#memoryList").innerHTML = list.length
        ? list.map((m) => `<li class="${m.shadow ? "shadow" : ""}" title="${esc(m.tier)} · importance ${m.importance}">${esc(m.content)}</li>`).join("")
        : `<li class="muted">First time with @${esc(handle)} — it will start remembering after this run.</li>`;
    } catch { /* ignore */ }
  }

  // ── Human in the loop ───────────────────────────────────────────────────
  function showHooks(d) {
    setStage("hook");
    state.hookQuestion = d.id;
    const hooks = d.options || [];
    $("#hookList").innerHTML = hooks.map((h, i) => `<li><button type="button" data-hook="${esc(h)}" style="animation-delay:${i * 70}ms"><i>${i + 1}</i><span>${esc(h)}<span class="formula">${h.split(/\s+/).length} words · ${h.length} chars</span></span></button></li>`).join("");
    $$("#hookList button").forEach((b) => b.addEventListener("click", () => answerHook(b.dataset.hook)));
    $("#hookModal").hidden = false;
    setTimeout(() => $("#hookList button")?.focus(), 60);
  }

  function hookKeys(e) {
    if ($("#hookModal").hidden || document.activeElement === $("#ownHookInput")) return;
    const n = Number(e.key);
    const btns = $$("#hookList button");
    if (n >= 1 && n <= btns.length) answerHook(btns[n - 1].dataset.hook);
  }

  async function answer(id, value) {
    try {
      await GV.api(`/api/runs/${state.runId}/answer`, { method: "POST", body: { id, answer: value } });
    } catch (err) {
      GV.toast(err.message);
    }
  }

  function answerHook(hook) {
    $("#hookModal").hidden = true;
    state.values.hookChoice = hook;
    answer(state.hookQuestion, hook);
  }

  function showPublish(d) {
    state.publishQuestion = d.id;
    if (!state.revealed) reveal();
    $("#publishPanel").hidden = false;
    $("#igMode").innerHTML = String(state.info.instagram || "").startsWith("connected")
      ? `Publishing to your ${esc(state.info.instagram.replace("connected as ", ""))} account.`
      : `Instagram isn't connected yet. <a href="/welcome?step=CONNECT" style="color:var(--color-accent-700)">Connect Instagram</a>`;
  }

  function publishSubmit(e) {
    e.preventDefault();
    const url = $("#videoUrl").value.trim();
    if (!/^https:\/\//.test(url)) { toast("Paste a public https:// video URL"); return; }
    answerPublish(url);
    $("#publishLog").innerHTML = "";
    plog("", "PUBLISHER", "Getting your Reel ready to post…");
  }

  function answerPublish(value) {
    if (!state.publishQuestion) return;
    answer(state.publishQuestion, value);
    state.publishQuestion = null;
    $("#publishForm").style.display = "none";
    if (value === "skip") plog("", "SKIPPED", "Copy the pack and post it yourself whenever you're ready.");
  }

  function showApproval(d) {
    state.approvalQuestion = d.id;
    const args = state.pendingApproval?.args || {};
    const rows = [["Account", state.me.handle ? "@" + state.me.handle : ""], ["Video", args.media_url], ["Caption", args.caption], ["Label", "Made with AI"]];
    $("#approvalArgs").innerHTML = rows.filter(([, v]) => v).map(([k, v]) => `<dt>${k}</dt><dd>${esc(v)}</dd>`).join("");
    $("#approvalModal").hidden = false;
  }

  function answerApproval(value) {
    $("#approvalModal").hidden = true;
    answer(state.approvalQuestion, value);
  }

  function onPublish(d) {
    if (d.status === "dry_run") {
      const steps = Object.entries(d.plan || {}).filter(([k]) => k.startsWith("step")).map(([, v]) => trim(v, 150));
      plog("dry", "NOT POSTED", "Instagram isn't connected, so nothing was posted. Connect it and approve again to go live.");
    } else if (d.status === "published") {
      state.published = true; toast("Posted to @" + (state.me.handle || "you"));
      const pb = $("#postBtn"); if (pb) pb.firstElementChild.textContent = "Posted to Instagram";
      plog("ok", "POSTED", `Posted! <a href="${esc(d.permalink)}" target="_blank" rel="noopener" style="color:var(--hot)">${esc(d.permalink || d.media_id)}</a>`, "", true);
      confetti();
    } else if (d.status === "failed") {
      plog("dry", "FAILED", "Instagram couldn't process the video. Nothing was posted.");
    } else if (d.status === "processing") {
      plog("", "PROCESSING", "Instagram is processing the video…");
    }
  }

  function plog(kind, pill, text, code, html) {
    const row = document.createElement("div");
    row.className = "plog";
    row.innerHTML = `<span class="pill ${kind}">${esc(pill)}</span><div>${html ? text : esc(text)}${code ? `<code>${esc(code)}</code>` : ""}</div>`;
    $("#publishLog").appendChild(row);
  }

  // ── Reveal ──────────────────────────────────────────────────────────────
  function reveal() {
    state.revealed = true;
    const v = state.values;
    $("#results").hidden = false;
    $("#studio").classList.add("is-shipped");
    const critic = v.criticReport || {};
    const score = Number(critic.score) || 0;
    $("#finalScore").textContent = score ? score.toFixed(1) : "–";
    requestAnimationFrame(() => ($(".ring-fill").style.strokeDashoffset = 327 - (327 * Math.min(score, 10)) / 10));
    $("#revealHook").textContent = "“" + (v.hookChoice || "") + "”";
    $("#revealEyebrow").textContent = state.build && state.build.complete ? "Your pack is ready"
      : "Almost there — the Showrunner is checking every artifact";
    $("#revealHeadline").textContent = critic.headline || "";
    const cast = v.castingSheet || {};
    const o = state.originality || { past: 0, checks: [] };
    const lastCast = [...o.checks].reverse().find((c) => c.stage === "casting");
    const recast = o.checks.some((c) => c.stage === "casting" && c.novelty === "REPEAT");
    $("#revealMeta").innerHTML = [
      cast.lens ? `<span>🎯 Lens: <b>${esc(cast.lens)}</b></span>` : "",
      cast.visual_style ? `<span>🎨 Style: <b>${esc(trim(cast.visual_style, 60))}</b></span>` : "",
      lastCast ? `<span>🧬 ${o.past ? (lastCast.novelty === "FRESH" ? `Original vs ${o.past === 1 ? "your last pack" : `your last ${o.past} packs`}` : "Close to earlier work") : "First pack — baseline set"}${recast ? " · re-cast once for originality" : ""}</span>` : "",
    ].filter(Boolean).join("");
    $("#exportBtn").href = `/api/runs/${state.runId}/export.md`;
    $("#exportBtn").setAttribute("download", "getviral-pack.md");
    if (!$("#badges").children.length) $("#badges").innerHTML = Array.from({ length: 5 }, () => `<div class="badge skel"></div>`).join("");
    renderResearch(v.researchDossier);
    renderTracker();
    renderBuild();
    renderWhy();
    renderX(v.xPack);
    renderReel(v.reelPack);
    renderYt(v.youtubePack);
    renderMedia();
    $$("#reelToggle button").forEach((b) => b.addEventListener("click", () => setReelMode(b.dataset.mode)));
    $("#studio").hidden = true;
    window.scrollTo({ top: 0, behavior: "smooth" });
    confetti();
  }

  function mediaFor(purpose) { return [...state.media].reverse().find((m) => m.purpose === purpose); }

  function renderMedia() {
    const media = state.media.filter((m) => m.purpose !== "reel_webm").sort((a, b) => MEDIA_ORDER.indexOf(a.purpose) - MEDIA_ORDER.indexOf(b.purpose));
    const expected = ["reel", "youtube_thumbnail", "reel_cover", "x_card", "broll_1", "broll_2"];
    const missing = expected.filter((p) => !media.some((m) => m.purpose === p));
    const done = ["DONE", "FAILED"].includes(state.final) || !!state.values.videoPack;
    $("#visualsHint").textContent = done ? `${media.length} files · ${media.filter((m) => m.ai).length} AI-generated` : "generating…";
    const tile = (m) => {
      const el = m.kind === "video"
        ? videoTag(m, state.media, `muted autoplay loop playsinline controls`)
        : `<img src="${esc(m.url)}" alt="${esc(m.purpose)}" loading="lazy" width="${m.width}" height="${m.height}">`;
      return `<figure class="tile ${m.purpose === "reel" ? "hero" : ""}">${el}<span class="prov ${m.ai ? "ai" : "local"}">${m.ai ? "AI · " : ""}${esc(m.provider)}</span>
        <figcaption><span>${esc(String(m.purpose).replace(/_/g, " "))}${m.seconds ? " · " + m.seconds + "s" : ""}</span><a href="${esc(downloadUrl(m))}" download title="Download">↓</a></figcaption></figure>`;
    };
    const skel = (p) => `<figure class="tile skel ${p === "reel" ? "hero" : ""}">${p === "reel" ? "rendering reel…" : esc(p.replace(/_/g, " "))}</figure>`;
    const videos = media.filter((m) => m.kind === "video");
    const stills = media.filter((m) => m.kind !== "video");
    $("#gallery").innerHTML =
      `<div class="gallery-reel">${videos.map(tile).join("") || (done ? "" : skel("reel"))}</div>` +
      `<div class="gallery-stills">${stills.map(tile).join("")}${done ? "" : missing.filter((p) => p !== "reel").map(skel).join("")}</div>`;

    const thumb = mediaFor("youtube_thumbnail");
    if (thumb) { $("#ytThumb").style.backgroundImage = `url("${thumb.url}")`; $("#ytThumbText").hidden = true; }
    const card = mediaFor("x_card");
    const firstTweet = $("#xThread .tweet p");
    if (card && firstTweet && !$("#xThread .media-card")) firstTweet.insertAdjacentHTML("afterend", `<div class="media-card"><img src="${esc(card.url)}" alt="X card"></div>`);
    const cover = mediaFor("reel_cover");
    if (cover && !$(".cover-thumb")) $("#reelCaption").insertAdjacentHTML("beforebegin", `<img class="cover-thumb" src="${esc(cover.url)}" alt="Reel cover">`);
    const reel = mediaFor("reel");
    const phone = $("#reelVideo");
    if (reel && phone.dataset.src !== reel.url) {
      phone.dataset.src = reel.url;
      const w = webmOf(reel, state.media);
      phone.innerHTML = `<source src="${esc(reel.url)}" type="video/mp4">${w ? `<source src="${esc(w.url)}" type="video/webm">` : ""}`;
      delete phone.dataset.gaveUp; delete phone.dataset.failed; phone.removeAttribute("src");
      whenUnplayable(phone, (e) => { setReelMode("story"); toast(`This browser couldn't play the Reel (${e.detail.why}) — showing the storyboard. Download the MP4 from Visuals.`); });
      phone.load();
      $("#reelToggle").hidden = false;
      setReelMode("video");
    }
    if (reel) {
      const player = $("#ytShortPlayer");
      if (player.dataset.src !== reel.url) {
        player.dataset.src = reel.url;
        player.innerHTML = videoTag(reel, state.media, `muted loop playsinline controls preload="metadata"`);
      }
      $("#ytShortMeta").textContent = `Vertical 9:16 · ${reel.seconds ? reel.seconds + "s · " : ""}${reel.width}×${reel.height} · silent`;
      const dl = $("#ytShortDl");
      dl.href = downloadUrl(reel, "getviral-youtube-short.mp4");
      dl.hidden = false;
    }
  }

  function setReelMode(mode) {
    $$("#reelToggle button").forEach((b) => b.classList.toggle("is-on", b.dataset.mode === mode));
    const video = $("#reelVideo");
    video.hidden = mode !== "video";
    if (mode === "video") video.play().catch(() => {}); else video.pause();
  }

  const safeUrl = (u) => /^https?:\/\//i.test(String(u || "")) ? String(u) : "";
  const hostOf = (u) => { try { return new URL(u).hostname.replace(/^www\./, ""); } catch { return ""; } };

  function renderBuild() {
    const b = state.build;
    if (!b) return;
    const finalState = ["DONE", "FAILED"].includes(state.final);
    $("#revealEyebrow").textContent = b.complete ? "Your pack is ready"
      : finalState ? "Needs attention — not every artifact passed" : "Almost there — the Showrunner is fixing what failed";
    const names = { x: "X thread", reel: "Instagram Reel", youtube: "YouTube package", visuals: "images", video: "Reel video" };
    const failing = Object.entries(b.areas || {}).filter(([, a]) => !a.pass);
    $("#qualityHint").innerHTML = b.complete
      ? `<span class="verdict v-pass">Build complete</span> signed off by the Showrunner after ${b.round} review round${b.round === 1 ? "" : "s"}`
      : `<span class="verdict v-fail">Needs attention</span> ${failing.map(([k]) => names[k] || k).join(", ")} still fail${failing.length === 1 ? "s" : ""} the gate (round ${b.round})`;
    $("#buildIssues").innerHTML = failing.map(([k, a]) => `<li><b>${esc(names[k] || k)}</b> ${esc((a.problems || []).join("; "))}</li>`).join("");
    // The file-by-file checks from the same review fill the Artifact check panel.
    const checks = b.checks || [];
    renderInspection({
      verdict: !b.complete ? "FAIL" : checks.some((c) => c.status === "WARN") ? "PASS_WITH_WARNINGS" : "PASS",
      summary: b.complete ? "Every file opens, plays and fits its platform — signed off by the Showrunner."
        : "Some artifacts still fail — the Showrunner is sending them back to their specialists.",
      checks, fixes: [],
    });
  }

  // ── Build tracker ─────────────────────────────────────────────────────────
  // Everything the creator gets, as cards that move queued → making → made → checked → passed (or sent
  // back → fixing), driven by the run's own events. Shown on the results page while the pack is finished.
  const ARTS = [
    { key: "x", label: "𝕏 thread", area: "x", agent: "XWriter", variable: "xPack" },
    { key: "reel", label: "Reel script", area: "reel", agent: "ReelDirector", variable: "reelPack" },
    { key: "youtube", label: "YouTube package", area: "youtube", agent: "YouTubeProducer", variable: "youtubePack" },
    { key: "youtube_thumbnail", label: "Thumbnail", area: "visuals", agent: "ArtDirector", media: /^youtube_thumbnail/ },
    { key: "reel_cover", label: "Reel cover", area: "visuals", agent: "ArtDirector", media: /^reel_cover/ },
    { key: "x_card", label: "X card", area: "visuals", agent: "ArtDirector", media: /^x_card/ },
    { key: "broll", label: "B-roll", area: "visuals", agent: "ArtDirector", media: /^broll/ },
    { key: "video", label: "Reel video", area: "video", agent: "VideoEditor", media: /^reel$/ },
    { key: "short", label: "YouTube Short", area: "video", agent: "VideoEditor", media: /^reel$/ },
  ];
  const ART_STATE = {
    queued: { w: 0, label: "queued" }, making: { w: 0.35, label: "making" }, made: { w: 0.7, label: "made" },
    checking: { w: 0.8, label: "checking" }, sent: { w: 0.5, label: "sent back" }, fixing: { w: 0.55, label: "fixing" },
    passed: { w: 1, label: "✓ passed" },
  };
  const AREA_NAMES = { x: "𝕏", reel: "Reel", youtube: "YouTube", visuals: "Images", video: "Video" };
  const art = (key) => (state.art[key] ||= { s: "queued" });
  const setArt = (key, s, extra = {}) => { const a = art(key); if (a.s !== s) a.changed = true; Object.assign(a, { s }, extra); };

  function trackEvent(ev) {
    const d = ev.data || {};
    switch (ev.type) {
      case "agent_start":
        if (d.review) {
          state.now = `The Showrunner is checking every file against the quality gate · round ${d.review}`;
          ARTS.forEach((a) => { if (["made", "fixing"].includes(art(a.key).s)) setArt(a.key, "checking"); });
        } else {
          ARTS.filter((a) => a.agent === d.agent).forEach((a) => {
            const s = art(a.key).s;
            if (s === "queued") setArt(a.key, "making");
            else if (s === "sent") setArt(a.key, "fixing");
          });
          const doing = { ArtDirector: "The ArtDirector is designing your images", VideoEditor: "The VideoEditor is cutting your Reel",
            XWriter: "XWriter is working on the thread", ReelDirector: "ReelDirector is working on the Reel script",
            YouTubeProducer: "YouTubeProducer is working on the YouTube package", ViralityCritic: "The critic is scoring the pack" }[d.agent];
          if (doing) state.now = String(d.task || "").startsWith("REVISE") || art(ARTS.find((a) => a.agent === d.agent)?.key || "").s === "fixing"
            ? doing.replace("is working on", "is fixing").replace("is designing", "is redoing").replace("is cutting", "is re-cutting") : doing;
        }
        break;
      case "agent_done":
        ARTS.filter((a) => a.variable && a.variable === d.variable).forEach((a) => setArt(a.key, "made", { preview: textPreview(a.key, d.value) }));
        break;
      case "media":
        ARTS.filter((a) => a.media && a.media.test(String(d.purpose))).forEach((a) => setArt(a.key, "made",
          { img: d.kind === "video" ? null : d.url, wide: d.width > d.height, video: d.kind === "video" ? d : null }));
        break;
      case "build_review": {
        state.gate.round = d.round;
        Object.entries(d.areas || {}).forEach(([area, v]) => {
          state.gate.areas[area] = v.pass;
          ARTS.filter((a) => a.area === area).forEach((a) => {
            if (v.pass) { if (art(a.key).s !== "queued") setArt(a.key, "passed", { why: "" }); }
            else setArt(a.key, "sent", { why: (v.problems || [])[0] || "needs another pass" });
          });
          if (!v.pass) state.gate.log.push(`Round ${d.round}: ${AREA_NAMES[area] || area} sent back — ${(v.problems || []).join("; ")}`);
        });
        if (d.complete) {
          state.gate.log.push(`Round ${d.round}: every artifact passed`);
          state.now = "Every file is built and checked";
        } else {
          state.now = "Fixes are on their way back to the specialists";
        }
        break;
      }
      case "status":
        if (d.status === "DONE" && state.build && state.build.complete) ARTS.forEach((a) => { if (art(a.key).s !== "queued") setArt(a.key, "passed"); });
        break;
      default:
        return;
    }
    if (state.revealed) scheduleTracker();
  }

  function textPreview(key, v) {
    if (!v || typeof v !== "object") return trim(String(v ?? ""), 90);
    if (key === "x") return trim((v.thread || [])[0] || "", 110);
    if (key === "reel") return `${(v.beats || []).length} beats · “${trim(v.cover_text || v.title || "", 50)}”`;
    if (key === "youtube") return trim((v.titles || [])[0] || "", 90);
    return "";
  }

  let trackerFrame = 0;
  function scheduleTracker() {
    updatePack();
    if (!trackerFrame) trackerFrame = requestAnimationFrame(() => { trackerFrame = 0; renderTracker(); });
  }

  function renderTracker() {
    const panel = $("#tracker");
    // The pack is finished the moment every artifact passes the gate; publishing is a separate choice.
    const complete = !!(state.build && state.build.complete);
    const stopped = ["FAILED", "BLOCKED"].includes(state.final) || (state.final === "DONE" && !complete);
    const finished = complete || stopped;
    panel.hidden = false;
    panel.classList.toggle("is-done", complete);
    const total = ARTS.reduce((n, a) => n + ART_STATE[art(a.key).s].w, 0) / ARTS.length;
    const pct = complete ? 100 : Math.min(99, Math.round(total * 100));
    $("#trackerPct").textContent = pct;
    $("#trackerFill").style.width = pct + "%";
    $("#trackerEyebrow").textContent = complete ? "Your pack is finished" : finished ? "Stopped before everything passed" : "Finishing your pack";
    $("#trackerNow").textContent = complete
      ? `Every file built and checked${state.gate.round ? ` in ${state.gate.round} review round${state.gate.round === 1 ? "" : "s"}` : ""}${state.sentBack ? ` · ${state.sentBack} fix${state.sentBack === 1 ? "" : "es"} along the way` : ""}`
      : state.now || "The team is building every file…";
    const list = $("#trackerCards");
    if (!list.children.length) list.innerHTML = ARTS.map((a) => `<li class="art" id="art-${a.key}"><div class="pv"></div><h5><span>${esc(a.label)}</span><span class="st"></span></h5><p class="why-sent" hidden></p></li>`).join("");
    ARTS.forEach((a) => {
      const st = art(a.key);
      const li = $("#art-" + a.key);
      const cls = { queued: "s-queued", making: "s-making", made: "s-made", checking: "s-checking", sent: "s-sent", fixing: "s-fixing", passed: "s-passed" }[st.s];
      li.className = "art " + cls;
      if (st.changed) { void li.offsetWidth; li.classList.add("pop"); st.changed = false; }
      $(".st", li).textContent = ART_STATE[st.s].label;
      const pv = $(".pv", li);
      const media = st.video || null;
      const busy = { queued: "waiting its turn", making: a.agent === "VideoEditor" ? "rendering…" : a.agent === "ArtDirector" ? "designing…" : "writing…",
        fixing: "fixing…", checking: "checking…" }[st.s] || "";
      const key = st.img || (media && media.url) || st.preview || "s:" + busy;
      if (pv.dataset.key !== key) {
        pv.dataset.key = key;
        pv.classList.toggle("has-img", !!(st.img || media));
        pv.style.backgroundImage = st.img ? `url("${st.img}")` : "";
        pv.classList.toggle("wide", !!st.wide);
        pv.innerHTML = media ? videoTag(media, state.media, `muted autoplay loop playsinline preload="metadata"`)
          : st.img ? "" : esc(st.preview || busy);
      }
      const why = $(".why-sent", li);
      why.hidden = st.s !== "sent" && st.s !== "fixing";
      why.textContent = st.why ? trim(st.why, 90) : "";
    });
    const g = state.gate;
    $("#trackerRound").textContent = g.round ? `Quality gate · round ${g.round}` : "Quality gate · runs once everything is built";
    $("#trackerAreas").innerHTML = Object.keys(AREA_NAMES).map((k) => `<span class="area ${g.areas[k] === true ? "pass" : g.areas[k] === false ? "fix" : ""}">${g.areas[k] === true ? "✓ " : g.areas[k] === false ? "↺ " : ""}${AREA_NAMES[k]}</span>`).join("");
    $("#trackerLog").innerHTML = g.log.slice(-4).map((l) => `<li class="${/every artifact passed/.test(l) ? "ok" : ""}">${esc(trim(l, 160))}</li>`).join("");
  }

  // The proof behind the pack: what made it original, what the studio remembered, where the facts came
  // from, what was fixed before the creator saw it, and which calls were theirs.
  function renderWhy() {
    const v = state.values;
    const plural = (n, w) => `${n} ${w}${n === 1 ? "" : "s"}`;
    const o = state.originality || { past: 0, checks: [] };
    const cast = [...o.checks].reverse().find((c) => c.stage === "casting");
    const recast = o.checks.some((c) => c.stage === "casting" && c.novelty === "REPEAT");
    const yt = [...o.checks].reverse().find((c) => c.stage === "youtube");
    const castingSheet = v.castingSheet || {};
    const findings = (v.researchDossier && v.researchDossier.findings) || [];
    const sources = new Set(findings.map((f) => safeUrl(f.url)).filter(Boolean));
    const b = state.build;
    const cards = [
      {
        ic: "🧬", t: "Originality", tone: cast && cast.novelty === "REPEAT" ? "warn" : "ok",
        v: !o.past ? "Baseline set" : cast && cast.novelty === "REPEAT" ? "Close to past work" : "Fresh",
        d: !o.past ? "Your first pack. Every future pack is checked against it so you never post the same angle twice."
          : `Checked against ${o.past === 1 ? "your last pack" : `your last ${o.past} packs`}${cast && cast.closest_similarity != null ? ` — the closest is ${Math.round(cast.closest_similarity * 100)}% similar` : ""}.`
            + (recast ? " The first casting was too close, so the Showrunner re-cast it." : "")
            + (yt ? ` YouTube title and thumbnail: ${yt.novelty === "REPEAT" ? "reworked to avoid a repeat" : "fresh"}.` : ""),
      },
      {
        ic: "🎯", t: "A new angle", tone: "ok",
        v: castingSheet.lens || "—",
        d: "Dealt from creative lenses you haven't used lately" + (castingSheet.visual_style ? `, in a visual style picked the same way: ${trim(castingSheet.visual_style, 70)}.` : "."),
      },
      {
        ic: "🧠", t: "Remembered", tone: "ok",
        v: `${state.memory.recalled + state.memory.learned} things about you`,
        d: "GetViral starts each pack from what worked for you, and every hook pick and rating teaches the next one.",
      },
      {
        ic: "🔎", t: "Researched", tone: findings.length ? "ok" : "warn",
        v: findings.length ? `${plural(findings.length, "fact")} · ${plural(sources.size, "source")}` : "No web research",
        d: findings.length ? "The Researcher read the web before anyone wrote. Every fact in the dossier links to where it came from." : "Research wasn't available for this run, so the writers worked from trends and your brief.",
      },
      {
        ic: "✅", t: "Checked first", tone: b && !b.complete ? "warn" : "ok",
        v: b ? plural(b.round, "review round") : "reviewing…",
        d: !b ? "The Showrunner is checking every file and post." : state.sentBack
          ? `${plural(state.sentBack, "artifact")} sent back to specialists and rebuilt until X, Instagram and YouTube all passed.`
          : "Every file, platform limit and judge passed on the first review.",
      },
      {
        ic: "✋", t: "Your call", tone: "ok",
        v: "You picked the hook",
        d: (v.hookChoice ? `“${trim(v.hookChoice, 80)}”. ` : "") + "Nothing is posted without your explicit approval.",
      },
    ];
    $("#why").hidden = false;
    $("#whyHint").textContent = o.past ? `vs ${plural(o.past, "past pack")}` : "first pack";
    $("#whyGrid").innerHTML = cards.map((c) => `<li class="why-card t-${c.tone}"><span class="why-ic">${c.ic}</span>
      <div><h4>${esc(c.t)}</h4><b>${esc(c.v)}</b><p>${esc(c.d)}</p></div></li>`).join("");
  }

  function renderInspection(r) {
    const panel = $("#inspection");
    if (!r || typeof r !== "object" || !(r.checks || []).length) { panel.hidden = true; return; }
    panel.hidden = false;
    const checks = r.checks || [];
    const count = (s) => checks.filter((c) => c.status === s).length;
    const verdict = { PASS: "All clear", PASS_WITH_WARNINGS: "Clear, with warnings", FAIL: "Needs attention" }[r.verdict] || r.verdict;
    $("#inspectionHint").innerHTML = `<span class="verdict v-${esc(String(r.verdict).toLowerCase())}">${esc(verdict)}</span> ${count("PASS")} pass · ${count("WARN")} warn · ${count("FAIL")} fail`;
    $("#inspectionSummary").textContent = r.summary || "";
    const order = { FAIL: 0, WARN: 1, PASS: 2 };
    $("#checks").innerHTML = [...checks].sort((a, b) => order[a.status] - order[b.status]).map((c) =>
      `<li class="chk s-${esc(String(c.status).toLowerCase())}"><b>${c.status === "PASS" ? "✓" : c.status === "WARN" ? "!" : "✕"}</b><span class="a">${esc(c.artifact)}</span><span class="d">${esc(c.detail)}</span></li>`).join("");
    $("#inspectionFixes").innerHTML = (r.fixes || []).map((f) => `<li>🔧 ${esc(f)}</li>`).join("");
  }

  function renderResearch(r) {
    const panel = $("#research");
    if (!r || typeof r !== "object" || !(r.findings || []).length) { panel.hidden = true; return; }
    panel.hidden = false;
    const findings = r.findings || [];
    const sources = new Set(findings.map((f) => safeUrl(f.url)).filter(Boolean));
    const plural = (n, w) => `${n} ${w}${n === 1 ? "" : "s"}`;
    $("#researchHint").textContent = `${plural(findings.length, "finding")} · ${plural(sources.size, "source")}`;
    $("#researchSummary").textContent = r.summary || "";
    $("#findings").innerHTML = findings.map((f) => {
      const url = safeUrl(f.url);
      const label = f.source || hostOf(url) || "source";
      return `<li><p>${esc(f.point)}</p>${url ? `<a href="${esc(url)}" target="_blank" rel="noopener noreferrer">${esc(trim(label, 60))} ↗</a>` : `<span class="src">${esc(label)}</span>`}</li>`;
    }).join("");
    const group = (title, items, cls) => (items || []).length
      ? `<div class="rx ${cls}"><h4>${title}</h4><ul>${items.map((i) => `<li>${esc(i)}</li>`).join("")}</ul></div>` : "";
    $("#researchExtra").innerHTML = group("Fresh angles", r.fresh_angles, "angles") + group("What people debate", r.debates, "debates") + group("Caveats", r.caveats, "caveats");
  }

  function renderX(x) {
    const box = $("#xThread");
    if (!x || typeof x !== "object") { box.innerHTML = `<pre class="mono">${esc(x)}</pre>`; return; }
    const handle = state.brief.handle;
    const initial = handle[0]?.toUpperCase() || "G";
    box.innerHTML = (x.thread || []).map((t) => {
      const len = [...String(t)].length;
      return `<div class="tweet"><div class="pp">${esc(initial)}</div><div><span class="nm">${esc(handle)}</span> <span class="hd">@${esc(handle)} · now</span><p>${esc(t)}</p><div class="tw-foot"><span>💬 ↻ ♡ ⎙</span><span class="${len > 280 ? "over" : ""}">${len}/280 <button class="copy" type="button">copy</button></span></div></div></div>`;
    }).join("");
    $$(".tweet .copy", box).forEach((b, i) => b.addEventListener("click", () => copy(x.thread[i])));
    $("#xExtra").innerHTML = `
      ${x.standalone ? `<div class="card"><h4>Standalone post</h4><p>${esc(x.standalone)}</p></div>` : ""}
      ${x.reply_bait ? `<div class="card"><h4>Reply bait</h4><p>${esc(x.reply_bait)}</p></div>` : ""}
      <button class="copy" type="button" id="copyThread">Copy whole thread</button>`;
    $("#copyThread").addEventListener("click", () => copy((x.thread || []).join("\n\n")));
  }

  function renderReel(r) {
    if (!r || typeof r !== "object") { $("#reelBeats").innerHTML = `<pre class="mono">${esc(r)}</pre>`; return; }
    $("#reelTitle").textContent = `${r.title || "Reel"} · ${r.duration || ""}`;
    $("#reelHandle").textContent = "@" + state.brief.handle;
    $("#reelAudio").innerHTML = `<span>♫ ${esc(r.audio || "original audio")} &nbsp;&nbsp; ♫ ${esc(r.audio || "")}</span>`;
    $("#reelCaption").textContent = r.caption || "";
    $("#reelTags").textContent = (r.hashtags || []).join(" ");
    const beats = Array.isArray(r.beats) ? r.beats : [];
    $("#reelBeats").innerHTML = beats.map((b, i) => `<li data-i="${i}"><span class="bt">${esc(b.time)}</span><span><span class="bv">${esc(b.voiceover)}</span><span class="bo">🎥 ${esc(b.shot)} · on-screen: “${esc(b.on_screen)}”</span></span></li>`).join("");
    $("#storyBars").innerHTML = beats.map(() => `<i><b></b></i>`).join("");
    $$("#reelBeats li").forEach((li) => li.addEventListener("click", () => playBeat(beats, Number(li.dataset.i))));
    if (beats.length) playBeat(beats, 0);
  }

  function playBeat(beats, i) {
    clearTimeout(state.reelTimer);
    const b = beats[i];
    const dur = 2600;
    $$("#storyBars i").forEach((bar, j) => { bar.className = j < i ? "done" : j === i ? "now" : ""; bar.style.setProperty("--beat", dur + "ms"); const fill = bar.firstChild; fill.style.animation = "none"; void fill.offsetWidth; fill.style.animation = ""; });
    $$("#reelBeats li").forEach((li, j) => li.classList.toggle("now", j === i));
    const text = $("#reelText"), vo = $("#reelVo");
    text.textContent = b.on_screen || ""; vo.textContent = b.voiceover || ""; $("#reelShot").textContent = "🎥 " + (b.shot || "");
    [text, vo].forEach((el) => { el.style.animation = "none"; void el.offsetWidth; el.style.animation = ""; });
    $(".reel-bg").classList.toggle("alt", i % 2 === 1);
    state.reelTimer = setTimeout(() => playBeat(beats, (i + 1) % beats.length), dur);
  }

  function renderYt(y) {
    if (!y || typeof y !== "object") { $("#ytConcept").textContent = String(y ?? ""); return; }
    $("#ytThumbText").textContent = y.thumbnail_text || "";
    const ref = String(y.thumbnail_reference || "");
    if (/^https:\/\//.test(ref) && /\.(jpe?g|png|webp)(\?|$)/i.test(ref)) {
      $("#ytThumb").style.backgroundImage = `linear-gradient(115deg, rgba(16,36,63,.75), rgba(255,46,136,.45)), url("${ref.replace(/"/g, "")}")`;
    }
    $("#ytTitles").innerHTML = (y.titles || []).map((t) => `<li><span>${esc(t)}</span><small>${String(t).length} chars</small></li>`).join("");
    $("#ytConcept").textContent = "🎨 " + (y.thumbnail_concept || "");
    $("#ytHook").textContent = y.hook_script || "";
    $("#ytShorts").textContent = y.shorts_cut || "";
    $("#ytChapters").innerHTML = (y.chapters || []).map((c) => { const m = String(c).match(/^(\d+:\d+)\s*(.*)$/); return m ? `<li><b>${esc(m[1])}</b>${esc(m[2])}</li>` : `<li>${esc(c)}</li>`; }).join("");
    $("#ytDesc").textContent = (y.description || "") + "\n\n" + (y.chapters || []).join("\n");
    $("#ytTags").textContent = (y.tags || []).map((t) => "#" + String(t).replace(/\s+/g, "")).join(" ");
  }

  function renderBadges(badges) {
    $("#qualityHint").textContent = `${badges.filter((b) => b.passed).length}/${badges.length} passed`;
    if (state.build) renderBuild();
    $("#badges").innerHTML = badges.map((b, i) => `
      <div class="badge ${b.passed ? "pass" : "fail"}" style="animation-delay:${i * 90}ms" title="${esc(b.reason)}">
        <div class="bh"><span class="bn">${esc(b.name)}</span><span class="bs">${Math.round(b.score * 100)}</span></div>
        <div class="bar"><i data-w="${Math.round(b.score * 100)}"></i></div>
        <p class="br">${esc(b.reason)}</p>
      </div>`).join("");
    requestAnimationFrame(() => $$(".badge .bar i").forEach((i) => (i.style.width = i.dataset.w + "%")));
  }

  function resultsTab(id) {
    $$("#resTabs button").forEach((b) => b.classList.toggle("is-on", b.dataset.pf === id));
    ["pfX", "pfReel", "pfYt"].forEach((p) => ($("#" + p).hidden = p !== id));
  }

  function bindRating(el) {
    $$("button", el).forEach((b) => b.addEventListener("click", async () => {
      $$("button", el).forEach((x) => x.classList.remove("is-on"));
      b.classList.add("is-on");
      const loved = b.dataset.loved === "true";
      await GV.api("/api/feedback", { method: "POST", body: { platform: el.dataset.platform, loved, detail: loved ? "hook: " + (state.values.hookChoice || "") : "" } });
      toast(loved ? "Remembered for next time." : "Noted — next pack will differ.");
      let lbl = $(".rate-note", el); if (!lbl) { lbl = document.createElement("span"); lbl.className = "rate-note hint"; el.prepend(lbl); }
      lbl.textContent = loved ? "Remembered for next time" : "Noted — next pack will differ";
    }));
  }

  // ── Little helpers ──────────────────────────────────────────────────────
  const TOOL_VERB = { web_search: "searched the web", read_page: "read a source", trending_now: "checked what's trending", hn_pulse: "checked tech chatter", trending_hashtags: "checked hashtags", moment_calendar: "checked the calendar", fact_check: "checked a fact", word_lab: "played with wording", trending_audio: "looked for trending audio", broll_finder: "looked for B-roll", viral_playbook: "opened the playbook", instagram_quota: "checked Instagram limits", instagram_publish: "got ready to post" };
  const toolVerb = (t) => TOOL_VERB[t] || "did some work";
  function nowBanner(text) { const b = $("#nowBanner"); if (b) $("span", b).textContent = text; }
  function updatePack() {
    const v = state.values, cast = v.castingSheet || {};
    const row = (k, val) => `<div><span>${k}</span><b>${esc(val || "—")}</b></div>`;
    $("#packLens").innerHTML = row("Lens", cast.lens) + row("Style", trim(cast.visual_style || "", 40)) + row("Hook", trim(v.hookChoice || "", 60));
    const NAMES = { queued: "Queued", making: "Making", made: "Made", sent: "Sent back", fixing: "Fixed", checking: "Making", passed: "Passed" };
    $("#packFiles").innerHTML = ARTS.map((a) => { const st = art(a.key).s; return `<div><span>${esc(a.label)}</span><b style="${st === "making" || st === "sent" ? "color:var(--color-accent-700)" : ""}">${NAMES[st] || st}</b></div>`; }).join("");
  }
  function icon(agent) { return (AGENTS.find((a) => a.id === agent) || {}).icon || "•"; }
  function toolIcon(tool) {
    return ({ web_search: "🌍", read_page: "📖", trending_now: "📈", hn_pulse: "🗣️", trending_hashtags: "#️⃣", moment_calendar: "📅", fact_check: "🔎", word_lab: "🔤", trending_audio: "🎵", broll_finder: "🖼️", viral_playbook: "📚", instagram_quota: "📊", instagram_publish: "📤" })[tool] || "🛠️";
  }
  function trim(s, n) { s = String(s ?? "").replace(/\s+/g, " ").trim(); return s.length > n ? s.slice(0, n - 1) + "…" : s; }
  async function copy(text) { try { await navigator.clipboard.writeText(text); toast("Copied"); } catch { toast("Copy failed — select the text manually"); } }
  function toast(msg) { const t = $("#toast"); t.textContent = msg; t.classList.add("show"); clearTimeout(state.tt); state.tt = setTimeout(() => t.classList.remove("show"), 2600); }

  function confetti() {
    if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const c = $("#confetti"), ctx = c.getContext("2d");
    const dpr = devicePixelRatio || 1;
    c.width = innerWidth * dpr; c.height = innerHeight * dpr; ctx.scale(dpr, dpr);
    const colors = ["#ff2e88", "#ff9a3d", "#8b5cff", "#2ad4f2", "#b9f36c", "#ffffff"];
    const parts = Array.from({ length: 180 }, () => ({
      x: innerWidth / 2 + (Math.random() - 0.5) * 200, y: innerHeight * 0.32,
      vx: (Math.random() - 0.5) * 16, vy: -Math.random() * 15 - 5, r: Math.random() * 6 + 3,
      c: colors[(Math.random() * colors.length) | 0], s: Math.random() * 6.28, sp: (Math.random() - 0.5) * 0.3, shape: Math.random() > 0.5,
    }));
    let frame = 0;
    (function tick() {
      ctx.clearRect(0, 0, innerWidth, innerHeight);
      parts.forEach((p) => {
        p.vy += 0.38; p.vx *= 0.99; p.x += p.vx; p.y += p.vy; p.s += p.sp;
        ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(p.s); ctx.fillStyle = p.c;
        if (p.shape) ctx.fillRect(-p.r / 2, -p.r / 4, p.r, p.r / 2); else { ctx.beginPath(); ctx.arc(0, 0, p.r / 2.4, 0, 6.28); ctx.fill(); }
        ctx.restore();
      });
      if (++frame < 170) requestAnimationFrame(tick); else ctx.clearRect(0, 0, innerWidth, innerHeight);
    })();
  }

  boot();
})();
