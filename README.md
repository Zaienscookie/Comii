# Comii

个人漫画 / 媒体库：**原生 Android 客户端 + FastAPI 服务端 + WebUI**。

Comii 直接用真实的文件夹结构管理漫画与视频——服务器上的 `library/` 就是仓库本身，新建文件夹、移动、重命名、删除都会立即反映到磁盘，不引入额外数据库。

```
┌──────────────────┐        HTTP / JSON        ┌──────────────────────┐
│ Android 客户端   │ ────────────────────────▶ │  数据接口  :8001      │
│ Kotlin + Compose │        /api/v1/*          │  /api/v1/*            │
└──────────────────┘                           └──────────┬───────────┘
┌──────────────────┐                           │
│ 浏览器 WebUI     │ ────────────────────────▶ │  WebUI   :8000        │
└──────────────────┘     页面 + 静态资源        │  └ /api/*（兼容层）   │
                                               └──────────┬───────────┘
                                                          │
                                                 ┌────────▼────────┐
                                                 │ library/ 磁盘库 │
                                                 └─────────────────┘
```

数据接口与 WebUI **分为两个端口**：`8001` 只给 App 传数据，`8000` 只服务网页。

---

## 功能

- **漫画阅读**：上下滚动 / 左右翻页、点击翻页、页码跳转、阅读进度记忆（本地 + 服务器同步）、竖屏自动旋转横图
- **视频播放**：在线播放，HTTP Range 拖动进度；左侧上下滑调亮度、右侧上下滑调音量、左右拖动快进（灵敏度可调）；可调低音增强
- **媒体库**：「全部」整库相册；「漫画 / 视频」Tab 直达 `comic` / `video` 目录；自定义封面
- **搜索**：服务端递归搜索，文件夹名、系列名、文件名都能命中
- **编辑**：长按或编辑模式多选，重命名、移动、批量删除；照片页支持横向滑动多选 + 上下滚动
- **回收站**：删除先进回收站（带缩略图预览），默认 **3 天**自动清理，可恢复或彻底删除
- **新建文件夹**：任意相对路径创建真实目录（漫画 / 视频两种类型）
- **导入 / 上传**：图片、ZIP/CBZ 压缩包、PDF（服务端逐页转图）、视频
- **下载到本地**：单个系列或整个文件夹下载到手机
- **权限**：Owner / Admin / Viewer / Custom 四种角色，Custom 逐项勾选权限；两层验证（服务器令牌 + 登录会话）
- **AI 模块**：人格、会话（本地存储，服务端人格接口已就绪）
- **登录与安全**：卡片式登录页、令牌/密码可显隐；令牌只存哈希、首次登录强制改密并轮换令牌；提示条深浅色自适应
- **外观**：Apple 风格扁平 UI，浅色 / 深色 / 跟随系统；WebUI 针对桌面端适配（宽屏栅格、居中模态框、悬浮效果）

---

## 快速开始（Docker Compose）

```bash
git clone https://github.com/Zaienscookie/Comii.git
cd Comii
```

> 收藏夹会在首次启动时自动创建在媒体库下，**前提是媒体库挂载可写**（不要带 `:ro`）。
> 只读挂载下无法建目录，收藏/上传/删除都会失败。

```bash
mkdir -p data
# 编辑 docker-compose.yml，把 ./library 换成你的媒体目录
#   只读挂载： - /vol1/1000/media:/library:ro
#   要上传/删除：去掉 :ro

docker compose up -d --build
docker compose logs comii | grep -E "Owner|令牌"
```

日志会打印首次创建的 Owner 与自动生成的令牌：

```
已自动创建 Owner：用户名=admin 密码=Abcd@0000（请尽快修改；配置见 /data/server.conf）
已自动生成服务器访问令牌：Hs6E3KbwYfx5nInSoma1MVUuCx1FBZVy（已写入 /data/server.conf）
```

打开：

| 地址 | 用途 |
|---|---|
| `http://<主机IP>:8000` | WebUI |
| `http://<主机IP>:8001` | App / API |

登录页填：用户名、密码、**服务器访问令牌**（就是日志里那串）。

### 自定义初始账号

`docker-compose.yml`：

```yaml
environment:
  COMII_OWNER_USER: "你的用户名"
  COMII_OWNER_PASSWORD: "你的密码"
  COMII_ACCESS_TOKEN: ""        # 留空 = 自动生成
```

---

## 部署方式

### 一、Docker Compose（推荐）

见上方「快速开始」。升级：

