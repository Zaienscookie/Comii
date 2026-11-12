# Comii 交接手册

> 版本：2.2.0 · 交接日期：2026-11-13
> 状态：**可运行、可安装、可继续开发**（后端已部署，APK 已构建，WebUI 可用）

---

## 1. 项目一句话

Comii 是一个**个人漫画仓库**：服务器上用一个真实的 `library/` 文件夹管理漫画与视频，
提供 **FastAPI 服务端**、**原生 Android 客户端（Kotlin + Compose）** 和 **保留的 WebUI**。
新建文件夹、移动、重命名、删除都直接作用于磁盘上的相对路径，不依赖数据库。

---

## 2. 交付物

| 交付物 | 位置 | 状态 |
|---|---|---|
| 源码仓库 | `https://github.com/Zaienscookie/Comii` | 已推送（main） |
| 开发服务器 | `192.168.89.200` | WebUI `:8000` · 数据接口 `:8001` |
| 服务器代码 | `/root/dev/comii` | 与仓库 `server/`、`web/` 同步 |
| Python 运行时 | `/root/dev/runtime/python` | 自带 3.11 + 后端依赖 |
| 漫画库 | `/root/dev/storage/library` | 演示 + 你的内容 |
| 运行数据 | `/root/dev/storage/data` | 缩略图缓存、回收站、阅读进度、日志 |
| 安卓 APK | 工作区 `release/Comii-2.2.0.apk` | 可直接安装（debug 签名） |
| 本机构建环境 | 工作区 `buildtools/`（不入库） | SDK 35 / Gradle 8.9 / JDK 17 |

> 安全说明：GitHub Token、SSH 密码不在仓库与本文档中保存。**建议交接后立即轮换 GitHub Token**，
> 因为它在聊天记录中出现过。

---

## 3. 架构

```
┌────────────────────┐        HTTP / JSON        ┌──────────────────────────┐
│ Android 原生客户端  │  ───────────────────────▶ │  数据接口 :8001           │
│ Kotlin + Compose   │       /api/v1/*           │  /api/v1/*               │
└────────────────────┘                           ├──────────────────────────┤
┌────────────────────┐                           │  WebUI     :8000         │
│ 浏览器 WebUI       │  ───────────────────────▶ │  ├─ 页面与静态资源        │
└────────────────────┘                           │  └─ /api/*（WebUI 专用）  │
                                                 ├──────────────────────────┤
                                                 │  storage/library/        │
                                                 └──────────────────────────┘
```

- 服务端是唯一数据源，**没有数据库**；`library/` 的目录结构就是数据模型。
- 原生 App 使用 `8001` 端口的 `/api/v1`；WebUI 使用 `8000` 端口的页面与 `/api/*` 兼容层。
- 阅读进度、回收站索引、缩略图缓存在 `storage/data`，删除它只会丢缓存/进度/回收站，不影响漫画文件。

---

## 4. 服务器

### 4.1 基本信息

| 项目 | 值 |
|---|---|
| 地址 | `192.168.89.200`（内网，RHEL 8.1） |
| SSH 账号 | `root`（密码由项目所有者保管） |
| Python | `/root/dev/runtime/python/bin/python3.11`（源码编译，独立于系统 3.6） |
| 服务 | `systemctl status comii` |
| 端口 | `8000` WebUI · `8001` 数据接口（均监听 0.0.0.0） |
| 防火墙 / SELinux | 均关闭 |

### 4.2 目录布局

```
/root/dev/
├── README.md         # 目录说明（部署脚本自动生成）
├── comii/            # 代码（server/ + web/ + app/ + deploy/ + tools/ + docs/）
├── runtime/python/   # CPython 3.11 + 依赖（fastapi/uvicorn/pillow/pypdfium2/…）
├── storage/library/  # 漫画库（真实目录）
├── storage/data/     # 运行数据：thumbs/ trash/ progress.json logs/
└── scripts/          # seed_library.py / integration_check.py / provision_python.sh
```

### 4.3 常用命令

```bash
systemctl status comii
systemctl restart comii
journalctl -u comii -f                        # 实时日志
tail -f /root/dev/storage/data/logs/comii.log # 应用日志
curl http://127.0.0.1:8001/api/v1/ping        # 数据接口
curl http://127.0.0.1:8000/health             # WebUI
```

### 4.4 配置（systemd 环境变量）

