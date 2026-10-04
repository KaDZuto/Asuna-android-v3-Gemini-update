// motion-engine.js — процедурный движок анимации поверх Cubism 2.1 параметров.
//
// Идея: вместо того чтобы дёргать setParamFloat один раз (значение тут же
// перетирается собственной idle/mtn-анимацией модели на следующем кадре),
// движок каждый кадр САМ пересчитывает нужное значение по временной кривой
// (несколько фаз: подъезд → удержание → возврат) и применяет его ПОСЛЕ
// внутреннего апдейта модели — точно так же, как уже сделано для липсинка
// в app.js (PIXI.UPDATE_PRIORITY.LOW).
//
// Слои (層/layers), от нижнего к верхнему — верхний перекрывает нижний,
// как в Photoshop:
//   idle       — фоновые микродвижения (см. gestures.js: startIdleDrift)
//   breathing  — дыхание (сейчас не используется отдельно, дыхание уже в .mtn)
//   facial     — мимика
//   gesture    — явные жесты (wave/nod/bow/...)
//   explicit   — прямой "params" из LLM (наивысший приоритет, эскейп-хэтч)
//
// Как только все фазы трека доиграли (и последняя фаза не "held") — трек
// удаляется из своего слоя, и параметр автоматически возвращается под
// управление нижнего слоя (обычно — родной .mtn/idle анимации модели).

(function (global) {
  const EASE = {
    linear: (t) => t,
    easeIn: (t) => t * t * t,
    easeOut: (t) => 1 - Math.pow(1 - t, 3),
    easeInOut: (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2)
  };

  // Сентинел: "вернуться к тому значению, с которого трек начал играть" —
  // движок подставит реальное число в момент play(), а не в момент описания
  // жеста (жест не может заранее знать текущее значение параметра).
  const FROM = '__FROM__';

  const LAYER_ORDER = ['idle', 'breathing', 'facial', 'gesture', 'explicit'];

  class MotionEngine {
    // setParam(paramId, value), getParam(paramId) -> number|null — обычно
    // тонкие обёртки над coreModel.setParamFloat/getParamFloat с try/catch.
    constructor(setParam, getParam) {
      this.setParam = setParam;
      this.getParam = getParam;
      this.layers = Object.fromEntries(LAYER_ORDER.map((l) => [l, new Map()]));
    }

    // spec: { layer?: string, tracks: [{ param, phases: [{to, duration, delay?, easing?, hold?}] }], onDone? }
    // Возвращает spec обратно — вызывающий может использовать его как "хэндл"
    // для последующего stop(), хотя обычно это и не нужно (жесты сами доигрывают).
    play(spec) {
      const layer = spec.layer && this.layers[spec.layer] ? spec.layer : 'gesture';
      const now = performance.now();
      spec._fired = false;
      spec._remaining = spec.tracks.length;
      for (const t of spec.tracks) {
        const from = this.getParam(t.param);
        const baseline = typeof from === 'number' ? from : 0;
        const phases = t.phases.map((p) => (p.to === FROM ? { ...p, to: baseline } : p));
        this.layers[layer].set(t.param, {
          param: t.param,
          phases,
          phaseIndex: 0,
          phaseStartedAt: now,
          phaseFromValue: baseline,
          onDoneSpec: spec
        });
      }
      return spec;
    }

    stopLayer(layer) {
      if (this.layers[layer]) this.layers[layer].clear();
    }

    isLayerBusy(layer) {
      return !!(this.layers[layer] && this.layers[layer].size > 0);
    }

    // Вызывать каждый кадр (см. app.js: pixiApp.ticker.add(..., LOW priority)).
    update(nowMs) {
      const composed = new Map();
      for (const layerName of LAYER_ORDER) {
        const layerMap = this.layers[layerName];
        for (const [paramId, track] of [...layerMap.entries()]) {
          const value = this._advance(track, nowMs, layerMap, paramId);
          if (value !== null) composed.set(paramId, value); // верхний слой перезапишет значение нижнего
        }
      }
      for (const [paramId, value] of composed) this.setParam(paramId, value);
    }

    _advance(track, nowMs, layerMap, paramId) {
      const phase = track.phases[track.phaseIndex];
      if (!phase) return null;
      const delay = phase.delay || 0;
      const elapsed = nowMs - track.phaseStartedAt - delay;
      if (elapsed < 0) return track.phaseFromValue;

      const duration = Math.max(1, phase.duration || 300);
      const t = Math.min(1, elapsed / duration);
      const ease = EASE[phase.easing] || EASE.easeInOut;
      const value = track.phaseFromValue + (phase.to - track.phaseFromValue) * ease(t);

      if (t >= 1) {
        if (phase.hold) return phase.to; // держим бесконечно, пока трек явно не остановят
        track.phaseIndex++;
        track.phaseStartedAt = nowMs;
        track.phaseFromValue = phase.to;
        if (!track.phases[track.phaseIndex]) {
          // фазы закончились — освобождаем параметр слою ниже
          layerMap.delete(paramId);
          const spec = track.onDoneSpec;
          if (spec) {
            spec._remaining -= 1;
            if (spec._remaining <= 0 && !spec._fired) {
              spec._fired = true;
              try { spec.onDone && spec.onDone(); } catch (e) { /* noop */ }
            }
          }
          return phase.to; // последний кадр ещё рисуем со значением на месте, дальше слой освобождён
        }
        return track.phaseFromValue;
      }
      return value;
    }
  }

  global.MotionEngine = MotionEngine;
  global.MOTION_FROM = FROM;
})(window);
