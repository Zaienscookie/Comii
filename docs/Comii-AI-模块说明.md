# Comii AI 模块说明（2.9.x：内嵌网页版）

> App 2.9.1 · 服务端 2.9.0
> 入口：App 底部 **AI** 标签（WebView）或浏览器打开 `http://<服务器>:8001/ai.html`

## 1. 为什么改成网页

原先 AI 是原生 Compose 页面，改人格/交互都要重新打包 APK。
现在 AI 的界面、人格、会话、插件全部在 WebUI 源码里：

```
web/static/ai.html   # 结构
web/static/ai.css    # 外观
web/static/ai.js     # 人格 / 会话 / 上下文 / 插件 / 请求逻辑
```

改完这三个文件、部署服务器，App 里 **下拉刷新/重开页面** 就是新版，不用重新装 APK。
App 只负责用 WebView 打开 `serverUrl/ai.html`（数据端口 8001 直接提供，不换端口）。

## 2. 结构（对齐 AstrBot 的思路）

```
Persona 人格 → Conversation 会话 → Context 组装（人格 + 记忆 + 插件 + 最近 N 条）
```

| 位置 | 内容 |
|---|---|
| `ai.js` 里的 `state.config` | Base URL / API Key / 模型 / 温度 / 上下文条数 / 流式 |
| `state.personas` | 多个 OC：名字、简介、system prompt、开场白、长期记忆、主题色、温度、插件 |
| `state.conversations` | 每个角色的多条独立会话 |
| `buildContext()` | 人格提示 → 记忆 → 插件注入 → 最近 N 条消息 |
| `PLUGINS` | 插件数组，实现 `inject()` 即可；内置「时间感知」「环境氛围」 |

所有数据都在 WebView / 浏览器的 `localStorage`（key `comii-ai-v1`），不上传服务器。

### 主题与布局

- App 打开时带 `?theme=dark|light`，页面直接跟随 App 的深浅色；右上角也有手动切换按钮。
- 页面布局用 `body{display:flex}` + `.app{flex:1;min-height:0}`，不依赖 `calc(100% - 固定高度)`，避免 WebView 高度计算出问题时内容被裁掉。
- 如果页面显示异常，右下角会弹出 JS 错误提示，方便定位。

## 3. 请求链路

```
WebView 页面  --POST /api/v1/ai/chat-->  Comii 服务器  --POST /chat/completions-->  OpenAI 兼容服务
```

- 服务器只做转发（`server/comii/api/ai.py`），**不落库、不存 Key**；Key 每次随请求带上。
- 支持 SSE 流式与非流式；上游错误会原样带回页面。
- 因为走同源代理，浏览器/WebView 不需要上游开 CORS。
- 该网关要求浏览器 UA，服务器代理已固定带 UA。

## 4. 使用

1. App 里先配置服务器（`192.168.2.126:8001`），打开 **AI**。
2. 首次会自动带一个示例角色「小满」；右上角 **⚙** 填 Base URL / Key / 模型，点「测试连接」。
3. **＋** 新建角色；聊天页右上角：新建会话、会话列表、编辑角色。
4. 浏览器也可以直接开 `http://<服务器>:8001/ai.html` 调。

默认 Base URL / 模型：

```
https://yachiyo8000-maxtoken.pages.dev/v1
Yachiyo-v8000-flash
```

## 5. 已知限制（demo）

- 上下文按「最近 N 条」截断，没有自动摘要/长期记忆检索。
- 没有工具调用 / MCP / 知识库 / Embedding。
- 插件是网页里的 JS 注入点，不是 AstrBot 的 Python 插件热加载。
- Key 明文存在 WebView 的 localStorage（App 私有目录），卸载即清。
- App 内 WebView 禁用了缓存（`LOAD_NO_CACHE`），保证改完网页立即生效，代价是每次重新拉取页面。

## 6. 想改 AI 时改哪里

| 想改 | 文件 |
|---|---|
| 布局、气泡、输入框 | `ai.html` / `ai.css` |
| 人格字段、会话逻辑 | `ai.js` 的 `openPersonaEditor` / `openSessions` |
| 上下文组装、截断 | `ai.js` 的 `buildContext` |
| 插件 | `ai.js` 的 `PLUGINS` |
| 代理/超时/UA | `server/comii/api/ai.py` |

改完 `python tools/dev/deploy_frp.py .` 部署即可。
