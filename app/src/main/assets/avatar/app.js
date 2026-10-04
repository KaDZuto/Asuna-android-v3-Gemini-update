/* global PIXI */
(async () => {
  console.log('!!! APP.JS LOADED - VERSION 2026-08-26 !!!');
  const state = {
    settings: null,
    models: [],
    currentModel: null,
    pixiApp: null,
    live2dModel: null,
    currentManifest: { expressions: [], motions: {} }
  };

  const cfg = await window.vh.init();
  state.settings = cfg.settings;
  state.models = cfg.models;

  // ─────────────────────────── плеер в отдельном окне ───────────────────────────
  let playerWindow = null;
  let playlistData = [];

  function openPlayerWindow() {
    if (playerWindow && !playerWindow.closed) {
      playerWindow.focus();
      return;
    }
    const win = window.open('player.html', 'MusicPlayer', 'width=380,height=600,resizable=yes,minimizable=yes');
    if (!win) {
      alert('Не удалось открыть окно плеера. Разрешите всплывающие окна.');
      return;
    }
    playerWindow = win;
    setTimeout(() => {
      win.postMessage({ type: 'playlistUpdate', playlist: playlistData }, '*');
    }, 200);
  }

  // Обработка сообщений от плеера
  window.addEventListener('message', (e) => {
    if (!e.data || typeof e.data !== 'object') return;
    if (e.data.type === 'playMusic' && e.data.path) {
      window.vh?.playMusic?.(e.data.path);
    } else if (e.data.type === 'stopMusic') {
      window.vh?.stopMusic?.();
    } else if (e.data.type === 'pauseMusic') {
      window.vh?.pauseMusic?.();
    } else if (e.data.type === 'resumeMusic') {
      window.vh?.resumeMusic?.();
    } else if (e.data.type === 'nextTrack') {
      window.vh?.nextTrack?.();
    } else if (e.data.type === 'prevTrack') {
      window.vh?.prevTrack?.();
    } else if (e.data.type === 'setVolume' && typeof e.data.volume === 'number') {
      window.vh?.setVolume?.(e.data.volume);
    }
  });

  // ─────────────────────────── липсинк ───────────────────────────
  // Раньше рот открывался один раз при получении action.mouth_open и тут же
  // "захлопывался" обратно — потому что Cubism2 сама каждый кадр прогоняет
  // параметры текущей motion/idle-анимации и перетирает разовый setParamFloat.
  // Решение: держим live-уровень громкости (шлёт окно чата, пока играет TTS)
  // и накатываем его КАЖДЫЙ кадр, ПОСЛЕ внутреннего апдейта модели (LOW priority
  // в тикере PIXI гарантирует такой порядок).
  //
  // ВАЖНО: объявляем mouthLevel ДО initPixi()/await loadModel() — тикер PIXI может
  // дёрнуть наш колбэк уже на первом кадре, пока идёт await, и если mouthLevel в
  // этот момент ещё не инициализирована (TDZ у let/const), тикер падает с
  // ReferenceError и — что хуже — вместе с этим намертво стопорится весь общий
  // rAF-луп PIXI, потому что следующий requestAnimationFrame просто не успевает
  // запланироваться. Итог — персонаж никогда не появляется, экран замирает пустым.
  let mouthLevel = 0;
  window.vh.onMouthLevel((level) => { mouthLevel = Math.max(0, Math.min(1, level || 0)); });

  // ─────────────────────────── Reaction Queue ───────────────────────────
  const reactionQueue = [];
  let isQueueBusy = false;

  function processQueue() {
    if (isQueueBusy || reactionQueue.length === 0) return;

    const task = reactionQueue.shift();
    console.log('[app] processQueue executing task:', JSON.stringify(task));
    isQueueBusy = true;

    try {
      if (task.type === 'expression') {
        const idx = state.currentManifest.expressions.findIndex(
          (e) => e.raw.replace('.exp.json', '') === task.value
        );
        console.log('[app] expression search:', { value: task.value, index: idx, total: state.currentManifest.expressions.length });
        if (idx >= 0) {
          console.log('[app] calling model.expression(', idx, ')');
          state.live2dModel?.expression?.(idx);
        } else {
          console.warn('[app] expression not found in manifest:', task.value);
        }
        // Expressions are instant, move to next immediately
        isQueueBusy = false;
        processQueue();
      } else if (task.type === 'motion') {
      const group = task.value?.group;
      const index = task.value?.index;
      console.log('[app] calling model.motion(', group, index, ')');
      state.live2dModel?.motion?.(group, index);
      // Native motions don't have a simple callback; use a default delay (e.g., 1s)
      setTimeout(() => {
        isQueueBusy = false;
        processQueue();
      }, 1000);
    } else if (task.type === 'gesture') {
        const spec = buildGestureSpec(task.value.name, task.value.opts, state.capabilities);
        if (spec) {
          console.log('[app] playing gesture:', task.value.name);
          let finished = false;
          const timeout = setTimeout(() => {
            if (!finished) {
              finished = true;
              isQueueBusy = false;
              processQueue();
            }
          }, 3000);
          state.motionEngine.play({
            ...spec,
            onDone: () => {
              if (!finished) {
                finished = true;
                clearTimeout(timeout);
                isQueueBusy = false;
                processQueue();
              }
            }
          });
        } else {
          console.warn('[app] gesture spec not found:', task.value.name);
          isQueueBusy = false;
          processQueue();
        }
      } else if (task.type === 'facial') {
        console.log('[app] playing facial tracks');
        state.motionEngine.play({ layer: 'facial', tracks: task.tracks });
        // Facial tracks are usually held (hold: true), so they don't "finish".
        // We don't block the queue for them.
        isQueueBusy = false;
        processQueue();
      }
    } catch (err) {
      console.error('[app] processQueue ERROR:', err);
      isQueueBusy = false;
      processQueue();
    }
  }

  function enqueueAction(type, value) {
    reactionQueue.push({ type, value });
    processQueue();
  }
  // Процедурный движок жестов (motion-engine.js/gestures.js) — накатывает
  // временные кривые параметров каждый кадр, слоями поверх родной .mtn-анимации.
  // Аксессоры обращаются к state.live2dModel ДИНАМИЧЕСКИ (а не через захваченный
  // на момент создания core), поэтому один и тот же engine переживает смену модели.
  function coreParamSet(paramId, value) {
    try {
      const core = state.live2dModel?.internalModel?.coreModel;
      if (core?.setParamFloat) core.setParamFloat(paramId, value);
    } catch (e) { /* у модели нет такого параметра — не критично */ }
  }
  function coreParamGet(paramId) {
    try {
      const core = state.live2dModel?.internalModel?.coreModel;
      if (core?.getParamFloat) return core.getParamFloat(paramId);
    } catch (e) { /* noop */ }
    return null;
  }
  const motionEngine = new MotionEngine(coreParamSet, coreParamGet);
  state.motionEngine = motionEngine;
  state.capabilities = null; // Set<paramId>, заполняется в loadModel() при загрузке каждой модели

  applyPinIcon();
  initPixi();
  await loadModel(state.settings.currentModel || state.models[0]?.id);
  wireToolbar();
  wirePetZoneDrag();

  window.vh.onModelSet((id) => loadModel(id));
  window.vh.onSettingsSync((s) => {
    state.settings = s;
    applyPinIcon();
    layoutModel();
  });
  window.vh.onAvatarReact((payload) => {
    applyAction(payload.action);
  });

  startIdleDrift();

  // ─────────────────────────── живой idle ───────────────────────────
  // Вместо голого чередования IDLING/IDLING_02/IDLING_03 добавляем изредка
  // едва заметные микродвижения (взгляд в сторону, лёгкий наклон головы) —
  // слой 'idle' в движке, самый нижний приоритет, поэтому любой настоящий
  // жест (層 'gesture') или ответ LLM (層 'facial') его тут же перекрывает.
  // Не трогает моргание/дыхание — это уже честно отрабатывает сама .mtn.
  const IDLE_DRIFT_GESTURES = ['look_left', 'look_right', 'tilt_head_left', 'tilt_head_right', 'look_up', 'nod', 'shake_head'];
  const CHEERFUL_IDLE_EXPRESSIONS = ['F_FUN', 'F_FUN_SMILE', 'F_FUN_WARM', 'F_EUPHORIA', 'F_PLAYFUL', 'F_WARM_SMILE', 'F_GIGGLE', 'F_TENDER', 'F_ADORE', 'F_PROUD'];

  function buildGestureSpec(name, options, capabilities) {
    const intensity = options.intensity || 0.5;
    const speed = options.speed || 0.5;
    const duration = 800 / (1 + speed * 0.5);
    const gestures = {
      'look_left': { param: 'ParamAngleY', to: -30 * intensity },
      'look_right': { param: 'ParamAngleY', to: 30 * intensity },
      'look_up': { param: 'ParamAngleX', to: -30 * intensity },
      'look_down': { param: 'ParamAngleX', to: 30 * intensity },
      'tilt_head_left': { param: 'ParamAngleZ', to: -15 * intensity },
      'tilt_head_right': { param: 'ParamAngleZ', to: 15 * intensity },
      'nod': { param: 'ParamAngleX', phases: [{ to: 10 * intensity, duration: duration/2 }, { to: 0, duration: duration/2 }] },
      'shake_head': { param: 'ParamAngleY', phases: [{ to: 15 * intensity, duration: duration/2 }, { to: 0, duration: duration/2 }] },
      'surprised_recoil': { param: 'ParamAngleX', phases: [{ to: -20 * intensity, duration: duration/2 }, { to: 0, duration: duration/2 }] },
      'wave': { param: 'ParamAngleY', phases: [{ to: 20 * intensity, duration: duration/2 }, { to: -20 * intensity, duration: duration/2 }, { to: 0, duration: duration/2 }] }
    };
    const g = gestures[name];
    if (!g) return null;
    if (g.phases) {
      return { tracks: [{ param: g.param, phases: g.phases }] };
    } else {
      return { tracks: [{ param: g.param, phases: [{ to: g.to, duration: duration }, { to: 0, duration: duration }] }] };
    }
  }

  function startIdleDrift() {
    const scheduleNext = () => {
      const delayMs = 5000 + Math.random() * 8000; // чуть чаще: раз в 5-13 сек
      setTimeout(() => {
        try {
          if (state.live2dModel && !state.motionEngine.isLayerBusy('gesture') && document.visibilityState !== 'hidden') {
            // 1. Выбираем движение
            const name = IDLE_DRIFT_GESTURES[Math.floor(Math.random() * IDLE_DRIFT_GESTURES.length)];
            const spec = buildGestureSpec(name, { intensity: 0.15 + Math.random() * 0.2, speed: 0.6 + Math.random() * 0.3 }, state.capabilities);
            if (spec) { spec.layer = 'idle'; state.motionEngine.play(spec); }

            // 2. С шансом 30% добавляем мимолётную весёлую эмоцию
            if (Math.random() < 0.3) {
              const expr = CHEERFUL_IDLE_EXPRESSIONS[Math.floor(Math.random() * CHEERFUL_IDLE_EXPRESSIONS.length)];
              const idx = state.currentManifest.expressions.findIndex(e => e.raw.replace('.exp.json', '') === expr);
              if (idx >= 0) state.live2dModel.expression?.(idx);
            }
          }
        } finally {
          scheduleNext();
        }
      }, delayMs);
    };
    scheduleNext();
  }

  // ─────────────────────────── PIXI / Live2D ───────────────────────────
  function initPixi() {
    state.pixiApp = new PIXI.Application({
      view: document.getElementById('live2d-canvas'),
      autoStart: true,
      resizeTo: document.getElementById('petZone'),
      transparent: true,
      antialias: true,
      backgroundAlpha: 0,
      resolution: window.devicePixelRatio || 1,
      autoDensity: true
    });

    document.getElementById('petZone').addEventListener('mousemove', (e) => {
      if (state.live2dModel) state.live2dModel.focus(e.clientX, e.clientY);
    });

    // LOW priority = выполнится после внутреннего апдейта Live2D-модели на этом же
    // тике, поэтому наши значения не будут тут же перезатёрты анимацией/физикой.
    state.pixiApp.ticker.add(() => {
      if (!state.live2dModel) return;
      if (mouthLevel > 0.001) coreParamSet('PARAM_MOUTH_OPEN_Y', mouthLevel);
      motionEngine.update(performance.now());
    }, null, PIXI.UPDATE_PRIORITY.LOW);
  }

  async function loadModel(modelId) {
    if (!modelId) return;
    const chipLabel = document.getElementById('chipLabel');
    chipLabel.textContent = 'ЗАГРУЗКА…';

    if (state.live2dModel) {
      state.pixiApp.stage.removeChild(state.live2dModel);
      state.live2dModel.destroy({ children: true, texture: true, baseTexture: true });
      state.live2dModel = null;
    }

    const modelPath = `../resources/models/${modelId}/${modelId}.model.json`;
    try {
      const model = await PIXI.live2d.Live2DModel.from(modelPath);
      state.live2dModel = model;
      state.currentModel = modelId;

      model.anchor.set(0.5, 0.5);
      layoutModel();
      model.autoInteract = true;
      state.pixiApp.stage.addChild(model);

      await parseManifest(modelPath);
      chipLabel.textContent = modelId.toUpperCase().replace('ASUNA_', 'ASUNA ');
      console.log('[app] Model loaded successfully:', modelId);
      buildCapabilities();
    } catch (err) {
      console.error('Live2D load error', err);
      chipLabel.textContent = 'ОШИБКА ЗАГРУЗКИ';
    }
  }

  // Аудит параметров ЗАГРУЖЕННОЙ модели — какие из параметров, нужных жестам,
  // у неё реально есть (getParamIndex возвращает -1, если параметра нет).
  // На всех 52 моделях Asuna в этом проекте набор идентичен (см.
  // audit_params.js / resources/models/_capability_audit.json), но эта проверка
  // не завязана на конкретную модель и одинаково корректно отработает для любой
  // будущей модели с урезанным/другим ригом — недостающие жесты просто тихо
  // не будут доступны вместо падения или искажения меша.
  function buildCapabilities() {
    const core = state.live2dModel?.internalModel?.coreModel;
    state.capabilities = new Set();
    if (!core?.getParamIndex) return;

    const gestures = window.GESTURES;
    const gestureNames = window.GESTURE_NAMES;

    if (!gestures || !gestureNames) {
      console.error('[app] GESTURES or GESTURE_NAMES not found on window. Check gestures.js loading.');
      return;
    }

    const check = new Set(['PARAM_ANGLE_X', 'PARAM_ANGLE_Y', 'PARAM_ANGLE_Z', 'PARAM_MOUTH_OPEN_Y', 'PARAM_EYE_L_OPEN', 'PARAM_EYE_R_OPEN']);
    for (const g of Object.values(gestures)) {
      if (g.requires) g.requires.forEach((p) => check.add(p));
    }
    for (const p of check) {
      try { if (core.getParamIndex(p) !== -1) state.capabilities.add(p); } catch (e) { /* noop */ }
    }
    const unavailable = gestureNames.filter((n) => n !== 'small_jump' && !isGestureAvailable(n, state.capabilities));
    if (unavailable.length) console.warn('[app] Модель', state.currentModel, '— недоступны жесты:', unavailable.join(', '));
  }

  function layoutModel() {
    if (!state.live2dModel || !state.pixiApp) return;
    const scalePct = (state.settings.scale || 1);
    const screenW = state.pixiApp.screen.width;
    const screenH = state.pixiApp.screen.height;
    state.live2dModel.position.set(screenW / 2, screenH / 2 + screenH * 0.06);
    const baseScale = (screenH * 0.86) / state.live2dModel.height;
    state.live2dModel.scale.set(baseScale * scalePct);
  }

  window.addEventListener('resize', () => layoutModel());

  async function parseManifest(manifestUrl) {
    state.currentManifest = { expressions: [], motions: {} };
    try {
      const res = await fetch(manifestUrl);
      const json = await res.json();
      if (json.expressions) {
        json.expressions.forEach((exp, index) => {
          state.currentManifest.expressions.push({ raw: exp.name || '', index });
        });
      }
      if (json.motions) state.currentManifest.motions = json.motions;
    } catch (e) {
      console.warn('Manifest parse failed', e);
    }
  }

  function playRandomReaction() {
    const groups = Object.keys(state.currentManifest.motions || {});
    if (!groups.length) return;
    const g = groups[Math.floor(Math.random() * groups.length)];
    const list = state.currentManifest.motions[g];
    if (!list || !list.length) return;
    const idx = Math.floor(Math.random() * list.length);
    state.live2dModel?.motion?.(g, idx);
    const exprCount = state.currentManifest.expressions.length;
    if (exprCount) {
      const ei = Math.floor(Math.random() * exprCount);
      state.live2dModel?.expression?.(ei);
    }
  }

  function playSqueezeEffect() {
    if (!state.live2dModel) return;

    const tracks = [
      {
        param: 'PARAM_BUST_Y',
        phases: [
          { to: -40, duration: 80, easing: 'easeOut' },    // Очень глубокое нажатие
          { to: 15, duration: 150, easing: 'easeInOut' },  // Сильнее отскок
          { to: -10, duration: 150, easing: 'easeInOut' },
          { to: 0, duration: 250, easing: 'easeInOut' }
        ]
      },
      {
        param: 'PARAM_BODY_ANGLE_Y',
        phases: [
          { to: -10, duration: 80, easing: 'easeOut' },
          { to: 0, duration: 400, easing: 'easeInOut' }
        ]
      }
    ];

    state.motionEngine.play({ layer: 'explicit', tracks });
  }

  function playCheekSnapBack() {
    if (!state.live2dModel) return;
    const tracks = ['PARAM_CHEEK_01', 'PARAM_CHEEK_02', 'PARAM_CHEEK_03', 'PARAM_CHEEK_04'].map(p => ({
      param: p,
      phases: [
        { to: 0, duration: 150, easing: 'easeOut' },
        { to: 2, duration: 100, easing: 'easeInOut' },
        { to: 0, duration: 150, easing: 'easeInOut' }
      ]
    }));
    state.motionEngine.play({ layer: 'explicit', tracks });
  }

  function handleTouchReaction(x, y) {
    if (!state.live2dModel) return;

    const hitArea = state.live2dModel.hitTest(x, y);
    console.log('[app] Hit area:', hitArea ? JSON.stringify(hitArea) : 'null');

    if (!hitArea) {
      // Нажатие в пустоте — просто игнорируем, чтобы не считать за касание и не сбивать перетаскивание
      return;
    }

    const area = String(hitArea).toLowerCase();
    const rect = state.pixiApp.view.getBoundingClientRect();
    const relY = y - rect.top;
    let finalZone = area;

    // Разделение зон на основе координат Y
    if (area.includes('foot')) {
      if (relY < rect.height * 0.75) finalZone = 'crotch';
      else if (relY < rect.height * 0.85) finalZone = 'thighs';
      else finalZone = 'calves';
    } else if (area.includes('body')) {
      if (relY < rect.height * 0.55) finalZone = 'chest'; // fallback
      else finalZone = 'stomach';
    }

    window.vh.sendTouch(finalZone);

    // ── МГНОВЕННЫЕ РЕАКЦИИ (без участия LLM) ──────────────────────────────────
    // Эти анимации срабатывают сразу, создавая ощущение отзывчивости
    if (area.includes('head')) {
      const relX = x - rect.left;
      const centerX = rect.width / 2;

      if (relY < rect.height * 0.3) {
        // Затылок -> Удивление + наклон
        applyAction({ expression: 'F_SURPRISE', gestures: [{ name: 'tilt_head_left', intensity: 0.5 }] });
      } else if (Math.abs(relX - centerX) > rect.width * 0.15) {
        // Щеки -> Смущение + кивок
        applyAction({ expression: 'F_FUN_HANIKAMI', gestures: [{ name: 'nod', intensity: 0.3 }] });
      } else {
        // Центр головы -> "Гладилки" (теплота + легкий кивок)
        applyAction({ expression: 'F_FUN_WARM', gestures: [{ name: 'nod', intensity: 0.4, speed: 0.7 }] });
      }
    } else if (area.includes('chest')) {
      playSqueezeEffect();
      applyAction({ expression: 'F_FUN_HANIKAMI', gestures: [{ name: 'surprised_recoil', intensity: 0.8 }] });
    } else if (area.includes('body')) {
      applyAction({ expression: 'F_FUN', gestures: [{ name: 'wave', side: 'right', intensity: 0.4 }] });
    } else if (area.includes('foot')) {
      applyAction({ expression: 'F_ANGRY', gestures: [{ name: 'shake_head', intensity: 0.7 }] });
    }
  }



  function applyAction(action) {
    if (!action || !state.live2dModel) return;
    console.log('[app] applyAction:', JSON.stringify(action, null, 2));

    // 1. Expressions - Instant
    if (action.expression) {
      enqueueAction('expression', action.expression);
    }
    // ... (rest of the function)

    // 2. Native Motions - Sequence
    if (action.motion_group !== undefined && action.motion_index !== undefined) {
      enqueueAction('motion', { group: action.motion_group, index: action.motion_index });
    }

    // 3. Facial Pose - Continuous (not queued)
    const facialTracks = [];
    if (typeof action.head_x === 'number') facialTracks.push({ param: 'PARAM_ANGLE_X', phases: [{ to: action.head_x * 30, duration: 380, easing: 'easeInOut', hold: true }] });
    if (typeof action.head_y === 'number') facialTracks.push({ param: 'PARAM_ANGLE_Y', phases: [{ to: action.head_y * 30, duration: 380, easing: 'easeInOut', hold: true }] });
    if (typeof action.body_angle === 'number') facialTracks.push({ param: 'PARAM_ANGLE_Z', phases: [{ to: action.body_angle, duration: 380, easing: 'easeInOut', hold: true }] });
    if (typeof action.eye_open === 'number') {
      facialTracks.push({ param: 'PARAM_EYE_L_OPEN', phases: [{ to: action.eye_open, duration: 200, easing: 'easeInOut', hold: true }] });
      facialTracks.push({ param: 'PARAM_EYE_R_OPEN', phases: [{ to: action.eye_open, duration: 200, easing: 'easeInOut', hold: true }] });
    }
    if (facialTracks.length) state.motionEngine.play({ layer: 'facial', tracks: facialTracks });

    // 4. Mouth - Special handling
    if (typeof action.mouth_open === 'number') coreParamSet('PARAM_MOUTH_OPEN_Y', action.mouth_open);

    // 5. Explicit Params - Continuous
    if (action.params && typeof action.params === 'object') {
      const explicitTracks = Object.entries(action.params)
        .filter(([paramId, value]) => paramId && typeof value === 'number')
        .map(([paramId, value]) => ({ param: paramId, phases: [{ to: value, duration: 250, easing: 'easeInOut', hold: true }] }));
      if (explicitTracks.length) state.motionEngine.play({ layer: 'explicit', tracks: explicitTracks });
    }

    // 6. Gestures - Sequence
    const gestureCalls = Array.isArray(action.gestures) ? action.gestures.slice(0, 4) : [];
    if (action.gesture && action.gesture !== 'none' && action.gesture !== 'jump') {
      gestureCalls.push({ name: action.gesture });
    }
    for (const call of gestureCalls) {
      if (!call || !call.name) continue;
      enqueueAction('gesture', {
        name: call.name,
        opts: {
          side: call.side,
          intensity: typeof call.intensity === 'number' ? call.intensity : undefined,
          speed: typeof call.speed === 'number' ? call.speed : undefined
        }
      });
    }

    if (action.gesture === 'jump') playJumpGesture();
  }

  // "Прыжок" — экспериментальный жест, который LLM может попросить через
  // <live2d>{"gesture":"jump"}</live2d>. Реализован программным твином позиции
  // спрайта (а не через Cubism-параметры), поэтому гарантированно работает на
  // ЛЮБОЙ модели, независимо от того, что у неё есть в риге.
  let jumpBusy = false;
  function playJumpGesture() {
    if (!state.live2dModel || !state.pixiApp || jumpBusy) return;
    jumpBusy = true;
    const model = state.live2dModel;
    const baseY = model.position.y;
    const peakOffset = state.pixiApp.screen.height * 0.10;
    const upMs = 220, downMs = 260;
    const t0 = performance.now();
    const ease = (t) => 1 - Math.pow(1 - t, 2); // easeOutQuad вверх, обратный — вниз

    function frame(now) {
      const elapsed = now - t0;
      if (elapsed <= upMs) {
        const p = ease(elapsed / upMs);
        model.position.y = baseY - peakOffset * p;
      } else if (elapsed <= upMs + downMs) {
        const p = ease((elapsed - upMs) / downMs);
        model.position.y = baseY - peakOffset * (1 - p);
      } else {
        model.position.y = baseY;
        jumpBusy = false;
        return;
      }
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  // ─────────────────────────── мини-панель ───────────────────────────
  // ─────────────────────────── музыка ───────────────────────────
  let audioPlayer = null;
  window.vh.onMusicPlay(({ filePath, fileName }) => {
    if (audioPlayer) audioPlayer.pause();
    const src = filePath.startsWith('http') ? filePath : `file://${filePath}`;
    audioPlayer = new Audio(src);
    audioPlayer.play().catch(e => console.error('Music play error:', e));
    console.log(`[app] playing: ${fileName}`);
  });
  window.vh.onMusicStop(() => {
    if (audioPlayer) {
      audioPlayer.pause();
      audioPlayer.currentTime = 0;
    }
  });

  function applyPinIcon() {
    document.getElementById('btnPin').classList.toggle('active', !!state.settings.alwaysOnTop);
  }

  function wireToolbar() {
    document.getElementById('btnOpenControl').onclick = () => window.vh.openControl();
    document.getElementById('btnHide').onclick = () => window.vh.hideWindow();
    document.getElementById('btnQuit').onclick = () => window.vh.quit();
    document.getElementById('btnPin').onclick = () => {
      const next = !state.settings.alwaysOnTop;
      state.settings.alwaysOnTop = next;
      window.vh.toggleAlwaysOnTop(next);
      applyPinIcon();
    };

    // Кнопка открытия плеера
    const btnPlayer = document.getElementById('btnPlayer');
    if (btnPlayer) btnPlayer.onclick = openPlayerWindow;

    // Голосовой ввод
    const btnMic = document.getElementById('btnMic');
    let mediaRecorder = null;
    let audioChunks = [];

    btnMic.onclick = async () => {
      if (mediaRecorder && mediaRecorder.state === 'recording') {
        mediaRecorder.stop();
        btnMic.classList.remove('recording');
        return;
      }

      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        mediaRecorder = new MediaRecorder(stream);
        audioChunks = [];

        mediaRecorder.ondataavailable = (e) => {
          if (e.data.size > 0) audioChunks.push(e.data);
        };

        mediaRecorder.onstop = async () => {
          const blob = new Blob(audioChunks, { type: 'audio/webm' });
          const buffer = await blob.arrayBuffer();
          window.vh.sendVoice(buffer);
          stream.getTracks().forEach(track => track.stop());
        };

        mediaRecorder.start();
        btnMic.classList.add('recording');
      } catch (err) {
        console.error('Mic access error:', err);
        alert('Нет доступа к микрофону!');
      }
    };
  }

  // ─────────────────────────── драг окна ───────────────────────────
  function wirePetZoneDrag() {
    const zone = document.getElementById('petZone');
    let dragging = false;
    let moved = false;
    let lastX = 0, lastY = 0;
    let startX = 0, startY = 0;
    let isPullingCheeks = false;

    zone.addEventListener('pointerdown', (e) => {
      if (e.button !== 0) return;

      const rect = state.pixiApp.view.getBoundingClientRect();
      const hitArea = state.live2dModel?.hitTest(e.clientX - rect.left, e.clientY - rect.top);

      dragging = true;
      moved = false;
      lastX = e.screenX;
      lastY = e.screenY;
      startX = e.clientX;
      startY = e.clientY;

      isPullingCheeks = hitArea && String(hitArea).toLowerCase().includes('head');

      zone.setPointerCapture(e.pointerId);
      zone.classList.add('dragging');
    });

    zone.addEventListener('pointermove', (e) => {
      if (!dragging) return;

      if (isPullingCheeks) {
        const dx = e.clientX - startX;
        const dy = e.clientY - startY;
        const dist = Math.sqrt(dx*dx + dy*dy);
        const pullValue = Math.min(dist / 50, 15);

        ['PARAM_CHEEK_01', 'PARAM_CHEEK_02', 'PARAM_CHEEK_03', 'PARAM_CHEEK_04'].forEach(p => {
          coreParamSet(p, pullValue);
        });
        return;
      }

      const dx = e.screenX - lastX;
      const dy = e.screenY - lastY;
      if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
        moved = true;
        window.vh.dragWindow(dx, dy);
        lastX = e.screenX;
        lastY = e.screenY;
      }
    });

    const endDrag = () => {
      if (!dragging) return;
      dragging = false;
      zone.classList.remove('dragging');

      if (isPullingCheeks) {
        playCheekSnapBack();
        applyAction({ expression: 'F_FUN_HANIKAMI', gestures: [{ name: 'nod', intensity: 0.3 }] });
        isPullingCheeks = false;
      } else if (!moved) {
        const rect = state.pixiApp.view.getBoundingClientRect();
        handleTouchReaction(startX - rect.left, startY - rect.top);
      }
    };

    zone.addEventListener('pointerup', endDrag);
    zone.addEventListener('pointercancel', endDrag);
  }

  // Для диагностики через DevTools (трей → "Диагностика"): например
  // window.__vh.applyAction({gestures:[{name:'wave',side:'right'}]})
  window.__vh = { applyAction, state, GESTURE_NAMES: window.GESTURE_NAMES };
})();
