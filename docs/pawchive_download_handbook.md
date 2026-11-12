# pawchive 资源下载交接手册

> 本手册用于在**你自己的本地计算机**上下载 pawchive 的漫画图片和视频资源。
> 目标作者共 **18 个**（详见作者清单），下载后建议部署到局域网网盘/漫画系统。

---

## 一、平台说明

**pawchive.pw** 是一个漫画/同人资源镜像站，把 Patreon / Fanbox 等平台的创作者帖子镜像过来。

- **API 基础地址**：`https://pawchive.pw/api/v1`
- **图片 CDN**：`https://img.pawchive.pw/thumbnail/data`
- **作者列表 API**：`GET /api/v1/{service}/user/{uid}?page={page}`
  - `service`：`patreon` 或 `fanbox`
  - `uid`：创作者的用户 ID
  - `page`：页码，每页 50 条，最多 40 页

### 请求头要求
```
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36
Accept: application/json
```

> ⚠️ 重要：这个站有 Cloudflare 反爬（"Are you a bot" 403 页面）。
> - **图片下载**：必须带完整浏览器头（Referer: `https://pawchive.pw/`）
> - **视频包下载**：必须用**完整 Chrome 头**（含 sec-ch-ua、Sec-Fetch-* 等），并在下载瞬间实时取 `temp_url`。用简化头会返回 376 字节的 403 页面。

---

## 二、作者清单（18 个）

| # | 作者名 | 平台 | UID | 帖子数 |
|---|---|---|---|---|
| 1 | maxmuscle3D | patreon | 65467931 | 750 |
| 2 | Evil Rakashaka Daddy Tagore | patreon | 210984252 | 44 |
| 3 | bontiage | patreon | 3537817 | 750 |
| 4 | Otsukimi | patreon | 5535108 | 750 |
| 5 | eljotafurro | patreon | 75879791 | 750 |
| 6 | MOO2 | fanbox | 90970116 | 750 |
| 7 | Alec | patreon | 17838557 | 37 |
| 8 | Nighdruth Animations | patreon | 20077238 | 750 |
| 9 | チヂメタロウ | fanbox | 2967817 | 750 |
| 10 | Slippery Squeaks | patreon | 5044724 | 750 |
| 11 | Kurtcbrox | patreon | 10803850 | 750 |
| 12 | ShimaTabi | patreon | 2673756 | 750 |
| 13 | Freschrenders | patreon | 15675318 | 750 |
| 14 | rodneybear | patreon | 9678554 | 750 |
| 15 | ZillaStudio | patreon | 18242065 | 750 |
| 16 | Lost Proxy | patreon | 132964382 | 750 |
| 17 | El Skelly Traquina | patreon | 169421863 | 15 |
| 18 | craw | patreon | 96211692 | 750 |

---

## 三、图片下载方法（Python 脚本）

### 3.1 获取作者的帖子列表（分页）
```python
import urllib.request, json

API = 'https://pawchive.pw/api/v1'
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

def get_posts(service, uid, page=1):
    url = f'{API}/{service}/user/{uid}?page={page}'
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': 'application/json'})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read())
```

### 3.2 图片缩略图 URL 生成
```python
IMG = 'https://img.pawchive.pw/thumbnail/data'

def thumb_url(path):
    """根据 file/attachment 的 path 字段生成缩略图 URL"""
    if not path: return None
    p = path.lstrip('/')
    parts = p.split('/')
    h = parts[-1].split('.')[0] if parts else ''
    if len(h) < 4: return None
    xx, yy = h[:2], h[2:4]
    return f'{IMG}/{xx}/{yy}/{h}.png'
```

### 3.3 下载图片（带 Referer）
```python
def fetch_image(url, timeout=30):
    req = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Referer': 'https://pawchive.pw/'
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = r.read()
    # 校验图片头
    if data[:2] == b'\xff\xd8' or data[:8] == b'\x89PNG\r\n\x1a\n' or \
       (data[:4] == b'RIFF' and data[8:12] == b'WEBP') or data[:3] == b'GIF':
        return data
    return None
```

