"use strict";

const params = new URLSearchParams(location.search);
const series = params.get("s") || "";
const canvas = document.getElementById("canvas");
const vscroll = document.getElementById("vscroll");
const hscroll = document.getElementById("hscroll");
const title = document.getElementById("title");
const bar = document.getElementById("bar");
const toolbar = document.getElementById("toolbar");
const prevBtn = document.getElementById("prevBtn");
const nextBtn = document.getElementById("nextBtn");
const backBtn = document.getElementById("backBtn");
const pageInfo = document.getElementById("pageInfo");
const gridBtn = document.getElementById("gridBtn");
const gridSheet = document.getElementById("gridSheet");
const gridClose = document.getElementById("gridClose");
const gridGrid = document.getElementById("gridGrid");
const sheet = document.getElementById("sheet");
const sheetOverlay = document.getElementById("sheetOverlay");

// ---------- Settings state ----------
function readBool(k, d) { return (localStorage.getItem(k) ?? String(d ? "1" : "0")) === "1"; }
let mode = localStorage.getItem("komi-mode") || "vert";
let effect = localStorage.getItem("komi-effect") || "none";
let tapTurn = readBool("komi-tap", true);
let showProgress = readBool("komi-progress", true);
let landscapeRotate = readBool("komi-landscape", true); // 横图旋转开关，默认开

// Theme
let theme = localStorage.getItem("komi-theme") || "auto";
function applyTheme(t) {
  if (t === "auto") root.dataset.theme = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  else root.dataset.theme = t;
}
const root = document.documentElement;
applyTheme(theme);
document.getElementById("themeToggle").addEventListener("click", () => {
  theme = root.dataset.theme === "dark" ? "light" : "dark";
  localStorage.setItem("komi-theme", theme);
  applyTheme(theme);
  syncSettingsUI();
});

// ---------- Mode ----------
function setMode(m) {
  mode = m;
  localStorage.setItem("komi-mode", m);
  canvas.dataset.mode = m;
  if (m === "vert") { vscroll.classList.remove("hidden"); hscroll.classList.add("hidden"); }
  else { vscroll.classList.add("hidden"); hscroll.classList.remove("hidden"); }
  hscroll.classList.toggle("fade", effect === "fade");
  updateBar();
}

function setEffect(e) {
  effect = e;
  localStorage.setItem("komi-effect", e);
  hscroll.classList.toggle("fade", e === "fade");
  syncSettingsUI();
}
function setTap(v) { tapTurn = v; localStorage.setItem("komi-tap", v ? "1" : "0"); }
function setProgress(v) {
  showProgress = v;
  localStorage.setItem("komi-progress", v ? "1" : "0");
  document.querySelector(".reader-progress").style.display = v ? "" : "none";
  syncSettingsUI();
}
function setLandscape(v) {
  landscapeRotate = v;
  localStorage.setItem("komi-landscape", v ? "1" : "0");
  // 立即重排所有横图页面
  document.querySelectorAll(".reader-page img.landscape").forEach(img => {
    const page = img.closest(".reader-page");
    if (v) {
      img.classList.add("rotated");
      layoutRotated(page, img);
    } else {
      img.classList.remove("rotated");
      // 还原为正常流（清除 layoutRotated 设置的所有内联样式）
      ["width","height","flex","max-width","min-height","display","align-items","justify-content","margin"].forEach(p => page.style.removeProperty(p));
      ["width","height","transform","max-width","max-height","object-fit"].forEach(p => img.style.removeProperty(p));
    }
  });
  syncSettingsUI();
}

// ---------- Data ----------
let images = [];
let total = 0;
let loaded = 0;
let prevPercent = 0;
let currentH = 0;

function clamp(i) { return Math.max(0, Math.min(total - 1, i)); }

function currentPage() {
  if (mode === "horiz") return currentH;
  return clamp(Math.round(window.scrollY / window.innerHeight));
}

function goToPage(i, smooth = false) {
  i = clamp(i);
  preloadNear(i);
  if (mode === "horiz") {
    const el = hscroll.children[i];
    if (el) el.scrollIntoView({ behavior: "auto", block: "nearest", inline: "start" });
  } else {
    window.scrollTo({ top: i * window.innerHeight, behavior: "auto" });
  }
  saveProgress(i);
}

// 预加载目标页附近的图片，减少翻页白闪/等待
function preloadNear(i) {
  const n = total;
  for (let d = 1; d <= 2; d++) {
    for (const idx of [i - d, i + d]) {
      if (idx < 0 || idx >= n) continue;
      const img = (vscroll.children[idx] || hscroll.children[idx])?.querySelector("img");
      if (img && !img.classList.contains("loaded")) {
        // 图片 src 已设置，浏览器会自行加载；这里只是提前触发解码
        try { img.decode && img.decode(); } catch (e) {}
      }
    }
  }
}