```bash
git pull
docker compose up -d --build
```

### 二、docker run

```bash
docker build -t comii:latest .

docker run -d --name comii --restart unless-stopped \
  -p 8000:8000 -p 8001:8001 \
  -e COMII_LIBRARY=/library \
  -e COMII_DATA=/data \
  -e COMII_AUTH_ENABLED=1 \
  -e COMII_OWNER_USER=admin \
  -e COMII_OWNER_PASSWORD=你的密码 \
  -v /你的/媒体目录:/library:ro \
  -v /你的/data目录:/data \
  comii:latest
```

> 改环境变量不能用 `docker update`，只能删除容器重建；数据在 `/data` 卷里不会丢。

### 三、裸机 + systemd

```bash
cd server
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

`/etc/systemd/system/comii.service`：

```ini
[Unit]
Description=Comii server
After=network-online.target

[Service]
Type=simple
User=comii
WorkingDirectory=/opt/comii/server
Environment=COMII_LIBRARY=/srv/media
Environment=COMII_DATA=/opt/comii/data
Environment=PYTHONUNBUFFERED=1
ExecStart=/opt/comii/server/.venv/bin/python -m comii.serve
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now comii
journalctl -u comii -n 30 | grep -E "Owner|令牌"
```

---

## 配置文件 `server.conf`

安全设置集中在一个文件：`<数据目录>/server.conf`（默认 `/data/server.conf`，可用 `COMII_CONFIG` 覆盖）。

```json
{
  "access_token_hash": "令牌的 SHA-256，服务端只存哈希，不存明文",
  "auth_enabled": true,
  "owner_user": "admin"
}
```

- **不再保存明文**：令牌只存哈希，密码只存 PBKDF2 哈希，`owner_password` 在首次创建后即清空
- **优先级**：环境变量 > `server.conf` > 默认值（`COMII_ACCESS_TOKEN` 仅用于首次注入，启动时自动转成哈希）
- 修改后重启服务 / 容器生效
- 建议权限设为 `600`

### 首次启动做了什么

服务端启动时执行 `auth.bootstrap()`：

1. 建表（`users` / `sessions` / `grants` / `tokens` / `audit`）
2. `users` 表为空且 `auth_enabled=true` → **创建 Owner**
   - 用户名取 `owner_user`（默认 `admin`），密码取 `owner_password`，为空则随机生成
3. 没有令牌 → **随机生成**
4. 账号密码与令牌**只在日志打印一次**，随后只把令牌哈希写入 `server.conf`
5. 新 Owner 带 `must_change=1`：**首次登录必须改密**

已有账号时不会重复创建，也不会覆盖密码。

---

## 账号与权限

| 角色 | 能力 | 是否可改 |
|---|---|---|
| Owner | 全部，含用户管理 | 预设，不可改；系统至少保留一个 |
| Admin | 内容管理（浏览 / 下载 / 上传 / 新建 / 删除 / 移动 / 封面标签） | 预设，不可改 |
| Viewer | 只读（浏览 + 下载） | 预设，不可改 |
| Custom | 点用户行勾选 7 项权限 | 可改 |

管理页 `/admin.html`：

- 点一行 = 选中用户 → 下方出现权限勾选（仅 Custom）
- 「状态」按钮：点一下封禁（同时踢下线），再点一下恢复
- 自己的角色不能改、不能封禁自己、不能把最后一个 Owner 降级
- 登录页的「服务器访问令牌」是所有人共用的那一个

### 首次登录与令牌轮换

新部署的实例第一次登录后会强制进入 `/setup.html`：

1. 重设**用户名 + 密码**
2. 生成**新的服务器令牌**（只显示一次，可一键复制）
3. 旧令牌立即失效，所有旧会话被踢下线

```bash
# 忘记令牌时：删除哈希并重启，会重新生成并打印
python3 - <<'PY'
import json,pathlib
p=pathlib.Path("data/server.conf"); d=json.loads(p.read_text())
d.pop("access_token_hash",None); p.write_text(json.dumps(d,indent=2))
PY
docker compose restart comii && docker compose logs comii | grep 令牌
```

### 两层验证

```
请求 → ① 服务器令牌（X-Comii-Token 或 cookie comii_access）
     → ② 登录会话（cookie）
     → ③ 角色 / Custom 权限
