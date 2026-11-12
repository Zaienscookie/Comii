"use strict";

// ---------- Theme ----------
const THEME_KEY = "komi-theme";
const root = document.documentElement;
const themeToggle = document.getElementById("themeToggle");

function applyTheme(t) {
  if (t === "auto") {
    const dark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    root.dataset.theme = dark ? "dark" : "light";
  } else {
    root.dataset.theme = t;
  }
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.content = root.dataset.theme === "dark" ? "#000000" : "#f2f2f7";
}

function initTheme() {
  let t = localStorage.getItem(THEME_KEY) || "auto";
  applyTheme(t);
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
    if (t === "auto") applyTheme("auto");
  });
  if (themeToggle) {
    themeToggle.addEventListener("click", () => {
      const cur = root.dataset.theme;
      t = cur === "dark" ? "light" : "dark";
      localStorage.setItem(THEME_KEY, t);
      applyTheme(t);
    });
  }
}

// ---------- Library ----------
let libraryData = { series: [] };
let filter = "all";
let query = "";
let searchResults = null;   // 服务端递归搜索结果
let searchSeq = 0;
let editMode = false;
let folderMode = true;          // 文件夹功能启用
let selectedItems = new Set();  // 编辑模式多选集合
let clipItems = [];             // vivo 风格移动剪贴板: [{name, path}]

const grid = document.getElementById("grid");
const empty = document.getElementById("empty");
const heroSub = document.getElementById("heroSub");
const search = document.getElementById("search");

function toast(msg) {
  let el = document.querySelector(".toast");
  if (!el) {
    el = document.createElement("div");
    el.className = "toast";
    document.body.appendChild(el);
  }
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.remove("show"), 2200);
}

function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

function kindLabel(kind) {
  if (kind === "manga") return "漫画";
  if (kind === "video") return "视频";
  return "混合";
}

async function loadLibrary() {
  try {
    const res = await KomiConfig.fetch("/api/tree");
    const data = await res.json();
    // 找当前路径对应的子项
    let items = data.tree || [];
    if (currentPath) {
      // 逐段下钻
      const parts = currentPath.split("/").filter(Boolean);
      let node = items;
      for (let i = 0; i < parts.length; i++) {
        const found = (node || []).find(it => it.name === parts[i] && it.kind === "folder");
        if (!found) break;
        node = found.children || [];
      }
      items = node;
    }
    libraryData = { series: items, tree: data.tree || [] };
    updateHero();
    render();
  } catch (e) {
    heroSub.textContent = "加载失败";
    toast("无法加载库，请检查后端服务或服务器设置");
  }
}

function updateHero() {
  heroSub.textContent = `${libraryData.series.length} 项`;
}

function matches(item) {
  if (item.kind === "folder") {
    // 文件夹按创建时选择的类型归类；没有类型的文件夹在两个分类中都显示
    const kind = item.forced_type || "";
    if (filter !== "all" && kind && kind !== "mixed" && kind !== filter) return false;
    return !query || item.name.toLowerCase().includes(query.toLowerCase());
  }
  const sub = item.subkind || (item.videos ? "video" : "manga");
  if (filter !== "all" && sub !== filter) return false;
  if (query && !item.name.toLowerCase().includes(query.toLowerCase())) return false;
  return true;
}

function render() {
  const items = query ? (searchResults || []) : libraryData.series.filter(matches);
  grid.innerHTML = "";
  empty.classList.toggle("hidden", items.length > 0);

  renderBreadcrumb();
  syncSegments();

  items.forEach((item, i) => {
    if (item.kind === "folder") {
      grid.appendChild(renderFolderCard(item, i));
    } else {
      grid.appendChild(renderSeriesCard(item, i));
    }
  });

  if (folderMode) {
    bindDragDrop(items);
  }
  syncEditBar();
}

