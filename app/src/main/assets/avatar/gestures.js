// gestures.js — семантическая библиотека жестов поверх motion-engine.js.
//
// Калибровка диапазонов ниже НЕ придумана "на глаз" — это реальные min/max
// значения, которые встречаются в родных .mtn-анимациях всех 52 моделей
// (см. audit_params.js и resources/models/_capability_audit.json):
//   PARAM_ANGLE_X/Y/Z        -30..30   (голова)
//   PARAM_BODY_ANGLE_X/Y/Z   -10..10   (корпус)
//   PARAM_ARM_L/R            -10..10   (это НЕ "угол подъёма руки", а блендинг
//                                       между 3 заготовленными в риге позами:
//                                       10 = рука опущена/нейтраль в большинстве
//                                       .mtn, 0 и -10 = альтернативные позы —
//                                       поэтому "жест рукой" здесь физически
//                                       реализован как плавные качели между
//                                       этими блендами, а не честное "поднятие")
//   PARAM_UPPER_BODY         -1..1
//   PARAM_BROW_L/R_Y/ANGLE   -1..1
//   PARAM_EYE_BALL_X/Y       -1..1
//
// ВАЖНО (см. resources/models/_capability_audit.json): у рига НЕТ ни одного
// параметра ноги — значит "поднять ногу"/полноценный прыжок ногами физически
// нельзя выполнить через параметры. small_jump поэтому реализован ОТДЕЛЬНО —
// программным твином позиции спрайта (см. app.js: playJumpGesture) — это
// единственный жест в списке, который не идёт через этот файл.
//
// Общий для всех 52 моделей набор параметров (проверено audit_params.js —
// вариативности между моделями НЕТ вообще), поэтому GESTURES.*.requires ниже
// это на будущее — на случай если когда-нибудь добавится модель с урезанным
// ригом, деградация сработает автоматически (см. isGestureAvailable).