// 历史观看：保存上次阅读位置
function saveProgress(i) {
  try {
    localStorage.setItem("komi-last-" + series, String(i));
    localStorage.setItem("komi-last-time-" + series, String(Date.now()));
  } catch (e) {}
}
function loadProgress() {
  try {
    const v = localStorage.getItem("komi-last-" + series);
    return v === null ? null : parseInt(v, 10);
  } catch (e) { return null; }
}

backBtn.addEventListener("click", () => history.back());
prevBtn.addEventListener("click", () => goToPage(currentPage() - 1));
nextBtn.addEventListener("click", () => goToPage(currentPage() + 1));

// 横图旋转布局：旋转90°后视觉宽高互换，居中且占满可用区域
function layoutRotated(page, img) {
  const vh = window.innerHeight;
  const vw = window.innerWidth;
  const iw = img.naturalWidth, ih = img.naturalHeight;
  const availH = vh;      // 顶满屏高（边顶手机屏幕边界）
  const availW = vw;      // 顶满屏宽
  // 旋转后视觉宽高比 = ih/iw (横图旋转后变竖)
  let dispH = availH;
  let dispW = dispH * (ih / iw);
  if (dispW > availW) { dispW = availW; dispH = dispW * (iw / ih); }
  // 容器容纳旋转后的视觉尺寸(宽=dispW, 高=dispH)
  // 用 !important 覆盖 vertical/horizontal 两种模式的 CSS 限制
  page.style.setProperty("width", dispW + "px", "important");
  page.style.setProperty("height", dispH + "px", "important");
  page.style.setProperty("flex", "0 0 auto", "important");
  page.style.setProperty("max-width", "none", "important");
  page.style.setProperty("min-height", "auto", "important");
  page.style.setProperty("display", "flex", "important");
  page.style.setProperty("align-items", "center", "important");
  page.style.setProperty("justify-content", "center", "important");
  page.style.setProperty("margin", "0 auto", "important");
  // 图片：旋转前设置原尺寸(width=dispH 对应旋转后高度, height=dispW)
  img.style.setProperty("width", dispH + "px", "important");
  img.style.setProperty("height", dispW + "px", "important");
  img.style.setProperty("transform", "rotate(90deg)", "important");
  img.style.setProperty("max-width", "none", "important");
  img.style.setProperty("max-height", "none", "important");
  img.style.setProperty("object-fit", "contain", "important");
}

function makePage(name, i) {
  const page = document.createElement("div");
  page.className = "reader-page loading";
  // Placeholder so a blank page is never shown
  page.style.background = "var(--bg)";  // 防白闪：用阅读背景色，不用浅色
  page.style.minHeight = "60vh";
  const img = document.createElement("img");
  img.className = "lazy";
  img.decoding = "async";
  img.alt = `第 ${i + 1} 页`;
  let revealed = false;
function reveal() {
    if (revealed) return;
    revealed = true;
    img.classList.remove("lazy");
    img.classList.add("loaded");
    // 横图（宽>高）自动旋转，让它占满屏幕；受 landscapeRotate 开关控制
    // 仅竖屏设备旋转（横屏设备横图本来就横向，无需旋转）
    if (img.naturalWidth > 0 && img.naturalHeight > 0 && img.naturalWidth > img.naturalHeight) {
      img.classList.add("landscape");
      if (landscapeRotate && window.innerHeight >= window.innerWidth) {
        img.classList.add("rotated");
        layoutRotated(page, img);
      }
    }
    page.classList.remove("loading");
    page.style.minHeight = "auto";
    loaded++;
    updateBar();
  }
  img.addEventListener("load", reveal);
  img.addEventListener("error", () => { if (!revealed) { revealed = true; loaded++; updateBar(); } });
  // Set src AFTER listeners are attached
  img.src = KomiConfig.url(`/api/img/${encodeURIComponent(series)}/${encodeURIComponent(name)}`);
  // If already cached (complete before events), reveal immediately
  if (img.complete && img.naturalWidth > 0) reveal();
  // Safety net: force reveal if never loaded (e.g. slow network edge cases)
  setTimeout(() => { if (!revealed) reveal(); }, 6000);
  page.appendChild(img);
  return page;
}

