(function () {
  const path = new URLSearchParams(location.search).get("p") || "";
  const pages = document.getElementById("pdfPages");
  const title = document.getElementById("pdfTitle");
  const count = document.getElementById("pdfCount");

  document.getElementById("backBtn").addEventListener("click", () => {
    if (document.referrer && history.length > 1) history.back();
    else location.href = "/";
  });

  if (!path) {
    title.textContent = "缺少 PDF 路径";
    return;
  }
  title.textContent = decodeURIComponent(path.split("/").pop() || "PDF").replace(/\.pdf$/i, "");

  const grid = document.getElementById("pdfGrid");
  document.getElementById("gridBtn").addEventListener("click", () => {
    grid.classList.toggle("hidden");
  });

  async function load() {
    try {
      const res = await KomiConfig.fetch("/api/pdf/info?path=" + encodeURIComponent(path));
      const info = await res.json();
      if (!res.ok) throw new Error(info.detail || res.status);
      const total = info.pages || 0;
      count.textContent = total + " 页";
      grid.innerHTML = "";
      for (let i = 0; i < total; i++) {
        const cell = document.createElement("div");
        cell.className = "g";
        cell.innerHTML = `<img loading="lazy" src="/api/pdf/page?path=${encodeURIComponent(path)}&page=${i}&w=200" alt=""><span>${i + 1}</span>`;
        cell.addEventListener("click", () => {
          grid.classList.add("hidden");
          const el = pages.children[i];
          if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
        });
        grid.appendChild(cell);
      }
      for (let i = 0; i < total; i++) {
        const img = document.createElement("img");
        img.className = "pdf-page";
        img.loading = i < 2 ? "eager" : "lazy";
        img.decoding = "async";
        img.src = "/api/pdf/page?path=" + encodeURIComponent(path) + "&page=" + i + "&w=1600";
        img.addEventListener("error", () => img.classList.add("failed"));
        pages.appendChild(img);
      }
    } catch (err) {
      count.textContent = "";
      title.textContent = "无法打开 PDF：" + (err.message || err);
    }
  }
  load();
})();