| 变量 | 当前值 | 说明 |
|---|---|---|
| `COMII_LIBRARY` | `/root/dev/storage/library` | 漫画库根目录 |
| `COMII_DATA` | `/root/dev/storage/data` | 缩略图 / 回收站 / 进度 / 日志 |
| `COMII_PORT` | `8000` | WebUI 端口 |
| `COMII_API_PORT` | `8001` | 数据接口端口（App 使用） |
| `COMII_TRASH_TTL_DAYS` | `3`（默认） | 回收站保留天数 |
| `COMII_MAX_UPLOAD_MB` | `512`（默认） | 单次上传限制 |

### 4.5 重新部署

方式一（本机，推荐）：

```powershell
$env:COMII_SSH_PASSWORD='******'
python tools/dev/deploy_server.py .
```

方式二（服务器上）：

```bash
# 代码已在 /root/dev/comii 时
bash /root/dev/comii/deploy/install.sh
```

`deploy/install.sh` 会：缺 Python 时编译 3.11 → 装依赖 → 写 systemd unit → 重启服务。

### 4.6 数据备份

```bash
# 漫画 + 运行数据（回收站/进度在内）
tar czf /root/comii-backup-$(date +%F).tar.gz -C /root/dev/storage library data
```

恢复时解压回 `/root/dev/storage` 并 `systemctl restart comii`。

---

## 5. WebUI（浏览器）

- 地址：`http://192.168.89.200:8000/`
- 页面：媒体库 `/`、阅读器 `/reader.html?s=<路径>`、视频 `/video.html?s=<路径>`
- 功能：分类（全部/漫画/视频）、搜索、面包屑、编辑模式（移动/重命名/删除）、回收站、
  新建文件夹、PDF 导入、打包下载 ZIP、服务器地址切换与局域网扫描、深色/浅色主题。
- WebUI 的静态资源在 `web/static/`，由服务端直接托管；修改后重新部署即可生效。
- WebUI 使用的是旧 `/api/*` 接口（兼容层），原生 App 使用 `/api/v1`，两者可同时使用。

---

## 6. Android 原生客户端

### 6.1 环境要求

- JDK 17（本机：`C:\Program Files\Java\jdk-17`）
- Android SDK：`platforms;android-35`、`build-tools;35.0.0`（本机已装在 `buildtools/sdk`）
- Gradle 8.9（项目已带 wrapper，也可用 `buildtools/gradle/gradle-8.9`）
- 依赖：Compose BOM 2024.10、Navigation、Coil、Media3/ExoPlayer、OkHttp、kotlinx.serialization

### 6.2 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:GRADLE_USER_HOME="$pwd\buildtools\gradle-home"   # 内含代理配置
$env:ANDROID_HOME="$pwd\buildtools\sdk"
$env:ANDROID_USER_HOME="$pwd\buildtools\android-home"
cd app
..\buildtools\gradle\gradle-8.9\bin\gradle.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

也可以 `cd app && .\gradlew.bat assembleDebug`（wrapper 会自行下载 Gradle 8.9，需能访问网络或配置代理）。

### 6.3 安装

- 手机与服务器同一局域网；
- 把 `release/Comii-2.2.0.apk` 传到手机安装（需允许「安装未知来源应用」）；
- 或 `adb install -r release/Comii-2.2.0.apk`。

### 6.4 首次使用

1. 打开 App → 输入数据接口地址 `192.168.89.200:8001`（可省略 `http://`）→「测试连接」；
2. 也可点「扫描局域网」自动发现同一网段的服务；
3. 点「开始使用」进入 Comii 首页。

### 6.5 页面与功能

| 页面 | 功能 |
|---|---|
| Comii | 封面网格、全部/漫画/视频筛选、搜索（文件夹 / 系列 / 文件名）、面包屑、刷新、新建文件夹、上传、回收站、编辑模式 |
| 长按卡片 | 打开 / 重命名 / 移动到… / 下载到本地 / 删除 |
| 编辑模式 | 多选 → 移动、重命名（单选）、批量删除 |
| 阅读器 | 上下滚动 / 左右翻页、点击左右翻页、页码滑杆、页码宫格跳转、横图自动旋转、进度记忆与服务器同步 |
| 系列页 | 封面、页数、继续阅读、视频列表、下载入口 |
| 播放器 | ExoPlayer 播放服务器视频（支持拖动），本地视频同样可播 |
| 下载 | 离线系列列表、分类（新建/长按归类）、封面、大小、打开、删除；阅读器/播放器自动优先使用本地文件 |
| 设置 | 服务器地址、主题（跟随系统/浅色/深色）、翻页方式、点击翻页、横图旋转、进度条、离线存储清理、闪退日志 |
| 导入 | 选择图片 / ZIP / CBZ / PDF / 视频，填写系列名与目标文件夹后上传 |
| 回收站 | 剩余时间、恢复、彻底删除 |