function renderFolderCard(item, i) {
  const card = document.createElement("div");
  card.className = "card folder";
  card.draggable = true;
  card.dataset.name = item.name;
  card.dataset.path = item.path || "";
  card.style.animationDelay = `${Math.min(i * 30, 400)}ms`;
  const count = item.child_count != null ? item.child_count : (item.children || []).length;
  card.innerHTML = `
    <div class="card-cover folder-cover">
      <svg viewBox="0 0 48 48" width="52" height="52" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 13c-3-2.6-7.5-4-13-4v26c5.5 0 10 1.4 13 4 3-2.6 7.5-4 13-4V9c-5.5 0-10 1.4-13 4z"/><path d="M22 13v26"/></svg>
    </div>
    <div class="card-body">
      <div class="card-title">${escapeHtml(item.name)}</div>
      <div class="card-meta">${count} 项</div>
    </div>
    <div class="card-actions">
      <button class="card-dl" data-series="${escapeHtml(item.name)}" data-path="${escapeHtml(item.path || '')}" aria-label="下载" title="打包下载整个文件夹">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/></svg>
      </button>
      <button class="card-del" data-series="${escapeHtml(item.name)}" aria-label="删除" title="删除文件夹">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14zM10 11v6M14 11v6"/></svg>
      </button>
      <button class="card-move" data-series="${escapeHtml(item.name)}" aria-label="移动" title="移动到文件夹">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 9l-3 3 3 3M9 5l3-3 3 3M15 19l-3 3-3-3M19 9l3 3-3 3M2 12h20M12 2v20"/></svg>
      </button>
    </div>`;
  // 长按 → 移动选择 (无需编辑模式)
  let lp = null, lpd = false;
  card.addEventListener("pointerdown", (e) => {
    if (e.button !== undefined && e.button !== 0) return;
    lpd = false;
    lp = setTimeout(() => {
      lpd = true;
      if (navigator.vibrate) navigator.vibrate(30);
      if (editMode) toggleSelect(item);
      else openMoveSheet(item);
    }, 500);
  });
  card.addEventListener("pointerup", () => clearTimeout(lp));
  card.addEventListener("pointerleave", () => clearTimeout(lp));
  card.addEventListener("click", (e) => {
    if (editMode) {
      if (lpd) { lpd = false; return; }
      toggleSelect(item); return;
    }
    if (lpd) { e.preventDefault(); e.stopPropagation(); lpd = false; return; }
    openFolder(item.path);
  });
  const delBtn = card.querySelector(".card-del");
  if (delBtn) {
    delBtn.addEventListener("pointerdown", (e) => e.stopPropagation());
    delBtn.addEventListener("click", async (e) => {
      e.stopPropagation();
      const ok = confirm(`确定删除文件夹「${item.name}」吗？
其内容将一并进入回收站，3天后自动清除。`);
      if (!ok) return;
      try {
        const res = await KomiConfig.fetch(`/api/delete/${pathUrl(item)}`, { method: "DELETE" });
        if (res.ok) { toast("已删除，3天后自动清除"); loadLibrary(); }
        else { const d = await res.json().catch(() => ({})); toast("删除失败: " + (d.detail || res.status)); }
      } catch (e) { toast("删除失败: " + e.message); }
    });
  }
  const downloadBtn = card.querySelector(".card-dl");
  if (downloadBtn) {
    downloadBtn.addEventListener("pointerdown", (e) => e.stopPropagation());
    downloadBtn.addEventListener("click", (e) => { e.stopPropagation(); downloadSeries(item); });
  }
  const moveBtn = card.querySelector(".card-move");
  if (moveBtn) {
    moveBtn.addEventListener("pointerdown", (e) => e.stopPropagation());
    moveBtn.addEventListener("click", (e) => { e.stopPropagation(); openMoveSheet(item); });
  }
  updateSelectState(card, item);
  return card;
}

// ---------- Drag & Drop ----------
let _dragItem = null;
function bindDragDrop(items) {
  const cards = grid.querySelectorAll(".card");
  cards.forEach(card => {
    // 拖拽开始
    card.addEventListener("dragstart", (e) => {
      const idx = Array.prototype.indexOf.call(cards, card);
      const item = items[idx];
      if (!item) return;
      _dragItem = item;
      card.classList.add("dragging");
      e.dataTransfer.effectAllowed = "move";
      try {
        e.dataTransfer.setData("text/plain", item.path || item.name);
        e.dataTransfer.setDragImage(card, 40, 40);
      } catch (err) {}
      document.body.classList.add("drag-active");
    });

    // 可放置目标：文件夹卡(本身) + 所有卡(用于高亮反馈)
    card.addEventListener("dragover", (e) => {
      if (!_dragItem) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
      // 只有目标不是自己 且 目标是文件夹 才高亮为可放置
      const targetItem = getItemByCard(card, items);
      if (targetItem && targetItem.kind === "folder" && targetItem.path !== _dragItem.path) {
        card.classList.add("drop-target");
      }
    });
    card.addEventListener("dragleave", () => {
      card.classList.remove("drop-target");
    });
    card.addEventListener("drop", (e) => {
      e.preventDefault();
      e.stopPropagation();
      card.classList.remove("drop-target");
      const targetItem = getItemByCard(card, items);
      if (!_dragItem) return;
      if (targetItem && targetItem.kind === "folder" && targetItem.path !== _dragItem.path) {
        doDragMove(_dragItem, targetItem.path);
      } else if (targetItem && targetItem.path !== _dragItem.path) {
        // 拖到非文件夹(如漫画卡)上，移动到当前所在文件夹? 不做，仅在文件夹上生效
        toast("请拖到文件夹上");
      }
    });
  });

  // 拖到 grid 空白处 → 移动到根目录 (仅当前不在根目录时有效)
  grid.addEventListener("dragover", (e) => {
    if (!_dragItem) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    grid.classList.add("drop-root");
  });
  grid.addEventListener("dragleave", (e) => {
    if (!grid.contains(e.relatedTarget)) grid.classList.remove("drop-root");
  });
  grid.addEventListener("drop", (e) => {
    e.preventDefault();
    grid.classList.remove("drop-root");
    if (!_dragItem) return;
    const target = e.target.closest(".card");
    if (target) return; // 卡片上已处理
    // 落在空白处 → 移到根目录
    if (currentPath) {
      doDragMove(_dragItem, "");
    } else {
      toast("已在根目录");
    }
  });
}

function getItemByCard(card, items) {
  const idx = Array.prototype.indexOf.call(grid.querySelectorAll(".card"), card);
  return items[idx] || null;
}

function doDragMove(item, to) {
  const from = item.path ? item.path.replace("/" + item.name, "") : "";
  if (from === to) { toast("已在目标位置"); return; }
  doMove(item.name, from, to, () => {
    toast(`已移动到「${to || "根目录"}」`);
    loadLibrary();
  });
}

