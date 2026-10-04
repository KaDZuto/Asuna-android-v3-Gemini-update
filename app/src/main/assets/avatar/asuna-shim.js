/* global window, console, fetch */
/**
 * Asuna Shim — прослойка совместимости между Electron-стилем `window.vh.*`
 * (используется в исходном `app.js`) и Android-мостом `window.Asuna.*`
 * (добавляется Kotlin-стороной через `addJavascriptInterface`).
 */
(function() {
  'use strict';
  if (window.vh) return;

  console.log('[AsunaShim] Loading…');

  const subscribers = {
    mouth: [],
    model: [],
    settings: [],
    react: [],
    musicPlay: [],
    musicStop: [],
    tilt: []
  };

  const FALLBACK_MODEL_IDS = [
    'asuna_01','asuna_02','asuna_03','asuna_04','asuna_05','asuna_06','asuna_07','asuna_08','asuna_09',
    'asuna_12','asuna_13','asuna_14','asuna_15','asuna_16','asuna_17','asuna_18','asuna_19',
    'asuna_20','asuna_21','asuna_22','asuna_23','asuna_24','asuna_25','asuna_26','asuna_27','asuna_28','asuna_29',
    'asuna_30','asuna_31','asuna_33','asuna_34','asuna_35','asuna_36','asuna_37','asuna_38','asuna_39',
    'asuna_40','asuna_41','asuna_43','asuna_44','asuna_45','asuna_46','asuna_47','asuna_48','asuna_49',
    'asuna_50','asuna_51','asuna_52','asuna_53','asuna_54','asuna_55','asuna_56'
  ];

  let currentSettings = {
    alwaysOnTop: true,
    currentModel: 'asuna_01',
    models: FALLBACK_MODEL_IDS.map(id => ({ id, expressions: 0, motions: 0 }))
  };

  let modelsManifest = null;

  function loadManifest() {
    return fetch('models_manifest.json')
      .then(r => r.ok ? r.json() : null)
      .then(arr => {
        if (Array.isArray(arr) && arr.length > 0) {
          modelsManifest = arr;
          currentSettings.models = arr.map(m => ({
            id: m.id,
            expressions: m.expressions || 0,
            motions: m.motions || 0
          }));
          console.log('[AsunaShim] Loaded models_manifest.json with ' + arr.length + ' models');
        }
      })
      .catch(err => {
        console.warn('[AsunaShim] models_manifest.json fetch failed:', err);
      });
  }
  loadManifest();

  const vh = {
    init: async function() {
      if (!modelsManifest) await loadManifest();
      if (window.Asuna && typeof window.Asuna.init === 'function') {
        try {
          const cfg = await window.Asuna.init();
          if (cfg && cfg.settings) currentSettings = { ...currentSettings, ...cfg.settings };
          if (cfg && cfg.models) currentSettings.models = cfg.models;
        } catch (e) {
          console.warn('[AsunaShim] Asuna.init failed:', e);
        }
      }
      console.log('[AsunaShim] init() done, models: ' + currentSettings.models.length);
      return { settings: currentSettings, models: currentSettings.models };
    },

    onMouthLevel: function(cb) { if (typeof cb === 'function') subscribers.mouth.push(cb); },
    onModelSet: function(cb) { if (typeof cb === 'function') subscribers.model.push(cb); },
    onSettingsSync: function(cb) { if (typeof cb === 'function') subscribers.settings.push(cb); },
    onAvatarReact: function(cb) { if (typeof cb === 'function') subscribers.react.push(cb); },
    onMusicPlay: function(cb) { if (typeof cb === 'function') subscribers.musicPlay.push(cb); },
    onMusicStop: function(cb) { if (typeof cb === 'function') subscribers.musicStop.push(cb); },
    onTilt: function(cb) { if (typeof cb === 'function') subscribers.tilt.push(cb); },

    emitMouth: function(level) {
      subscribers.mouth.forEach(cb => { try { cb(Math.max(0, Math.min(1, level || 0))); } catch (e) {} });
    },
    emitModel: function(id) {
      subscribers.model.forEach(cb => { try { cb(id); } catch (e) {} });
    },
    emitSettings: function(s) {
      currentSettings = { ...currentSettings, ...s };
      subscribers.settings.forEach(cb => { try { cb(currentSettings); } catch (e) {} });
    },
    emitReact: function(actionOrJson) {
      let action = actionOrJson;
      if (typeof actionOrJson === 'string') {
        try { action = JSON.parse(actionOrJson); } catch (e) { return; }
      }
      subscribers.react.forEach(cb => { try { cb({ action: action }); } catch (e) {} });
    },
    emitMusicPlay: function(payload) {
      subscribers.musicPlay.forEach(cb => { try { cb(payload); } catch (e) {} });
    },
    emitMusicStop: function() {
      subscribers.musicStop.forEach(cb => { try { cb(); } catch (e) {} });
    },
    /**
     * Проброс данных с акселерометра (Kotlin -> JS) -> подписчики (motion-engine).
     * @param {{x:number, y:number, z:number, isBouncing:boolean}} data
     */
    emitTilt: function(data) {
      subscribers.tilt.forEach(cb => { try { cb(data || { x: 0, y: 0, z: 0, isBouncing: false }); } catch (e) {} });
    },

    sendVoice: function(buffer) {
      if (window.Asuna && typeof window.Asuna.sendVoice === 'function') {
        const bytes = new Uint8Array(buffer);
        let binary = '';
        for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]);
        window.Asuna.sendVoice(btoa(binary));
      }
    },
    sendTouch: function(zone) {
      if (window.Asuna && typeof window.Asuna.sendTouch === 'function') window.Asuna.sendTouch(zone);
    },
    openControl: function() {
      if (window.Asuna && typeof window.Asuna.openControl === 'function') window.Asuna.openControl();
    },
    hideWindow: function() {},
    quit: function() {},
    dragWindow: function() {},
    toggleAlwaysOnTop: function() {},
    playMusic: function() {},
    stopMusic: function() {},
    pauseMusic: function() {},
    resumeMusic: function() {},
    nextTrack: function() {},
    prevTrack: function() {},
    setVolume: function() {}
  };

  window.vh = vh;
  console.log('[AsunaShim] Initialized (Android bridge:', !!window.Asuna, ')');
})();
