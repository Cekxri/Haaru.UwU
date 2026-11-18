/*
 * Haaru KernelSU WebUI. Apache-2.0.
 * exec bridge adapted from KernelSU's Apache-2.0 js/index.js:
 * https://github.com/tiann/KernelSU/blob/64833c005eca6a8dc3d73ec2d37d84c37e0d9ad2/js/index.js
 * Changes: timeout handling, strict command/response validation, and callback cleanup.
 * All assets are local. No external scripts or network requests.
 */
(function (global) {
  "use strict";

  var CLI = "sh /data/adb/modules/haaru/bin/haaructl";
  var callbackCounter = 0;
  var COMMANDS = ["status", "set", "disable", "enable", "refresh", "clear", "reset", "forget", "prefs", "saveprefs"];
  function countryPresets() {
    var source = global.HaaruPresets && global.HaaruPresets.countries;
    return Array.isArray(source) ? source.filter(function (item) {
      return item && /^[A-Z]{2}$/.test(item.code) && typeof item.name === "string";
    }) : [];
  }

  function carrierPresets() {
    var source = global.HaaruPresets && global.HaaruPresets.carriers;
    return Array.isArray(source) ? source.filter(function (item) {
      return item && typeof item.id === "string" && item.id
        && typeof item.name === "string" && typeof item.displayName === "string"
        && item.displayName && /^[A-Z]{2}$/.test(item.region);
    }) : [];
  }

  function validateSlot(slot) {
    var value = String(slot);
    if (!/^[0-7]$/.test(value)) throw new Error("SIM 插槽無效，請重新讀取。");
    return value;
  }

  function validateCountry(country) {
    var value = String(country == null ? "" : country).trim().toUpperCase();
    if (!/^[A-Z]{2}$/.test(value)) throw new Error("請輸入兩個英文字母的國家碼，例如 US 或 JP。");
    return value;
  }

  function encodeCarrier(carrier) {
    var value = String(carrier == null ? "" : carrier).trim();
    if (!value) return "-";
    if (value.length > 80 || /[\u0000-\u001f\u007f]/.test(value)) {
      throw new Error("電信商名稱請使用 80 個字元以內的單行文字。");
    }
    return encodeUtf8(value);
  }

  function encodeUtf8(value) {
    if (typeof value !== "string") throw new Error("設定內容必須是文字。");
    var bytes = [];
    for (var i = 0; i < value.length; i++) {
      var cp = value.charCodeAt(i);
      if (cp >= 0xd800 && cp <= 0xdbff) {
        var next = value.charCodeAt(++i);
        if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error("設定包含無效字元。");
        cp = 0x10000 + ((cp - 0xd800) << 10) + next - 0xdc00;
      } else if (cp >= 0xdc00 && cp <= 0xdfff) {
        throw new Error("設定包含無效字元。");
      }
      if (cp < 0x80) bytes.push(cp);
      else if (cp < 0x800) bytes.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
      else if (cp < 0x10000) bytes.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
      else bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    }
    return bytes.map(function (byte) { return ("0" + byte.toString(16)).slice(-2); }).join("");
  }

  function encodeJson(value) {
    var data;
    try { data = typeof value === "string" ? JSON.parse(value) : value; }
    catch (_) { throw new Error("設定內容不是有效 JSON。"); }
    if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error("外觀設定格式不正確。");
    var choices = { style: ["MIUIX", "MATERIAL3"], appearance: ["SYSTEM", "LIGHT", "DARK"], palette: ["MINT", "BLUE", "VIOLET", "ROSE"] };
    Object.keys(data).forEach(function (key) {
      if (choices[key] ? choices[key].indexOf(data[key]) < 0 : ((key !== "glass" && key !== "reduceMotion") || typeof data[key] !== "boolean")) {
        throw new Error("外觀設定欄位不正確。");
      }
    });
    var hex = encodeUtf8(JSON.stringify(data));
    if (hex.length > 4096) throw new Error("設定內容超過 2048 位元組。");
    return hex;
  }

  function buildCommand(action, slot, country, carrier) {
    if (COMMANDS.indexOf(action) === -1) throw new Error("不支援的操作。");
    if (action === "saveprefs") return CLI + " saveprefs " + encodeJson(slot);
    if (action === "set") {
      // Only a canonical slot, ISO/dash, and UTF-8 hex/dash reach the shell.
      var countryValue = String(country == null ? "" : country).trim();
      var iso = !countryValue || countryValue === "-" ? "-" : validateCountry(countryValue);
      var name = encodeCarrier(carrier);
      if (iso === "-" && name === "-") throw new Error("請至少選擇國家碼或電信商名稱。");
      return CLI + " set " + validateSlot(slot) + " " + iso + " " + name;
    }
    if (action === "reset" || action === "forget") return CLI + " " + action + " " + validateSlot(slot);
    return CLI + " " + action;
  }

  function errorText(payload, fallback) {
    var parts = [];
    if (payload && typeof payload.error === "string" && payload.error) parts.push(payload.error);
    if (payload && Array.isArray(payload.errors)) {
      payload.errors.forEach(function (item) {
        if (typeof item === "string" && item) parts.push(item);
        else if (item && typeof item.error === "string") parts.push(item.error);
      });
    }
    // Do not dump raw stdout/stderr, which could contain device identifiers.
    return parts.length ? parts.join("；").slice(0, 1200) : fallback;
  }

  function commandError(message, payload) {
    var error = new Error(message);
    error.payload = payload;
    return error;
  }

  function parseResponse(result, action) {
    if (!result || !Number.isInteger(result.errno)) throw new Error("管理器沒有回報有效的執行結果。");
    var payload;
    try { payload = JSON.parse(String(result.stdout || "").trim()); }
    catch (_) { throw new Error("核心沒有回報有效狀態，請重新讀取。若持續失敗，請核對模組版本與系統相容性。"); }
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) throw new Error("核心回報格式不正確。");
    if (result.errno !== 0 || payload.ok !== true || payload.partial === true || (Array.isArray(payload.errors) && payload.errors.length)) {
      throw commandError(errorText(payload, payload.partial ? "部分設定未成功，請重新讀取並核對每張 SIM。" : "核心執行失敗（代碼 " + result.errno + "）。"), payload);
    }
    if (action === "prefs" && payload.appearance != null && (typeof payload.appearance !== "object" || Array.isArray(payload.appearance))) {
      throw commandError("模組外觀設定格式不正確。", payload);
    }
    if (action !== "status" && action !== "prefs" && payload.verified !== true) {
      throw commandError("尚未取得儲存確認。操作可能已送出，請重新讀取後再決定是否重試。", payload);
    }
    return payload;
  }

  function globalEngineState(data) {
    data = data && typeof data === "object" ? data : {};
    var engine = data.globalEngine;
    if (!engine || typeof engine !== "object" || Array.isArray(engine)) engine = {};
    var error = typeof engine.error === "string" ? engine.error.trim() : "";
    var confirmed = data.global === true && data.engine === "system-global-volatile"
      && engine.ok === true && engine.initialized === true && engine.applied === true
      && Number.isInteger(engine.hooks) && engine.hooks > 0 && Number.isInteger(engine.sdk) && engine.sdk >= 29
      && typeof engine.error === "string" && !error.trim()
      && typeof engine.bootId === "string" && !!engine.bootId.trim()
      && typeof engine.configHash === "string" && !!engine.configHash
      && typeof data.currentConfigHash === "string"
      && engine.configHash === data.currentConfigHash;
    var title = error ? "全域引擎回報錯誤" : confirmed
      ? (data.enabled === true ? "系統全域設定已確認" : "系統全域還原已確認")
      : engine.ok !== true || engine.initialized !== true ? "全域引擎尚未準備" : "全域套用待確認";
    var description = error || (confirmed
      ? "目前設定已由系統全域引擎確認。下方保留 SIM 原始資料供核對。"
      : engine.initialized !== true ? "尚未取得全域引擎初始化回報；保存設定不代表已套用。"
      : "設定已保存，等待目前設定的系統全域套用確認。");
    return { confirmed: confirmed, title: title, description: description,
      hooks: Number.isInteger(engine.hooks) && engine.hooks >= 0 ? engine.hooks : 0,
      sdk: Number.isInteger(engine.sdk) ? engine.sdk : null };
  }

  function exec(command, timeoutMs) {
    return new Promise(function (resolve, reject) {
      if (!global.ksu || typeof global.ksu.exec !== "function") {
        reject(new Error("請從 KernelSU 管理器開啟模組 WebUI；目前頁面無法執行 Root 操作。"));
        return;
      }
      var name = "haaru_exec_callback_" + Date.now() + "_" + callbackCounter++;
      var finished = false;
      var timer;
      function cleanup() {
        if (timer != null) global.clearTimeout(timer);
        delete global[name];
      }
      global[name] = function (errno, stdout, stderr) {
        if (finished) return;
        finished = true;
        cleanup();
        resolve({ errno: errno, stdout: stdout, stderr: stderr });
      };
      timer = global.setTimeout(function () {
        if (finished) return;
        finished = true;
        cleanup();
        reject(new Error("等待回應逾時；模組設定可能仍在儲存。請先重新讀取狀態，避免連續重複操作。"));
      }, timeoutMs == null ? 45000 : timeoutMs);
      try { global.ksu.exec(command, "{}", name); }
      catch (error) { finished = true; cleanup(); reject(error); }
    });
  }

  function createApp(doc, executor) {
    executor = executor || exec;
    var snapshot = null;
    var busy = false;
    var fresh = false;
    var cardControls = [];
    var drafts = Object.create(null);
    var draftControls = Object.create(null);
    var pollTimer = null;
    var pollGeneration = 0;
    var connected = !!global.ksu && typeof global.ksu.exec === "function";
    var versionNode = doc.getElementById("module-version");
    var nodes = {};
    ["read-status", "enable", "reapply", "disable", "clear", "state-label", "state-description", "message", "warnings", "sim-count", "empty-state", "sim-cards"].forEach(function (id) {
      nodes[id] = doc.getElementById(id);
    });

    function element(tag, className, content) {
      var node = doc.createElement(tag);
      if (className) node.className = className;
      if (content != null) node.textContent = String(content);
      return node;
    }

    function showMessage(text, kind) {
      nodes.message.textContent = text;
      nodes.message.className = "message " + kind;
      nodes.message.hidden = !text;
      nodes.message.setAttribute("role", kind === "error" ? "alert" : "status");
    }

    function lock() {
      var profiles = snapshot && Array.isArray(snapshot.profiles) ? snapshot.profiles : [];
      var hasProfiles = profiles.length > 0;
      var hasStopped = profiles.some(function (profile) {
        return profile && (profile.enabled === false || (profile.enabled == null && snapshot.enabled !== true));
      });
      var readOnly = snapshot && snapshot.readOnly === true;
      var upgradePending = snapshot && snapshot.upgradePending === true;
      nodes["read-status"].disabled = busy || !connected;
      nodes.enable.disabled = busy || !connected || readOnly || upgradePending || !fresh || !hasProfiles || (!hasStopped && snapshot.enabled === true);
      nodes.reapply.disabled = busy || !connected || readOnly || upgradePending || !fresh || !hasProfiles || snapshot.enabled !== true;
      nodes.disable.disabled = busy || !connected || upgradePending || !snapshot;
      nodes.clear.disabled = busy || !connected || upgradePending || !hasProfiles;
      cardControls.forEach(function (update) { update(); });
      nodes["sim-cards"].setAttribute("aria-busy", String(busy));
    }

    function renderWarnings(data) {
      while (nodes.warnings.firstChild) nodes.warnings.removeChild(nodes.warnings.firstChild);
      var warnings = Array.isArray(data.warnings) ? data.warnings.filter(function (item) { return typeof item === "string" && item; }) : [];
      if (data.upgradePending === true) warnings.push("模組更新已準備，請完整重新啟動手機後使用新版；重新啟動前仍可能載入舊版引擎，設定暫時無法修改。");
      else if (data.readOnly === true) warnings.push("模組已停用或等待移除，目前只能讀取與還原設定。");
      nodes.warnings.hidden = !warnings.length;
      if (warnings.length) {
        var list = element("ul");
        warnings.forEach(function (text) { list.appendChild(element("li", null, text)); });
        nodes.warnings.appendChild(list);
      }
    }

    function field(form, labelText, input, hint) {
      var wrap = element("div", "field");
      var label = element("label", null, labelText);
      label.htmlFor = input.id;
      wrap.appendChild(label);
      wrap.appendChild(input);
      if (hint) {
        var help = element("p", "small", hint);
        help.id = input.id + "-hint";
        input.setAttribute("aria-describedby", help.id);
        wrap.appendChild(help);
      }
      form.appendChild(wrap);
      return wrap;
    }

    function renderCard(subscription, profiles) {
      var slot;
      try { slot = validateSlot(subscription.slot); }
      catch (_) { return null; }
      if (!Number.isInteger(subscription.subId) || subscription.subId < 0) return null;
      var profile = profiles.find(function (item) { return item && String(item.slot) === slot; });
      var card = element("article", "sim-card");
      var profileEnabled = profile && (profile.enabled === true || (profile.enabled == null && snapshot.enabled === true));
      if (!drafts[slot]) {
        drafts[slot] = {
          country: profileEnabled && profile.countryOverride !== false && /^[A-Za-z]{2}$/.test(String(profile.country || ""))
            ? String(profile.country).toUpperCase() : "",
          carrier: profileEnabled && profile.carrierOverride !== false ? String(profile.carrierName || "") : "",
          scope: "region"
        };
      }
      var draft = drafts[slot];
      card.appendChild(element("h3", null, "SIM " + (Number(slot) + 1)));
      card.appendChild(element("p", "small", profile ? "已存設定 · " + (profileEnabled ? "已啟用" : "已停用") : "尚未儲存設定"));
      var current = element("div", "current");
      var list = element("dl");
      function currentRow(label, value) {
        list.appendChild(element("dt", null, label));
        list.appendChild(element("dd", null, value));
      }
      currentRow(fresh ? "系統回報國家" : "上次讀取國家", subscription.country ? String(subscription.country).toUpperCase() : "未提供");
      currentRow("電信商名稱", subscription.carrierName || "未提供");
      if (subscription.configCountry != null) currentRow("實際系統設定國家碼", String(subscription.configCountry).toUpperCase() || "未提供");
      current.appendChild(list);
      if (typeof subscription.error === "string" && subscription.error) {
        current.appendChild(element("p", "message error", "這張 SIM 讀取未確認：" + subscription.error));
      } else if (subscription.ready === false) {
        current.appendChild(element("p", "small", "這張 SIM 的電信設定尚未載入，請稍後重新讀取。"));
      }
      card.appendChild(current);

      var form = element("form");
      var country = element("input", "country-input");
      country.id = "country-" + slot;
      country.name = "country";
      country.type = "text";
      country.maxLength = 2;
      country.required = false;
      country.pattern = "[A-Za-z]{2}";
      country.autocomplete = "off";
      country.spellcheck = false;
      country.autocapitalize = "characters";
      country.placeholder = "留空：不覆寫國家碼";
      country.value = draft.country;
      var countryField = field(form, "目標國家碼（選用）", country, "可只改國家、只改電信商，或同時修改；至少選擇一項。");
      var presetWrap = element("div", "preset-row");
      var preset = element("select");
      preset.id = "preset-" + slot;
      preset.setAttribute("aria-label", "SIM " + (Number(slot) + 1) + " 選擇國家");
      var blank = element("option", null, "不覆寫國家碼");
      blank.value = "";
      preset.appendChild(blank);
      countryPresets().forEach(function (entry) {
        var option = element("option", null, entry.name + " · " + entry.code);
        option.value = entry.code;
        preset.appendChild(option);
      });
      var customCountry = element("option", null, "自訂國家碼");
      customCountry.value = "__custom__";
      preset.appendChild(customCountry);
      syncCountryPreset();
      presetWrap.appendChild(preset);
      countryField.appendChild(presetWrap);

      var carriers = carrierPresets();
      var carrier = element("input");
      carrier.id = "carrier-" + slot;
      carrier.name = "carrier";
      carrier.type = "text";
      carrier.maxLength = 80;
      carrier.autocomplete = "off";
      carrier.placeholder = "留空：不覆寫電信商名稱";
      carrier.value = draft.carrier;

      var carrierChoice = element("select");
      carrierChoice.id = "carrier-preset-" + slot;
      carrierChoice.setAttribute("aria-label", "SIM " + (Number(slot) + 1) + " 選擇電信商");
      var carrierScope = element("select");
      carrierScope.id = "carrier-scope-" + slot;
      carrierScope.setAttribute("aria-label", "SIM " + (Number(slot) + 1) + " 電信商篩選");
      [["region", "依所選國家顯示"], ["all", "全部電信商"]].forEach(function (entry) {
        var option = element("option", null, entry[1]); option.value = entry[0]; carrierScope.appendChild(option);
      });
      carrierScope.value = draft.scope;
      var carrierField = field(form, "選擇電信商（ISP）", carrierChoice, "選擇電信商只設定顯示名稱，不會自動更改國家碼。");
      carrierField.appendChild(carrierScope);
      field(form, "電信商顯示名稱（可自訂）", carrier, "選擇預設或自行輸入；留空時使用原有名稱。");

      function appendCarrier(item, suffix) {
        var option = element("option", null, item.name + " · " + item.displayName + " · " + item.region + (suffix || ""));
        option.value = item.id; carrierChoice.appendChild(option);
      }
      function renderCarriers() {
        var selected = carriers.find(function (entry) { return entry.displayName === carrier.value; });
        var code = String(country.value || "").trim().toUpperCase();
        var filtered = carrierScope.value === "all" || !/^[A-Z]{2}$/.test(code)
          ? carriers : carriers.filter(function (entry) { return entry.region === code; });
        while (carrierChoice.firstChild) carrierChoice.removeChild(carrierChoice.firstChild);
        var original = element("option", null, "不覆寫電信商名稱"); original.value = ""; carrierChoice.appendChild(original);
        if (selected && !filtered.some(function (entry) { return entry.id === selected.id; })) appendCarrier(selected, "（目前選擇）");
        filtered.forEach(function (entry) { appendCarrier(entry); });
        var custom = element("option", null, "自訂名稱"); custom.value = "__custom__"; carrierChoice.appendChild(custom);
        carrierChoice.value = selected ? selected.id : (carrier.value ? "__custom__" : "");
      }
      var submit = element("button", "button primary submit", "保存這張 SIM 全域設定");
      submit.type = "submit";
      var restore = element("button", "button restore", "還原這張 SIM");
      restore.type = "button";
      var forget = element("button", "button secondary", "移除這張 SIM 設定");
      forget.type = "button";
      function updateControls() {
        var locked = busy || !connected;
        [country, preset, carrier, carrierChoice, carrierScope].forEach(function (node) { node.disabled = locked; });
        var iso = String(country.value || "").trim();
        var name = String(carrier.value || "").trim();
        var hasChoice = !!iso || !!name;
        var validIso = !iso || /^[A-Za-z]{2}$/.test(iso);
        var validName = name.length <= 80 && !/[\u0000-\u001f\u007f]/.test(name);
        submit.disabled = locked || snapshot.readOnly === true || snapshot.upgradePending === true || !fresh || subscription.ready === false || !!subscription.error || !hasChoice || !validIso || !validName;
        restore.disabled = locked || snapshot.upgradePending === true || !profile;
        forget.disabled = locked || snapshot.upgradePending === true || !profile;
      }
      cardControls.push(updateControls);
      function saveDraft() {
        drafts[slot] = { country: country.value, carrier: carrier.value, scope: carrierScope.value };
      }
      function syncCountryPreset() {
        preset.value = countryPresets().some(function (entry) { return entry.code === country.value.toUpperCase(); })
          ? country.value.toUpperCase() : (country.value ? "__custom__" : "");
      }
      draftControls[slot] = function () {
        country.value = drafts[slot].country;
        carrier.value = drafts[slot].carrier;
        carrierScope.value = drafts[slot].scope;
        syncCountryPreset(); renderCarriers(); updateControls();
      };
      preset.addEventListener("change", function () {
        if (preset.value !== "__custom__") country.value = preset.value;
        else if (typeof country.focus === "function") country.focus();
        saveDraft(); renderCarriers(); updateControls();
      });
      country.addEventListener("input", function () {
        syncCountryPreset(); saveDraft(); renderCarriers(); updateControls();
      });
      carrierScope.addEventListener("change", function () { saveDraft(); renderCarriers(); });
      carrierChoice.addEventListener("change", function () {
        if (!carrierChoice.value) carrier.value = "";
        else if (carrierChoice.value === "__custom__") { if (typeof carrier.focus === "function") carrier.focus(); }
        else {
          var chosen = carriers.find(function (entry) { return entry.id === carrierChoice.value; });
          if (chosen) carrier.value = chosen.displayName;
        }
        saveDraft(); updateControls();
      });
      carrier.addEventListener("input", function () { saveDraft(); renderCarriers(); updateControls(); });
      restore.addEventListener("click", function () { if (!busy) run("reset", slot); });
      forget.addEventListener("click", function () { if (!busy) run("forget", slot); });
      form.addEventListener("submit", function (event) {
        event.preventDefault();
        if (busy) return;
        run("set", slot, country.value, carrier.value);
      });
      renderCarriers();
      form.appendChild(submit);
      var actions = element("div", "global-actions"); actions.appendChild(restore); actions.appendChild(forget);
      form.appendChild(actions);
      card.appendChild(form);
      return card;
    }

    function renderModuleVersion(data) {
      var version = typeof data.moduleVersion === "string" ? data.moduleVersion.trim() : "";
      if (versionNode) versionNode.textContent = version ? "Haaru " + version : "Haaru · 版本待確認";
      doc.title = (version ? "Haaru " + version : "Haaru") + " · 系統全域 SIM 地區設定";
    }

    function render(data) {
      snapshot = data;
      renderModuleVersion(data);
      fresh = data.ok === true;
      cardControls = [];
      draftControls = Object.create(null);
      var engine = globalEngineState(data);
      nodes["state-label"].textContent = engine.title;
      nodes["state-label"].className = "badge " + (engine.confirmed ? "active" : "neutral");
      nodes["state-description"].textContent = engine.description;
      renderWarnings(data);
      while (nodes["sim-cards"].firstChild) nodes["sim-cards"].removeChild(nodes["sim-cards"].firstChild);
      var subscriptions = Array.isArray(data.subscriptions) ? data.subscriptions : [];
      var profiles = Array.isArray(data.profiles) ? data.profiles : [];
      var count = 0;
      subscriptions.forEach(function (subscription) {
        if (!subscription || typeof subscription !== "object") return;
        var card = renderCard(subscription, profiles);
        if (card) { nodes["sim-cards"].appendChild(card); count++; }
      });
      nodes["sim-count"].textContent = count + " 張有效 SIM";
      nodes["empty-state"].hidden = count > 0;
      nodes["empty-state"].textContent = "未偵測到可用的 SIM 訂閱。請確認 SIM／eSIM 已啟用，稍後重新讀取；系統服務或介面不相容時也可能無法列出。";
      lock();
    }

    function commitDraft(action, slot, country, carrier) {
      function clearSlot(key) {
        drafts[key] = { country: "", carrier: "", scope: "region" };
        if (draftControls[key]) draftControls[key]();
      }
      if (action === "set") {
        var key = validateSlot(slot);
        var iso = String(country == null ? "" : country).trim().toUpperCase();
        drafts[key] = {
          country: iso === "-" ? "" : iso,
          carrier: String(carrier == null ? "" : carrier).trim(),
          scope: drafts[key] ? drafts[key].scope : "region"
        };
        if (draftControls[key]) draftControls[key]();
      } else if (action === "reset" || action === "forget") {
        clearSlot(validateSlot(slot));
      } else if (action === "clear") {
        // Keep blank entries so stale/failing readback cannot refill removed choices.
        for (var index = 0; index <= 7; index++) clearSlot(String(index));
      }
    }

    function cancelEnginePoll() {
      pollGeneration++;
      if (pollTimer != null) global.clearTimeout(pollTimer);
      pollTimer = null;
    }

    function scheduleEnginePoll(generation, attempt) {
      if (generation !== pollGeneration || attempt >= 5) return;
      pollTimer = global.setTimeout(async function () {
        pollTimer = null;
        if (generation !== pollGeneration || busy) return;
        try {
          var data = parseResponse(await executor(buildCommand("status")), "status");
          if (generation !== pollGeneration || busy) return;
          snapshot = data;
          renderModuleVersion(data);
          fresh = true;
          var engine = globalEngineState(data);
          // Updating engine observations must preserve form nodes and editing drafts.
          nodes["state-label"].textContent = engine.title;
          nodes["state-label"].className = "badge " + (engine.confirmed ? "active" : "neutral");
          nodes["state-description"].textContent = engine.description;
          renderWarnings(data);
          lock();
          if (engine.confirmed) {
            showMessage("設定已保存，系統全域引擎已確認目前設定。", "success");
            return;
          }
          scheduleEnginePoll(generation, attempt + 1);
        } catch (error) {
          if (generation !== pollGeneration) return;
          fresh = false;
          nodes["state-label"].textContent = "全域套用待確認";
          showMessage("設定已保存，但全域引擎回報尚未確認。" + (error.message || "請重新讀取。"), "error");
          lock();
        }
      }, 2000);
      // Node verification timers should not keep a completed test process alive.
      if (pollTimer && typeof pollTimer.unref === "function") pollTimer.unref();
    }

    async function run(action, slot, country, carrier) {
      if (busy) return false;
      if (action !== "status" && snapshot && snapshot.upgradePending === true) {
        showMessage("模組更新等待完整重新啟動，設定暫時無法修改。", "error");
        return false;
      }
      if ((action === "set" || action === "enable" || action === "refresh") && !fresh) {
        showMessage("請先重新讀取並確認狀態，再儲存設定。", "error");
        return false;
      }
      if ((action === "set" || action === "enable" || action === "refresh") && snapshot && snapshot.readOnly === true) {
        showMessage("模組已停用或等待移除，目前只能讀取與還原設定。", "error");
        return false;
      }
      var command;
      try { command = buildCommand(action, slot, country, carrier); }
      catch (error) { showMessage(error.message, "error"); return false; }
      cancelEnginePoll();
      busy = true;
      lock();
      showMessage(action === "status" ? "正在讀取系統狀態…" : "正在執行並確認結果，請稍候…", "info");
      try {
        var data = parseResponse(await executor(command), action);
        if (action !== "status") {
          commitDraft(action, slot, country, carrier);
          // Never optimistically update the enabled flag or current carrier data.
          data = parseResponse(await executor(buildCommand("status")), "status");
        }
        render(data);
        if (action === "status") showMessage("", "info");
        else {
          var confirmed = globalEngineState(data).confirmed;
          showMessage(confirmed ? "設定已保存，系統全域引擎已確認目前設定。" : "設定已保存，等待系統全域引擎套用。", confirmed ? "success" : "info");
          if (!confirmed) scheduleEnginePoll(pollGeneration, 0);
        }
        return true;
      } catch (error) {
        fresh = false;
        if (error.payload && Array.isArray(error.payload.subscriptions)) render(error.payload);
        var failureMessage = error.message || "操作失敗，請重新讀取並核對狀態。";
        if (action !== "status") {
          // A failed/partial write can still change some state. Read it without
          // turning the original operation into a reported success.
          try { render(parseResponse(await executor(buildCommand("status")), "status")); }
          catch (_) {
            fresh = false;
            nodes["state-label"].textContent = "狀態待確認";
            nodes["state-label"].className = "badge error";
            failureMessage += " 重新讀取也未成功，請稍後再核對狀態。";
          }
        }
        showMessage(failureMessage, "error");
        return false;
      } finally {
        busy = false;
        lock();
      }
    }

    function start() {
      nodes["read-status"].addEventListener("click", function () { run("status"); });
      nodes.enable.addEventListener("click", function () { run("enable"); });
      nodes.reapply.addEventListener("click", function () { run("refresh"); });
      nodes.disable.addEventListener("click", function () { run("disable"); });
      nodes.clear.addEventListener("click", function () { run("clear"); });
      lock();
      if (!connected) {
        nodes["sim-count"].textContent = "尚未連接管理器";
        nodes["state-label"].textContent = "唯讀頁面";
        showMessage("請從 KernelSU 管理器開啟模組 WebUI，才能讀取與修改設定。", "info");
        return Promise.resolve(false);
      }
      return run("status");
    }

    return { start: start, run: run, render: render, isBusy: function () { return busy; } };
  }

  var api = { buildCommand: buildCommand, encodeCarrier: encodeCarrier, encodeJson: encodeJson, globalEngineState: globalEngineState, validateCountry: validateCountry, validateSlot: validateSlot, parseResponse: parseResponse, exec: exec, countryPresets: countryPresets, carrierPresets: carrierPresets, createApp: createApp };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  global.HaaruUi = api;
  if (global.document && global.document.getElementById("sim-cards")) createApp(global.document).start();
})(typeof window !== "undefined" ? window : globalThis);
