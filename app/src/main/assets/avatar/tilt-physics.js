/* global window, console */
/**
 * Jiggle Physics — реакция аватара на движение телефона.
 *
 * Реализует ПРУЖИННУЮ (underdamped) "желейную" физику:
 *  - При резком движении телефона пружина прыгает + колеблется туда-сюда
 *    3-4 раза с постепенным затуханием (как грудь при ходьбе или беге).
 *  - Spring-damper выполняется в JS, в PIXI ticker (60Hz) — НЕ через motion-engine,
 *    потому что Live2D Physics plugin не загружен в WebView (в PC-версии тоже нет).
 *
 * Параметры Cubism 2, которые качаются:
 *  - PARAM_BUST_Y         — основной параметр груди (если есть в .moc)
 *  - PARAM_BUST_X         — боковое качание (если есть)
 *  - PARAM_BODY_ANGLE_X   — fallback: наклон тела вперёд/назад
 *  - PARAM_BODY_ANGLE_Y   — fallback: наклон тела бок (Z)
 *  - PARAM_BODY_ANGLE_Z   — fallback: наклон тела вокруг Z
 *  - PARAM_ANGLE_X        — наклон головы
 *  - PARAM_HAIR_FRONT/SIDE/BACK — волосы (есть во всех моделях)
 *  - PARAM_BREATH         — глубже дыхание при тряске
 *
 * ВАЖНО: PIXI ticker работает в Main loop, между applyAction и motion-engine.
 * Поэтому jiggle-параметры нужно писать НАПРЯМУЮ в coreModel.setParamFloat,
 * и motion-engine (gesture/facial) не должен их перезаписывать.
 * Решение: jiggle пишет в слой 'explicit' — это высший приоритет, выше facial.
 */