### 6.6 设计规范

- Apple 风格扁平化：分组背景 `#F2F2F7`（深色 `#000000`）、卡片白色/`#1C1C1E`、发丝分割线；
- 主色 `#0A84FF`，危险色 `#FF3B30`（深色 `#FF453A`）；
- 大圆角：卡片 14–22dp，弹层 22dp；无阴影、无渐变按钮；
- 状态栏/导航栏跟随主题切换图标颜色（已做 Android 15 edge-to-edge 适配）。

### 6.7 代码结构

```
app/app/src/main/java/com/comii/app/
├── MainActivity.kt        # 主题、导航、启动路由
├── data/
│   ├── Models.kt          # 与服务端一致的 JSON 模型
│   ├── ComiiApi.kt        # OkHttp + kotlinx.serialization 客户端
│   ├── ComiiRepository.kt # 业务入口：浏览/管理/上传/下载/局域网扫描
│   ├── OfflineStore.kt    # 离线文件与元数据
│   └── SettingsStore.kt   # 服务器地址、主题、阅读偏好
└── ui/
    ├── AppState.kt        # 全局 UI 状态 + 路由参数编码
    ├── theme/Theme.kt     # Apple 风格调色板与字体
    ├── components/        # 通用组件（卡片、弹层、分段控件、确认框…）
    └── screens/           # Setup / Home / Library / Series / Reader / Player / Trash / Upload
```

---

## 7. API 参考

### 7.1 原生 App：`/api/v1`（数据接口端口 `8001`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ping` | 探活、版本、feature 列表 |
| GET | `/browse?path=` | 浏览目录：`items[]` 含 `kind`(folder/manga/video/mixed)、封面、计数 |
| GET | `/series?path=` | 系列详情：`images[]`、`videos[]`、`progress` |
| GET | `/search?q=` | 递归搜索文件夹名 / 系列名 / 媒体文件名 |
| GET | `/media/image|thumb|video|video_thumb?path=&name=` | 图片 / 缩略图 / 视频（Range） / 视频封面 |
| POST | `/folders` | `{parent,name,kind}` 新建真实文件夹 |
| POST | `/rename` | `{path,name}` 重命名 |
| POST | `/move` | `{paths:[...],to}` 批量移动 |
| DELETE | `/items?path=` | 移入回收站 |
| POST | `/items/delete` | `{paths:[...]}` 批量移入回收站 |
| GET | `/trash` | 回收站列表（含 `remaining_seconds`） |
| POST | `/trash/{id}/restore` | 恢复 |
| DELETE | `/trash/{id}` | 彻底删除 |
| POST | `/upload` | multipart：`files[]`、`folder`、`name`（图片/ZIP/CBZ/视频） |
| POST | `/import/pdf` | multipart：`file`、`folder`、`name`、`dpi` |
| GET | `/export?path=&include_video=` | 打包 ZIP |
| GET/PUT | `/progress` | 阅读进度（全部 / 单条 / 写入） |
| GET | `/stats` | 系列、文件夹、图片、视频、占用空间 |

### 7.2 WebUI 兼容层：`/api/*`

`/api/tree`、`/api/series/{path}`、`/api/img|thumb|video|video_thumb/{path}/{name}`、
`/api/folder`、`/api/rename`、`/api/move`、`/api/delete/{path}`、`/api/trash*`、
`/api/import/pdf`、`/api/export`、`/api/scan`、`/api/ping`、`/api/settings`、`/api/logs`。

---

## 8. 测试与验证

### 8.1 后端单元测试

```powershell
cd server
$env:PYTHONPATH='..\buildtools\pylibs'   # 含 pytest；或使用自己的虚拟环境
python -m pytest -q
```

当前结果：**16 passed**（浏览、详情、缩略图、Range、路径穿越、建夹/重命名/移动、
删除→回收站→恢复→彻底删除、进度、图片上传、CBZ 上传、ZIP 导出、统计、
空文件夹可见性、递归搜索）。

### 8.2 线上端到端检查

```bash
python tools/dev/integration_check.py     # 在服务器上或指向 COMII_BASE
```

已验证通过：ping（8001）、browse、series、thumb、PDF 导入（1 页）、图片上传、
删除→回收站→恢复→彻底删除、stats、旧 WebUI 接口（8000）、首页 HTML、递归搜索。

### 8.3 构建验证

- `assembleDebug` 成功，产物约 21 MB；
- `aapt dump badging`：`com.comii.app` / minSdk 24 / targetSdk 35。

---

## 9. 已知限制（重要）