// 结束拖拽时清除状态
document.addEventListener("dragend", () => {
  document.body.classList.remove("drag-active");
  grid.classList.remove("drop-root");
  grid.querySelectorAll(".drop-target").forEach(el => el.classList.remove("drop-target"));
  _dragItem = null;
});

function renderSeriesCard(item, i) {
  const sub = item.subkind || (item.videos ? "video" : "manga");
  const card = document.createElement("div");
  card.className = "card" + (sub === "video" ? " video" : "");
  card.dataset.name = item.name;
  card.dataset.path = item.path || "";
  if (folderMode) card.draggable = true;
  card.style.animationDelay = `${Math.min(i * 30, 400)}ms`;
  // 历史观看: 读取上次进度
  let progressHtml = "";
  if (sub === "manga" && item.images > 0) {
    let last = null, lastT = null;
    try {
      last = parseInt(localStorage.getItem("komi-last-" + item.name) || "", 10);
      lastT = parseInt(localStorage.getItem("komi-last-time-" + item.name) || "", 10);
    } catch (e) {}
    if (!isNaN(last) && last > 0 && last < item.images) {
      const pct = Math.round((last / (item.images - 1)) * 100);
      const timeStr = lastT ? timeAgo(lastT) : "";
      progressHtml = `
        <div class="card-progress" title="上次看到第${last + 1}页${timeStr ? " · " + timeStr : ""}">
          <div class="card-progress-bar"><div class="card-progress-fill" style="width:${pct}%"></div></div>
          <span class="card-progress-text">上次 ${last + 1}页 · ${pct}%</span>
        </div>`;
    }
  }
  card.innerHTML = `
    <div class="card-cover">
      ${item.cover ? `<img src="${item.cover}" alt="${item.name}" loading="lazy">` : ""}
      <span class="card-badge">${kindLabel(sub)}</span>
      ${sub !== "manga" ? `
        <div class="card-play"><span>
          <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
        </span></div>` : ""}
    </div>
    <div class="card-body">
      <div class="card-title">${escapeHtml(item.name)}</div>
      <div class="card-meta">${sub === "video" ? item.videos + " 集" : sub === "manga" ? item.images + " 页" : item.images + " 图 · " + item.videos + " 视频"}</div>
    </div>
    ${progressHtml}
    <div class="card-actions">
      <button class="card-dl" data-series="${escapeHtml(item.name)}" data-path="${escapeHtml(item.path || '')}" aria-label="下载" title="打包下载到本地">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/></svg>
      </button>
      ${(folderMode || editMode) ? `
      <button class="card-del" data-series="${escapeHtml(item.name)}" aria-label="删除" title="删除(3天后自动清除)">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14zM10 11v6M14 11v6"/></svg>
      </button>
      ${folderMode ? `
      <button class="card-move" data-series="${escapeHtml(item.name)}" aria-label="移动" title="移动到文件夹">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 9l-3 3 3 3M9 5l3-3 3 3M15 19l-3 3-3-3M19 9l3 3-3 3M2 12h20M12 2v20"/></svg>
      </button>` : ""}
      ` : ""}
    </div>`;
  // 长按卡片 → 直接弹出移动选择 (无需编辑模式)
  let longPressTimer = null, longPressed = false;
  if (folderMode) {
    card.addEventListener("pointerdown", (e) => {
      if (e.button !== undefined && e.button !== 0) return;
      longPressed = false;
      longPressTimer = setTimeout(() => {
        longPressed = true;
        if (navigator.vibrate) navigator.vibrate(30);
        if (editMode) toggleSelect(item);
        else openMoveSheet(item);
      }, 500);
    });
    card.addEventListener("pointerup", () => { clearTimeout(longPressTimer); });
    card.addEventListener("pointerleave", () => { clearTimeout(longPressTimer); });
  }
  // 点击打开 (始终可用)
  card.addEventListener("click", (e) => {
    if (editMode) {
      if (longPressed) { longPressed = false; return; }
      toggleSelect(item); return;
    }
    if (longPressed) { e.preventDefault(); e.stopPropagation(); longPressed = false; return; }
    openItem(item);
  });
  // 下载按钮: 始终可用, 点一下打包当前系列 ZIP 下载到本地
  const dlBtn = card.querySelector(".card-dl");
  if (dlBtn) {
    dlBtn.addEventListener("pointerdown", (e) => e.stopPropagation());
    dlBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      downloadSeries(item);
    });
  }
  // 编辑漫画: 编辑模式/文件夹功能 下显示删除按钮并绑定
  if (folderMode || editMode) {
    const delBtn = card.querySelector(".card-del");
    if (delBtn) {
      delBtn.addEventListener("pointerdown", (e) => e.stopPropagation());
      delBtn.addEventListener("click", async (e) => {
        e.stopPropagation();
        const ok = confirm(`确定删除「${item.name}」吗？
删除后进入回收站，3天后自动清除。`);
        if (!ok) return;
        try {
          const res = await KomiConfig.fetch(`/api/delete/${pathUrl(item)}`, { method: "DELETE" });
          if (res.ok) {
            toast("已删除，3天后自动清除");
            loadLibrary();
          } else {
            const d = await res.json().catch(() => ({}));
            toast("删除失败: " + (d.detail || res.status));
          }
        } catch (e) {
          toast("删除失败: " + e.message);
        }
      });
    }
    const moveBtn = card.querySelector(".card-move");
    if (moveBtn) {
      moveBtn.addEventListener("pointerdown", (e) => e.stopPropagation());
      moveBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        openMoveSheet(item);
      });
    }
  }
  updateSelectState(card, item);
  return card;
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// 用完整相对路径调用 /api/delete/{path}（多层文件夹内也能正确删除），
// 逐段编码，保留 "/" 分隔符，避免把整个路径当成单个 %2F 段。
function pathUrl(item) {
  const raw = item.path || item.name || "";
  return raw.split("/").map(encodeURIComponent).join("/");
}

