"use strict";

/**
 * KomiConfig - global app config stored in localStorage.
 * Supports a user-configured remote server address so the same
 * frontend can be packaged into a standalone app / WebView and
 * pointed at a remote host (e.g. over VPN) without rebuild.
 *
 * Keys:
 *   komi-server  : "" (same origin) or "http://host:port"
 *   komi-theme   : "light" | "dark" | "auto"
 *   komi-mode    : "vert" | "horiz"   (reader mode)
 *   komi-effect  : "slide" | "fade" | "none"  (turn effect)
 *   komi-tap     : "1" | "0"  (tap-to-turn)
 *   komi-progress: "1" | "0"  (show progress bar)
 */
const KomiConfig = (() => {
  const KEY_SERVER = "komi-server";
  const KEY_THEME = "komi-theme";

  function get(key, fallback = "") {
    const v = localStorage.getItem(key);
    return v === null ? fallback : v;
  }
  function set(key, value) {
    localStorage.setItem(key, value);
  }

  // Resolve API base. Trailing slash stripped. "" => same origin.
  function serverBase() {
    return get(KEY_SERVER, "").replace(/\/+$/, "");
  }

  // Build an absolute URL for a path (e.g. "/api/library").
  function url(path) {
    const base = serverBase();
    if (!base) return path;
    return base + (path.startsWith("/") ? path : "/" + path);
  }

  // Fetch wrapper that always uses the configured server.
  async function fetch(path, opts = {}) {
    return window.fetch(url(path), opts);
  }

  function setServer(s) {
    set(KEY_SERVER, (s || "").trim());
  }

  return {
    get, set,
    serverBase, url, fetch, setServer,
    KEY_SERVER, KEY_THEME,
  };
})();
