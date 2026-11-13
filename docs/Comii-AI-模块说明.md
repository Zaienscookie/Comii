# Comii AI 模块说明（demo）

> 版本：App 2.7.0（versionCode 13）
> 位置：底部导航 **AI**；服务配置在 **AI → 右上角齿轮** 或 **设置 → AI 服务设置**

## 1. 它是什么

一个跑在 App 本地的「人格对话」模块，参照 AstrBot 的三段式设计：

```
Persona（人格） → Session/Conversation（会话） → Context 组装（人格 + 记忆 + 插件 + 最近 N 条）
```

| AstrBot 概念 | Comii 里的对应 |
|---|---|
| Persona 人格 | 一个 OC 角色：名字、简介、system prompt、开场白、长期记忆、主题色、温度、插件 |
| Session 会话 | 每个角色可以有多条独立会话（聊天记录互不污染） |
| Context 管理 | 每次请求 = 人格提示词 + 记忆 + 插件注入 + 最近 `上下文条数` 条消息 |
| 插件 | `AiPlugin` 接口：请求前向 system 注入一段文本（demo 内置「时间感知」「环境氛围」） |
| 长期记忆 | 人格里的「长期记忆 / 用户档案」，每次请求都会带上 |

## 2. 使用

1. 打开 **AI** → 内置示例角色「小满」，可直接聊；点右上角 **+** 新建自己的 OC。
2. 角色编辑页可写：
   - 人格设定（system prompt）
   - 开场白（新会话的第一句）
   - 长期记忆 / 用户档案
   - 插件开关、温度、主题色
3. 聊天页右上角：
   - **+** 新建会话
   - 会话图标：切换 / 删除历史会话
   - 铅笔：编辑人格
4. **AI 设置**里填 Base URL / API Key / 模型名，点「测试连接」验证。

默认已经填好：

```
Base URL: https://yachiyo8000-maxtoken.pages.dev/v1
模型:     Yachiyo-v8000-flash
```

API Key 需要你自己粘贴（不会写进源码或仓库）。只调用 OpenAI 兼容的
`POST /v1/chat/completions`，支持流式（SSE）与非流式。

> 该服务会校验 User-Agent：App 里已固定带浏览器 UA，直接填地址和 Key 即可。

## 3. 数据与隐私

- 人格、会话、消息、Key 都保存在 App 私有目录 `files/ai_state.json`，不上传 Comii 服务器。
- 卸载 App 或清除应用数据会一并删除。
- Key 是明文存储在私有目录里的（demo 取舍），介意的话可以只填临时 Key。

## 4. 已知限制（demo）

- 只做对话：没有工具调用 / MCP / 知识库 / Embedding。
- 上下文按「最近 N 条消息」截断，没有自动摘要或长期记忆检索。
- 插件只支持本地注入，不支持 Python 插件热加载。
- 没有跨设备同步、没有导出对话。
- 未做内容安全过滤；适合自用。

## 5. 扩展点

`app/app/src/main/java/com/comii/app/data/ai/AiPlugins.kt` 里：

```kotlin
interface AiPlugin {
    val id: String
    val name: String
    val description: String
    fun inject(persona: Persona): String?
}
```

新增插件只需实现接口并加入 `AiPluginRegistry.all`，人格编辑页会自动出现开关。