function timeAgo(ts) {
  const diff = Date.now() - ts;
  if (diff < 60000) return "刚刚";
  if (diff < 3600000) return Math.floor(diff / 60000) + "分钟前";
  if (diff < 86400000) return Math.floor(diff / 3600000) + "小时前";
  if (diff < 604800000) return Math.floor(diff / 86400000) + "天前";
  return new Date(ts).toLocaleDateString();
}

function openItem(item) {
  const base = KomiConfig.serverBase();
  // 使用完整多级路径 (移入文件夹后的漫画/视频需要完整路径)
  const target = item.path || item.name;
  const kind = item.subkind || (item.videos ? "video" : "manga");
  if (kind === "manga") {
    location.href = base + `/reader.html?s=${encodeURIComponent(target)}`;
  } else {
    location.href = base + `/video.html?s=${encodeURIComponent(target)}`;
  }
}

// ---------- Folder navigation ----------
let currentPath = "";

function openFolder(path) {
  currentPath = path;
  if (query) {
    query = "";
    searchResults = null;
    if (search) search.value = "";
  }
  loadLibrary();
}

function renderBreadcrumb() {
  const bc = document.getElementById("breadcrumb");
  if (!bc) return;
  if (query) { bc.innerHTML = ""; return; }
  if (!currentPath) { bc.innerHTML = ""; return; }
  const parts = currentPath.split("/").filter(Boolean);
  let html = `<a class="bc-link" data-depth="-1">根目录</a>`;
  let acc = "";
  parts.forEach((p, i) => {
    acc = acc ? acc + "/" + p : p;
    const sep = i < parts.length - 1 ? "" : "";
    html += `<span class="bc-sep">›</span><a class="bc-link" data-depth="${i}">${escapeHtml(p)}</a>`;
  });
  bc.innerHTML = html;
  bc.querySelectorAll(".bc-link").forEach(a => {
    a.addEventListener("click", () => {
      const d = parseInt(a.dataset.depth, 10);
      const targetParts = parts.slice(0, d + 1);
      currentPath = targetParts.join("/");
      loadLibrary();
    });
  });
}

// ---------- Edit toggle ----------
function bindEditToggle() {
  const btn = document.getElementById("editToggle");
  if (!btn) return;
  btn.addEventListener("click", () => {
    editMode = !editMode;
    document.body.classList.toggle("editing", editMode);
    btn.classList.toggle("active", editMode);
    if (!editMode) { selectedItems.clear(); clipItems = []; }
    render();
  });
}

// ---------- 编辑模式多选 ----------
function toggleSelect(item) {
  const key = item.path || item.name;
  if (selectedItems.has(key)) selectedItems.delete(key);
  else selectedItems.add(key);
  render();
}

function updateSelectState(card, item) {
  if (!editMode) return;
  const key = item.path || item.name;
  if (selectedItems.has(key)) card.classList.add("selected");
  else card.classList.remove("selected");
}

function getSelectedItems() {
  const all = libraryData.series || [];
  const sel = [];
  all.forEach(it => {
    const key = it.path || it.name;
    if (selectedItems.has(key)) sel.push(it);
  });
  return sel;
}

function syncEditBar() {
  const bar = document.getElementById("editBar");
  if (!bar) return;
  const count = selectedItems.size;
  const cnt = document.getElementById("editCount");
  if (cnt) cnt.textContent = count ? `已选 ${count} 项` : "未选择项目";
  const moveBtn = document.getElementById("editMoveBtn");
  const delBtn = document.getElementById("editDelBtn");
  const renameBtn = document.getElementById("editRenameBtn");
  if (moveBtn) moveBtn.disabled = count === 0;
  if (delBtn) delBtn.disabled = count === 0;
  if (renameBtn) renameBtn.disabled = count !== 1;
  bar.classList.toggle("show", editMode);
}

// ---------- Create folder ----------
function bindFolderSheet() {
  const overlay = document.getElementById("folderOverlay");
  const sheet = document.getElementById("folderSheet");
  const name = document.getElementById("folderName");
  const parent = document.getElementById("folderParent");
  const create = document.getElementById("folderCreate");
  const btn = document.getElementById("newFolderBtn");
  const kindBox = document.getElementById("folderKind");
  if (!sheet || !btn) return;

  let selKind = "manga"; // 只有漫画 / 视频两种
  // 类型选择
  if (kindBox) {
    kindBox.querySelectorAll(".type-opt").forEach(opt => {
      opt.addEventListener("click", () => {
        kindBox.querySelectorAll(".type-opt").forEach(o => o.classList.remove("active"));
        opt.classList.add("active");
        selKind = opt.dataset.kind || "manga";
      });
    });
  }

  btn.addEventListener("click", () => {
    parent.value = currentPath;
    name.value = "";
    selKind = "manga";
    if (kindBox) {
      kindBox.querySelectorAll(".type-opt").forEach(o => o.classList.remove("active"));
      const first = kindBox.querySelector('[data-kind="manga"]');
      if (first) first.classList.add("active");
    }
    sheet.classList.add("show");
    overlay.classList.add("show");
    setTimeout(() => name.focus(), 100);
  });
  overlay.addEventListener("click", () => { sheet.classList.remove("show"); overlay.classList.remove("show"); });
  create.addEventListener("click", async () => {
    const n = name.value.trim();
    if (!n) { toast("请输入文件夹名称"); return; }
    try {
      const res = await KomiConfig.fetch("/api/folder", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: n, parent: parent.value.trim(), kind: selKind })
      });
      if (res.ok) {
        toast("文件夹已创建");
        sheet.classList.remove("show");
        overlay.classList.remove("show");
        loadLibrary();
      } else {
        const d = await res.json().catch(() => ({}));
        toast("创建失败: " + (d.detail || res.status));
      }
    } catch (e) {
      toast("创建失败: " + e.message);
    }
  });
}

