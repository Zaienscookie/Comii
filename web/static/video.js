"use strict";

const params = new URLSearchParams(location.search);
const series = params.get("s") || "";
const video = document.getElementById("video");
const title = document.getElementById("title");
const listToggle = document.getElementById("listToggle");
const videoList = document.getElementById("videoList");
const backBtn = document.getElementById("backBtn");

const THEME_KEY = "komi-theme";
const root = document.documentElement;
function applyTheme(t) {
  if (t === "auto") root.dataset.theme = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  else root.dataset.theme = t;
}
let theme = localStorage.getItem(THEME_KEY) || "auto";
applyTheme(theme);

let videos = [];
let current = 0;

backBtn.addEventListener("click", () => history.back());

listToggle.addEventListener("click", () => videoList.classList.toggle("open"));

async function init() {
  title.textContent = series;
  try {
    const res = await KomiConfig.fetch(`/api/series/${encodeURIComponent(series)}`);
    const data = await res.json();
    videos = data.videos;
    if (!videos.length) {
      title.textContent = "没有视频";
      return;
    }
    renderList();
    play(0);
  } catch (e) {
    title.textContent = "加载失败";
  }
}

function renderList() {
  videoList.innerHTML = "";
  videos.forEach((name, i) => {
    const item = document.createElement("div");
    item.className = "video-list-item" + (i === current ? " active" : "");
    item.innerHTML = `<span class="num">${i + 1}</span><span class="name">${escapeHtml(name)}</span>`;
    item.addEventListener("click", () => play(i));
    videoList.appendChild(item);
  });
}

function play(i) {
  current = i;
  video.src = KomiConfig.url(`/api/video/${encodeURIComponent(series)}/${encodeURIComponent(videos[i])}`);
  video.play().catch(() => {});
  videoList.classList.remove("open");
  renderList();
  // keep title short
  title.textContent = videos[i].length > 24 ? videos[i].slice(0, 24) + "…" : videos[i];
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// auto next episode
video.addEventListener("ended", () => {
  if (current < videos.length - 1) play(current + 1);
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") backBtn.click();
});

init();
