(async () => {
  "use strict";
  const $ = (s) => document.querySelector(s);

  // Already signed in? Go straight to the right place.
  try {
    const me = await GV.api("/api/me");
    location.replace(me.onboardingStep === "DONE" ? "/studio" : "/welcome");
    return;
  } catch { /* signed out — show the landing page */ }

  let info = {};
  try { info = await GV.api("/api/public/info"); } catch { /* offline */ }
  if (info.googleSignIn) $("#googleBtn").hidden = false;
  if (info.devLogin) $("#devLogin").hidden = false;
  if (!info.googleSignIn && !info.devLogin) {
    $("#signinNote").textContent = "Sign-in isn't configured on this server yet.";
  } else if (info.packsPerMonth) {
    $("#signinNote").textContent = `Free: ${info.packsPerMonth} packs a month.`;
  }

  $("#devLogin").addEventListener("submit", async (e) => {
    e.preventDefault();
    $("#signinError").textContent = "";
    try {
      const res = await GV.api("/auth/dev-login", { method: "POST", body: { email: $("#devEmail").value.trim() } });
      location.replace(res.redirect || "/welcome");
    } catch (err) {
      $("#signinError").textContent = err.message;
    }
  });
})();