// ---------- Move sheet ----------
// ---------- Trash (回收站) ----------
function bindTrashSheet() {
  const btn = document.getElementById("trashToggle");
  const overlay = document.getElementById("trashOverlay");
  const sheet = document.getElementById("trashSheet");
  const list = document.getElementById("trashList");
  const count = document.getElementById("trashCount");
  if (!btn || !sheet) return;

  async function open() {
    sheet.classList.add("show");
    overlay.classList.add("show");
    list.innerHTML = `<div style="text-align:center;color:var(--text-2);padding:20px">加载中…</div>`;
    try {
      const res = await KomiConfig.fetch("/api/trash");
      const data = await res.json();
      const items = data.items || [];
      count.textContent = items.length ? `（${items.length} 项）` : "";
      if (!items.length) {
        list.innerHTML = `<div style="text-align:center;color:var(--text-2);padding:20px">回收站为空</div>`;
        return;
      }
      list.innerHTML = "";
      items.forEach(it => {
        const name = it.name;
        const el = document.createElement("div");
        el.className = "move-target";
        el.innerHTML = `<span class="mt-label">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14z"/></svg>
          ${escapeHtml(name)}</span>
          <span style="display:flex;gap:8px;align-items:center">
            <button class="trash-restore" data-name="${escapeHtml(name)}">恢复</button>
            <button class="trash-del" data-name="${escapeHtml(name)}">彻底删除</button>
          </span>`;
        list.appendChild(el);
      });
      list.querySelectorAll(".trash-restore").forEach(b => b.addEventListener("click", () => restore(b.dataset.name)));
      list.querySelectorAll(".trash-del").forEach(b => b.addEventListener("click", () => purge(b.dataset.name)));
    } catch (e) {
      list.innerHTML = `<div style="text-align:center;color:#ff3b30;padding:20px">加载失败: ${escapeHtml(e.message)}</div>`;
    }
  }
  function close() { sheet.classList.remove("show"); overlay.classList.remove("show"); }

  async function restore(name) {
    try {
      const res = await KomiConfig.fetch("/api/trash/restore/" + encodeURIComponent(name), { method: "POST" });
      if (res.ok) { toast("已恢复「" + name + "」"); open(); loadLibrary(); }
      else { const d = await res.json().catch(() => ({})); toast("恢复失败: " + (d.detail || res.status)); }
    } catch (e) { toast("恢复失败: " + e.message); }
  }
  async function purge(name) {
    if (!confirm("彻底删除「" + name + "」？此操作不可恢复。")) return;
    try {
      const res = await KomiConfig.fetch("/api/trash/restore/" + encodeURIComponent(name) + "?purge=1", { method: "POST" });
      if (res.ok) { toast("已彻底删除"); open(); }
      else { const d = await res.json().catch(() => ({})); toast("删除失败: " + (d.detail || res.status)); }
    } catch (e) { toast("删除失败: " + e.message); }
  }

  btn.addEventListener("click", open);
  overlay.addEventListener("click", close);
  const closeBtn = document.getElementById("trashClose");
  if (closeBtn) closeBtn.addEventListener("click", close);
}

