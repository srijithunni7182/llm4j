/* GetViral Studio — streams a Loom workflow run over SSE and turns it into a live show. */
(() => {
  "use strict";

  const AGENTS = [
    { id: "Showrunner", icon: "🎬", role: "Casts the team & writes every prompt", c: "linear-gradient(135deg,#8b5cff,#ff2e88)", glow: "#8b5cff" },
    { id: "TrendScout", icon: "📡", role: "Live trends from public APIs", c: "linear-gradient(135deg,#2ad4f2,#1e6bff)", glow: "#2ad4f2" },
    { id: "Researcher", icon: "🔬", role: "Researches the web, reads sources", c: "linear-gradient(135deg,#6ee7b7,#2ad4f2)", glow: "#6ee7b7" },
    { id: "Strategist", icon: "🧭", role: "Angle, hooks & verified facts", c: "linear-gradient(135deg,#ff9a3d,#ff2e88)", glow: "#ff9a3d" },
    { id: "XWriter", icon: "𝕏", role: "Threads that get quoted", c: "linear-gradient(135deg,#3a3a44,#0b0b0f)", glow: "#9ca3af" },
    { id: "ReelDirector", icon: "🎞️", role: "Beat-by-beat Reels", c: "linear-gradient(45deg,#f58529,#dd2a7b,#8134af)", glow: "#dd2a7b" },
    { id: "YouTubeProducer", icon: "▶", role: "Titles, thumbnail & chapters", c: "linear-gradient(135deg,#ff3355,#b3001e)", glow: "#ff3355" },
    { id: "ViralityCritic", icon: "🔥", role: "Scores. Sends back. Ships.", c: "linear-gradient(135deg,#ffd166,#ff6a3d)", glow: "#ffb13d" },
    { id: "ArtDirector", icon: "🎨", role: "Generates thumbnail, cover & B-roll", c: "linear-gradient(135deg,#ffd166,#ff2e88)", glow: "#ffd166" },
    { id: "VideoEditor", icon: "📹", role: "Renders the Reel to MP4", c: "linear-gradient(135deg,#2ad4f2,#8b5cff)", glow: "#2ad4f2" },
    { id: "Publisher", icon: "🚀", role: "Posts to Instagram — with your OK", c: "linear-gradient(135deg,#b9f36c,#2bb673)", glow: "#b9f36c" },
  ];
  const STAGE_OF = { Showrunner: "cast", TrendScout: "scout", Researcher: "scout", Strategist: "strategy", XWriter: "create", ReelDirector: "create", YouTubeProducer: "create", ViralityCritic: "critique", ArtDirector: "visuals", VideoEditor: "visuals", Publisher: "ship" };
  const STAGES = ["cast", "scout", "strategy", "hook", "create", "critique", "visuals", "ship"];
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
  const store = {
    get(k) { try { return localStorage.getItem("gv." + k) || ""; } catch { return ""; } },
    set(k, v) { try { localStorage.setItem("gv." + k, v); } catch { /* private mode */ } },
  };

  const state = {
    runId: null, es: null, brief: null, started: 0, timer: null,
    values: {}, prompts: {}, stats: { llm: 0, api: 0, live: 0 }, events: 0, media: [],
    pendingApproval: null, revealed: false, stage: null, reelTimer: null, info: {},
  };

  // ── Boot ────────────────────────────────────────────────────────────────
  async function boot() {
    state.me = await GV.requireMe();
    renderAccount();
    bindComposer();
    $("#againBtn").addEventListener("click", () => { location.href = "/studio"; });
    $("#drawerClose").addEventListener("click", () => ($("#promptDrawer").hidden = true));
    $("#ownHook").addEventListener("submit", (e) => { e.preventDefault(); const v = $("#ownHookInput").value.trim(); if (v) answerHook(v); });
    $("#approveBtn").addEventListener("click", () => answerApproval("approve"));
    $("#rejectBtn").addEventListener("click", () => answerApproval("reject"));
    $("#publishForm").addEventListener("submit", publishSubmit);
    $("#skipPublish").addEventListener("click", () => answerPublish("skip"));
    document.addEventListener("keydown", hookKeys);
    $$(".rate").forEach(bindRating);
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
    $("#quotaPill").textContent = `${me.quota.used}/${me.quota.limit} packs this month`;
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
      <button class="agent" id="ag-${a.id}" style="--c:${a.c};--glow:${a.glow}" type="button" data-agent="${a.id}">
        <div class="av">${a.icon}</div><h4>${a.id}</h4><p>${a.role}</p>
        <span class="state">idle</span><span class="pv" hidden></span>
      </button>`).join("");
    $$(".agent").forEach((el) => el.addEventListener("click", () => openPrompt(el.dataset.agent)));
    state.started = performance.now();
    state.timer = setInterval(() => ($("#clock").textContent = ((performance.now() - state.started) / 1000).toFixed(1) + "s"), 100);
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
    $("#libPacks").innerHTML = runs.length ? runs.map((r, i) => `
      <button type="button" class="pack-card" data-run="${esc(r.id)}" style="animation-delay:${Math.min(i, 12) * 40}ms">
        <div class="pack-cover" style="${r.cover ? `background-image:url('${esc(r.cover)}')` : ""}">
          <span class="status">${esc(statusLabel(r.status))}</span>${r.score ? `<span class="score">${Number(r.score).toFixed(1)}</span>` : ""}
        </div>
        <div class="pack-body"><h3>${esc(r.idea)}</h3>${r.hook ? `<p>“${esc(r.hook)}”</p>` : ""}
          <small>${new Date(r.createdAt).toLocaleString()}${r.mediaCount ? ` · ${r.mediaCount} media` : ""}</small></div>
      </button>`).join("")
      : `<div class="empty"><h3>No packs yet</h3><p>Your packs — copy, images and Reels — will live here.</p><a class="cta" href="/studio"><span>Make your first pack</span></a></div>`;
    $$("#libPacks [data-run]").forEach((b) => b.addEventListener("click", () => { location.href = `/studio?run=${b.dataset.run}`; }));
  }

  async function libraryTab(tab) {
    $$(".tabs button").forEach((b) => b.classList.toggle("is-on", b.dataset.tab === tab));
    $("#libPacks").hidden = tab !== "packs";
    $("#libMedia").hidden = tab !== "media";
    if (tab !== "media" || $("#libMedia").dataset.loaded) return;
    const media = await GV.api("/api/library/media");
    $("#libMedia").dataset.loaded = "1";
    $("#libMedia").innerHTML = media.length ? media.map((m) => `
      <figure class="tile">${m.kind === "video"
        ? `<video src="${esc(m.url)}" muted loop playsinline controls preload="metadata"></video>`
        : `<img src="${esc(m.url)}" alt="${esc(m.purpose)}" loading="lazy" width="${m.width}" height="${m.height}">`}
        <span class="prov ${m.ai ? "ai" : "local"}">${m.ai ? "AI · " : ""}${esc(m.provider)}</span>
        <figcaption><span>${esc(String(m.purpose).replace(/_/g, " "))} · ${esc(m.idea)}</span><a href="${esc(m.url)}" download>↓</a></figcaption>
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
        feed("✦", "GetViral", "Brief received — assembling the team", `${esc(d.model)}`, "", ev.t);
        break;
      case "agent_start": {
        agentState(d.agent, "working");
        if (STAGE_OF[d.agent]) setStage(STAGE_OF[d.agent]);
        const task = String(d.task || "");
        const recast = task.startsWith("RECAST"), revise = task.startsWith("REVISE");
        const label = recast ? "is re-casting prompts from critic feedback" : revise ? "is revising" : d.agent === "ViralityCritic" ? `is reviewing the pack · round ${d.round}` : "started";
        feed(icon(d.agent), d.agent, label, "", recast ? "prompt" : "", ev.t);
        if (d.agent === "ViralityCritic") $("#criticToast").hidden = true;
        break;
      }
      case "prompt_injected":
        feed("✎", d.agent, `running on Showrunner prompt v${d.version}`, "", "prompt", ev.t);
        break;
      case "prompt": {
        (state.prompts[d.agent] ||= []).push(d);
        const pv = $(`#ag-${d.agent} .pv`);
        if (pv) { pv.hidden = false; pv.textContent = "prompt v" + d.version; $(`#ag-${d.agent}`).classList.remove("flash"); void pv.offsetWidth; $(`#ag-${d.agent}`).classList.add("flash"); }
        $("#statPrompts").textContent = Object.values(state.prompts).reduce((n, v) => n + v.length, 0);
        feed("✍️", "Showrunner", `wrote ${d.agent}'s prompt · v${d.version}`, trim(d.prompt, 150), "prompt", ev.t);
        break;
      }
      case "thought":
        if (d.text && !/^I have everything/.test(d.text)) feed("💭", d.agent, "", esc(trim(d.text, 180)), "thought", ev.t);
        break;
      case "action":
        feed(toolIcon(d.tool), d.agent, `→ <code>${esc(d.tool)}</code>`, esc(trim(d.input, 110)), "", ev.t);
        break;
      case "api":
        state.stats.api++;
        if (d.live) state.stats.live++;
        $("#statApi").textContent = state.stats.api;
        $("#statLive").textContent = Math.round((state.stats.live / state.stats.api) * 100) + "%";
        feed("🌐", "", `<span class="api-pill ${d.live ? "live" : "sample"}"><span class="d"></span>${esc(d.host)} · ${d.live ? "live" : "sample"} · ${d.ms}ms</span>`, "", "", ev.t);
        break;
      case "rag":
        feed("📚", "RAG", `searched the ${esc(d.scope)} for “${esc(trim(d.query, 60))}”`, esc((d.sources || []).join(" · ")), "memory", ev.t);
        break;
      case "llm":
        state.stats.llm++;
        $("#statLlm").textContent = state.stats.llm;
        break;
      case "memory_recall":
        if (d.recalled) feed("🧠", "Engram", `briefed ${esc(d.agent)} with what it remembers`, esc(trim(String(d.briefing).split("\n").filter((l) => l.includes("•")).join(" "), 180)), "memory", ev.t);
        break;
      case "memory_store":
        if (d.learned > 0) { feed("🧠", "Engram", `learned ${d.learned} new thing${d.learned > 1 ? "s" : ""} from ${esc(d.agent)}`, esc(d.content || ""), "memory", ev.t); loadMemory(); }
        break;
      case "agent_done":
        agentState(d.agent, "done");
        state.values[d.variable] = d.value;
        if (state.revealed && (d.agent === "VideoEditor" || d.agent === "ArtDirector")) renderMedia();
        onAgentDone(d, ev.t);
        break;
      case "agent_error":
        feed("⚠️", d.agent, `hit an error (attempt ${d.attempt}/${d.maxAttempts})`, esc(trim(d.error, 160)), "err", ev.t);
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
        feed(d.approved ? "✅" : "✋", "You", d.approved ? "approved publishing" : "rejected publishing", "", "", ev.t);
        plog(d.approved ? "ok" : "dry", d.approved ? "APPROVED" : "REJECTED", d.approved ? "You approved the publish call." : "You rejected it — nothing was posted.");
        break;
      case "publish":
        onPublish(d);
        break;
      case "media": {
        state.media.push(d);
        $("#statMedia").textContent = state.media.length;
        const portrait = d.height > d.width;
        const label = d.kind === "video" ? `rendered the Reel · ${d.seconds}s · ${d.width}×${d.height}` : `generated ${esc(String(d.purpose).replace(/_/g, " "))}`;
        const body = d.kind === "video"
          ? `<video class="shot portrait" src="${esc(d.url)}" muted autoplay loop playsinline></video>`
          : `<img class="shot ${portrait ? "portrait" : ""}" src="${esc(d.url)}" alt="${esc(d.purpose)}">`;
        feed(d.kind === "video" ? "📹" : "🎨", d.kind === "video" ? "VideoEditor" : "ArtDirector", label, `${esc(d.provider)}${d.ai ? "" : " · not AI"}${body}`, "media", ev.t);
        if (state.revealed) renderMedia();
        break;
      }
      case "quality":
        renderBadges(d.badges || []);
        break;
      case "pack":
        state.values.pack = d;
        if (!state.revealed) reveal();
        renderPromptLab(d.prompts || {});
        break;
      case "blocked":
        setStage("cast");
        feed("🛡️", "Guardrail", "Personal data detected — generation blocked", esc(d.notice), "err", ev.t);
        toast("Loom's PII guardrail blocked this brief. Remove personal details and try again.");
        setTimeout(() => { $("#studioIdea").insertAdjacentHTML("afterend", `<p class="muted" style="margin-top:10px">${esc(d.notice)} <a href="/" style="color:var(--hot)">Try again →</a></p>`); }, 50);
        break;
      case "error":
        feed("✗", "GetViral", "run failed", esc(d.message), "err", ev.t);
        toast("Something went wrong: " + d.message);
        break;
      case "status":
        state.final = d.status;
        if (["DONE", "BLOCKED", "FAILED"].includes(d.status)) {
          clearInterval(state.timer);
          state.es && state.es.close();
          if (state.revealed) renderMedia();
          GV.api("/api/me").then((me) => { state.me = me; $("#quotaPill").textContent = `${me.quota.used}/${me.quota.limit} packs this month`; }).catch(() => {});
          if (d.status === "DONE") { setStage("ship", true); agentState("Publisher", "done"); $("#qualityHint").textContent = "graded"; }
        }
        break;
    }
  }

  function onAgentDone(d, t) {
    const v = d.value;
    if (d.agent === "Showrunner" && v && typeof v === "object") {
      if (v.run_title) $("#runTitle").textContent = v.run_title;
      feed("🎬", "Showrunner", "cast the team", esc(trim(v.creative_direction, 200)), "prompt", t);
    } else if (d.agent === "Researcher" && v && typeof v === "object") {
      const n = (v.findings || []).length;
      feed("🔬", "Researcher", `brought back ${n} sourced finding${n === 1 ? "" : "s"}`, esc(trim(v.summary, 200)), "", t);
    } else if (d.agent === "ViralityCritic" && v && typeof v === "object") {
      criticMoment(v);
      $("#statRounds").textContent = v.round || 1;
      feed("🔥", "ViralityCritic", `scored ${v.score}/10 — ${v.verdict === "SHIP" ? "SHIP IT" : "back to the team"}`, esc(v.headline || ""), "critic", t);
      if (v.verdict === "SHIP" && !state.revealed) setTimeout(reveal, 1600);
    } else {
      feed("✓", d.agent, `done · ${d.iterations} step${d.iterations === 1 ? "" : "s"}${d.tools?.length ? " · " + [...new Set(d.tools)].join(", ") : ""}`, "", "", t);
    }
  }

  function agentState(agent, s) {
    const el = $("#ag-" + agent);
    if (!el) return;
    el.classList.toggle("is-working", s === "working");
    el.classList.toggle("is-done", s === "done");
    $(".state", el).textContent = s === "working" ? "● live" : s === "done" ? "✓ done" : "idle";
  }

  function setStage(stage, finished = false) {
    const idx = STAGES.indexOf(stage);
    if (idx < STAGES.indexOf(state.stage || "cast") && !finished) return;
    state.stage = stage;
    $$("#rail li").forEach((li, i) => {
      li.classList.toggle("is-done", i < idx || (finished && i <= idx));
      li.classList.toggle("is-active", i === idx && !finished);
    });
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
        : `<li class="muted">First time with @${esc(handle)} — Engram will start remembering after this run.</li>`;
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
      : `Instagram isn't connected, so this is an honest dry run showing the exact API calls. <a href="/welcome?step=CONNECT" style="color:var(--hot)">Connect Instagram</a>`;
  }

  function publishSubmit(e) {
    e.preventDefault();
    const url = $("#videoUrl").value.trim();
    if (!/^https:\/\//.test(url)) { toast("Paste a public https:// video URL"); return; }
    answerPublish(url);
    $("#publishLog").innerHTML = "";
    plog("", "PUBLISHER", "Checking the Instagram quota and preparing the Reel…");
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
    const rows = [["Tool", state.pendingApproval?.tool || "instagram_publish"], ["Type", args.media_type], ["Media", args.media_url], ["Caption", args.caption]];
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
      plog("dry", "DRY RUN", "No Instagram token is set, so nothing was posted. With IG_USER_ID + IG_ACCESS_TOKEN the Publisher makes exactly these Graph API calls:", steps.join("\n"));
    } else if (d.status === "published") {
      plog("ok", "LIVE", `Published! <a href="${esc(d.permalink)}" target="_blank" rel="noopener" style="color:var(--hot)">${esc(d.permalink || d.media_id)}</a>`, "", true);
      confetti();
    } else if (d.status === "failed") {
      plog("dry", "FAILED", `Instagram couldn't process the media (${esc(d.container_status)}). Nothing was published.`);
    } else if (d.status === "processing") {
      plog("", "PROCESSING", `Container status: ${esc(d.container_status)} · poll ${d.poll}`);
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
    $("#revealHeadline").textContent = critic.headline || "";
    $("#exportBtn").href = `/api/runs/${state.runId}/export.md`;
    $("#exportBtn").setAttribute("download", "getviral-pack.md");
    if (!$("#badges").children.length) $("#badges").innerHTML = Array.from({ length: 5 }, () => `<div class="badge skel"></div>`).join("");
    renderResearch(v.researchDossier);
    renderX(v.xPack);
    renderReel(v.reelPack);
    renderYt(v.youtubePack);
    renderMedia();
    $$("#reelToggle button").forEach((b) => b.addEventListener("click", () => setReelMode(b.dataset.mode)));
    renderPromptLab(Object.fromEntries(Object.entries(state.prompts).map(([k, list]) => [k, list.map((p) => ({ version: p.version, prompt: p.prompt, reason: p.reason }))])));
    $("#studio").hidden = true;
    window.scrollTo({ top: 0, behavior: "smooth" });
    confetti();
  }

  function mediaFor(purpose) { return [...state.media].reverse().find((m) => m.purpose === purpose); }

  function renderMedia() {
    const media = [...state.media].sort((a, b) => MEDIA_ORDER.indexOf(a.purpose) - MEDIA_ORDER.indexOf(b.purpose));
    const expected = ["reel", "youtube_thumbnail", "reel_cover", "x_card", "broll_1", "broll_2"];
    const missing = expected.filter((p) => !media.some((m) => m.purpose === p));
    const done = ["DONE", "FAILED"].includes(state.final) || !!state.values.videoPack;
    $("#visualsHint").textContent = done ? `${media.length} files · ${media.filter((m) => m.ai).length} AI-generated` : "generating…";
    const tile = (m) => {
      const el = m.kind === "video"
        ? `<video src="${esc(m.url)}" muted autoplay loop playsinline controls></video>`
        : `<img src="${esc(m.url)}" alt="${esc(m.purpose)}" loading="lazy" width="${m.width}" height="${m.height}">`;
      return `<figure class="tile ${m.purpose === "reel" ? "hero" : ""}">${el}<span class="prov ${m.ai ? "ai" : "local"}">${m.ai ? "AI · " : ""}${esc(m.provider)}</span>
        <figcaption><span>${esc(String(m.purpose).replace(/_/g, " "))}${m.seconds ? " · " + m.seconds + "s" : ""}</span><a href="${esc(m.url)}" download>↓</a></figcaption></figure>`;
    };
    const skel = (p) => `<figure class="tile skel ${p === "reel" ? "hero" : ""}">${p === "reel" ? "rendering reel…" : esc(p.replace(/_/g, " "))}</figure>`;
    const videos = media.filter((m) => m.kind === "video");
    const stills = media.filter((m) => m.kind !== "video");
    $("#gallery").innerHTML =
      `<div class="gallery-reel">${videos.map(tile).join("") || (done ? "" : skel("reel"))}</div>` +
      `<div class="gallery-stills">${stills.map(tile).join("")}${done ? "" : missing.filter((p) => p !== "reel").map(skel).join("")}</div>`;
    $$("#gallery video").forEach((v) => v.addEventListener("error", () => {
      if (!v.parentElement.querySelector(".note")) v.insertAdjacentHTML("afterend", `<p class="note">This browser can't decode H.264 here — download the MP4 (↓) to watch it.</p>`);
    }, { once: true }));

    const thumb = mediaFor("youtube_thumbnail");
    if (thumb) { $("#ytThumb").style.backgroundImage = `url("${thumb.url}")`; $("#ytThumbText").hidden = true; }
    const card = mediaFor("x_card");
    const firstTweet = $("#xThread .tweet p");
    if (card && firstTweet && !$("#xThread .media-card")) firstTweet.insertAdjacentHTML("afterend", `<div class="media-card"><img src="${esc(card.url)}" alt="X card"></div>`);
    const cover = mediaFor("reel_cover");
    if (cover && !$(".cover-thumb")) $("#reelCaption").insertAdjacentHTML("beforebegin", `<img class="cover-thumb" src="${esc(cover.url)}" alt="Reel cover">`);
    const reel = mediaFor("reel");
    if (reel && $("#reelVideo").getAttribute("src") !== reel.url) {
      $("#reelVideo").addEventListener("error", () => { setReelMode("story"); toast("This browser can't play the MP4 inline — showing the storyboard. Download it from Visuals."); }, { once: true });
      $("#reelVideo").src = reel.url;
      $("#reelToggle").hidden = false;
      setReelMode("video");
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
    $("#badges").innerHTML = badges.map((b, i) => `
      <div class="badge ${b.passed ? "pass" : "fail"}" style="animation-delay:${i * 90}ms" title="${esc(b.reason)}">
        <div class="bh"><span class="bn">${esc(b.name)}</span><span class="bs">${Math.round(b.score * 100)}</span></div>
        <div class="bar"><i data-w="${Math.round(b.score * 100)}"></i></div>
        <p class="br">${esc(b.reason)}</p>
      </div>`).join("");
    requestAnimationFrame(() => $$(".badge .bar i").forEach((i) => (i.style.width = i.dataset.w + "%")));
  }

  function renderPromptLab(prompts) {
    const entries = Object.entries(prompts || {});
    if (!entries.length) return;
    $("#promptCards").innerHTML = entries.map(([agent, versions]) => {
      const last = versions[versions.length - 1];
      return `<div class="prompt-card"><h4>${esc(agent)} <span>v${last.version}</span></h4><p>${esc(last.prompt)}</p><button class="copy" type="button" data-agent="${esc(agent)}">Open prompt history</button></div>`;
    }).join("");
    $$("#promptCards button").forEach((b) => b.addEventListener("click", () => openPrompt(b.dataset.agent, prompts)));
  }

  function openPrompt(agent, fromPack) {
    const versions = (fromPack && fromPack[agent]) || state.prompts[agent] || [];
    $("#drawerTitle").textContent = agent;
    $("#drawerEyebrow").textContent = versions.length ? `${versions.length} version${versions.length > 1 ? "s" : ""} · written by the Showrunner` : "Live prompt";
    $("#drawerBody").innerHTML = versions.length
      ? versions.slice().reverse().map((v, i) => `<div class="version ${i === 0 ? "latest" : ""}"><h5><span>v${v.version}${i === 0 ? " · live" : ""}</span><span>${esc(v.reason || "")}</span></h5><p>${esc(v.prompt)}</p></div>`).join("") +
        `<p class="house">+ fixed frame on every version:\n“You are ${esc(agent)} on the GetViral creator team.” … HOUSE RULES (no invented stats, no personal data, platform limits, exact format)</p>`
      : `<p class="muted">${agent === "Showrunner" ? "The Showrunner is the orchestrator — its meta-prompt lives in getviral.loom. It writes everyone else's." : "No runtime prompt yet — this agent runs on its one-line fallback from getviral.loom until the Showrunner casts it."}</p>`;
    $("#promptDrawer").hidden = false;
  }

  function bindRating(el) {
    $$("button", el).forEach((b) => b.addEventListener("click", async () => {
      $$("button", el).forEach((x) => x.classList.remove("is-on"));
      b.classList.add("is-on");
      const loved = b.dataset.loved === "true";
      await GV.api("/api/feedback", { method: "POST", body: { platform: el.dataset.platform, loved, detail: loved ? "hook: " + (state.values.hookChoice || "") : "" } });
      toast(loved ? "🧠 Saved to Engram — next time GetViral leans into this." : "🧠 Noted in Engram — next time GetViral tries a different approach.");
    }));
  }

  // ── Little helpers ──────────────────────────────────────────────────────
  function icon(agent) { return (AGENTS.find((a) => a.id === agent) || {}).icon || "•"; }
  function toolIcon(tool) {
    return ({ web_search: "🌍", read_page: "📖", trending_now: "📈", hn_pulse: "🗣️", trending_hashtags: "#️⃣", moment_calendar: "📅", fact_check: "🔎", word_lab: "🔤", trending_audio: "🎵", broll_finder: "🖼️", viral_playbook: "📚", instagram_quota: "📊", instagram_publish: "📤" })[tool] || "🛠️";
  }
  function trim(s, n) { s = String(s ?? "").replace(/\s+/g, " ").trim(); return s.length > n ? s.slice(0, n - 1) + "…" : s; }
  async function copy(text) { try { await navigator.clipboard.writeText(text); toast("Copied ✓"); } catch { toast("Copy failed — select the text manually"); } }
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
