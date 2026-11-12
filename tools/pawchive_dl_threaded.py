#!/usr/bin/env python3
"""pawchive 多线程下载器 - 作者/帖子/图片 三级结构 v3
用法: python3 pawchive_dl_threaded.py <service> <uid> <author> <base_dir> [max_posts] [threads] [delay]
优化: 多线程并发下载图片 + 断点续传(跳过已存在文件) + 代理支持
"""
import urllib.request, json, time, re, os, sys
from concurrent.futures import ThreadPoolExecutor, as_completed

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
API = 'https://pawchive.pw/api/v1'
IMG = 'https://img.pawchive.pw/thumbnail/data'
PROXY = None  # e.g. 'http://127.0.0.1:7890'

def _opener():
    op = urllib.request.build_opener()
    if PROXY:
        op.add_handler(urllib.request.ProxyHandler({'http': PROXY, 'https': PROXY}))
    return op

def gj(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': 'application/json'})
    try:
        with _opener().open(req, timeout=25) as r:
            return json.loads(r.read())
    except Exception:
        return None

def safe_name(s):
    s = re.sub(r'[\\/:*?"<>|\n\r\t]', '_', str(s))
    s = re.sub(r'_+', '_', s).strip('._')
    return s[:80] or 'untitled'

def thumb_url(path):
    if not path: return None
    p = path.lstrip('/')
    parts = p.split('/')
    base = parts[-1] if parts else ''
    ext = '.png'
    if '.' in base:
        real = base.rsplit('.', 1)[1].lower()
        if real in ('jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp'):
            ext = '.' + real
    h = base.split('.')[0]
    if len(h) < 4: return None
    return f'{IMG}/{h[:2]}/{h[2:4]}/{h}{ext}'

def fetch(url, timeout=30):
    """下载并校验图片，返回 bytes 或 None。跳过已存在文件由调用方处理。"""
    try:
        req = urllib.request.Request(url, headers={'User-Agent': UA, 'Referer': 'https://pawchive.pw/'})
        with _opener().open(req, timeout=timeout) as r:
            data = r.read()
        if len(data) < 1000: return None
        is_jpg = data[:2] == b'\xff\xd8'
        is_png = data[:8] == b'\x89PNG\r\n\x1a\n'
        is_webp = data[:4] == b'RIFF' and data[8:12] == b'WEBP'
        is_gif = data[:3] == b'GIF'
        if not (is_jpg or is_png or is_webp or is_gif): return None
        return data
    except Exception:
        return None

def _dl_one(url, dest, ok, fail, lock):
    """下载单图到 dest，断点续传：已存在跳过。"""
    if os.path.exists(dest) and os.path.getsize(dest) > 1000:
        with lock:
            ok[0] += 1
        return
    data = fetch(url)
    with lock:
        if data:
            try:
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                open(dest, 'wb').write(data)
                ok[0] += 1
            except Exception:
                fail[0] += 1
        else:
            fail[0] += 1

def download_user(svc, uid, author, base_dir, max_posts=None, threads=8, dly=0.2):
    a_dir = os.path.join(base_dir, author)
    os.makedirs(a_dir, exist_ok=True)
    ok = [0]; fail = [0]
    lock = __import__('threading').Lock()
    posts = 0
    seen_ids = set()   # 帖子ID去重
    page = 0           # 从 o=0 开始, 否则漏首页
    # 已有目录的标题集合: 断点续传时按标题跳过, 避免编号不同导致重复
    a_dir_existing = set()
    a_dir = os.path.join(base_dir, author)
    os.makedirs(a_dir, exist_ok=True)
    for _e in os.listdir(a_dir):
        _num, _sep, _title = _e.partition('_')
        if _sep and _title:
            a_dir_existing.add(_title)
    while page <= 40:
        # pawchive 真实分页参数是 o (offset), 每页50条; page 参数已被服务端忽略
        d = gj(f'{API}/{svc}/user/{uid}?o={page * 50}')
        if not d or len(d) == 0: break
        # 去重: API 分页失效时会返回重复页, 命中重复页则停止翻页
        page_ids = set(str(x.get('id')) for x in d if x.get('id'))
        if page_ids and page_ids <= seen_ids:
            repeat_pages += 1
            if repeat_pages >= 1:
                break
            continue
        repeat_pages = 0
        seen_ids |= page_ids
        # 收集本页所有待下载URL
        tasks = []
        for p in d:
            if max_posts and posts >= max_posts: break
            title = safe_name(p.get('title', ''))
            # 断点续传: 标题已存在则跳过(避免重复目录)
            if title in a_dir_existing:
                continue
            posts += 1
            a_dir_existing.add(title)
            pdir = os.path.join(a_dir, f'{posts:04d}_{title}')
            # 主图
            f = p.get('file') or {}
            if f.get('path'):
                url = thumb_url(f['path'])
                if url:
                    dest = os.path.join(pdir, safe_name(f.get('name', 'main.png')) or 'main.png')
                    tasks.append((url, dest))
            # 附件(跳过压缩包)
            for a in (p.get('attachments') or []):
                if not a.get('path'): continue
                aname = str(a.get('name', ''))
                if aname.lower().endswith(('.zip', '.7z', '.rar', '.tar', '.gz')): continue
                url = thumb_url(a['path'])
                if url:
                    dest = os.path.join(pdir, safe_name(aname or 'attach.png'))
                    tasks.append((url, dest))
            # content内嵌
            c = p.get('content') or ''
            for i, u in enumerate(re.findall(r'<img[^>]+src="([^"]+)"', c)):
                m = re.search(r'/([0-9a-f]{4,})\.(png|jpg|jpeg|webp|gif)', u, re.I)
                if m:
                    h = m.group(1)
                    ext = '.' + (m.group(2).lower() if m.group(2).lower() != 'jpeg' else 'jpg')
                    url = f'{IMG}/{h[:2]}/{h[2:4]}/{h}{ext}'
                    tasks.append((url, os.path.join(pdir, f'content_{i:02d}{ext}')))
        # 多线程并发
        if tasks:
            with ThreadPoolExecutor(max_workers=threads) as ex:
                futs = [ex.submit(_dl_one, u, d_, ok, fail, lock) for u, d_ in tasks]
                for _ in as_completed(futs):
                    pass
        if max_posts and posts >= max_posts: break
        if len(d) < 50: break
        page += 1
        time.sleep(dly)
    return ok[0], fail[0], posts

if __name__ == '__main__':
    svc, uid, author = sys.argv[1], sys.argv[2], sys.argv[3]
    base = sys.argv[4]
    mp = int(sys.argv[5]) if len(sys.argv) > 5 else None
    th = int(sys.argv[6]) if len(sys.argv) > 6 else 8
    dly = float(sys.argv[7]) if len(sys.argv) > 7 else 0.2
    if len(sys.argv) > 8 and sys.argv[8]:
        PROXY = sys.argv[8]
    t0 = time.time()
    ok, fail, posts = download_user(svc, uid, author, base, mp, th, dly)
    print(f'RESULT {author}: {posts}帖 OK:{ok} FAIL:{fail} 耗时{time.time()-t0:.0f}s', flush=True)