function bindMoveSheet() {
  const overlay = document.getElementById("moveOverlay");
  const sheet = document.getElementById("moveSheet");
  const targets = document.getElementById("moveTargets");
  const pasteBtn = document.getElementById("movePaste");
  if (!sheet) return;

  window._moveItem = null;

  // 递归收集所有文件夹 (含层级深度)
  function collectFolders(items, prefix, depth, out) {
    (items || []).forEach(it => {
      if (it.kind === "folder") {
        const path = it.path;
        out.push({ path, name: it.name, depth });
        collectFolders(it.children, path, depth + 1, out);
      }
    });
  }

  function buildTargets(curFolder) {
    let folders = [];
    collectFolders(libraryData.tree || libraryData.series, "", 0, folders);
    targets.innerHTML = `<div class="move-target" data-path=""><span class="mt-label"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg> 根目录</span>${!curFolder ? '<span class="mt-current">当前位置</span>' : ""}</div>`;
    folders.forEach(f => {
      const el = document.createElement("div");
      el.className = "move-target";
      el.dataset.path = f.path;
      const isCur = f.path === curFolder;
      el.style.paddingLeft = (18 + (f.depth || 0) * 18) + "px";
      el.innerHTML = `<span class="mt-label"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg> ${escapeHtml(f.name)}</span><small>${escapeHtml(f.path)}</small>${isCur ? '<span class="mt-current">当前位置</span>' : ""}`;
      if (isCur) { el.classList.add("disabled"); el.addEventListener("click", (e) => e.stopPropagation()); }
      else el.addEventListener("click", () => {
        // vivo 风格: 选择目标文件夹 = 复制到剪贴板，然后点"粘贴到此处"
        window._moveTarget = f.path;
        targets.querySelectorAll(".move-target").forEach(t => t.classList.remove("mt-active"));
        el.classList.add("mt-active");
        if (pasteBtn) { pasteBtn.textContent = `粘贴到「${f.path || "根目录"}」`; pasteBtn.disabled = false; }
      });
      targets.appendChild(el);
    });
  }

  // vivo 风格: 打开移动 → 选择目标文件夹 → 点粘贴 → 执行
  window.openMoveSheet = function (item) {
    window._moveItem = item;
    window._moveTarget = null;
    const curFolder = item.path ? item.path.replace(new RegExp("/" + item.name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "$"), "") : "";
    buildTargets(curFolder);
    if (pasteBtn) { pasteBtn.textContent = "粘贴到此处"; pasteBtn.disabled = true; }
    sheet.classList.add("show");
    overlay.classList.add("show");
  };

  // 批量移动入口 (编辑模式多选)
  window.openMoveSheetBatch = function (selItems) {
    window._moveItem = null;
    window._moveBatch = selItems;
    window._moveTarget = null;
    // 当前所在文件夹
    let curFolder = currentPath;
    buildTargets(curFolder);
    if (pasteBtn) { pasteBtn.textContent = `粘贴 ${selItems.length} 项`; pasteBtn.disabled = true; }
    sheet.classList.add("show");
    overlay.classList.add("show");
  };

  if (pasteBtn) {
    pasteBtn.addEventListener("click", async () => {
      const to = window._moveTarget;
      if (to === undefined || to === null) { toast("请先选择目标文件夹"); return; }
      try {
        let payload;
        if (window._moveBatch && window._moveBatch.length) {
          payload = { items: window._moveBatch.map(it => {
            const p = it.path || "";
            // from = 父目录路径: 若 path 不含 "/" 说明在根目录, from 为空
            const from = p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "";
            return { name: it.name, from: from };
          }), to: to };
        } else if (window._moveItem) {
          const it = window._moveItem;
          const p = it.path || "";
          const from = p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "";
          payload = { name: it.name, from: from, to: to };
        } else { toast("没有待移动项目"); return; }
        const res = await KomiConfig.fetch("/api/move", {
          method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload)
        });
        const d = await res.json().catch(() => ({}));
        if (res.ok || res.status === 200) {
          if (d.errors && d.errors.length) toast("部分移动失败: " + d.errors.join("; "));
          else toast(`已移动到「${to || "根目录"}」`);
          sheet.classList.remove("show"); overlay.classList.remove("show");
          selectedItems.clear(); clipItems = [];
          loadLibrary();
        } else { toast("移动失败: " + (d.detail || res.status)); }
      } catch (e) { toast("移动失败: " + e.message); }
    });
  }

  overlay.addEventListener("click", () => { sheet.classList.remove("show"); overlay.classList.remove("show"); });

  // 全局可复用：供拖放共用 (单条移动)
  window.doMove = async function (name, from, to, onSuccess) {
    try {
      const res = await KomiConfig.fetch("/api/move", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: name, from: from, to: to })
      });
      if (res.ok) {
        if (onSuccess) onSuccess();
      } else {
        const d = await res.json().catch(() => ({}));
        toast("移动失败: " + (d.detail || res.status));
      }
    } catch (e) {
      toast("移动失败: " + e.message);
    }
  };
}

function bindSegments() {
  document.querySelectorAll(".seg").forEach(seg => {
    seg.addEventListener("click", () => {
      const nextFilter = seg.dataset.filter;
      filter = nextFilter;
      // 文件夹功能仅漫画/视频分类启用；"全部"隐藏文件夹与新建按钮
      folderMode = filter !== "all";
      document.body.classList.toggle("filter-all", filter === "all");
      document.body.classList.toggle("show-folders", folderMode);
      if (!editMode) { selectedItems.clear(); clipItems = []; }

      // 「漫画 / 视频」直接对应根目录的 comic / video 文件夹
      if (filter === "manga" || filter === "video") {
        const target = filter === "manga" ? "comic" : "video";
        const rootTree = libraryData.tree || [];
        const found = rootTree.find(
          it => it.kind === "folder" && (it.name || "").toLowerCase() === target
        );
        if (found) {
          currentPath = found.path;
        } else {
          currentPath = "";
          toast("没有找到 " + target + " 文件夹，可先新建一个");
        }
      } else {
        currentPath = "";
      }
      loadLibrary();
    });
  });
}

/** 根据当前路径点亮对应的分类 Tab */
function syncSegments() {
  const section = currentPath === "comic" || currentPath.startsWith("comic/")
    ? "manga"
    : currentPath === "video" || currentPath.startsWith("video/")
      ? "video"
      : "all";
  document.querySelectorAll(".seg").forEach(s => {
    s.classList.toggle("active", s.dataset.filter === section);
  });
}

