"use strict";

/**
 * Comii AI 网页版（demo）
 * 人格 / 会话 / 上下文 / 插件都在这一个文件里，改完刷新即可生效，
 * 不需要重新打包 App（WebView 直接加载本页）。
 */
(function () {
  const STORAGE_KEY = "comii-ai-v1";
  const API_URL = "/api/v1/ai/chat";
  const COLORS = ["#0A84FF", "#34C759", "#FF9F0A", "#FF375F", "#AF52DE", "#5AC8FA"];

  const PLUGINS = [
    {
      id: "time",
      name: "时间感知",
      desc: "让角色知道当前日期、时间和星期",
      inject: function () {
        const now = new Date();
        const date = now.toLocaleDateString("zh-CN", {
          year: "numeric", month: "long", day: "numeric", weekday: "long",
        });
        const time = now.toTimeString().slice(0, 5);
        return "现在是 " + date + " " + time + "。你需要自然地意识到当前时间，但不要机械地复述它。";
      },
    },
    {
      id: "ambient",
      name: "环境氛围",
      desc: "每轮补充一点场景氛围，让对话更像真实相处",
      inject: function () {
        const hour = new Date().getHours();
        let scene = "夜很深了，周围很安静。";
        if (hour < 6) scene = "深夜，周围很安静，只有零星灯光。";
        else if (hour < 11) scene = "上午，光线明亮，空气里有一点清晨的凉意。";
        else if (hour < 14) scene = "午后，时间流速变慢，适合闲聊。";
        else if (hour < 18) scene = "下午，光线开始偏暖。";
        else if (hour < 22) scene = "傍晚到夜里，气氛放松下来。";
        return "场景提示：" + scene;
      },
    },
  ];

  const DEFAULT_CONFIG = {
    baseUrl: "https://yachiyo8000-maxtoken.pages.dev/v1",
    apiKey: "",
    model: "Yachiyo-v8000-flash",
    temperature: 0.8,
    maxContext: 20,
    stream: true,
  };

  const DEMO_PERSONA = {
    id: "demo-xiaoman",
    name: "小满",
    tagline: "旧书店的店员，温柔、慢热",
    prompt: "你是小满，在一家旧书店工作。你说话温和、简短，偶尔引用书里的句子。" +
      "你会记得对方告诉过你的事情，并在合适的时候自然地提起。不要自称 AI。",
    greeting: "门上的铃响了一下。小满从书堆后抬头：「来了？外面风大吗？」",
    memory: "",
    color: "#34C759",
    plugins: ["time"],
    temperature: 0.8,
  };

  const els = {};
  let state = loadState();
  let view = { mode: "list", personaId: null, conversationId: null };
  let streaming = false;
  let streamText = "";
  let streamError = "";
  let editingPersonaId = null;
  let editingColor = COLORS[0];
  let editingPlugins = [];
  let toastTimer = null;

  init();

  function init() {
    ["app", "pageTitle", "pageSub", "backBtn", "newChatBtn", "sessionsBtn",
      "editPersonaBtn", "newPersonaBtn", "settingsBtn", "settingsModal",
      "personaModal", "sessionsModal", "toast"].forEach(function (id) {
      els[id] = document.getElementById(id);
    });

    els.backBtn.addEventListener("click", showList);
    els.newPersonaBtn.addEventListener("click", function () { openPersonaEditor(null); });
    els.settingsBtn.addEventListener("click", openSettings);
    els.newChatBtn.addEventListener("click", newConversation);
    els.sessionsBtn.addEventListener("click", openSessions);
    els.editPersonaBtn.addEventListener("click", function () {
      if (view.personaId) openPersonaEditor(view.personaId);
    });

    els.app.addEventListener("click", onAppClick);
    bindSettingsModal();
    bindPersonaModal();
    bindModalClosers();
    renderList();
  }

  // ---------------------------------------------------------------- state
  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && typeof parsed === "object") return normalize(parsed);
      }
    } catch (error) {
      console.warn("AI state load failed", error);
    }
    return {
      config: Object.assign({}, DEFAULT_CONFIG),
      personas: [Object.assign({}, DEMO_PERSONA)],
      conversations: [],
      seeded: true,
    };
  }

  function normalize(data) {
    data.config = Object.assign({}, DEFAULT_CONFIG, data.config || {});
    data.personas = Array.isArray(data.personas) ? data.personas : [];
    data.conversations = Array.isArray(data.conversations) ? data.conversations : [];
    if (!data.seeded) {
      data.seeded = true;
      if (data.personas.length === 0) data.personas = [Object.assign({}, DEMO_PERSONA)];
    }
    return data;
  }

  function saveState() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (error) {
      toast("保存失败：存储空间不足");
    }
  }

  function uid() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return "id-" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  }

  function getPersona(id) {
    return state.personas.find(function (item) { return item.id === id; }) || null;
  }

  function conversationsOf(personaId) {
    return state.conversations
      .filter(function (item) { return item.personaId === personaId; })
      .sort(function (a, b) { return b.updatedAt - a.updatedAt; });
  }

  function latestConversation(personaId) {
    return conversationsOf(personaId)[0] || null;
  }

  function getConversation(id) {
    return state.conversations.find(function (item) { return item.id === id; }) || null;
  }

  function ensureConversation(personaId) {
    const existing = latestConversation(personaId);
    if (existing) return existing;
    return createConversation(personaId);
  }

  function createConversation(personaId) {
    const persona = getPersona(personaId);
    const greeting = persona && persona.greeting ? persona.greeting.trim() : "";
    const conversation = {
      id: uid(),
      personaId: personaId,
      title: "新对话",
      messages: greeting ? [{ role: "assistant", content: greeting, ts: Date.now() }] : [],
      updatedAt: Date.now(),
    };
    state.conversations.push(conversation);
    saveState();
    return conversation;
  }

  // ---------------------------------------------------------------- list
  function renderList() {
    view.mode = "list";
    els.app.className = "app";
    els.pageTitle.textContent = "AI";
    els.pageSub.textContent = state.personas.length + " 个角色 · 对话只存在本机";
    setHeader({ back: false, chat: false });

    if (state.personas.length === 0) {
      els.app.innerHTML =
        '<div class="empty"><div class="big">✦</div>' +
        "<h3>还没有角色</h3><p>创建一个 OC 人格，给它写设定、记忆和插件</p></div>";
      return;
    }

    els.app.innerHTML = state.personas.map(function (persona) {
      const last = latestConversation(persona.id);
      const preview = last && last.messages.length
        ? last.messages[last.messages.length - 1].content
        : (persona.tagline || "点开开始对话");
      return '<div class="persona-card" data-action="open-chat" data-id="' + esc(persona.id) + '">' +
        '<div class="avatar" style="background:' + esc(persona.color || COLORS[0]) +
        '22;color:' + esc(persona.color || COLORS[0]) + '">' + esc((persona.name || "?").slice(0, 1)) + "</div>" +
        '<div class="persona-meta"><h3>' + esc(persona.name) + "</h3>" +
        "<p>" + esc(persona.tagline || " ") + "</p>" +
        '<div class="persona-preview">' + esc(preview) + "</div></div>" +
        '<span class="chev">›</span></div>';
    }).join("") +
      '<div class="persona-card" data-action="new-persona">' +
      '<div class="avatar" style="background:var(--line);color:var(--accent)">＋</div>' +
      '<div class="persona-meta"><h3>新建角色</h3><p>自定义人格、记忆、插件</p></div></div>';
  }

  function onAppClick(event) {
    const target = event.target.closest("[data-action]");
    if (!target) return;
    const action = target.getAttribute("data-action");
    const id = target.getAttribute("data-id");
    if (action === "open-chat") openChat(id);
    else if (action === "new-persona") openPersonaEditor(null);
  }

  // ---------------------------------------------------------------- chat
  function openChat(personaId) {
    const persona = getPersona(personaId);
    if (!persona) return;
    const conversation = ensureConversation(personaId);
    view = { mode: "chat", personaId: personaId, conversationId: conversation.id };
    streamText = "";
    streamError = "";
    renderChat();
  }

  function currentConversation() {
    return getConversation(view.conversationId) || ensureConversation(view.personaId);
  }

  function currentPersona() {
    return getPersona(view.personaId);
  }

  function renderChat() {
    const persona = currentPersona();
    if (!persona) { showList(); return; }
    view.mode = "chat";
    const conversation = currentConversation();
    els.app.className = "app chat-mode";
    els.pageTitle.textContent = persona.name;
    els.pageSub.textContent = "本地保存 · 上下文 " + state.config.maxContext + " 条";
    setHeader({ back: true, chat: true });

    let html = '<div class="chat-head">' +
      '<div class="avatar" style="width:32px;height:32px;font-size:14px;background:' +
      esc(persona.color) + '22;color:' + esc(persona.color) + '">' +
      esc((persona.name || "?").slice(0, 1)) + "</div>" +
      '<div><div class="name">' + esc(persona.name) + "</div>" +
      '<div class="sub">' + esc(persona.tagline || " ") + "</div></div></div>";

    html += '<div class="messages" id="messages">';
    conversation.messages.forEach(function (message) {
      html += bubbleHTML(message.role, message.content);
    });
    if (streaming) {
      html += '<div class="bubble assistant"><span id="streamBubble">' + esc(streamText) +
        '</span><span class="cursor"></span></div>';
    }
    html += "</div>";
    if (streamError) html += '<p class="chat-error">' + esc(streamError) + "</p>";

    html += '<form class="composer" id="composer">' +
      '<textarea id="input" rows="1" placeholder="说点什么…"></textarea>' +
      '<button class="send-btn" id="sendBtn" type="submit"' +
      (streaming ? ' disabled' : "") + ">➤</button></form>";

    els.app.innerHTML = html;

    const composer = document.getElementById("composer");
    const input = document.getElementById("input");
    composer.addEventListener("submit", function (event) {
      event.preventDefault();
      const text = input.value.trim();
      if (text && !streaming) sendMessage(text);
    });
    input.addEventListener("keydown", function (event) {
      if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        const text = input.value.trim();
        if (text && !streaming) sendMessage(text);
      }
    });
    input.addEventListener("input", function () {
      input.style.height = "auto";
      input.style.height = Math.min(input.scrollHeight, 120) + "px";
    });

    scrollBottom();
    if (!streaming) input.focus();
  }

  function bubbleHTML(role, content) {
    const cls = role === "user" ? "user" : "assistant";
    return '<div class="bubble ' + cls + '">' + esc(content) + "</div>";
  }

  async function sendMessage(text) {
    const persona = currentPersona();
    const conversation = currentConversation();
    if (!persona || !conversation) return;
    conversation.messages.push({ role: "user", content: text, ts: Date.now() });
    if (conversation.title === "新对话") conversation.title = text.slice(0, 18);
    conversation.updatedAt = Date.now();
    saveState();

    streaming = true;
    streamText = "";
    streamError = "";
    renderChat();

    const config = state.config;
    const payload = {
      baseUrl: config.baseUrl,
      apiKey: config.apiKey,
      model: config.model,
      temperature: persona.temperature != null ? persona.temperature : config.temperature,
      stream: !!config.stream,
      messages: buildContext(persona, conversation),
    };

    try {
      const response = await fetch(API_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      if (!response.ok) {
        let detail = "HTTP " + response.status;
        try {
          const data = await response.json();
          if (data && data.detail) detail = data.detail;
        } catch (error) { /* ignore */ }
        throw new Error(detail);
      }

      if (config.stream) {
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        while (true) {
          const chunk = await reader.read();
          if (chunk.done) break;
          buffer += decoder.decode(chunk.value, { stream: true });
          let index;
          while ((index = buffer.indexOf("\n\n")) >= 0) {
            handleEvent(buffer.slice(0, index));
            buffer = buffer.slice(index + 2);
          }
        }
        if (buffer.trim()) handleEvent(buffer);
      } else {
        const data = await response.json();
        streamText = (data.choices && data.choices[0] && data.choices[0].message &&
          data.choices[0].message.content) || "";
      }

      if (!streamText.trim()) throw new Error("模型没有返回内容");
      if (streamError) throw new Error(streamError);
      conversation.messages.push({ role: "assistant", content: streamText.trim(), ts: Date.now() });
      conversation.updatedAt = Date.now();
    } catch (error) {
      streamError = error.message || "请求失败";
    }

    streaming = false;
    saveState();
    renderChat();
  }

  function handleEvent(chunk) {
    chunk.split("\n").forEach(function (line) {
      if (!line.startsWith("data:")) return;
      const payload = line.slice(5).trim();
      if (!payload || payload === "[DONE]") return;
      try {
        const data = JSON.parse(payload);
        if (data.error && data.error.message) {
          streamError = data.error.message;
          return;
        }
        const delta = data.choices && data.choices[0] && data.choices[0].delta &&
          data.choices[0].delta.content;
        if (delta) {
          streamText += delta;
          const bubble = document.getElementById("streamBubble");
          if (bubble) bubble.textContent = streamText;
        }
      } catch (error) {
        if (!(error instanceof SyntaxError)) streamError = error.message || "解析失败";
      }
    });
  }

  function buildContext(persona, conversation) {
    const messages = [];
    messages.push({
      role: "system",
      content: persona.prompt && persona.prompt.trim()
        ? persona.prompt
        : "你是" + persona.name + "。" + (persona.tagline || "") + " 保持角色设定，用自然、简短、有人情味的方式聊天。",
    });
    if (persona.memory && persona.memory.trim()) {
      messages.push({ role: "system", content: "你记得这些事：\n" + persona.memory.trim() });
    }
    (persona.plugins || []).forEach(function (pluginId) {
      const plugin = PLUGINS.find(function (item) { return item.id === pluginId; });
      if (!plugin) return;
      const injected = plugin.inject();
      if (injected) messages.push({ role: "system", content: injected });
    });
    const limit = Math.max(2, Math.min(200, Number(state.config.maxContext) || 20));
    conversation.messages.slice(-limit).forEach(function (message) {
      messages.push({ role: message.role, content: message.content });
    });
    return messages;
  }

  function scrollBottom() {
    const box = document.getElementById("messages");
    if (box) box.scrollTop = box.scrollHeight;
  }

  function showList() {
    streaming = false;
    streamText = "";
    streamError = "";
    view = { mode: "list", personaId: null, conversationId: null };
    renderList();
  }

  function newConversation() {
    if (!view.personaId) return;
    const conversation = createConversation(view.personaId);
    view.conversationId = conversation.id;
    streamText = "";
    streamError = "";
    renderChat();
  }

  function setHeader(options) {
    els.backBtn.classList.toggle("hidden", !options.back);
    els.newChatBtn.classList.toggle("hidden", !options.chat);
    els.sessionsBtn.classList.toggle("hidden", !options.chat);
    els.editPersonaBtn.classList.toggle("hidden", !options.chat);
    els.newPersonaBtn.classList.toggle("hidden", options.chat);
    els.settingsBtn.classList.toggle("hidden", options.chat);
  }

  // ---------------------------------------------------------------- settings
  function openSettings() {
    const config = state.config;
    document.getElementById("cfgBaseUrl").value = config.baseUrl;
    document.getElementById("cfgApiKey").value = config.apiKey;
    document.getElementById("cfgModel").value = config.model;
    document.getElementById("cfgTemp").value = config.temperature;
    document.getElementById("cfgCtx").value = config.maxContext;
    document.getElementById("cfgStream").checked = !!config.stream;
    updateSettingLabels();
    document.getElementById("testResult").textContent = "";
    showModal("settingsModal");
  }

  function updateSettingLabels() {
    const temperature = Number(document.getElementById("cfgTemp").value);
    const context = Number(document.getElementById("cfgCtx").value);
    document.getElementById("cfgTempLabel").textContent = temperature.toFixed(2);
    document.getElementById("cfgCtxLabel").textContent = String(context);
  }

  function readSettingsForm() {
    return {
      baseUrl: document.getElementById("cfgBaseUrl").value.trim(),
      apiKey: document.getElementById("cfgApiKey").value.trim(),
      model: document.getElementById("cfgModel").value.trim(),
      temperature: Number(document.getElementById("cfgTemp").value),
      maxContext: Number(document.getElementById("cfgCtx").value),
      stream: document.getElementById("cfgStream").checked,
    };
  }

  function bindSettingsModal() {
    document.getElementById("cfgTemp").addEventListener("input", updateSettingLabels);
    document.getElementById("cfgCtx").addEventListener("input", updateSettingLabels);
    document.getElementById("saveSettingsBtn").addEventListener("click", function () {
      state.config = readSettingsForm();
      saveState();
      closeModal("settingsModal");
      toast("设置已保存");
      if (view.mode === "chat") renderChat();
    });
    document.getElementById("testBtn").addEventListener("click", async function () {
      const button = document.getElementById("testBtn");
      const result = document.getElementById("testResult");
      const config = readSettingsForm();
      button.disabled = true;
      result.className = "result";
      result.textContent = "正在测试…";
      try {
        const response = await fetch(API_URL, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            baseUrl: config.baseUrl,
            apiKey: config.apiKey,
            model: config.model,
            temperature: 0.2,
            stream: false,
            messages: [
              { role: "system", content: "你是连接测试助手，只回复一句简短中文。" },
              { role: "user", content: "回复：连接成功" },
            ],
          }),
        });
        const data = await response.json().catch(function () { return {}; });
        if (!response.ok) throw new Error(data.detail || ("HTTP " + response.status));
        const content = data.choices && data.choices[0] && data.choices[0].message &&
          data.choices[0].message.content;
        result.className = "result ok";
        result.textContent = "连接成功：" + (content || "OK");
      } catch (error) {
        result.className = "result err";
        result.textContent = "连接失败：" + (error.message || "未知错误");
      } finally {
        button.disabled = false;
      }
    });
  }

  // ---------------------------------------------------------------- persona
  function openPersonaEditor(personaId) {
    editingPersonaId = personaId;
    const persona = personaId ? getPersona(personaId) : null;
    document.getElementById("personaModalTitle").textContent = persona ? "编辑角色" : "新建角色";
    document.getElementById("pName").value = persona ? persona.name : "";
    document.getElementById("pTagline").value = persona ? persona.tagline || "" : "";
    document.getElementById("pPrompt").value = persona ? persona.prompt || "" : "";
    document.getElementById("pGreeting").value = persona ? persona.greeting || "" : "";
    document.getElementById("pMemory").value = persona ? persona.memory || "" : "";
    document.getElementById("pTemp").value = persona && persona.temperature != null ? persona.temperature : 0.8;
    document.getElementById("pTempLabel").textContent = Number(document.getElementById("pTemp").value).toFixed(2);
    editingColor = persona && persona.color ? persona.color : COLORS[0];
    editingPlugins = (persona && persona.plugins ? persona.plugins.slice() : []);

    document.getElementById("deletePersonaBtn").classList.toggle("hidden", !persona);
    document.getElementById("deletePersonaBtn").textContent = "删除角色";

    renderColorDots();
    renderPluginToggles();
    showModal("personaModal");
  }

  function renderColorDots() {
    const box = document.getElementById("pColors");
    box.innerHTML = COLORS.map(function (color) {
      return '<button type="button" class="color-dot' + (color === editingColor ? " active" : "") +
        '" data-color="' + color + '" style="background:' + color + '"></button>';
    }).join("");
    box.querySelectorAll("[data-color]").forEach(function (button) {
      button.addEventListener("click", function () {
        editingColor = button.getAttribute("data-color");
        renderColorDots();
      });
    });
  }

  function renderPluginToggles() {
    const box = document.getElementById("pPlugins");
    box.innerHTML = PLUGINS.map(function (plugin) {
      const checked = editingPlugins.indexOf(plugin.id) >= 0;
      return '<label class="plugin"><div class="p-meta"><div class="p-name">' + esc(plugin.name) +
        '</div><div class="p-desc">' + esc(plugin.desc) + "</div></div>" +
        '<input type="checkbox" data-plugin="' + plugin.id + '"' + (checked ? " checked" : "") + "></label>";
    }).join("");
    box.querySelectorAll("[data-plugin]").forEach(function (input) {
      input.addEventListener("change", function () {
        const id = input.getAttribute("data-plugin");
        if (input.checked) {
          if (editingPlugins.indexOf(id) < 0) editingPlugins.push(id);
        } else {
          editingPlugins = editingPlugins.filter(function (item) { return item !== id; });
        }
      });
    });
  }

  function bindPersonaModal() {
    document.getElementById("pTemp").addEventListener("input", function (event) {
      document.getElementById("pTempLabel").textContent = Number(event.target.value).toFixed(2);
    });
    document.getElementById("savePersonaBtn").addEventListener("click", function () {
      const name = document.getElementById("pName").value.trim();
      if (!name) { toast("先给角色起个名字"); return; }
      const data = {
        name: name,
        tagline: document.getElementById("pTagline").value.trim(),
        prompt: document.getElementById("pPrompt").value.trim(),
        greeting: document.getElementById("pGreeting").value.trim(),
        memory: document.getElementById("pMemory").value.trim(),
        color: editingColor,
        plugins: editingPlugins.slice(),
        temperature: Number(document.getElementById("pTemp").value),
      };
      if (editingPersonaId) {
        const persona = getPersona(editingPersonaId);
        if (persona) Object.assign(persona, data);
      } else {
        const persona = Object.assign({ id: uid(), createdAt: Date.now() }, data);
        state.personas.push(persona);
        editingPersonaId = persona.id;
      }
      saveState();
      closeModal("personaModal");
      toast("角色已保存");
      if (view.mode === "chat") renderChat(); else renderList();
    });
    document.getElementById("deletePersonaBtn").addEventListener("click", function () {
      if (!editingPersonaId) return;
      const button = document.getElementById("deletePersonaBtn");
      if (!button.classList.contains("armed")) {
        button.classList.add("armed");
        button.textContent = "再点一次确认删除";
        return;
      }
      const personaId = editingPersonaId;
      state.personas = state.personas.filter(function (item) { return item.id !== personaId; });
      state.conversations = state.conversations.filter(function (item) { return item.personaId !== personaId; });
      saveState();
      closeModal("personaModal");
      toast("已删除角色");
      showList();
    });
  }

  // ---------------------------------------------------------------- sessions
  function openSessions() {
    if (!view.personaId) return;
    const body = document.getElementById("sessionsBody");
    const conversations = conversationsOf(view.personaId);
    body.innerHTML =
      '<button class="btn primary" id="sessionNew" style="width:100%;margin-bottom:12px">新建会话</button>' +
      conversations.map(function (conversation) {
        return '<div class="session-row' + (conversation.id === view.conversationId ? " active" : "") +
          '" data-session="' + esc(conversation.id) + '">' +
          '<div class="s-meta"><div class="s-title">' + esc(conversation.title) + "</div>" +
          '<div class="s-sub">' + conversation.messages.length + " 条消息</div></div>" +
          '<button class="s-del" data-delete="' + esc(conversation.id) + '">删除</button></div>';
      }).join("");
    body.querySelectorAll("[data-session]").forEach(function (row) {
      row.addEventListener("click", function (event) {
        if (event.target.closest("[data-delete]")) return;
        view.conversationId = row.getAttribute("data-session");
        streamText = "";
        streamError = "";
        closeModal("sessionsModal");
        renderChat();
      });
    });
    body.querySelectorAll("[data-delete]").forEach(function (button) {
      button.addEventListener("click", function (event) {
        event.stopPropagation();
        const id = button.getAttribute("data-delete");
        state.conversations = state.conversations.filter(function (item) { return item.id !== id; });
        saveState();
        if (view.conversationId === id) {
          const remaining = conversationsOf(view.personaId)[0] || createConversation(view.personaId);
          view.conversationId = remaining.id;
        }
        openSessions();
        renderChat();
      });
    });
    document.getElementById("sessionNew").addEventListener("click", function () {
      const conversation = createConversation(view.personaId);
      view.conversationId = conversation.id;
      closeModal("sessionsModal");
      renderChat();
    });
    showModal("sessionsModal");
  }

  // ---------------------------------------------------------------- helpers
  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function showModal(id) {
    document.getElementById(id).classList.remove("hidden");
  }

  function closeModal(id) {
    document.getElementById(id).classList.add("hidden");
  }

  function bindModalClosers() {
    document.querySelectorAll("[data-close]").forEach(function (button) {
      button.addEventListener("click", function () {
        closeModal(button.getAttribute("data-close"));
      });
    });
    document.querySelectorAll(".modal").forEach(function (modal) {
      modal.addEventListener("click", function (event) {
        if (event.target === modal) modal.classList.add("hidden");
      });
    });
  }

  function toast(message) {
    els.toast.textContent = message;
    els.toast.classList.remove("hidden");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { els.toast.classList.add("hidden"); }, 2200);
  }
})();
