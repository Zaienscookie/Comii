# Comii 2.5.0 部署修复与更新说明

> 适用版本：服务端 / App 2.5.0（versionCode 10）
> 记录日期：2026-11-12

## 1. 本次服务器部署（frp-six.com:45435 → 内网 192.168.2.126）

| 项目 | 值 |
|---|---|
| 登录 | `debug`（SSH 端口 45435，经 frp 转发） |
| 代码目录 | `/home/debug/service/comii`（`server/` + `web/`） |
| Python | `/home/debug/service/comii/.venv`（Ubuntu 22.04 自带 3.10.12 + venv） |
| 漫画库 | `/home/debug/service/comii/storage/library`（`comic/` + `video/`） |
| 运行数据 | `/home/debug/service/comii/data`（缩略图 / 回收站 / 进度 / 日志） |
| systemd | `comii.service`，`User=debug`，开机自启 |
| 端口 | WebUI `8000`、数据接口 `8001`（均 0.0.0.0） |
| 访问 | WebUI `http://192.168.2.126:8000/`，App 填 `192.168.2.126:8001` |

旧的 `komi` Docker 容器已停止并删除（其镜像与 `/srv/komi` 数据保留，需要回滚时在
`/srv/komi` 执行 `docker compose up -d`）。目前 8000/8001 由 `comii.service` 独占。

## 2. “没有 comic / video 文件夹” 的修复

现象：App 点「漫画 / 视频」提示找不到文件夹。

原因有两层：

1. 服务端最初沿用了旧 Komi 的库 `/srv/komi/library`，根目录下只有作者目录，
   **没有 `comic/` 和 `video/`**；
2. App 的「漫画 / 视频」两个 Tab 不是分类过滤，而是**直接打开库根下的
   `comic/`、`video/` 文件夹**，目录不存在就会报错。

修复：

```bash
# 用 Comii 约定的库结构，并把原有漫画放进 comic/
cd /home/debug/service/comii
mkdir -p storage/library/comic storage/library/video
cp -a /srv/komi/library/. storage/library/comic/
sudo systemctl restart comii
```

并把 systemd 单元里的环境变量改为：

```
COMII_LIBRARY=/home/debug/service/comii/storage/library
COMII_DATA=/home/debug/service/comii/data
```

目录约定（磁盘结构就是数据模型，没有数据库）：

```
storage/library/
├── comic/          # 「漫画」Tab → 作者/系列/图片
│   └── <作者或系列>/
│       ├── 001.jpg
│       └── .comii_cover   # 可选，自定义封面
└── video/          # 「视频」Tab → 系列/视频
    └── <系列>/
        └── 001.mp4
```

只要 `comic/`、`video/` 存在（哪怕为空），App 就不会再报“文件夹不存在”。

## 3. 2.5.0 功能与修复

### App

1. **「全部」分页加载**：整库相册不再只显示 300 张，滚到底自动续载
   （每页 200 条，标题显示“已显示 x / 总数”）。
2. **下载弹层可隐藏**：点「隐藏」后弹层不再随着每一页下载重新弹出，下载在后台继续，
   完成时用 toast 提示；下载失败的提示也会明确写出服务器返回的原因，并自动刷新列表。
3. **按作者查看**：进入任意文件夹后，顶部新增「按文件夹 / 全部图片」切换。
   - 按文件夹：一个系列（帖子）一张卡片，和以前一样；
   - 全部图片：把该文件夹（含子文件夹）的全部页面按“文件夹顺序 + 页码”摊平，
     一口气从头看到尾，点任意页直接进阅读器。
4. **下载页批量删除**：点「编辑」进入多选，底部可「删除」或「移动到分类」。
5. **下载页新布局**：改成与媒体库一致的 2 列卡片网格（封面 + 名称 + 页数/大小），
   并有「分类 / 全部图片」两种排列；「全部图片」把离线内容按顺序摊平，可连续阅读。
6. **主题平滑过渡**：浅色 / 深色切换改为 450ms 颜色动画，不再整屏闪一下。
7. **未配置服务器也能进入**：启动直接进首页，「Comii」页会提示去「设置 → 服务器地址」，
   也可以先看离线下载内容；设置页随时可改地址。

### WebUI

1. **封面显示修复**：文件夹卡片封面由 3:2 横版改为 2:3 竖版，漫画封面不再被裁掉。
2. **「全部」分页加载**：滚到底自动加载下一页，标题显示“已加载 x / 总数”。
3. 新增深链：`/?tab=all`、`/?tab=comic`、`/?tab=video` 可直接打开对应分类。

### 服务端 API

`GET /api/v1/gallery` 新增两个可选参数（旧调用不受影响）：

| 参数 | 说明 |
|---|---|
| `path` | 只列某个文件夹（含子文件夹）里的图片/视频；为空表示整库 |
| `sort` | `recent`（默认，按文件时间，相机胶卷）/ `name`（按文件夹 + 页码顺序） |

## 4. 常用命令

```bash
sudo systemctl status comii
sudo systemctl restart comii
journalctl -u comii -f
tail -f /home/debug/service/comii/data/logs/comii.log

curl http://127.0.0.1:8001/api/v1/ping
curl 'http://127.0.0.1:8001/api/v1/gallery?path=comic&sort=name&limit=20'
```

## 5. 构建 App

需要 JDK 17+、Android SDK 35（`platforms;android-35`、`build-tools;35.0.0`）。

```bash
cd app
echo 'sdk.dir=/path/to/Android/Sdk' > local.properties
./gradlew assembleDebug
# 产物：app/app/build/outputs/apk/debug/app-debug.apk
```

国内网络如果 `services.gradle.org` 太慢，可把
`app/gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成
`https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip`（仅本机构建用，不要提交）。