// ---------- 编辑模式操作栏 (vivo 风格移动 + 删除 + 重命名) ----------
function bindEditBar() {
  const bar = document.getElementById("editBar");
  if (!bar) return;
  const moveBtn = document.getElementById("editMoveBtn");
  const delBtn = document.getElementById("editDelBtn");
  const renameBtn = document.getElementById("editRenameBtn");

  // 移动: 收集选中项 → 打开移动弹窗(目录树)
  if (moveBtn) moveBtn.addEventListener("click", () => {
    const sel = getSelectedItems();
    if (!sel.length) { toast("请先选择要移动的项目"); return; }
    openMoveSheetBatch(sel);
  });

  // 重命名: 单选时可用
  if (renameBtn) renameBtn.addEventListener("click", () => {
    const sel = getSelectedItems();
    if (sel.length !== 1) { toast("重命名需选中单个项目"); return; }
    openRenameSheet(sel[0]);
  });

  // 删除: 批量删除
  if (delBtn) delBtn.addEventListener("click", async () => {
    const sel = getSelectedItems();
    if (!sel.length) { toast("请先选择要删除的项目"); return; }
    if (!confirm(`确定删除选中的 ${sel.length} 项吗？
删除后进入回收站，3天后自动清除。`)) return;
    let okCount = 0, errs = [];
    for (const it of sel) {
      try {
        const res = await KomiConfig.fetch(`/api/delete/${pathUrl(it)}`, { method: "DELETE" });
        if (res.ok) okCount++;
        else { const d = await res.json().catch(() => ({})); errs.push(it.name + ": " + (d.detail || res.status)); }
      } catch (e) { errs.push(it.name + ": " + e.message); }
    }
    toast(okCount ? `已删除 ${okCount} 项` : "删除失败");
    selectedItems.clear();
    loadLibrary();
  });
}

// ---------- 重命名 ----------
function openRenameSheet(item) {
  const overlay = document.getElementById("renameOverlay");
  const sheet = document.getElementById("renameSheet");
  const input = document.getElementById("renameInput");
  const save = document.getElementById("renameSave");
  if (!sheet) return;
  input.value = item.name;
  sheet.classList.add("show");
  overlay.classList.add("show");
  setTimeout(() => { input.focus(); input.select(); }, 100);
  const doRename = async () => {
    const newName = input.value.trim();
    if (!newName) { toast("请输入新名称"); return; }
    if (newName === item.name) { closeRename(); return; }
    try {
      const from = item.path ? item.path.replace("/" + item.name, "") : "";
      const res = await KomiConfig.fetch("/api/rename", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: item.name, from: from, newName: newName })
      });
      if (res.ok) {
        toast("已重命名");
        closeRename();
        selectedItems.clear();
        loadLibrary();
      } else { const d = await res.json().catch(() => ({})); toast("重命名失败: " + (d.detail || res.status)); }
    } catch (e) { toast("重命名失败: " + e.message); }
  };
  const closeRename = () => { sheet.classList.remove("show"); overlay.classList.remove("show"); };
  save.onclick = doRename;
  input.onkeydown = (e) => { if (e.key === "Enter") doRename(); };
  overlay.onclick = closeRename;
}

// ---------- PDF 导入 ----------
function bindPdfImport() {
  const btn = document.getElementById("importToggle");
  const overlay = document.getElementById("pdfOverlay");
  const sheet = document.getElementById("pdfSheet");
  const file = document.getElementById("pdfFile");
  const name = document.getElementById("pdfName");
  const folder = document.getElementById("pdfFolder");
  const importBtn = document.getElementById("pdfImport");
  if (!btn || !sheet) return;

  let dpi = 200;
  const dpiBox = document.getElementById("pdfDpi");
  if (dpiBox) {
    dpiBox.querySelectorAll(".type-opt").forEach(opt => {
      opt.addEventListener("click", () => {
        dpiBox.querySelectorAll(".type-opt").forEach(o => o.classList.remove("active"));
        opt.classList.add("active");
        dpi = parseInt(opt.dataset.dpi || "200", 10);
      });
    });
  }

  btn.addEventListener("click", () => {
    file.value = ""; name.value = ""; folder.value = currentPath;
    sheet.classList.add("show"); overlay.classList.add("show");
  });
  overlay.addEventListener("click", () => { sheet.classList.remove("show"); overlay.classList.remove("show"); });

  importBtn.addEventListener("click", async () => {
    const f = file.files && file.files[0];
    if (!f) { toast("请选择 PDF 文件"); return; }
    if (f.type && !f.type.includes("pdf") && !f.name.toLowerCase().endsWith(".pdf")) {
      toast("仅支持 PDF 文件"); return;
    }
    importBtn.disabled = true;
    importBtn.textContent = "导入中…";
    try {
      const fd = new FormData();
      fd.append("file", f);
      fd.append("name", name.value.trim());
      fd.append("folder", folder.value.trim());
      // dpi 通过 query 传给后端 (后端用固定 200 也可后续调)
      const res = await KomiConfig.fetch("/api/import/pdf?dpi=" + dpi, { method: "POST", body: fd });
      const d = await res.json().catch(() => ({}));
      if (res.ok) {
        toast(`已导入「${d.series}」${d.pages}页`);
        sheet.classList.remove("show"); overlay.classList.remove("show");
        loadLibrary();
      } else { toast("导入失败: " + (d.detail || res.status)); }
    } catch (e) {
      toast("导入失败: " + e.message);
    } finally {
      importBtn.disabled = false;
      importBtn.textContent = "开始导入";
    }
  });
}