async function init() {
  title.textContent = series;
  try {
    const res = await KomiConfig.fetch(`/api/series/${encodeURIComponent(series)}`);
    const data = await res.json();
    images = data.images;
    total = images.length;
    if (!total) {
      canvas.innerHTML = `<div class="empty"><p>该系列没有图片</p></div>`;
      return;
    }
    // IMPORTANT: never clear canvas.innerHTML — that detaches vscroll/hscroll
    // from the DOM, and pages appended later would never be visible.
    vscroll.innerHTML = "";
    hscroll.innerHTML = "";
    images.forEach((name, i) => {
      vscroll.appendChild(makePage(name, i));
      hscroll.appendChild(makePage(name, i));
    });
    setMode(mode);
    setProgress(showProgress);
    updateBar();
    buildGrid();
    // 恢复上次观看位置: URL 参数优先，否则读 localStorage 历史
    let p = parseInt(params.get("p") || "0", 10);
    if (!(p > 0)) {
      const saved = loadProgress();
      if (saved !== null && saved > 0 && saved < total) p = saved;
    }
    if (p > 0) goToPage(p, false);
  } catch (e) {
    title.textContent = "加载失败";
  }
}

function updateBar() {
  // 进度按当前图片序号 / 总图片数 计算
  const cur = currentPage();
  const frac = total > 1 ? cur / (total - 1) : 0;
  const pct = Math.min(100, Math.max(0, frac * 100));
  bar.style.width = `${pct}%`;
  if (pageInfo) pageInfo.textContent = `${cur + 1} / ${total}`;
  // 高亮网格中的当前页
  if (gridGrid) {
    gridGrid.querySelectorAll(".grid-item").forEach((it, idx) => {
      it.classList.toggle("active", idx === cur);
    });
  }
}

// ---------- Global grid (page navigator) ----------
function buildGrid() {
  if (!gridGrid) return;
  gridGrid.innerHTML = "";
  images.forEach((name, i) => {
    const it = document.createElement("div");
    it.className = "grid-item";
    it.dataset.idx = i;
    it.innerHTML = `<img src="${KomiConfig.url(`/api/thumb/${encodeURIComponent(series)}/${encodeURIComponent(name)}`)}" loading="lazy" alt="p${i + 1}"><span class="grid-num">${i + 1}</span>`;
    it.addEventListener("click", () => {
      goToPage(i);
      closeGrid();
    });
    gridGrid.appendChild(it);
  });
}

function openGrid() {
  if (!gridSheet) return;
  gridSheet.classList.add("show");
  // 滚动当前页到可见
  const cur = currentPage();
  const act = gridGrid.querySelector(`.grid-item[data-idx="${cur}"]`);
  if (act) act.scrollIntoView({ block: "center" });
}
function closeGrid() {
  if (gridSheet) gridSheet.classList.remove("show");
}

// ---------- Settings sheet ----------
function syncSettingsUI() {
  document.querySelectorAll(".sheet .seg[data-s]").forEach(b => {
    const v = b.dataset.v;
    const key = b.dataset.s;
    let cur = "";
    if (key === "mode") cur = mode;
    else if (key === "effect") cur = effect;
    else if (key === "theme") cur = theme;
    b.classList.toggle("active", cur === v);
  });
  document.getElementById("tapTurn").checked = tapTurn;
  document.getElementById("showProgress").checked = showProgress;
  const ls = document.getElementById("landscapeRotate");
  if (ls) ls.checked = landscapeRotate;
}

function bindSheet() {
  if (!sheet) return;
  document.getElementById("settingsToggle").addEventListener("click", () => {
    syncSettingsUI();
    sheet.classList.add("show");
    sheetOverlay.classList.add("show");
  });
  sheetOverlay.addEventListener("click", () => {
    sheet.classList.remove("show");
    sheetOverlay.classList.remove("show");
  });
  sheet.querySelectorAll(".seg[data-s]").forEach(b => {
    b.addEventListener("click", () => {
      const key = b.dataset.s, v = b.dataset.v;
      if (key === "mode") setMode(v);
      else if (key === "effect") setEffect(v);
      else if (key === "theme") { theme = v; localStorage.setItem("komi-theme", v); applyTheme(v); syncSettingsUI(); }
    });
  });
  document.getElementById("tapTurn").addEventListener("change", e => setTap(e.target.checked));
  document.getElementById("showProgress").addEventListener("change", e => setProgress(e.target.checked));
  const ls = document.getElementById("landscapeRotate");
  if (ls) ls.addEventListener("change", e => setLandscape(e.target.checked));
}