1. **未在真机/模拟器上实际运行过**（本机无 adb 与设备），只完成编译与静态检查；
   首次安装请重点验证：局域网连接、阅读器翻页、视频播放、上传大文件。
2. 视频缩略图依赖服务器 `ffmpeg`（当前未安装）；缺失时显示占位图，不影响播放。
3. WebUI 的「删除」已改为使用完整相对路径（逐段编码），多层文件夹内删除正常。
4. WebUI 上传入口只有 PDF；图片/ZIP/视频上传走原生 App 的「导入」。
5. 服务器磁盘 `/` 仅剩约 6.4 GB，库较大时需要加盘或挂载新分区。
6. 服务端无鉴权，仅适合内网/个人使用；如需公网访问请加反向代理与认证。
7. 回收站清理线程每 10 分钟扫描一次，删除后最多 10 分钟内完成到期清理。
8. debug APK 使用调试签名，升级安装不受影响；如需正式签名参考第 10 节。
9. 当前工作区里仍有重构前的旧目录（`static/`、`backend/`、`komi_apk/` 等），
   因当时会话文件权限受限未能删除，已在 `.gitignore` 中排除，不影响仓库与运行。
10. 旧版 App 如果保存的是 `8000` 端口，升级后需要改回 `8001`（数据接口已独立端口）。
11. 下载页新建分类如果再次闪退，请到「设置 → 诊断 → 上次闪退日志」复制日志反馈。

---

## 10. 后续路线（建议顺序）

1. **真机联调**：安装 APK，验证阅读器、视频、上传、下载四类主流程，修 UI 细节。
2. **正式签名**：生成 keystore，`app/app/build.gradle.kts` 增加 `signingConfigs` + `buildTypes.release`。
3. **视频缩略图**：服务器 `dnf install ffmpeg` 或改用纯 Python 方案。
4. **WebUI 与 v1 统一**：把 `web/static/app.js` 迁到 `/api/v1`，去掉兼容层。
5. **服务端鉴权**：共享 token 或 Basic Auth，反向代理可选。
6. **多用户/多设备进度**：当前进度按系列路径全局共享，如需区分设备可加 device id。
7. **库容量**：`/root/dev/storage/library` 迁到独立数据盘，改 `COMII_LIBRARY` 即可。

---

## 11. 交接检查清单

- [x] 仓库可克隆，`server/`、`app/`、`web/`、`deploy/`、`tools/`、`docs/` 齐全
- [x] 服务端 `systemctl status comii` active，`8001/api/v1/ping` 正常
- [x] WebUI 首页、旧 `/api/*` 接口正常
- [x] 演示库可浏览、可出缩略图
- [x] PDF 导入、上传、删除/恢复/彻底删除 已实测通过
- [x] 递归搜索（文件夹 / 系列 / 文件名）已实测通过
- [x] 空文件夹创建后立即出现在浏览结果中
- [x] 后端 16 项测试通过
- [x] APK 构建成功并输出到 `release/Comii-2.2.0.apk`
- [ ] 真机安装与四大主流程验证（待接手人完成）
- [ ] GitHub Token 轮换（建议）

---

## 12. 快速上手（接手人 5 分钟）

```bash
# 1) 看服务是否活着
curl http://192.168.89.200:8001/api/v1/ping

# 2) 浏览器打开 WebUI
#    http://192.168.89.200:8000/

# 3) 装 APK，填写 192.168.89.200:8001

# 4) 改代码后部署
#    本机：python tools/dev/deploy_server.py .
#    服务器：bash /root/dev/comii/deploy/install.sh

# 5) 看日志
ssh root@192.168.89.200 'journalctl -u comii -f'
```

---

## 13. 版本记录

### v2.2.0（本次）

- 服务端拆成两个端口：WebUI `8000`、数据接口 `8001`（`python -m comii.serve`）。
- `/root/dev` 重整为 `comii/`（代码）、`runtime/python/`（运行时）、`storage/{library,data}/`、
  `scripts/`，并删除旧 `.python` / `.src` 释放约 800 MB。
- 新增递归搜索：文件夹名、系列名、媒体文件名（App 与 WebUI 均支持）。
- 修复空文件夹不显示：新建文件夹后立刻出现在浏览列表。
- 新建文件夹类型只保留「漫画 / 视频」两种（App 与 WebUI）。
- App 首页标题改为蓝色 `Comii.`，底部标签同步改名。
- 下载页分类交互重做：分类新建/归类合并为一个底部弹层，避免弹窗叠加导致的闪退；
  并新增「设置 → 诊断 → 上次闪退日志」，方便反馈崩溃堆栈。
- 版本号 2.2.0（versionCode 4）。

