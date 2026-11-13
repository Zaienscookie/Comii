# Comii 部署到绿联 NAS

> 目标：把 Comii 服务端放到绿联（UGOS）NAS 上，手机 App / 浏览器访问 NAS 上的漫画和视频。
> 推荐用 Docker；文末也给了不用 Docker 的原生方式。

## 1. 先理解「文件路径」怎么接

Comii 不把路径存数据库，它直接读一个**媒体库根目录**（容器里的 `/library`，由 `COMII_LIBRARY` 指定）。

这个根目录下只认两个一级文件夹：

```
<媒体库根目录>/
├── comic/                 # 漫画
│   ├── 作者A/
│   │   └── 系列1/
│   │       ├── 001.jpg
│   │       └── 002.jpg
│   └── 单行本系列/
│       └── ...
└── video/                 # 视频
    ├── 系列1/
    │   ├── 01.mp4
    │   └── 02.mp4
    └── ...
```

- App 里的「漫画 / 视频」标签就是这两个目录。
- App / WebUI 里显示的路径都是**相对于根目录**的，例如 `comic/作者A/系列1`。
- 文件名、文件夹层级可以任意，但一级必须是 `comic` 和 `video`（小写）。
- 图片、视频文件直接放进去就会出现在 Comii 里，不需要导入数据库。

所以「连接图片和视频路径」= 把 NAS 上真实的漫画/视频文件夹**挂载到容器的 `/library/comic` 和 `/library/video`**。

## 2. 准备目录

在 NAS 上准备两个共享文件夹（或使用已有的）：

```
/volume1/comic      # 漫画
/volume1/video      # 视频
/volume1/docker/comii/data   # 给 Comii 存缩略图/回收站/更新包
```

> 绿联不同型号/UGOS 版本的存储卷路径可能不同，`/volume1` 只是常见写法。
> 以你在「文件管理 / Docker」里看到的真实路径为准；下面命令里的路径都要替换成你的。

## 3. Docker 部署（推荐）

### 3.0 直接用已经构建好的镜像（不用在 NAS 上 build）

已经在一台 x86_64 构建机上构建好 `comii:2.24.0`，NAS 只需要导入镜像：

```bash
# 1) 从构建机下载镜像包（约几百 MB）
scp debug@192.168.89.153:/home/debug/comii-2.24.0-image.tar.gz .

# 2) 传到 NAS，或直接从本机推到 NAS
scp comii-2.24.0-image.tar.gz admin@NAS_IP:/volume1/docker/

# 3) 在 NAS 上导入
gunzip -c /volume1/docker/comii-2.24.0-image.tar.gz | docker load
# 或者先 gunzip，再 docker load -i comii-2.24.0-image.tar
```

导入后确认：

```bash
docker images | grep comii
```

然后使用 [docker-compose.image.yml](D:\.Desktop\komi_source_delivery\deploy\docker-compose.image.yml)（不需要 build），改好 volumes 后：

```bash
docker compose -f deploy/docker-compose.image.yml up -d
```

> 镜像是 x86_64 的；如果你的绿联是 ARM 机型，需要重新构建 ARM 版本。
> 镜像包只包含程序，不包含你的漫画/视频；媒体仍然从挂载目录读取。

### 3.1 把源码放到 NAS

任选一种：

- SSH 登录 NAS，`git clone` 本仓库；
- 或者把整个项目压缩包上传到 NAS，解压到例如 `/volume1/docker/comii-src`。

### 3.2 改 `deploy/docker-compose.yml` 的挂载路径

打开 `deploy/docker-compose.yml`，把这三行改成你 NAS 的真实路径：

```yaml
volumes:
  - /volume1/docker/comii/data:/data
  - /volume1/comic:/library/comic
  - /volume1/video:/library/video
```

两种常见情况：

**情况 A：漫画、视频是两个独立共享文件夹**

```yaml
- /volume1/comic:/library/comic
- /volume1/video:/library/video
```

**情况 B：一个总目录，里面已经有 comic / video 两个子文件夹**

```yaml
- /volume1/media:/library
```

不要再额外挂 `/library/comic`，否则会重复。

### 3.3 构建并启动

SSH 到 NAS，在项目根目录执行：

```bash
cd /volume1/docker/comii-src
docker compose -f deploy/docker-compose.yml up -d --build
```

查看日志：

```bash
docker compose -f deploy/docker-compose.yml logs -f comii
```

确认容器里能看到文件：

```bash
docker compose -f deploy/docker-compose.yml exec comii ls -la /library
docker compose -f deploy/docker-compose.yml exec comii ls -la /library/comic
```

### 3.4 绿联 Docker 图形界面

也可以在绿联的 Docker / 容器管理里「创建项目 / Compose」：

1. 项目目录选到仓库根目录（必须能同时看到 `server/`、`web/`、`deploy/`）；
2. Compose 文件填 `deploy/docker-compose.yml`；
3. 启动。

如果图形界面不支持 build（只支持现成镜像），先在 SSH 里执行一次：

```bash
docker build -f deploy/Dockerfile -t comii:latest .
```

