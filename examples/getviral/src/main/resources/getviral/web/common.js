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

  window.GV = { api, requireMe, esc, toast, avatar, signOut };
})();