(function (global) {
  const FROM = global.MOTION_FROM;

  function holdReturn(param, target, { approach = 380, holdMs = 420, release = 480, easing = 'easeInOut', delay = 0 } = {}) {
    return {
      param,
      phases: [
        { to: target, duration: approach, easing, delay },
        { to: target, duration: holdMs, easing: 'linear' },
        { to: FROM, duration: release, easing }
      ]
    };
  }

  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

  const GESTURES = {
    // ── руки ──────────────────────────────────────────────────────────────
    wave: {
      requires: ['PARAM_ARM_R', 'PARAM_ARM_L', 'PARAM_BODY_ANGLE_Z'],
      build: ({ side = 'right', intensity = 0.7, speed = 1 } = {}) => {
        const param = side === 'left' ? 'PARAM_ARM_L' : 'PARAM_ARM_R';
        const amp = clamp(intensity, 0.15, 1);
        const swing = -10 * amp; // качели к альтернативной позе, амплитуда от интенсивности
        const d = 260 / speed;
        return {
          layer: 'gesture',
          tracks: [
            { param, phases: [
              { to: swing, duration: d, easing: 'easeOut' },
              { to: swing * 0.3, duration: d * 0.75, easing: 'easeInOut' },
              { to: swing, duration: d * 0.75, easing: 'easeInOut' },
              { to: swing * 0.3, duration: d * 0.75, easing: 'easeInOut' },
              { to: FROM, duration: d * 1.1, easing: 'easeInOut' }
            ] },
            { param: 'PARAM_BODY_ANGLE_Z', phases: [
              { to: (side === 'left' ? -1 : 1) * 3.5 * amp, duration: d * 1.3, easing: 'easeInOut' },
              { to: FROM, duration: d * 1.6, easing: 'easeInOut' }
            ] }
          ]
        };
      }
    },

    raise_right_hand: {
      requires: ['PARAM_ARM_R'],
      build: ({ intensity = 0.8, speed = 1 } = {}) => ({
        layer: 'gesture',
        tracks: [holdReturn('PARAM_ARM_R', -10 * clamp(intensity, 0.2, 1), { approach: 420 / speed, holdMs: 700 })]
      })
    },
    raise_left_hand: {
      requires: ['PARAM_ARM_L'],
      build: ({ intensity = 0.8, speed = 1 } = {}) => ({
        layer: 'gesture',
        tracks: [holdReturn('PARAM_ARM_L', -10 * clamp(intensity, 0.2, 1), { approach: 420 / speed, holdMs: 700 })]
      })
    },
    raise_both_hands: {
      requires: ['PARAM_ARM_R', 'PARAM_ARM_L'],
      build: ({ intensity = 0.8, speed = 1 } = {}) => {
        const target = -10 * clamp(intensity, 0.2, 1);
        return {
          layer: 'gesture',
          tracks: [
            holdReturn('PARAM_ARM_R', target, { approach: 420 / speed, holdMs: 700 }),
            holdReturn('PARAM_ARM_L', target, { approach: 420 / speed, holdMs: 700, delay: 40 })
          ]
        };
      }
    },

    shrug: {
      requires: ['PARAM_ARM_R', 'PARAM_ARM_L', 'PARAM_BODY_ANGLE_Y'],
      build: ({ intensity = 0.6, speed = 1 } = {}) => {
        const amp = clamp(intensity, 0.2, 1);
        const d = 300 / speed;
        return {
          layer: 'gesture',
          tracks: [
            holdReturn('PARAM_ARM_R', 3 * amp, { approach: d, holdMs: 380 }),
            holdReturn('PARAM_ARM_L', 3 * amp, { approach: d, holdMs: 380 }),
            holdReturn('PARAM_BODY_ANGLE_Y', -2.5 * amp, { approach: d * 0.8, holdMs: 380 })
          ]
        };
      }
    },

    stretch: {
      requires: ['PARAM_ARM_R', 'PARAM_ARM_L', 'PARAM_BODY_ANGLE_Y', 'PARAM_ANGLE_Y'],
      build: ({ intensity = 0.8, speed = 0.7 } = {}) => {
        const amp = clamp(intensity, 0.3, 1);
        const d = 500 / speed;
        return {
          layer: 'gesture',
          tracks: [
            holdReturn('PARAM_ARM_R', -9 * amp, { approach: d, holdMs: 900, release: d * 1.2 }),
            holdReturn('PARAM_ARM_L', -9 * amp, { approach: d, holdMs: 900, release: d * 1.2, delay: 60 }),
            holdReturn('PARAM_BODY_ANGLE_Y', -4 * amp, { approach: d, holdMs: 900, release: d * 1.2 }),
            holdReturn('PARAM_ANGLE_Y', -8 * amp, { approach: d, holdMs: 900, release: d * 1.2 })
          ]
        };
      }
    },

    // ── голова ────────────────────────────────────────────────────────────
    nod: {
      requires: ['PARAM_ANGLE_Y'],
      build: ({ intensity = 1, speed = 1 } = {}) => {
        const amp = clamp(intensity, 0.2, 1);
        const d = 210 / speed;
        return {
          layer: 'gesture',
          tracks: [{ param: 'PARAM_ANGLE_Y', phases: [
            { to: -12 * amp, duration: d, easing: 'easeOut' },
            { to: 6 * amp, duration: d * 0.9, easing: 'easeInOut' },
            { to: -8 * amp, duration: d * 0.9, easing: 'easeInOut' },
            { to: FROM, duration: d * 1.2, easing: 'easeInOut' }
          ] }]
        };
      }
    },

    shake_head: {
      requires: ['PARAM_ANGLE_X'],
      build: ({ intensity = 1, speed = 1 } = {}) => {
        const amp = clamp(intensity, 0.2, 1);
        const d = 190 / speed;
        return {
          layer: 'gesture',
          tracks: [{ param: 'PARAM_ANGLE_X', phases: [
            { to: -16 * amp, duration: d, easing: 'easeOut' },
            { to: 16 * amp, duration: d * 1.1, easing: 'easeInOut' },
            { to: -10 * amp, duration: d, easing: 'easeInOut' },
            { to: FROM, duration: d * 1.2, easing: 'easeInOut' }
          ] }]
        };
      }
    },

    tilt_head_left: {
      requires: ['PARAM_ANGLE_Z'],
      build: ({ intensity = 0.7, speed = 1 } = {}) => ({
        layer: 'gesture',
        tracks: [holdReturn('PARAM_ANGLE_Z', 18 * clamp(intensity, 0.2, 1), { approach: 380 / speed, holdMs: 600 })]
      })
    },
    tilt_head_right: {
      requires: ['PARAM_ANGLE_Z'],
      build: ({ intensity = 0.7, speed = 1 } = {}) => ({
        layer: 'gesture',
        tracks: [holdReturn('PARAM_ANGLE_Z', -18 * clamp(intensity, 0.2, 1), { approach: 380 / speed, holdMs: 600 })]
      })
    },

    look_left: {
      requires: ['PARAM_EYE_BALL_X'],
      build: ({ intensity = 0.8, speed = 1 } = {}) => ({
        layer: 'gesture',
        tracks: [
          holdReturn('PARAM_EYE_BALL_X', -0.9 * clamp(intensity, 0.2, 1), { approach: 220 / speed, holdMs: 650 }),
          holdReturn('PARAM_ANGLE_Y', 5 * clamp(intensity, 0.2, 1), { approach: 260 / speed, holdMs: 650 })
        ]
      })
    },
    look_right: {
      requires: ['PARAM_EYE_BALL_X'],
      build: ({ intensity = 0.8, speed = 1 } = {}) => ({
        layer: 'gesture',
        tracks: [
          holdReturn('PARAM_EYE_BALL_X', 0.9 * clamp(intensity, 0.2, 1), { approach: 220 / speed, holdMs: 650 }),
          holdReturn('PARAM_ANGLE_Y', -5 * clamp(intensity, 0.2, 1), { approach: 260 / speed, holdMs: 650 })
        ]
      })
    },
    look_up: {
      requires: ['PARAM_EYE_BALL_Y'],
      build: ({ intensity = 0.7, speed = 1 } = {}) => ({
        layer: 'gesture',
        tracks: [
          holdReturn('PARAM_EYE_BALL_Y', 0.9 * clamp(intensity, 0.2, 1), { approach: 220 / speed, holdMs: 600 }),
          holdReturn('PARAM_ANGLE_X', 6 * clamp(intensity, 0.2, 1), { approach: 260 / speed, holdMs: 600 })
        ]
      })
    },
    look_down: {
      requires: ['PARAM_EYE_BALL_Y'],
      build: ({ intensity = 0.7, speed = 1 } = {}) => ({
        layer: 'gesture',
        tracks: [
          holdReturn('PARAM_EYE_BALL_Y', -0.9 * clamp(intensity, 0.2, 1), { approach: 220 / speed, holdMs: 600 }),
          holdReturn('PARAM_ANGLE_X', -6 * clamp(intensity, 0.2, 1), { approach: 260 / speed, holdMs: 600 })
        ]
      })
    },

    // ── корпус ────────────────────────────────────────────────────────────
    bow: {
      requires: ['PARAM_BODY_ANGLE_X', 'PARAM_ANGLE_X'],
      build: ({ intensity = 0.8, speed = 0.9 } = {}) => {
        const amp = clamp(intensity, 0.3, 1);
        const d = 420 / speed;
        return {
          layer: 'gesture',
          tracks: [
            holdReturn('PARAM_BODY_ANGLE_X', 8 * amp, { approach: d, holdMs: 500, release: d * 1.3 }),
            holdReturn('PARAM_ANGLE_X', 10 * amp, { approach: d, holdMs: 500, release: d * 1.3 })
          ]
        };
      }
    },

    lean_forward: {
      requires: ['PARAM_BODY_ANGLE_Y'],
      build: ({ intensity = 0.6, speed = 1 } = {}) => ({
        layer: 'gesture',
        tracks: [holdReturn('PARAM_BODY_ANGLE_Y', 6 * clamp(intensity, 0.2, 1), { approach: 350 / speed, holdMs: 550 })]
      })
    },
    lean_backward: {
      requires: ['PARAM_BODY_ANGLE_Y'],
      build: ({ intensity = 0.6, speed = 1 } = {}) => ({
        layer: 'gesture',
        tracks: [holdReturn('PARAM_BODY_ANGLE_Y', -6 * clamp(intensity, 0.2, 1), { approach: 350 / speed, holdMs: 550 })]
      })
    },

    surprised_recoil: {
      requires: ['PARAM_BODY_ANGLE_Y', 'PARAM_ANGLE_Y', 'PARAM_BROW_L_Y', 'PARAM_BROW_R_Y'],
      build: ({ intensity = 0.9, speed = 1.3 } = {}) => {
        const amp = clamp(intensity, 0.3, 1);
        const d = 150 / speed;
        return {
          layer: 'gesture',
          tracks: [
            { param: 'PARAM_BODY_ANGLE_Y', phases: [{ to: -7 * amp, duration: d, easing: 'easeOut' }, { to: FROM, duration: d * 3, easing: 'easeInOut' }] },
            { param: 'PARAM_ANGLE_Y', phases: [{ to: 8 * amp, duration: d, easing: 'easeOut' }, { to: FROM, duration: d * 3, easing: 'easeInOut' }] },
            { param: 'PARAM_BROW_L_Y', phases: [{ to: 0.8 * amp, duration: d, easing: 'easeOut' }, { to: FROM, duration: d * 3.5, easing: 'easeInOut' }] },
            { param: 'PARAM_BROW_R_Y', phases: [{ to: 0.8 * amp, duration: d, easing: 'easeOut' }, { to: FROM, duration: d * 3.5, easing: 'easeInOut' }] }
          ]
        };
      }
    },
    shiver: {
      requires: ['PARAM_ANGLE_X', 'PARAM_ANGLE_Y', 'PARAM_BODY_ANGLE_Y'],
      build: ({ intensity = 0.5, speed = 1 } = {}) => {
        const amp = clamp(intensity, 0.1, 1);
        const d = 80 / speed;
        return {
          layer: 'gesture',
          tracks: [
            { param: 'PARAM_ANGLE_X', phases: [
              { to: 2 * amp, duration: d, easing: 'linear' }, { to: -2 * amp, duration: d, easing: 'linear' },
              { to: 2 * amp, duration: d, easing: 'linear' }, { to: FROM, duration: d * 1.2, easing: 'easeInOut' }
            ] },
            { param: 'PARAM_ANGLE_Y', phases: [
              { to: 2 * amp, duration: d, easing: 'linear' }, { to: -2 * amp, duration: d, easing: 'linear' },
              { to: 2 * amp, duration: d, easing: 'linear' }, { to: FROM, duration: d * 1.2, easing: 'easeInOut' }
            ] },
            { param: 'PARAM_BODY_ANGLE_Y', phases: [
              { to: 1 * amp, duration: d, easing: 'linear' }, { to: -1 * amp, duration: d, easing: 'linear' },
              { to: 1 * amp, duration: d, easing: 'linear' }, { to: FROM, duration: d * 1.2, easing: 'easeInOut' }
            ] }
          ]
        };
      }
    },
    bashful: {
      requires: ['PARAM_ANGLE_Z', 'PARAM_BODY_ANGLE_Y', 'PARAM_ARM_L', 'PARAM_ARM_R'],
      build: ({ intensity = 0.7, speed = 1 } = {}) => {
        const amp = clamp(intensity, 0.3, 1);
        const d = 400 / speed;
        return {
          layer: 'gesture',
          tracks: [
            holdReturn('PARAM_ANGLE_Z', 12 * amp, { approach: d, holdMs: 800 }),
            holdReturn('PARAM_BODY_ANGLE_Y', 4 * amp, { approach: d, holdMs: 800 }),
            holdReturn('PARAM_ARM_L', 2 * amp, { approach: d, holdMs: 800, delay: 50 }),
            holdReturn('PARAM_ARM_R', 2 * amp, { approach: d, holdMs: 800, delay: 100 })
          ]
        };
      }
    },
    excited_bounce: {
      requires: ['PARAM_BODY_ANGLE_Y', 'PARAM_ANGLE_Y'],
      build: ({ intensity = 0.8, speed = 1 } = {}) => {
        const amp = clamp(intensity, 0.3, 1);
        const d = 150 / speed;
        return {
          layer: 'gesture',
          tracks: [
            { param: 'PARAM_BODY_ANGLE_Y', phases: [
              { to: 5 * amp, duration: d, easing: 'easeOut' }, { to: FROM, duration: d, easing: 'easeInOut' },
              { to: 5 * amp, duration: d, easing: 'easeOut' }, { to: FROM, duration: d, easing: 'easeInOut' },
              { to: 5 * amp, duration: d, easing: 'easeOut' }, { to: FROM, duration: d * 1.5, easing: 'easeInOut' }
            ] },
            { param: 'PARAM_ANGLE_Y', phases: [
              { to: -5 * amp, duration: d, easing: 'easeOut' }, { to: FROM, duration: d, easing: 'easeInOut' },
              { to: -5 * amp, duration: d, easing: 'easeOut' }, { to: FROM, duration: d * 1.5, easing: 'easeInOut' }
            ] }
          ]
        };
      }
    },
    deep_sigh: {
      requires: ['PARAM_ANGLE_Y', 'PARAM_BODY_ANGLE_Y', 'PARAM_ARM_L', 'PARAM_ARM_R'],
      build: ({ intensity = 0.7, speed = 0.8 } = {}) => {
        const amp = clamp(intensity, 0.3, 1);
        const d = 600 / speed;
        return {
          layer: 'gesture',
          tracks: [
            holdReturn('PARAM_ANGLE_Y', 10 * amp, { approach: d, holdMs: 400, release: d * 1.2 }),
            holdReturn('PARAM_BODY_ANGLE_Y', 6 * amp, { approach: d, holdMs: 400, release: d * 1.2 }),
            holdReturn('PARAM_ARM_L', 4 * amp, { approach: d, holdMs: 400, release: d * 1.2 }),
            holdReturn('PARAM_ARM_R', 4 * amp, { approach: d, holdMs: 400, release: d * 1.2 })
          ]
        };
      }
    },
    curious_lean: {
      requires: ['PARAM_BODY_ANGLE_Y', 'PARAM_ANGLE_Z', 'PARAM_EYE_BALL_Y'],
      build: ({ intensity = 0.6, speed = 0.8 } = {}) => {
        const amp = clamp(intensity, 0.3, 1);
        const d = 500 / speed;
        return {
          layer: 'gesture',
          tracks: [
            holdReturn('PARAM_BODY_ANGLE_Y', 8 * amp, { approach: d, holdMs: 1000, release: d }),
            holdReturn('PARAM_ANGLE_Z', 10 * amp, { approach: d, holdMs: 1000, release: d, delay: 100 }),
            holdReturn('PARAM_EYE_BALL_Y', 0.5 * amp, { approach: d, holdMs: 1000, release: d, delay: 200 })
          ]
        };
      }
    },
    impatient_tap: {
      requires: ['PARAM_BODY_ANGLE_Y', 'PARAM_ANGLE_X'],
      build: ({ intensity = 0.5, speed = 1 } = {}) => {
        const amp = clamp(intensity, 0.2, 1);
        const d = 200 / speed;
        return {
          layer: 'gesture',
          tracks: [
            { param: 'PARAM_BODY_ANGLE_Y', phases: [
              { to: 2 * amp, duration: d, easing: 'easeOut' }, { to: FROM, duration: d, easing: 'easeInOut' },
              { to: 2 * amp, duration: d, easing: 'easeOut' }, { to: FROM, duration: d * 1.2, easing: 'easeInOut' }
            ] },
            { param: 'PARAM_ANGLE_X', phases: [
              { to: 3 * amp, duration: d, easing: 'linear' }, { to: -3 * amp, duration: d, easing: 'linear' },
              { to: 3 * amp, duration: d, easing: 'linear' }, { to: FROM, duration: d * 1.2, easing: 'easeInOut' }
            ] }
          ]
        };
      }
    },
    ethereal_float: {
      requires: ['PARAM_BODY_ANGLE_Y', 'PARAM_BODY_ANGLE_Z', 'PARAM_ANGLE_X', 'PARAM_ANGLE_Y'],
      build: ({ intensity = 0.5, speed = 0.5 } = {}) => {
        const amp = clamp(intensity, 0.2, 1);
        const d = 1000 / speed;
        return {
          layer: 'gesture',
          tracks: [
            { param: 'PARAM_BODY_ANGLE_Y', phases: [
              { to: 4 * amp, duration: d, easing: 'easeInOut' },
              { to: -4 * amp, duration: d, easing: 'easeInOut' },
              { to: FROM, duration: d, easing: 'easeInOut' }
            ] },
            { param: 'PARAM_BODY_ANGLE_Z', phases: [
              { to: 0, duration: 0, easing: 'linear' },
              { to: 6 * amp, duration: d, easing: 'easeInOut' },
              { to: -6 * amp, duration: d, easing: 'easeInOut' },
              { to: FROM, duration: d, easing: 'easeInOut' }
            ] },
            { param: 'PARAM_ANGLE_X', phases: [
              { to: -5 * amp, duration: d * 1.2, easing: 'easeInOut' },
              { to: 5 * amp, duration: d * 1.2, easing: 'easeInOut' },
              { to: FROM, duration: d, easing: 'easeInOut' }
            ] },
            { param: 'PARAM_ANGLE_Y', phases: [
              { to: 3 * amp, duration: d * 0.8, easing: 'easeInOut' },
              { to: -3 * amp, duration: d * 0.8, easing: 'easeInOut' },
              { to: FROM, duration: d, easing: 'easeInOut' }
            ] }
          ]
        };
      }
    }
  };

    // small_jump сюда намеренно не входит — см. комментарий вверху файла.

  // capabilities: Set<string> параметров, реально существующих у загруженной модели
  // (заполняется в app.js через coreModel.getParamIndex(id) !== -1).
  function isGestureAvailable(name, capabilities) {
    const g = GESTURES[name];
    if (!g) return false;
    if (!capabilities) return true; // если аудит ещё не готов — не блокируем
    return g.requires.every((p) => capabilities.has(p));
  }

  function buildGestureSpec(name, opts, capabilities) {
    const g = GESTURES[name];
    if (!g) return null;
    if (!isGestureAvailable(name, capabilities)) return null;
    return g.build(opts || {});
  }

  global.GESTURES = GESTURES;
  global.isGestureAvailable = isGestureAvailable;
  global.buildGestureSpec = buildGestureSpec;
  global.GESTURE_NAMES = Object.keys(GESTURES).concat(['small_jump']);
})(window);