```

| 情况 | 返回 |
|---|---|
| 没带令牌 / 令牌错 | `401 无效的服务器访问令牌` |
| 令牌对但未登录 | `401 请先登录` |
| 已登录但权限不足 | `403` |

---

## 反向代理 / 隧道

### Cloudflare Tunnel

```bash
cloudflared tunnel route dns comii api.example.com
cloudflared tunnel run --url http://127.0.0.1:8001 comii
```

- 必须透传 `X-Comii-Token`、`Cookie`
- 服务端读取 `CF-Connecting-IP` 作为真实来源（审计日志）
- 媒体响应带 `Cache-Control: private`，不会被 CDN 缓存

### frp

```ini
[comii-api]
type = tcp
local_ip = 127.0.0.1
local_port = 8001
remote_port = 18001
```

---

## 升级 / 备份 / 回滚

```bash
# 升级
git pull
docker compose up -d --build        # 或 systemctl restart comii

# 备份
tar czf comii-data-$(date +%F).tgz data/

# 回滚
docker compose down
tar xzf comii-data-2026-11-16.tgz
docker compose up -d
```

- 数据库在 `data/`，升级不影响
- 服务端版本：`GET /api/v1/ping`
- App 更新：把 APK 放入 `<data>/updates/` 并写好 `update.json`

---

## 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `401 无效的服务器访问令牌` | 没带 token 或不对 | 看 `data/server.conf` 的 `access_token`，App / 登录页填一致 |
| `401 请先登录` | token 对但未登录 | 登录页登录 |
| 新建文件夹提示“只读” | 库目录 `:ro` 挂载 | 去掉 `:ro` 后 `docker compose up -d` |
| 端口被占用 | 8000 / 8001 冲突 | 改 `ports` 左值 |
| 登录页空白 | 静态资源缓存 | Ctrl+F5 强刷 |
| 忘记 Owner 密码 | — | 删 `data/users.db` 重启会重建 Owner 并打印一次（媒体不受影响） |
| 忘记服务器令牌 | 只存哈希，无法反查 | 删 `server.conf` 里的 `access_token_hash` 后重启，日志会打印新令牌 |
| 登录后被带到 `/setup.html` | 首次登录必须改密 | 按页面设置新账号、密码与新令牌即可 |
| 管理页提示“仅 Owner” | 当前账号不是 Owner | 用 Owner 登录，或改 `users` 表的 `role` |

---

## 从源码构建 Android 客户端

需要 JDK 21 + Android SDK（compileSdk 35）。

```bash
cd app
./gradlew assembleRelease        # Windows: gradlew.bat assembleRelease
```

产物：`app/app/build/outputs/apk/release/app-release.apk`

签名（可选）：

```bash
export COMII_KEYSTORE=~/comii.jks
export COMII_KEYSTORE_PASSWORD=******
export COMII_KEY_ALIAS=comii
export COMII_KEY_PASSWORD=******
```

---

## 目录结构

```
comii/
├── server/                    # FastAPI 服务端
│   ├── comii/
│   │   ├── api/v1.py          # App 用的 /api/v1
│   │   ├── api/auth.py        # 登录、用户、审计
│   │   ├── api/legacy.py      # WebUI 用的 /api/*（兼容层）
│   │   ├── services/          # library / media / thumbs / trash / manage / importers
│   │   ├── config.py          # 配置（环境变量 + server.conf）
│   │   ├── auth.py            # 账号、令牌、权限、审计
│   │   └── main.py
│   ├── tests/
│   ├── requirements.txt
│   └── run.py
├── app/                       # Android（Kotlin + Jetpack Compose）
├── web/static/                # WebUI（登录页、管理页、阅读器）
├── deploy/                    # systemd 单元与安装脚本
├── tools/                     # 辅助脚本
├── Dockerfile
├── docker-compose.yml
└── README.md
```

---

## 本地开发

```bash
cd server
python -m venv .venv && . .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
COMII_LIBRARY=./library COMII_DATA=./.comii python run.py
```

- WebUI：<http://127.0.0.1:8000/>
- API：<http://127.0.0.1:8001/api/v1>
- 接口文档：<http://127.0.0.1:8001/api/v1/docs>

---

## 安全检查清单

- [ ] `server.conf` 权限 `600`（其中只有令牌哈希，没有明文）
- [ ] 首次登录后确认已完成 `/setup.html` 的改密与令牌轮换
- [ ] 公网只暴露 8001（或仅经隧道），8000 尽量内网
- [ ] 定期轮换令牌：改 `server.conf` → 重启 → App 与登录页同步
- [ ] 不要把含账号 / 令牌的文档提交到公开仓库
- [ ] 备份 `data/`，但别放公网可读目录
