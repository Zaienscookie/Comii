# Comii 2.25.0：阅读清晰度、预加载、标签与相册封面

> 版本：服务端 / App 2.25.0（App versionCode 33）
> 日期：2026-11-13
> 部署位置：绿联 NAS（`laner`，aarch64），Docker 容器 `comii`

## 1. 为什么改

通过 Tailscale 远程看漫画时带宽有限，原来阅读器只取原图，翻页会明显卡顿；
另外库里内容多了以后，只靠文件夹不好分类。2.25.0 因此加了：

- 阅读清晰度（服务端按宽度缩放后再传）
- 可配置的向后预加载页数
- 自定义标签（可复用历史标签，并按标签浏览）
- 给文件夹（相册）单独设置封面

## 2. 阅读清晰度

### 服务端

新增 `GET /api/v1/media/view?path=&name=&w=1080`：

| 参数 | 说明 |
|---|---|
| `path` / `name` | 与 `/media/image` 相同 |
| `w` | 目标宽度，服务端按 240/480/720/1080/1440/1920 档位取整；`0` 表示原图 |

行为：

- 只缩小不放大：原图比目标宽度小就直接返回原图；
- 结果 JPEG 缓存在 `/data/cache/<宽度>/<hash>.jpg`，同一页同一宽度只转一次；
- 响应带 `Cache-Control: public, max-age=604800`，App 端 Coil 也会落盘缓存。

实测（R18 内一页 2000px 左右的 JPG）：原图 468 KB → `w=720` 191 KB。

### App

设置 → 阅读 → **图片清晰度**：原图 / 高清(1440) / 标准(1080，默认) / 流畅(720) / 省流(480)。
阅读器内「阅读设置」弹层里也能直接切换，切换后立刻按新宽度重新取图。

## 3. 预加载

设置 → 阅读 → **预加载页数**：关闭 / 3 页 / 5 页（默认）/ 8 页。

阅读器在页码变化时会把「当前页前 1 页 + 后 N 页」的图片通过 Coil 预取到内存 / 磁盘缓存，
快速翻页时不用等网络。宽度变化或清晰度切换会重新预取。

## 4. 标签

### 服务端

标签存在 `/data/tags.json`（唯一一处不在文件系统里的元数据），原子写入。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/tags` | 所有标签 + 使用次数，用于「历史标签」建议 |
| GET | `/api/v1/tags/series?path=` | 某个系列的标签 |
| PUT | `/api/v1/tags` | `{path, tags:[...]}`，空数组即清除 |
| GET | `/api/v1/tagged?tag=` | 带该标签的系列列表（返回 LibraryNode） |

`GET /api/v1/series` 的返回值多了 `tags` 字段，App 打开系列页不用再发一次请求。

### App

- 系列页标题下方显示标签，点「+ 标签 / 编辑标签」打开编辑框；
- 编辑框里可以直接输入新标签，也可以点「历史标签」里已有的标签复用；
- 媒体库顶部「全部 / 漫画 / 视频」右侧的标签按钮进入**标签浏览**：
  列出所有标签和使用次数，点标签列出对应系列。

## 5. 相册封面

文件夹（作者 / 相册）默认取第一个子系列的封面。现在可以在文件夹里长按任意系列：
「设为相册封面」，服务端在文件夹里写一个 `.comii_album_cover`（内容是子系列相对路径），
`browse` / WebUI `/api/tree` 都会优先用它。文件夹卡片上长按文件夹本身可以「恢复默认封面」。

R18 是只读挂载，在里面设置封面会返回
`400 这个文件夹是只读的，无法设置封面`，不会报“内部错误”。

## 6. 部署记录（NAS）

服务端代码镜像化，不挂源码。这次用叠加镜像，避免在 NAS 上重新装 Python 依赖：

```dockerfile
# deploy/Dockerfile.overlay
FROM comii:2.24.0-arm64
COPY server /app/server
COPY web /app/web
```

```bash
cd /volume1/docker/comii-src
sudo docker build -f deploy/Dockerfile.overlay -t comii:2.25.0-arm64 .
sudo docker rm -f comii
sudo docker run -d --name comii --restart unless-stopped \
  -p 8000:8000 -p 8001:8001 -e COMII_MAX_UPLOAD_MB=2048 \
  -v /volume1/docker/comii/data:/data \
  -v /volume1/docker/comii/library:/library \
  -v /volume1/R18:/library/comic/R18:ro \
  -v /volume1/R18:/library/video/R18:ro \
  comii:2.25.0-arm64
```

回滚：`docker rm -f comii` 后用 `comii:2.24.0-arm64` 执行同样的 `docker run`。

App 走服务端自带的更新通道：把 APK 放到 `/volume1/docker/comii/data/updates/`，
并写 `update.json`（`versionCode` / `versionName` / `apk` / `sha256` / `notes`），
App 内「设置 → 检查更新」即可下载安装。

## 7. 验证清单

- [x] `GET /api/v1/ping` 返回 2.25.0，features 含 `quality`、`tags`
- [x] `/media/view?w=720` 返回更小的 JPEG，`/data/cache/720` 生成缓存
- [x] `/tags` PUT/GET、`/tagged`、`series.tags` 正常
- [x] 只读目录设相册封面返回友好 400，可写目录返回 200 并写入标记
- [x] `GET /api/v1/app/update` 返回 versionCode 33 / 2.25.0
- [ ] 真机：清晰度切换、快速翻页、标签编辑与标签浏览（待你上手确认）