然后把 compose 里的 `build:` 删掉，只保留 `image: comii:latest`。

## 4. 启动后怎么连

假设 NAS 局域网 IP 是 `192.168.1.50`：

| 用途 | 地址 |
|---|---|
| WebUI | `http://192.168.1.50:8000` |
| App 数据接口 | `http://192.168.1.50:8001` |
| 连通性检查 | `http://192.168.1.50:8001/api/v1/ping` |

手机 App：

1. 首次打开填服务器地址 `192.168.1.50:8001`（不要带 http，按 App 当前输入框格式）；
2. 保存后会请求 `/api/v1/ping`，返回 `"app":"Comii"` 就成功；
3. 进「漫画 / 视频」就能看到 `/library/comic`、`/library/video` 下的内容。

> App 用的是 **8001**；8000 只给浏览器 WebUI。两个端口都要放行。

## 5. 把旧库搬过来

如果之前已经在别的机器跑过 Comii：

```bash
# 在旧机器上
rsync -av /path/to/old/library/ user@NAS:/volume1/media/

# 或者只拷 comic / video
```

保持 `comic/`、`video/` 两个一级目录不变即可，缩略图会按需重建。

## 6. 权限、ffmpeg、端口

- Docker 镜像里已经装了 **ffmpeg**，视频封面/缩略图可以直接用。
- 容器默认以 root 运行；如果 NAS 文件权限很严，确保容器能读：
  ```bash
  chmod -R a+rX /volume1/comic /volume1/video
  ```
- 端口冲突时改 compose 左侧：
  ```yaml
  - "18000:8000"
  - "18001:8001"
  ```
  同时把 `COMII_PORT` / `COMII_API_PORT` 保持容器内 8000/8001，App 地址改成 NAS_IP:18001。
- 上传大文件默认上限 2048MB，可用 `COMII_MAX_UPLOAD_MB` 调整。

## 7. App 更新通道

更新包放在数据目录：

```
/volume1/docker/comii/data/updates/
├── update.json
└── comii-x.y.z.apk
```

发布脚本（在开发机执行）：

```powershell
python tools/dev/publish_update.py release\Comii-x.y.z.apk 33 x.y.z "更新说明"
```

前提是脚本里的 `COMII_REMOTE_CODE` / SSH 指向你的 NAS（或手动把 APK 和 update.json 拷进上面的目录）。

## 8. 外网访问（重要）

Comii **没有内置登录/鉴权**，不要把 8000/8001 直接映射到公网。

推荐：

- **Tailscale / ZeroTier**：NAS 装客户端，手机装客户端，App 里填 NAS 的 Tailscale IP；
- 或 frp/端口转发 + 只给自己用，并加防火墙白名单；
- 如果要 HTTPS/域名，前面加 Nginx/Caddy 反代，同时反代 8000 和 8001（或只反代 8001 + WebUI 路径），并加 Basic Auth。

## 9. 不用 Docker 的原生方式（可选）

如果不想用容器：

```bash
# NAS 上安装 Python 3.10+ 与 ffmpeg
cd /volume1/docker/comii-src
python3 -m venv .venv
. .venv/bin/activate
pip install -r server/requirements.txt

export COMII_LIBRARY=/volume1/media
export COMII_DATA=/volume1/docker/comii/data
export COMII_PORT=8000
export COMII_API_PORT=8001
cd server
python -m comii.serve
```

长期运行用 systemd（如果你的 UGOS 允许）或 NAS 的「开机启动/计划任务」：

```ini
[Unit]
Description=Comii
After=network-online.target

[Service]
WorkingDirectory=/volume1/docker/comii-src/server
Environment=COMII_LIBRARY=/volume1/media
Environment=COMII_DATA=/volume1/docker/comii/data
Environment=COMII_PORT=8000
Environment=COMII_API_PORT=8001
ExecStart=/volume1/docker/comii-src/.venv/bin/python -m comii.serve
Restart=always

[Install]
WantedBy=multi-user.target
```

## 10. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| App 显示连不上 | 地址必须是 `NAS_IP:8001`；先在浏览器打开 `/api/v1/ping` |
| WebUI 能开，App 不能 | 8001 没放行 / 被防火墙拦 |
| 媒体库是空的 | `/library` 下没有 `comic`、`video`，或挂载路径写错；进容器 `ls /library` 确认 |
| 有文件但没缩略图 | 容器里没有 ffmpeg（本镜像已带），或文件权限不可读 |
| 上传失败/413 | `COMII_MAX_UPLOAD_MB` 太小，或反代限制了 body 大小 |
| 重启后数据没了 | `COMII_DATA` 没挂到宿主机；检查 `/data` 挂载 |
| 中文文件名乱码 | 确保 NAS 文件系统 UTF-8；Comii 按文件名原样读取 |

## 11. 一句话总结路径

> 把 NAS 的漫画目录挂到容器的 `/library/comic`，视频目录挂到 `/library/video`；
> App 填 `NAS_IP:8001`，WebUI 开 `NAS_IP:8000`。
> Comii 里的路径都是相对 `/library` 的，不需要再配置任何绝对路径。