(function() {
  'use strict';
  if (window.__tiltPhysics) return;
  window.__tiltPhysics = true;

  // Состояние пружинной системы (для каждого параметра)
  // pos = текущая позиция, vel = скорость, target = целевая
  const springState = {};
  // Целевые значения (последние полученные с датчика)
  let targets = { x: 0, y: 0, z: 0 };
  // Предыдущие целевые (для вычисления скорости от тряски)
  let prevTargets = { x: 0, y: 0, z: 0 };
  let prevTime = 0;
  let motionEngine = null;
  let capabilities = null;
  let coreModel = null;
  let pixiTickerFn = null;
  let mounted = false;

  // Жёсткость пружин и демпфирование для разных параметров
  // Меньше omega = медленнее пружина = больше "желе"
  // Меньше damping = больше колебаний
  const SPRING_PARAMS = {
    'PARAM_BUST_Y':          { k: 18, damping: 0.10, gain: 12,  max: 10 }, // САМОЕ "ЖЕЛЕ"
    'PARAM_BUST_X':          { k: 22, damping: 0.14, gain: 8,   max: 8 },
    'PARAM_BODY_ANGLE_X':    { k: 20, damping: 0.22, gain: 12,  max: 10 },
    'PARAM_BODY_ANGLE_Y':    { k: 18, damping: 0.20, gain: 8,   max: 6 },
    'PARAM_BODY_ANGLE_Z':    { k: 22, damping: 0.22, gain: 10,  max: 8 },
    'PARAM_ANGLE_X':         { k: 25, damping: 0.18, gain: 6,   max: 5 },
    'PARAM_ANGLE_Y':         { k: 28, damping: 0.32, gain: 3,   max: 3 },
    'PARAM_ANGLE_Z':         { k: 28, damping: 0.32, gain: 3,   max: 3 },
    'PARAM_HAIR_FRONT':      { k: 10, damping: 0.06, gain: 8,   max: 10 },
    'PARAM_HAIR_SIDE':       { k: 8,  damping: 0.05, gain: 8,   max: 10 },
    'PARAM_HAIR_BACK':       { k: 6,  damping: 0.04, gain: 10,  max: 12 },
    'PARAM_BREATH':          { k: 30, damping: 0.50, gain: 0.5, max: 0.5 }
  };

  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  function initSpring(paramId) {
    if (!springState[paramId]) {
      springState[paramId] = { pos: 0, vel: 0 };
    }
  }

  // Spring-damper simulation (semi-implicit Euler)
  // dt ~= 1/60
  function updateSpring(paramId, target, dt) {
    const p = SPRING_PARAMS[paramId];
    if (!p) return 0;
    initSpring(paramId);
    const s = springState[paramId];
    // F = -k * (pos - target) - c * vel
    // где c = 2 * sqrt(k * m) * damping_ratio, m = 1
    const c = 2 * Math.sqrt(p.k) * p.damping;
    const accel = -p.k * (s.pos - target) - c * s.vel;
    s.vel += accel * dt;
    s.pos += s.vel * dt;
    return clamp(s.pos, -p.max, p.max);
  }

  // PIXI ticker callback — обновляет пружины и пишет в coreModel
  // Счётчик кадров для периодического лога
  if (window.__tiltFrame === undefined) window.__tiltFrame = 0;
  function onTick(deltaMS) {
    window.__tiltFrame++;
    if (window.__tiltFrame % 30 === 0 && coreModel) {
      // Логируем какая пружина качается сильнее всего
      let maxPos = 0, maxParam = '';
      for (const id of Object.keys(springState)) {
        if (Math.abs(springState[id].pos) > Math.abs(maxPos)) {
          maxPos = springState[id].pos;
          maxParam = id;
        }
      }
      if (Math.abs(maxPos) > 0.1) {
        console.log('[TiltPhysics] jiggle: ' + maxParam + '=' + maxPos.toFixed(2) + ' targets=(x:' + targets.x.toFixed(2) + ',y:' + targets.y.toFixed(2) + ')');
      }
    }

    if (!coreModel) return;
    const dt = Math.min(deltaMS / 1000, 1/30); // clamp dt to 1/30 to avoid big jumps
    for (const paramId of Object.keys(SPRING_PARAMS)) {
      if (!capabilities || !capabilities.has(paramId)) continue;
      // Целевое значение зависит от параметра
      let target = 0;
      if (paramId === 'PARAM_BUST_Y' || paramId === 'PARAM_BODY_ANGLE_X' || paramId === 'PARAM_ANGLE_X') {
        // Тряска по Y (вверх-вниз) -> -tiltY (при тряске вверх тело "откидывается назад" -> грудь по Y падает вниз)
        target = -targets.y * SPRING_PARAMS[paramId].gain;
      } else if (paramId === 'PARAM_BUST_X' || paramId === 'PARAM_BODY_ANGLE_Z' || paramId === 'PARAM_ANGLE_Z') {
        // Наклон влево-вправо -> грудь качается вбок (отстаёт)
        target = -targets.x * SPRING_PARAMS[paramId].gain;
      } else if (paramId === 'PARAM_BODY_ANGLE_Y') {
        // Покачивание вперёд-назад
        target = -targets.y * SPRING_PARAMS[paramId].gain * 0.6;
      } else if (paramId === 'PARAM_HAIR_FRONT' || paramId === 'PARAM_HAIR_SIDE' || paramId === 'PARAM_HAIR_BACK') {
        // Волосы качаются от любого движения
        const totalTilt = Math.sqrt(targets.x * targets.x + targets.y * targets.y);
        target = -totalTilt * SPRING_PARAMS[paramId].gain;
      } else if (paramId === 'PARAM_BREATH') {
        // Дыхание глубже при движении
        const totalTilt = Math.abs(targets.y) + Math.abs(targets.x) * 0.5;
        target = clamp(0.5 + totalTilt * SPRING_PARAMS[paramId].gain, 0.4, 1.0);
      }
      const value = updateSpring(paramId, target, dt);
      // Пишем напрямую в coreModel. Это будет перекрыто motion-engine только
      // если motion-engine.play() слоя 'explicit' (которого у нас нет).
      try {
        coreModel.setParamFloat(paramId, value);
      } catch (e) {
        // параметр не существует — игнорируем
      }
    }
  }

  function tryMount() {
    if (mounted) return;
    const state = (window.__vh && window.__vh.state) || null;
    if (!state) return;
    if (!state.live2dModel) return;
    if (!state.motionEngine) return;

    // Получаем coreModel и pixiApp
    coreModel = state.live2dModel.internalModel && state.live2dModel.internalModel.coreModel;
    if (!coreModel) return;
    capabilities = state.capabilities;
    motionEngine = state.motionEngine;
    const pixiApp = state.pixiApp;
    if (!pixiApp) return;

    // Подписываемся на PIXI ticker
    pixiTickerFn = onTick;
    if (pixiApp.ticker) {
      pixiApp.ticker.add(pixiTickerFn, pixiApp, 0); // priority 0 = normal, between motion-engine (LOW=-1) и standard (0)
      mounted = true;
      console.log('[TiltPhysics] Mounted. SPRING_PARAMS for', Array.from(capabilities).filter(p => SPRING_PARAMS[p]).join(', '));
    }
  }

  function applyTilt(data) {
    if (!data) return;
    targets = {
      x: clamp(data.x || 0, -1.5, 1.5),
      y: clamp(data.y || 0, -1.5, 1.5),
      z: clamp(data.z || 0, 0, 1.5)
    };
  }

  // Подписываемся на onTilt сразу
  if (window.vh && window.vh.onTilt) {
    window.vh.onTilt(applyTilt);
  }

  // Проверяем готовность app.js и motionEngine
  let attempts = 0;
  const interval = setInterval(() => {
    attempts++;
    if (mounted || attempts > 100) {
      clearInterval(interval);
      if (!mounted) {
        console.warn('[TiltPhysics] Failed to mount after 100 attempts');
      }
      return;
    }
    tryMount();
  }, 200);

  setTimeout(tryMount, 100);
})();
