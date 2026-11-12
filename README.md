# Comii

个人漫画仓库：**原生 Android 客户端 + FastAPI 服务端 + 保留的 WebUI**。

Comii 用一个真实的文件夹结构管理漫画与视频：服务器上的 `library/` 就是仓库本身，
新建文件夹、移动、重命名、删除都会直接反映到磁盘上的相对路径，不引入额外的数据库。

```
┌────────────────────┐        HTTP / JSON        ┌──────────────────────────┐
│  Android 原生客户端 │  ───────────────────────▶ │  Comii Server (FastAPI)  │
│  Kotlin + Compose  │  /api/v1/*                │  ├─ /api/v1/*  原生 App  │
└────────────────────┘                           │  ├─ /api/*     旧 WebUI  │
┌────────────────────┐                           │  ├─ /static    网页资源  │
│  浏览器 WebUI      │  ───────────────────────▶ │  └─ library/   磁盘仓库  │
└────────────────────┘                           └──────────────────────────┘
```

## 功能

- **漫画阅读**：上下滚动 / 左右翻页、点击翻页、页码跳转、阅读进度记忆（本地 + 服务器同步）、竖屏自动旋转横图。
- **视频播放**：服务器视频在线播放，支持 HTTP Range 拖动进度。
- **媒体库**：封面缩略图、漫画 / 视频 / 混合分类、搜索、面包屑式文件夹浏览。
- **编辑**：长按或编辑模式多选，支持重命名、移动到其它文件夹、批量删除。
- **回收站**：删除先进入回收站，默认 **3 天** 后自动清理，可随时恢复或彻底删除。
- **新建文件夹**：在任意相对路径下创建真实目录，可标记为漫画 / 视频 / 混合。
- **导入 / 上传**：图片、ZIP/CBZ 压缩包、PDF（服务器逐页转图）、视频；上传后直接成为库中的系列。
- **下载到本地**：整个系列（图片 + 视频）下载到手机，断网也能阅读和播放。
- **服务器配置**：手动填写地址和端口、连接测试、局域网自动扫描。
- **外观**：Apple 风格扁平化 UI，大圆角卡片，浅色 / 深色 / 跟随系统。
- **WebUI 保留**：`web/static` 继续由同一个服务端提供，浏览器访问根路径即可使用。

## 目录结构

```
comii/
├── server/                 # FastAPI 服务端
│   ├── comii/
│   │   ├── api/v1.py       # 原生 App 使用的 /api/v1
│   │   ├── api/legacy.py   # 旧 WebUI 使用的 /api/*（兼容层）
│   │   ├── services/       # library / media / thumbs / trash / manage / importers ...
│   │   ├── config.py       # 环境变量配置
│   │   └── main.py
│   ├── tests/              # pytest 接口测试
│   ├── requirements.txt
│   └── run.py
├── app/                    # 原生 Android（Kotlin + Jetpack Compose）
│   └── app/src/main/java/com/comii/app/
│       ├── data/           # API 客户端、离线下载、设置
│       └── ui/             # 主题与各页面
├── web/static/             # 保留的 WebUI（浏览器）
├── deploy/                 # systemd 单元与安装脚本
├── docs/                   # 部署与下载器手册
└── tools/                  # pawchive 下载器脚本
```

## 服务端

### 本地运行

```bash
cd server
python -m venv .venv && . .venv/bin/activate    # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
COMII_LIBRARY=./library COMII_DATA=./.comii python run.py
```

打开 <http://127.0.0.1:8000/> 是 WebUI；接口文档在 <http://127.0.0.1:8000/api/v1/docs>。

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `COMII_LIBRARY` | `<项目>/library` | 漫画库根目录 |
| `COMII_DATA` | `<项目>/.comii` | 缩略图、回收站、阅读进度、日志 |
| `COMII_PORT` | `8000` | 监听端口 |
| `COMII_TRASH_TTL_DAYS` | `3` | 回收站保留天数 |
| `COMII_MAX_UPLOAD_MB` | `512` | 单次上传大小限制 |

### 部署到服务器

```bash
sudo bash deploy/install.sh          # 安装 Python 3.11、依赖、systemd 服务
sudo systemctl enable --now comii
curl http://127.0.0.1:8000/api/v1/ping
```

仓库默认监听 `0.0.0.0:8000`，手机与服务器在同一局域网即可访问。

## Android 客户端

要求：JDK 17、Android SDK 35（`platforms;android-35`、`build-tools;35.0.0`）。

```bash
cd app
# local.properties 写入 sdk.dir=/path/to/Android/Sdk
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次启动填写服务器地址（例如 `192.168.89.200:8000`），可以点「扫描局域网」自动发现。

## WebUI

服务端会直接托管 `web/static`：

- `/`：媒体库
- `/reader.html?s=<系列路径>`：漫画阅读器
- `/video.html?s=<系列路径>`：视频播放

## API 摘要（`/api/v1`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ping` | 服务探活与版本 |
| GET | `/browse?path=` | 浏览目录（文件夹 / 系列） |
| GET | `/series?path=` | 系列详情（图片、视频、进度） |
| GET | `/media/image|thumb|video|video_thumb?path=&name=` | 媒体与缩略图 |
| POST | `/folders` | 新建真实文件夹 `{parent,name,kind}` |
| POST | `/rename` | 重命名 `{path,name}` |
| POST | `/move` | 移动 `{paths:[...],to}` |
| DELETE | `/items?path=` | 移入回收站 |
| POST | `/items/delete` | 批量移入回收站 |
| GET | `/trash` / POST `/trash/{id}/restore` / DELETE `/trash/{id}` | 回收站 |
| POST | `/upload` | 上传图片 / ZIP / CBZ / 视频 |
| POST | `/import/pdf` | PDF 逐页转图导入 |
| GET | `/export?path=` | 打包 ZIP |
| GET/PUT | `/progress` | 阅读进度同步 |
| GET | `/stats` | 库统计 |

## 测试

```bash
cd server
python -m pytest -q
```

## 文档

- [交接手册](docs/Comii-交接手册.md)：服务器现状、构建/部署步骤、功能说明、已知限制、后续路线。
- [开发辅助脚本](tools/dev/README.md)：SDK / Gradle / 部署 / 演示库 / 端到端检查。

## License

私人项目，仅供个人使用。