// ---------- Keyboard ----------
document.addEventListener("keydown", (e) => {
  if (e.key === "ArrowRight") nextBtn.click();
  if (e.key === "ArrowLeft") prevBtn.click();
  if (e.key === "Escape") { backBtn.click(); }
});

// ---------- Horizontal scroll tracking ----------
let hScrollTimer;
hscroll.addEventListener("scroll", () => {
  const w = hscroll.clientWidth || 1;
  currentH = clamp(Math.round(hscroll.scrollLeft / w));
  updateBar();
  clearTimeout(hScrollTimer);
  // 防惯性滑过多页: 滑动停止后 snap 到最近的整页
  clearTimeout(hscroll._snapT);
  hscroll._snapT = setTimeout(() => {
    if (mode !== "horiz") return;
    const target = clamp(Math.round(hscroll.scrollLeft / w));
    const el = hscroll.children[target];
    if (el) el.scrollIntoView({ behavior: "auto", block: "nearest", inline: "start" });
    currentH = target;
    updateBar();
    saveProgress(target);
  }, 130);
  hScrollTimer = setTimeout(() => updateBar(), 150);
}, { passive: true });

// ---------- Vertical scroll tracking + auto-hide toolbar ----------
let scrollTimer;
let saveProgressTimer;
window.addEventListener("scroll", () => {
  if (mode === "vert") {
    preloadNear(currentPage());
    const doc = document.documentElement;
    const scrollable = doc.scrollHeight - window.innerHeight;
    // Only reflect scroll progress once content actually scrolls
    if (scrollable > 0) {
      const pct = Math.min(100, Math.max(0, (window.scrollY / scrollable) * 100));
      bar.style.width = `${Math.max(prevPercent, pct)}%`;
    }
    // 历史观看: 滚动时保存位置(防抖)
    clearTimeout(saveProgressTimer);
    saveProgressTimer = setTimeout(() => saveProgress(currentPage()), 200);
  }
  toolbar.classList.add("hidden-toolbar");
  clearTimeout(scrollTimer);
  scrollTimer = setTimeout(() => toolbar.classList.remove("hidden-toolbar"), 800);
}, { passive: true });

// ---------- Tap to turn (both modes) ----------
let tapStartX = null;
document.addEventListener("pointerdown", (e) => {
  if (!tapTurn) return;
  if (e.target.closest("button, video, a, input, label, .sheet, .sheet-overlay")) return;
  tapStartX = e.clientX;
});
document.addEventListener("pointerup", (e) => {
  if (!tapTurn || tapStartX === null) return;
  const dx = e.clientX - tapStartX;
  tapStartX = null;
  if (Math.abs(dx) < 12) {
    const w = window.innerWidth;
    if (e.clientX < w / 3) prevBtn.click();
    else if (e.clientX > (w * 2) / 3) nextBtn.click();
  }
});

// ---------- Visible error reporting (debug aid) ----------
function showFatal(msg) {
  let el = document.getElementById("fatal");
  if (!el) {
    el = document.createElement("div");
    el.id = "fatal";
    el.style.cssText = "position:fixed;top:70px;left:12px;right:12px;z-index:999;background:#ff453a;color:#fff;padding:12px 16px;border-radius:12px;font-size:13px;box-shadow:0 6px 24px rgba(0,0,0,.3);";
    document.body.appendChild(el);
  }
  el.textContent = "错误: " + msg;
}
window.addEventListener("error", (e) => showFatal(e.message));

bindSheet();

// ---------- Global grid events ----------
if (gridBtn) gridBtn.addEventListener("click", openGrid);
if (gridClose) gridClose.addEventListener("click", closeGrid);
if (gridSheet) {
  gridSheet.addEventListener("click", (e) => {
    if (e.target === gridSheet) closeGrid();
  });
}

// ---------- 设备旋转时重新布局横图 ----------
let resizeTimer;
window.addEventListener("resize", () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => {
    document.querySelectorAll(".reader-page img").forEach(img => {
      if (img.naturalWidth > 0 && img.naturalHeight > 0 && img.naturalWidth > img.naturalHeight) {
        const page = img.closest(".reader-page");
        if (!page) return;
        const shouldRotate = landscapeRotate && window.innerHeight >= window.innerWidth;
        if (shouldRotate) {
          img.classList.add("rotated");
          layoutRotated(page, img);
        } else {
          img.classList.remove("rotated");
          ["width","height","flex","max-width","min-height","display","align-items","justify-content","margin"].forEach(p => page.style.removeProperty(p));
          ["width","height","transform","max-width","max-height","object-fit"].forEach(p => img.style.removeProperty(p));
        }
      }
    });
  }, 200);
});

init();
