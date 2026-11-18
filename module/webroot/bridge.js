/* Haaru local-only Compose Web bridge. Apache-2.0. */
(function (global) {
  "use strict";
  var preferences = null;
  var preferenceFailure = null;
  var prepared = null;
  var revision = 0;
  var saves = Promise.resolve("");
  var loading = global.document && global.document.getElementById("loading");
  // Root WebUI assets are local. Block library fallback/CDN requests before
  // they reach the browser's network stack; CSP independently enforces this.
  if (typeof global.fetch === "function" && global.location) {
    var localFetch = global.fetch.bind(global);
    global.fetch = function (input, options) {
      var address = typeof input === "string" ? input : (input && (input.url || input.href));
      var target = new URL(address, global.location.href);
      if (target.origin !== global.location.origin && target.protocol !== "data:") {
        return Promise.reject(new Error("External asset loading is disabled"));
      }
      return localFetch(input, options);
    };
  }
  global.haaruNative = {
    prepare: function () {
      if (prepared) return prepared;
      prepared = (async function () {
        if (!global.ksu || typeof global.ksu.exec !== "function") return "";
        try {
          var api = global.HaaruUi;
          if (!api) throw new Error("模組橋接尚未載入");
          var result = api.parseResponse(await api.exec(api.buildCommand("prefs")), "prefs");
          preferences = result.appearance == null ? null : JSON.stringify(result.appearance);
          preferenceFailure = null;
        } catch (error) {
          preferenceFailure = "無法讀取模組外觀設定，目前使用預設外觀。" + (error.message || "");
        }
        return "";
      })();
      return prepared;
    },
    loadPreferences: function () {
      return preferences;
    },
    savePreferences: function (value) {
      var api = global.HaaruUi;
      if (!api) throw new Error("模組橋接尚未載入");
      var command = api.buildCommand("saveprefs", value);
      preferences = JSON.stringify(JSON.parse(value));
      var currentRevision = ++revision;
      preferenceFailure = null;
      if (!global.ksu || typeof global.ksu.exec !== "function") return;
      saves = saves.catch(function () {}).then(async function () {
        api.parseResponse(await api.exec(command), "saveprefs");
        if (currentRevision === revision) preferenceFailure = null;
        return "";
      }).catch(function (error) {
        var message = "外觀尚未儲存到模組；本頁顯示未儲存變更，重載可能回到先前外觀。請重新儲存。" + (error.message || "");
        if (currentRevision === revision) preferenceFailure = message;
        throw new Error(message);
      });
      // A synchronous appearance update must never create an unhandled rejection.
      saves.catch(function () {});
    },
    flushPreferences: flushPreferences,
    preferenceError: function () { return preferenceFailure; },
    command: async function (action, slot, country, carrier) {
      var api = global.HaaruUi;
      if (!api) throw new Error("模組橋接尚未載入");
      try { await flushPreferences(); }
      catch (error) {
        // Cosmetic persistence failures must not prevent observation or withdrawal.
        // The preference error remains available to the independent appearance notice.
        if (["status", "reset", "disable", "clear", "forget"].indexOf(action) < 0) throw error;
      }
      var result = await api.exec(api.buildCommand(action, slot, country, carrier));
      return JSON.stringify(api.parseResponse(result, action));
    },
    failLoad: renderFailure,
    ready: function () {
      if (loading) loading.hidden = true;
      global.document.documentElement.dataset.composeReady = "true";
    }
  };
  async function flushPreferences() {
    for (;;) {
      var pending = saves;
      try { await pending; }
      catch (error) { if (pending === saves) throw error; }
      if (pending === saves) return "";
    }
  }
  function renderFailure() {
    if (loading) {
      loading.hidden = false;
      global.document.documentElement.dataset.composeReady = "false";
      var message = global.document.getElementById("load-message");
      if (message) message.textContent = "目前 WebView 無法載入此介面。請更新 Android System WebView，或開啟相容介面。";
    }
  }
  global.addEventListener("error", renderFailure);
  global.addEventListener("unhandledrejection", renderFailure);
})(globalThis);