// ---------- Server settings sheet ----------
function bindServerSheet() {
  const sheet = document.getElementById("sheet");
  const overlay = document.getElementById("sheetOverlay");
  const serverToggle = document.getElementById("serverToggle");
  const serverInput = document.getElementById("serverInput");
  const serverTest = document.getElementById("serverTest");
  const serverScan = document.getElementById("serverScan");
  const serverSave = document.getElementById("serverSave");
  const serverStatus = document.getElementById("serverStatus");
  const scanResults = document.getElementById("scanResults");
  if (!sheet) return;

  function open() {
    serverInput.value = KomiConfig.serverBase();
    serverStatus.textContent = "";
    serverStatus.className = "server-status";
    scanResults.innerHTML = "";
    sheet.classList.add("show");
    overlay.classList.add("show");
  }
  function close() {
    sheet.classList.remove("show");
    overlay.classList.remove("show");
  }

  serverToggle.addEventListener("click", open);
  overlay.addEventListener("click", close);

  function normAddr(v) {
    v = (v || "").trim().replace(/\/+$/, "");
    if (!v) return "";
    if (!/^https?:\/\//i.test(v)) v = "http://" + v;
    return v;
  }

  async function test(addr) {
    const url = normAddr(addr);
    serverStatus.textContent = "测试中…";
    serverStatus.className = "server-status";
    const probeBase = url || "";
    try {
      const ctl = new AbortController();
      const t = setTimeout(() => ctl.abort(), 5000);
      const res = await window.fetch(probeBase + "/api/ping", { signal: ctl.signal });
      clearTimeout(t);
      const data = await res.json();
      if (res.ok && data.status === "ok") {
        serverStatus.textContent = `连接成功 · ${data.app} v${data.version}`;
        serverStatus.className = "server-status ok";
        return true;
      }
      throw new Error("bad response");
    } catch (e) {
      serverStatus.textContent = "连接失败，请检查地址与服务";
      serverStatus.className = "server-status err";
      return false;
    }
  }

  serverTest.addEventListener("click", () => test(serverInput.value));

  serverSave.addEventListener("click", async () => {
    const addr = normAddr(serverInput.value);
    if (addr) {
      const ok = await test(addr);
      if (!ok) { toast("未保存：无法连接到该服务器"); return; }
    }
    KomiConfig.setServer(addr);
    toast(addr ? "已保存服务器地址，正在刷新…" : "已切换回当前站点");
    setTimeout(() => location.reload(), 600);
  });

  serverScan.addEventListener("click", async () => {
    const port = parseInt((serverInput.value.match(/:(\d+)$/) || [])[1] || "8000", 10);
    serverScan.disabled = true;
    serverScan.textContent = "扫描中…";
    scanResults.innerHTML = "";
    try {
      const res = await KomiConfig.fetch(`/api/scan?port=${port}`);
      const data = await res.json();
      const nodes = data.nodes || [];
      if (!nodes.length) {
        scanResults.innerHTML = `<div class="scan-result-item" style="justify-content:center">未发现可用节点</div>`;
      } else {
        scanResults.innerHTML = "";
        nodes.forEach(n => {
          const it = document.createElement("div");
          it.className = "scan-result-item";
          it.innerHTML = `<span><span class="dot"></span>${n.host}:${n.port}</span><span>点此使用</span>`;
          it.addEventListener("click", () => {
            serverInput.value = `http://${n.host}:${n.port}`;
            test(serverInput.value);
          });
          scanResults.appendChild(it);
        });
      }
    } catch (e) {
      scanResults.innerHTML = `<div class="scan-result-item" style="justify-content:center">扫描失败（需在局域网内）</div>`;
    } finally {
      serverScan.disabled = false;
      serverScan.textContent = "扫描局域网";
    }
  });
}

// ---------- 打包下载到本地 ----------
async function downloadSeries(item) {
  const target = item.path || item.name;
  try {
    toast("正在打包 " + item.name + " …");
    const res = await KomiConfig.fetch(`/api/export?target=${encodeURIComponent(target)}`);
    if (!res.ok) {
      const d = await res.json().catch(() => ({}));
      toast("下载失败: " + (d.detail || res.status));
      return;
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = (item.name || "series").replace(/[\\/:*?"<>|]/g, "_") + ".zip";
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    toast("已开始下载 " + item.name + ".zip");
  } catch (e) {
    toast("下载失败: " + e.message);
  }
}

// ---------- Init ----------
document.addEventListener("DOMContentLoaded", () => {
  initTheme();
  bindSegments();
  bindServerSheet();
  bindEditToggle();
  bindEditBar();
  bindFolderSheet();
  bindMoveSheet();
  bindTrashSheet();
  bindPdfImport();
  // 默认启用文件夹功能 (漫画/视频分类)，"全部"隐藏
  folderMode = filter !== "all";
  document.body.classList.toggle("filter-all", filter === "all");
  document.body.classList.toggle("show-folders", folderMode);
  if (search) {
    // 递归搜索：文件夹名、系列名、媒体文件名（由服务端 /api/search 匹配）
    search.addEventListener("input", debounce(async e => {
      query = e.target.value.trim();
      if (!query) {
        searchResults = null;
        render();
        return;
      }
      const seq = ++searchSeq;
      try {
        const res = await KomiConfig.fetch("/api/search?q=" + encodeURIComponent(query));
        const data = await res.json();
        if (seq !== searchSeq) return;
        searchResults = data.items || [];
      } catch (err) {
        if (seq !== searchSeq) return;
        searchResults = [];
      }
      render();
    }, 260));
  }
  if (grid) loadLibrary();
});