### 3.4 完整下载一个作者（三级结构：作者→帖子→图片）
```python
import os, re, time

def download_author(service, uid, author, base_dir, max_posts=None, delay=0.4):
    a_dir = os.path.join(base_dir, author)
    os.makedirs(a_dir, exist_ok=True)
    posts = 0
    for page in range(1, 41):
        data = get_posts(service, uid, page)
        if not data or len(data) == 0:
            break
        for p in data:
            if max_posts and posts >= max_posts:
                return
            posts += 1
            title = re.sub(r'[\\/:*?"<>|\n\r\t]', '_', str(p.get('title', '')))[:80]
            pdir = os.path.join(a_dir, f'{posts:04d}_{title}')
            os.makedirs(pdir, exist_ok=True)
            # 主图
            f = p.get('file')
            if f and f.get('path'):
                url = thumb_url(f['path'])
                if url:
                    data = fetch_image(url)
                    if data:
                        open(os.path.join(pdir, f.get('name', 'main.png')), 'wb').write(data)
            # 附件
            for a in (p.get('attachments') or []):
                if not a.get('path'): continue
                aname = str(a.get('name', ''))
                if aname.lower().endswith(('.zip', '.7z', '.rar', '.tar', '.gz')): continue
                url = thumb_url(a['path'])
                if url:
                    data = fetch_image(url)
                    if data:
                        open(os.path.join(pdir, aname or 'attach.png'), 'wb').write(data)
            # content 内嵌图片
            content = p.get('content') or ''
            for i, u in enumerate(re.findall(r'<img[^>]+src="([^"]+)"', content)):
                m = re.search(r'/([0-9a-f]{4,})\.(?:png|jpg|jpeg|webp|gif)', u)
                if m:
                    h = m.group(1)
                    url = f'{IMG}/{h[:2]}/{h[2:4]}/{h}.png'
                    data = fetch_image(url)
                    if data:
                        open(os.path.join(pdir, f'content_{i:02d}.png'), 'wb').write(data)
            time.sleep(delay)
        if len(data) < 50:
            break
        time.sleep(delay)
```

### 3.5 使用示例
```python
# 下载 maxmuscle3D (patreon/65467931) 到 ./downloads/
download_author('patreon', '65467931', 'maxmuscle3D', './downloads/', max_posts=750)
```

---

## 四、视频下载方法

视频是**打包压缩文件**（.7z / .zip），需要通过 API 拿临时下载链接。

### 4.1 获取帖子中的视频/压缩包临时链接
视频包通常以 `.7z` / `.zip` 附件或 content 里的链接出现。关键是**下载瞬间实时取 temp_url**（链接会过期）。

```python
# 从帖子数据里找视频/压缩包，拿到临时 URL 后立即下载
# 注意：temp_url 可能有时效，必须下载那一刻重新获取
def get_video_temp_url(post):
    """根据帖子内容解析视频/压缩包的真实下载链接"""
    # 附件里的压缩包
    for a in (post.get('attachments') or []):
        if a.get('path','').lower().endswith(('.zip', '.7z', '.rar', '.tar', '.gz')):
            # 用 API 获取该附件的 temp_url
            return a['path']  # 需替换为实际 temp_url 获取逻辑
    return None
```

### 4.2 下载视频包的完整请求头（关键！）
```python
FULL_CHROME_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9',
    'Accept-Encoding': 'gzip, deflate, br',
    'Referer': 'https://pawchive.pw/',
    'Connection': 'keep-alive',
    'sec-ch-ua': '"Chromium";v="120", "Google Chrome";v="120", "Not?A_Brand";v="24"',
    'sec-ch-ua-mobile': '?0',
    'sec-ch-ua-platform': '"Windows"',
    'Sec-Fetch-Dest': 'document',
    'Sec-Fetch-Mode': 'navigate',
    'Sec-Fetch-Site': 'same-origin',
    'Sec-Fetch-User': '?1',
    'Upgrade-Insecure-Requests': '1',
}

# ⚠️ 视频包必须：
# 1. 用 FULL_CHROME_HEADERS（完整头）
# 2. 在下载瞬间实时获取 temp_url（不要用缓存）
```

---

## 五、Cloudflare 反爬处理（如果遇到 403）

如果直接请求被 Cloudflare 拦（返回 "Are you a bot" 403 页面），需要：

1. **方案A：无头浏览器（playwright）过 CF**
   ```python
   # pip install playwright && playwright install chromium
   from playwright.sync_api import sync_playwright
   with sync_playwright() as p:
       browser = p.chromium.launch()
       page = browser.new_page()
       page.goto('https://pawchive.pw/')
       # 拿到 cookies 后再用 requests 下载
       cookies = page.context.cookies()
   ```

2. **方案B：完整 Chrome 头 + 直接请求**（视频包场景已实测有效）

3. **方案C：代理**（如果被墙，走 clash 等代理，端口如 127.0.0.1:7890）

---

## 六、部署建议（下载后）

下载后建议的结构（用于漫画系统）：
```
漫画库根目录/
├── 作者名/
│   ├── 0001_帖子标题/
│   │   ├── main.png
│   │   ├── attach.png
│   │   └── content_00.png
│   ├── 0002_另一个帖子/
│   └── ...
└── 另一个作者/
```

- 图片建议存为 jpg/png/webp
- 视频包（.7z/.zip）建议解压后放对应帖子目录，或单独视频库
- 漫画系统会扫描「作者→帖子→文件」三级结构

---

## 七、注意事项

1. **不要用简化 User-Agent**（会 403），必须完整头
2. **temp_url 会过期**，视频包下载要实时取
3. **请求不要太快**：每个请求之间 sleep 0.4s 以上，避免被封
4. **帖子 API 每页 50 条，最多 40 页**（即单作者最多约 2000 帖）
5. **图片是缩略图**（thumbnail），如果要原图需另找原图 URL
6. **部分帖子是纯视频帖**（无缩略图），下载时跳过即可

---

*手册版本：v1.0（2026-11-11）*
