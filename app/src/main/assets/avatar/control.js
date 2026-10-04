(async () => {
  const state = {
    settings: null,
    models: [],
    chatHistory: [],            // {role, content} для LLM
    currentManifest: { expressions: [], motions: {} },
    isDialogueMode: false,
    ttsQueue: [],
    ttsPlaying: false
  };

  const cfg = await window.vh.init();
  state.settings = cfg.settings;
  state.models = cfg.models;

  wireTabs();
  buildModelGrid();
  await loadManifestForCurrentModel();
  applySettingsToUI();
  updateMemoryCount(cfg.memoryCount || 0);
  wireChat();
  wireSettings();
  wireCalendar();
  await refreshCalendar();
  await setupMicSelect();
  wireDialogue();
  wireImages();

  window.vh.onSettingsSync((s) => {
    state.settings = s;
    applySettingsToUI();
  });
  window.vh.onFocusTab((tab) => selectTab(tab));
  window.vh.onSleepCollectHistory(() => {
    window.vh.sendHistoryForSleep(state.chatHistory);
  });
  window.vh.onChatIncoming(({ text, action }) => {
    if (!text) return;
    appendChatMsg('a', text);
    state.chatHistory.push({ role: 'assistant', content: text });
    if (state.settings.audio?.autoSpeak) enqueueTts(text);
  });

  // ─────────────────────────── вкладки ───────────────────────────
  function wireTabs() {
    document.querySelectorAll('.tab').forEach((tab) => {
      tab.addEventListener('click', () => selectTab(tab.dataset.tab));
    });
  }
  function selectTab(name) {
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
    document.querySelectorAll('.panel').forEach((p) => p.classList.toggle('active', p.dataset.panel === name));
    if (name === 'memory') refreshMemoryList();
  }

  // ─────────────────────────── модели / манифест (для вкладки "Эмоции") ───────────────────────────
  function buildModelGrid() {
    const grid = document.getElementById('modelGrid');
    grid.innerHTML = '';
    state.models.forEach((m) => {
      const btn = document.createElement('button');
      btn.className = 'mbtn';
      btn.dataset.id = m.id;
      btn.textContent = m.id.replace('asuna_', '#');
      btn.title = `${m.id} · ${m.expressions} эмоций · ${m.motions} анимаций`;
      if (m.id === state.settings.currentModel) btn.classList.add('active');
      btn.onclick = async () => {
        state.settings.currentModel = m.id;
        window.vh.setCurrentModel(m.id);
        document.querySelectorAll('#modelGrid .mbtn').forEach((b) => b.classList.toggle('active', b.dataset.id === m.id));
        await loadManifestForCurrentModel();
      };
      grid.appendChild(btn);
    });
    document.getElementById('modelSearch').addEventListener('input', (e) => {
      const q = e.target.value.trim().toLowerCase();
      grid.querySelectorAll('.mbtn').forEach((b) => {
        b.style.display = b.dataset.id.toLowerCase().includes(q) ? '' : 'none';
      });
    });
  }

  async function loadManifestForCurrentModel() {
    const modelId = state.settings.currentModel;
    const expGrid = document.getElementById('expGrid');
    const mtnList = document.getElementById('mtnList');
    expGrid.innerHTML = '';
    mtnList.innerHTML = '';
    state.currentManifest = { expressions: [], motions: {} };
    if (!modelId) return;
    try {
      const res = await fetch(`../resources/models/${modelId}/${modelId}.model.json`);
      const json = await res.json();

      (json.expressions || []).forEach((exp, index) => {
        let name = (exp.name || `EXP_${index}`).replace('.exp.json', '').replace('F_', '').replace('f0', '');
        state.currentManifest.expressions.push({ raw: exp.name || '', label: name, index });
        const btn = document.createElement('button');
        btn.className = 'mbtn';
        btn.textContent = name.toUpperCase();
        btn.onclick = () => {
          document.querySelectorAll('#expGrid .mbtn').forEach((b) => b.classList.remove('active'));
          btn.classList.add('active');
          window.vh.avatarReact({ action: { expression: exp.name.replace('.exp.json', '') }, text: '' });
        };
        expGrid.appendChild(btn);
      });
      if (!expGrid.children.length) expGrid.innerHTML = '<div class="hint">Эмоции не объявлены в манифесте</div>';

      if (json.motions) {
        state.currentManifest.motions = json.motions;
        Object.keys(json.motions).forEach((groupName) => {
          json.motions[groupName].forEach((motion, index) => {
            let fileLabel = motion.file ? motion.file.split('/').pop().replace('.mtn', '') : `${groupName}_${index}`;
            fileLabel = fileLabel.replace('I_', '');
            const btn = document.createElement('button');
            btn.className = 'mtnbtn';
            btn.textContent = `${fileLabel.toUpperCase()} [${groupName || 'react'}]`;
            btn.onclick = () => {
              window.vh.avatarReact({ action: { motion_group: groupName, motion_index: index }, text: '' });
            };
            mtnList.appendChild(btn);
          });
        });
      }
      if (!mtnList.children.length) mtnList.innerHTML = '<div class="hint">Анимации тела не найдены</div>';
    } catch (e) {
      console.warn('Manifest parse failed', e);
    }
  }

  // ─────────────────────────── чат: текст ───────────────────────────
  function wireChat() {
    document.getElementById('chatSend').onclick = sendTextChat;
    document.getElementById('chatInput').addEventListener('keydown', (e) => {
      if (e.key === 'Enter') sendTextChat();
    });
    document.getElementById('btnSleepNow').onclick = sleepNow;
  }

  async function sendTextChat() {
    const input = document.getElementById('chatInput');
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    appendChatMsg('u', text);

    const typing = appendTyping();
    const res = await window.vh.chat(text, state.chatHistory.slice(-8));
    typing.remove();
    handleChatResult(text, res);
  }

  function handleChatResult(userText, res) {
    if (!res.ok) {
      appendChatMsg('err', `Ошибка: ${res.error}`);
      return;
    }
    appendChatMsg('a', res.display || '…');
    state.chatHistory.push({ role: 'user', content: userText || '[голосовое сообщение]' });
    state.chatHistory.push({ role: 'assistant', content: res.display || '' });

    let finalAction = res.action;
    if (state.settings.personality === 'unsafe') {
      // В режиме personality: 'unsafe' добавляем "хорни" атрибуты
      window.vh.avatarReact({ action: { expression: 'blush' }, text: '' });
      window.vh.avatarReact({ action: { motion_group: 'idle', motion_index: 1 }, text: '' }); // Предполагаем, что 1 - это виляние бедрами
    }

    window.vh.avatarReact({ action: finalAction, text: res.display });
    if (state.settings.audio?.autoSpeak && res.display) {
      enqueueTts(res.display);
    }
  }

  function appendChatMsg(kind, text) {
    const log = document.getElementById('chatLog');
    const div = document.createElement('div');
    div.className = `msg msg-${kind}`;
    if (kind === 'a') div.innerHTML = '<span class="who">Asuna</span>';
    if (kind === 'u') div.innerHTML = '<span class="who">Вы</span>';
    div.appendChild(document.createTextNode(text));
    log.appendChild(div);
    log.scrollTop = log.scrollHeight;
    return div;
  }

  function appendTyping() {
    const log = document.getElementById('chatLog');
    const div = document.createElement('div');
    div.className = 'msg msg-a';
    div.innerHTML = '<span class="who">Asuna</span>…думает…';
    log.appendChild(div);
    log.scrollTop = log.scrollHeight;
    return div;
  }

  // ─────────────────────────── микрофон: выбор устройства ───────────────────────────
  async function setupMicSelect() {
    const select = document.getElementById('micSelect');
    try {
      const tmpStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      tmpStream.getTracks().forEach((t) => t.stop());
    } catch (e) {
      select.innerHTML = '<option value="">Микрофон недоступен</option>';
      console.warn('getUserMedia (init) failed', e);
      return;
    }
    await refreshMicList();
    navigator.mediaDevices.addEventListener('devicechange', refreshMicList);

    select.onchange = () => {
      window.vh.patchSettings({ audio: { ...state.settings.audio, micDeviceId: select.value } });
    };
  }

  async function refreshMicList() {
    const select = document.getElementById('micSelect');
    const devices = await navigator.mediaDevices.enumerateDevices();
    const mics = devices.filter((d) => d.kind === 'audioinput');
    select.innerHTML = '';
    mics.forEach((d, i) => {
      const opt = document.createElement('option');
      opt.value = d.deviceId;
      opt.textContent = d.label || `Микрофон ${i + 1}`;
      select.appendChild(opt);
    });
    if (state.settings.audio?.micDeviceId && mics.some((m) => m.deviceId === state.settings.audio.micDeviceId)) {
      select.value = state.settings.audio.micDeviceId;
    }
  }

  // ─────────────────────────── запись голоса -> Whisper -> текст ───────────────────────────
  // Раньше здесь была отдельная кнопка "Запись" (классический push-to-talk — жать
  // каждый раз заново, буквально "по рации"). Убрали её: остаётся один-единственный
  // голосовой режим — кнопка "Диалог" (VAD, см. ниже) — жмёшь один раз, и дальше
  // не нужно ничего нажимать, она сама слышит начало и конец каждой фразы.

  async function handleRecordedAudioFromBlob(blob) {
    const status = document.getElementById('recStatus');
    if (!blob.size) {
      status.textContent = '';
      return;
    }

    status.textContent = 'распознаю голос…';
    const placeholder = appendChatMsg('u', '…распознаю голос…');
    let text = '';
    try {
      text = await transcribeAudio(blob);
    } catch (e) {
      placeholder.remove();
      appendChatMsg('err', `Не удалось распознать голос локально: ${e.message || e}. Проверь, что сервер запущен (server/run_server.bat) и в него подтянулся Whisper.`);
      status.textContent = '';
      return;
    }
    placeholder.remove();
    if (!text) {
      status.textContent = '';
      return;
    }
    appendChatMsg('u', text);

    const typing = appendTyping();
    const res = await window.vh.chat(text, state.chatHistory.slice(-8));
    typing.remove();
    status.textContent = '';
    handleChatResult(text, res);
  }

  async function transcribeAudio(blob) {
    const base = (state.settings.audio?.ttsServerUrl || 'http://127.0.0.1:8009').replace(/\/$/, '');
    const form = new FormData();
    form.append('audio', blob, 'voice.webm');
    form.append('language', 'ru');
    const resp = await fetch(`${base}/stt`, { method: 'POST', body: form });
    if (!resp.ok) {
      const detail = await resp.text().catch(() => '');
      throw new Error(`STT сервер вернул ${resp.status}${detail ? ': ' + detail.slice(0, 160) : ''}`);
    }
    const json = await resp.json();
    return (json.text || '').trim();
  }

  // ─────────────────────────── VAD (Voice Activity Detection) ───────────────────────────
  // "Нормальный" голосовой диалог: не жать кнопку на каждую фразу — микрофон слушает
  // постоянно, сама фраза определяется по громкости (начало) и тишине (конец).
  //
  // Раньше здесь не было защиты от простого, но критичного бага: VAD продолжал слушать
  // микрофон, ПОКА играет TTS-ответ Асуны — из динамиков её же голос попадает обратно
  // в микрофон, превышает порог громкости, и она начинает "отвечать сама себе" по кругу.
  // Плюс не было защиты от повторного триггера, пока предыдущая фраза ещё обрабатывается
  // (распознаётся/уходит в LLM). Оба случая теперь замьючены через state.ttsPlaying /
  // vadProcessing — пока это в процессе, VAD просто ничего не анализирует.
  let dialogueStream = null;
  let vadContext = null;
  let vadAnalyser = null;
  let vadInterval = null;
  let vadRecorder = null;
  let vadChunks = [];
  let vadIsSpeaking = false;
  let vadSilenceStart = null;
  let vadSpeechStart = null;
  state.vadProcessing = false;

  async function startVAD() {
    const micId = document.getElementById('micSelect').value;
    try {
      dialogueStream = await navigator.mediaDevices.getUserMedia({
        audio: micId
          ? { deviceId: { exact: micId }, echoCancellation: true, noiseSuppression: true, autoGainControl: true }
          : { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
      });

      vadContext = new (window.AudioContext || window.webkitAudioContext)();
      if (vadContext.state === 'suspended') await vadContext.resume();
      const source = vadContext.createMediaStreamSource(dialogueStream);
      vadAnalyser = vadContext.createAnalyser();
      // Времянная область + RMS вместо усреднения частотных бинов: голос концентрируется
      // в первых ~15-20 бинах из 128 при типичном fftSize=256, так что усреднение по ВСЕМ
      // бинам (включая пустые высокие частоты) сильно занижало показания громкости и делало
      // порог непредсказуемым. RMS по сырой waveform — стандартный, надёжный способ.
      vadAnalyser.fftSize = 2048;
      source.connect(vadAnalyser);

      const dataArray = new Uint8Array(vadAnalyser.fftSize);
      vadIsSpeaking = false;
      vadSilenceStart = null;
      vadSpeechStart = null;

      function readRms() {
        vadAnalyser.getByteTimeDomainData(dataArray);
        let sumSquares = 0;
        for (let i = 0; i < dataArray.length; i++) {
          const v = (dataArray[i] - 128) / 128;
          sumSquares += v * v;
        }
        return Math.sqrt(sumSquares / dataArray.length);
      }

      // Автокалибровка: меряем фоновый шум комнаты/микрофона первые ~800мс — фиксированный
      // порог из настроек одинаково плохо работает и в тихой комнате с тихим микрофоном
      // (никогда не срабатывает), и в шумной (фон никогда не опускается ниже порога —
      // запись не останавливается сама, приходится жать кнопку руками).
      let calibrating = true;
      let noiseFloor = 0.01;
      const calibrationSamples = [];
      document.getElementById('recStatus').textContent = 'калибрую тишину…';
      setTimeout(() => {
        if (calibrationSamples.length) {
          calibrationSamples.sort((a, b) => a - b);
          noiseFloor = calibrationSamples[Math.floor(calibrationSamples.length * 0.7)];
        }
        calibrating = false;
        console.log('[VAD] калибровка завершена, фоновый шум ≈', noiseFloor.toFixed(4));
        document.getElementById('recStatus').textContent = 'слушаю…';
      }, 800);

      // Раньше шумовой порог считался ОДИН раз при старте (800мс) и дальше не
      // менялся весь сеанс — если фон менялся (включили музыку/вентилятор,
      // кто-то зашёл в комнату, микрофон чуть сместился), порог оставался
      // прежним: либо VAD переставал слышать тихую речь на шумном фоне, либо
      // начинал ложно триггериться в новой тишине. Теперь noiseFloor медленно
      // дрейфует вслед за реальным фоном все время работы VAD, но обновляется
      // ТОЛЬКО по замерам, сделанным во время подтверждённой тишины (никогда —
      // по замерам во время речи), чтобы не "съесть" голос пользователя в
      // сторону завышения порога.
      const NOISE_EMA_ALPHA = 0.02;

      // Барж-ин: перебить Асуну голосом, пока она говорит через TTS. Выключен
      // по умолчанию (это осознанный компромисс — из динамиков в микрофон
      // всегда просачивается что-то от её же голоса, даже с echoCancellation,
      // и полностью надёжно отличить "пользователь перебивает" от "это её же
      // голос долетел до микрофона" без честного AEC нельзя). Включается
      // отдельным тумблером в Настройках; порог для него всегда заметно выше
      // обычного и требует более длинного устойчивого сигнала, чтобы обычные
      // короткие призвуки TTS не срывали ответ на середине фразы.
      let bargeSpeechStart = null;

      let debugTick = 0;
      vadInterval = setInterval(() => {
        const bargeInEnabled = !!state.settings.audio?.bargeIn;

        if (state.vadProcessing) {
          // Идёт распознавание/думание — звук точно не относится к новой фразе,
          // мьютим полностью независимо от barge-in.
          vadIsSpeaking = false;
          vadSilenceStart = null;
          vadSpeechStart = null;
          bargeSpeechStart = null;
          if (vadRecorder && vadRecorder.state === 'recording') vadRecorder.stop();
          document.getElementById('recStatus').textContent = 'распознаю и думаю…';
          return;
        }

        if (state.ttsPlaying) {
          if (!bargeInEnabled) {
            vadIsSpeaking = false;
            vadSilenceStart = null;
            vadSpeechStart = null;
            if (vadRecorder && vadRecorder.state === 'recording') vadRecorder.stop();
            document.getElementById('recStatus').textContent = 'Асуна отвечает…';
            return;
          }
          // Barge-in включён: слушаем поверх её же голоса, но заметно строже.
          const volume = readRms();
          const bargeThreshold = Math.max(state.settings.audio?.vadThreshold || 0.03, noiseFloor * 1.8 + 0.008) * 2.2;
          const bargeMinMs = 450; // дольше обычного — короткий шум/смешок TTS не должен обрывать ответ
          if (volume > bargeThreshold) {
            if (!bargeSpeechStart) bargeSpeechStart = Date.now();
            else if (Date.now() - bargeSpeechStart >= bargeMinMs) {
              console.log('[VAD] barge-in: пользователь перебил Асуну');
              stopTts();
              bargeSpeechStart = null;
              vadIsSpeaking = true;
              vadSpeechStart = Date.now();
              vadSilenceStart = null;
              document.getElementById('recStatus').textContent = 'слышу тебя…';
            }
          } else {
            bargeSpeechStart = null;
          }
          return;
        }

        const volume = readRms();

        if (calibrating) {
          calibrationSamples.push(volume);
          return;
        }

        const userThreshold = state.settings.audio?.vadThreshold || 0.03;
        const threshold = Math.max(userThreshold, noiseFloor * 1.8 + 0.008);
        const silenceMs = state.settings.audio?.vadSilenceMs ?? 900;
        const minSpeechMs = state.settings.audio?.vadMinSpeechMs ?? 250;

        debugTick++;
        if (debugTick % 25 === 0) {
          console.log(`[VAD] громкость=${volume.toFixed(4)} порог=${threshold.toFixed(4)} шумфон=${noiseFloor.toFixed(4)} говорит=${vadIsSpeaking}`);
        }

        if (volume > threshold) {
          vadSilenceStart = null;
          if (!vadIsSpeaking) {
            vadIsSpeaking = true;
            vadSpeechStart = Date.now();
            document.getElementById('recStatus').textContent = 'слышу тебя…';
          } else if (vadSpeechStart && Date.now() - vadSpeechStart >= minSpeechMs) {
            // достаточно долгий устойчивый звук — это уже похоже на речь, а не щелчок
            startVADRecording();
          }
        } else if (vadIsSpeaking) {
          if (!vadSilenceStart) vadSilenceStart = Date.now();
          if (Date.now() - vadSilenceStart > silenceMs) {
            vadIsSpeaking = false;
            vadSpeechStart = null;
            stopVADRecording();
          }
        } else {
          // тишина, никто не говорит — постоянно показываем, что режим ЖИВ и слушает,
          // а не "завис"/выключился молча. Заодно это единственный момент, когда
          // безопасно обновлять плавающий фон шума.
          noiseFloor = noiseFloor * (1 - NOISE_EMA_ALPHA) + volume * NOISE_EMA_ALPHA;
          document.getElementById('recStatus').textContent = 'слушаю…';
        }
      }, 80);
    } catch (e) {
      console.error('VAD start failed', e);
      toggleDialogueMode(false);
    }
  }

  function startVADRecording() {
    if (vadRecorder && vadRecorder.state === 'recording') return;

    const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus'
      : 'audio/webm';

    vadRecorder = new MediaRecorder(dialogueStream, { mimeType });
    vadChunks = [];
    vadRecorder.ondataavailable = (e) => { if (e.data && e.data.size > 0) vadChunks.push(e.data); };
    vadRecorder.onstop = async () => {
      const blob = new Blob(vadChunks, { type: mimeType });
      state.vadProcessing = true;
      try {
        await handleRecordedAudioFromBlob(blob);
      } finally {
        // ttsPlaying (если ответ озвучивается) подхватывает эстафету мьюта сам —
        // здесь просто снимаем "обрабатываю", чтобы не держать VAD замьюченным лишний раз,
        // если TTS выключен/недоступен и озвучки не будет вовсе.
        state.vadProcessing = false;
      }
    };
    vadRecorder.start();
    document.getElementById('recStatus').textContent = 'записываю фразу…';
  }

  function stopVADRecording() {
    if (vadRecorder && vadRecorder.state === 'recording') {
      vadRecorder.stop();
    }
  }

  async function toggleDialogueMode(enabled) {
    state.isDialogueMode = enabled;
    if (enabled) {
      document.getElementById('btnDialogue').classList.add('recording');
      document.querySelector('#btnDialogue .rec-label').textContent = 'Голос вкл (клик = выкл)';
      document.getElementById('recStatus').textContent = 'слушаю…';
      await startVAD();
    } else {
      document.getElementById('btnDialogue').classList.remove('recording');
      document.querySelector('#btnDialogue .rec-label').textContent = 'Голос выкл';
      document.getElementById('recStatus').textContent = '';

      if (vadInterval) clearInterval(vadInterval);
      if (vadRecorder && vadRecorder.state === 'recording') vadRecorder.stop();
      if (vadContext) vadContext.close();
      if (dialogueStream) {
        dialogueStream.getTracks().forEach(t => t.stop());
        dialogueStream = null;
      }
      vadInterval = null;
      vadContext = null;
    }
  }

  function wireDialogue() {
    document.getElementById('btnDialogue').onclick = () => {
      if (state.isDialogueMode) toggleDialogueMode(false);
      else toggleDialogueMode(true);
    };
  }

  // ─────────────────────────── Изображения ───────────────────────────
  function wireImages() {
    const btnImg = document.getElementById('btnImg');
    const imgInput = document.getElementById('imgInput');

    btnImg.onclick = () => imgInput.click();
    imgInput.onchange = async (e) => {
      const file = e.target.files[0];
      if (file) {
        await sendImage(file);
      }
      imgInput.value = '';
    };

    window.addEventListener('paste', async (e) => {
      const items = e.clipboardData.items;
      for (const item of items) {
        if (item.type.indexOf('image') !== -1) {
          const blob = item.getAsFile();
          await sendImage(blob);
        }
      }
    });
  }

  async function sendImage(blob) {
    const reader = new FileReader();
    reader.onload = async (e) => {
      const base64 = e.target.result.split(',')[1];
      const mimeType = blob.type;

      appendChatMsg('u', '[отправлено изображение]');
      const typing = appendTyping();

      const res = await window.vh.chatImage(base64, mimeType, state.chatHistory.slice(-8));
      typing.remove();
      handleChatResult('[изображение]', res);
    };
    reader.readAsDataURL(blob);
  }

  // ─────────────────────────── TTS: эквалайзер (Web Audio API) ───────────────────────────
  const eq = {
    ctx: null,
    analyser: null,
    data: null,
    bars: null,
    raf: null
  };

  function ensureEqualizer(audioEl) {
    if (eq.ctx) return;
    try {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      eq.ctx = new Ctx();
      const source = eq.ctx.createMediaElementSource(audioEl);
      eq.analyser = eq.ctx.createAnalyser();
      eq.analyser.fftSize = 64;
      eq.analyser.smoothingTimeConstant = 0.75;
      source.connect(eq.analyser);
      eq.analyser.connect(eq.ctx.destination);
      eq.data = new Uint8Array(eq.analyser.frequencyBinCount);
      eq.bars = Array.from(document.querySelectorAll('#ttsEqualizer span'));
    } catch (e) {
      console.warn('Equalizer unavailable:', e.message);
    }
  }

  function startEqualizer() {
    const el = document.getElementById('ttsEqualizer');
    el.classList.add('playing');
    if (!eq.analyser) return;
    if (eq.ctx.state === 'suspended') eq.ctx.resume().catch(() => {});
    const barCount = eq.bars.length;
    const step = () => {
      eq.analyser.getByteFrequencyData(eq.data);
      let sum = 0;
      for (let i = 0; i < barCount; i++) {
        const bin = Math.floor((i / barCount) * (eq.data.length * 0.6));
        const v = eq.data[bin] / 255;
        sum += v;
        eq.bars[i].style.height = `${Math.max(8, Math.round(v * 100))}%`;
      }
      const mouthLevel = Math.min(1, (sum / barCount) * 1.6);
      window.vh.sendMouthLevel(mouthLevel);
      eq.raf = requestAnimationFrame(step);
    };
    step();
  }

  function stopEqualizer() {
    const el = document.getElementById('ttsEqualizer');
    el.classList.remove('playing');
    if (eq.raf) cancelAnimationFrame(eq.raf);
    eq.raf = null;
    if (eq.bars) eq.bars.forEach((b) => (b.style.height = '8%'));
    window.vh.sendMouthLevel(0);
  }

  function splitIntoSpeechChunks(text) {
    const parts = text.split(/(?<=[.!?…])\s+/).map((s) => s.trim()).filter(Boolean);
    return parts.length ? parts : [text];
  }

  function enqueueTts(text) {
    const chunks = splitIntoSpeechChunks(text);
    state.ttsQueue.push(...chunks);
    if (!state.ttsPlaying) playNextTts();
  }

  async function playNextTts() {
    if (!state.ttsQueue.length) {
      state.ttsPlaying = false;
      return;
    }
    state.ttsPlaying = true;
    const chunk = state.ttsQueue.shift();
    const player = document.getElementById('ttsPlayer');
    const status = document.getElementById('recStatus');
    try {
      const url = `${(state.settings.audio?.ttsServerUrl || 'http://127.0.0.1:8009').replace(/\/$/, '')}/tts`;
      const resp = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: chunk, speaker: state.settings.audio?.ttsSpeaker || 'baya' })
      });
      if (!resp.ok) throw new Error(`TTS сервер вернул ${resp.status}`);
      const audioBlob = await resp.blob();
      const objUrl = URL.createObjectURL(audioBlob);
      player.src = objUrl;
      ensureEqualizer(player);
      status.textContent = 'говорит…';
      startEqualizer();
      await new Promise((resolve) => {
        // Сохраняем resolve отдельно от onended/onerror, чтобы barge-in
        // (stopTts) мог оборвать ожидание немедленно, а не через событие
        // паузы, которое player.pause() сам по себе не генерирует.
        state.ttsAbortCurrent = resolve;
        player.onended = resolve;
        player.onerror = resolve;
        player.play().catch(resolve);
      });
      state.ttsAbortCurrent = null;
      stopEqualizer();
      URL.revokeObjectURL(objUrl);
    } catch (e) {
      console.warn('TTS playback failed', e);
      stopEqualizer();
      status.textContent = 'TTS недоступен';
      setTimeout(() => { status.textContent = ''; }, 2000);
    }
    playNextTts();
  }

  // Барж-ин: пользователь перебил Асуну голосом посреди ответа. Останавливаем
  // очередь TTS и текущее воспроизведение немедленно, чтобы VAD мог тут же
  // начать записывать то, что он говорит, вместо того чтобы ждать, пока
  // Асуна договорит все оставшиеся куски.
  function stopTts() {
    state.ttsQueue = [];
    const player = document.getElementById('ttsPlayer');
    try {
      player.onended = null;
      player.onerror = null;
      player.pause();
    } catch (e) { /* noop */ }
    if (state.ttsAbortCurrent) {
      const resolve = state.ttsAbortCurrent;
      state.ttsAbortCurrent = null;
      resolve(); // будит await внутри playNextTts, очередь уже пуста -> цикл сам завершится
    }
    stopEqualizer();
    state.ttsPlaying = false;
  }

  async function sleepNow() {
    const btn = document.getElementById('btnSleepNow');
    const old = btn.textContent;
    btn.textContent = 'Сохраняю память…';
    btn.disabled = true;
    try {
      const entries = await window.vh.sleepNow(state.chatHistory);
      updateMemoryCount(entries.length);
      state.chatHistory = [];
      appendChatMsg('a', 'Я сохранила наш разговор в долгосрочную память. Можно начинать заново.');
    } catch (e) {
      appendChatMsg('err', `Не удалось сохранить память: ${e.message || e}`);
    } finally {
      btn.textContent = old;
      btn.disabled = false;
    }
  }

  function updateMemoryCount(n) {
    document.getElementById('memCount').textContent = n ? `Сессий в памяти: ${n}` : '';
    document.getElementById('memCount2').textContent = String(n || 0);
  }

  function wireCalendar() {
    const today = new Date().toISOString().slice(0, 10);
    document.getElementById('calDate').value = today;
    document.getElementById('calAddBtn').onclick = async () => {
      const title = document.getElementById('calTitle').value.trim();
      if (!title) return;
      const event = {
        title,
        date: document.getElementById('calDate').value || today,
        time: document.getElementById('calTime').value || '',
        notes: document.getElementById('calNotes').value.trim()
      };
      await window.vh.calendarAdd(event);
      document.getElementById('calTitle').value = '';
      document.getElementById('calNotes').value = '';
      await refreshCalendar();
    };
  }

  async function refreshCalendar() {
    const list = document.getElementById('calList');
    const events = await window.vh.calendarList(60);
    list.innerHTML = '';
    if (!events.length) {
      list.innerHTML = '<div class="hint">Событий не запланировано</div>';
      return;
    }
    events.forEach((ev) => {
      const row = document.createElement('div');
      row.className = 'cal-item';
      row.innerHTML = `
        <div class="cal-item-main">
          <b>${escapeHtml(ev.title)}</b>
          <span>${ev.date}${ev.time ? ' · ' + ev.time : ''}</span>
          ${ev.notes ? `<em>${escapeHtml(ev.notes)}</em>` : ''}
        </div>`;
      const del = document.createElement('button');
      del.className = 'cal-del';
      del.textContent = '✕';
      del.onclick = async () => {
        await window.vh.calendarRemove(ev.id);
        await refreshCalendar();
      };
      row.appendChild(del);
      list.appendChild(row);
    });
  }

  async function refreshMemoryList() {
    const list = document.getElementById('memList');
    const memories = await window.vh.getMemoryContents();
    list.innerHTML = '';
    if (!memories || !memories.length) {
      list.innerHTML = '<div class="hint">Память пока пуста.</div>';
      return;
    }
    memories.forEach((m) => {
      const row = document.createElement('div');
      row.className = 'cal-item';
      row.innerHTML = `
        <div class="cal-item-main">
          <span class="cal-date">${m.date || ''}</span>
          <div class="cal-content">${escapeHtml(m.content || m.summary || '')}</div>
        </div>`;
      list.appendChild(row);
    });
  }

  function escapeHtml(s) {
    const div = document.createElement('div');
    div.textContent = s;
    return div.innerHTML;
  }

  function wireSettings() {
    document.getElementById('setApiType').onchange = (e) => {
      const show = (e.target.value === 'openai-compatible' || e.target.value === 'gemini');
      document.getElementById('baseUrlField').style.display = show ? 'flex' : 'none';
      document.getElementById('baseUrlHint').style.display = show ? 'block' : 'none';
    };
    document.getElementById('setSave').onclick = () => {
      const llm = {
        apiType: document.getElementById('setApiType').value,
        apiKey: document.getElementById('setApiKey').value.trim(),
        model: document.getElementById('setModel').value.trim(),
        baseUrl: document.getElementById('setBaseUrl').value.trim()
      };
      state.settings.llm = llm;
      window.vh.patchSettings({ llm });
      flashSaved(document.getElementById('setSave'));
    };

    document.getElementById('setTtsUrl').onchange = (e) => {
      state.settings.audio = { ...state.settings.audio, ttsServerUrl: e.target.value.trim() };
      window.vh.patchSettings({ audio: state.settings.audio });
    };
    document.getElementById('setTtsSpeaker').onchange = (e) => {
      state.settings.audio = { ...state.settings.audio, ttsSpeaker: e.target.value.trim() || 'baya' };
      window.vh.patchSettings({ audio: state.settings.audio });
    };
    document.getElementById('setVadThreshold').oninput = (e) => {
      const val = parseFloat(e.target.value);
      document.getElementById('vadVal').textContent = val.toFixed(2);
      state.settings.audio = { ...state.settings.audio, vadThreshold: val };
    };
    document.getElementById('setVadThreshold').onchange = () => {
      window.vh.patchSettings({ audio: state.settings.audio });
    };
    document.getElementById('setVadSilence').oninput = (e) => {
      const val = parseInt(e.target.value, 10);
      document.getElementById('vadSilenceVal').textContent = val;
      state.settings.audio = { ...state.settings.audio, vadSilenceMs: val };
    };
    document.getElementById('setVadSilence').onchange = () => {
      window.vh.patchSettings({ audio: state.settings.audio });
    };
    document.getElementById('toggleAutoSpeak').onchange = (e) => {
      state.settings.audio = { ...state.settings.audio, autoSpeak: e.target.checked };
      window.vh.patchSettings({ audio: state.settings.audio });
    };
    document.getElementById('toggleBargeIn').onchange = (e) => {
      state.settings.audio = { ...state.settings.audio, bargeIn: e.target.checked };
      window.vh.patchSettings({ audio: state.settings.audio });
    };
    document.getElementById('btnTestTts').onclick = () => enqueueTts('Привет! Это проверка моего голоса.');

    document.getElementById('toggleAOT').onchange = (e) => {
      state.settings.alwaysOnTop = e.target.checked;
      window.vh.toggleAlwaysOnTop(e.target.checked);
    };
    document.getElementById('scaleRange').oninput = (e) => {
      const pct = Number(e.target.value);
      document.getElementById('scaleVal').textContent = pct + '%';
      state.settings.scale = pct / 100;
    };
    document.getElementById('scaleRange').onchange = () => {
      window.vh.patchSettings({ scale: state.settings.scale });
    };
    document.getElementById('btnResetPos').onclick = () => window.vh.resetPosition();

    document.getElementById('btnClearMemory').onclick = async () => {
      const entries = await window.vh.clearMemory();
      updateMemoryCount(entries.length);
    };

    document.getElementById('setToolsSave').onclick = () => {
      const tools = {
        braveApiKey: document.getElementById('setBraveKey').value.trim(),
        proactivity: document.getElementById('setProactivity').value
      };
      state.settings.tools = tools;
      window.vh.patchSettings({ tools });
      flashSaved(document.getElementById('setToolsSave'));
    };

    document.getElementById('setScreenSave').onclick = () => {
      const screenwatch = {
        enabled: document.getElementById('toggleScreenwatch').checked,
        intervalSeconds: Number(document.getElementById('setScreenInterval').value) || 15,
        idleMinutes: Number(document.getElementById('setScreenIdle').value) || 5
      };
      state.settings.screenwatch = screenwatch;
      window.vh.patchSettings({ screenwatch });
      flashSaved(document.getElementById('setScreenSave'));
    };
    document.getElementById('toggleScreenwatch').onchange = (e) => {
      const screenwatch = { ...state.settings.screenwatch, enabled: e.target.checked };
      state.settings.screenwatch = screenwatch;
      window.vh.patchSettings({ screenwatch });
    };

    document.getElementById('setOllamaSave').onclick = () => {
      const ollama = {
        autoStart: document.getElementById('toggleOllamaAuto').checked,
        host: document.getElementById('setOllamaHost').value.trim() || 'http://127.0.0.1:11434',
        model: document.getElementById('setOllamaModel').value.trim() || 'gemma3n',
        keepAliveMinutes: state.settings.ollama?.keepAliveMinutes || 30
      };
      state.settings.ollama = ollama;
      window.vh.patchSettings({ ollama });
      flashSaved(document.getElementById('setOllamaSave'));
    };

    document.getElementById('setOllamaSave').onclick = () => {
      const ollama = {
        autoStart: document.getElementById('toggleOllamaAuto').checked,
        host: document.getElementById('setOllamaHost').value.trim() || 'http://127.0.0.1:11434',
        model: document.getElementById('setOllamaModel').value.trim() || 'gemma3n',
        keepAliveMinutes: state.settings.ollama?.keepAliveMinutes || 30
      };
      state.settings.ollama = ollama;
      window.vh.patchSettings({ ollama });
      flashSaved(document.getElementById('setOllamaSave'));
    };

    document.getElementById('setSmalltalkSave').onclick = () => {
      const smalltalk = {
        enabled: document.getElementById('toggleSmalltalk').checked,
        frequency: document.getElementById('setSmalltalkFreq').value
      };
      state.settings.smalltalk = smalltalk;
      window.vh.patchSettings({ smalltalk });
      flashSaved(document.getElementById('setSmalltalkSave'));
    };
    document.getElementById('toggleSmalltalk').onchange = (e) => {
      const smalltalk = { ...state.settings.smalltalk, enabled: e.target.checked };
      state.settings.smalltalk = smalltalk;
      window.vh.patchSettings({ smalltalk });
    };

    document.getElementById('setPersonalitySave').onclick = () => {
      const personality = document.getElementById('setPersonality').value;
      state.settings.personality = personality;
      window.vh.patchSettings({ personality });
      flashSaved(document.getElementById('setPersonalitySave'));
    };

    document.getElementById('gcalConsoleLink').onclick = () => {
      window.vh.openExternal('https://console.cloud.google.com/apis/credentials');
    };

    document.getElementById('btnGcalConnect').onclick = async () => {
      const clientId = document.getElementById('setGcalClientId').value.trim();
      const clientSecret = document.getElementById('setGcalClientSecret').value.trim();
      if (!clientId || !clientSecret) {
        setGcalStatus('Сначала впиши Client ID и Client Secret.');
        return;
      }
      await window.vh.patchSettings({ googleCalendar: { ...state.settings.googleCalendar, clientId, clientSecret } });
      setGcalStatus('Открываю окно согласия Google в браузере…');
      const res = await window.vh.gcalConnect();
      if (res.ok) {
        setGcalStatus('Подключено ✓ — Асуна теперь читает и пишет в твой настоящий Google Calendar.');
      } else {
        setGcalStatus(`Не подключилось: ${res.error}`);
      }
    };

    document.getElementById('btnGcalDisconnect').onclick = async () => {
      await window.vh.gcalDisconnect();
      setGcalStatus('Отключено — Асуна снова использует локальный календарь.');
    };
  }

  function setGcalStatus(text) {
    document.getElementById('gcalStatusHint').textContent = text;
  }

  function flashSaved(btn) {
    const old = btn.textContent;
    btn.textContent = 'Сохранено ✓';
    setTimeout(() => (btn.textContent = old), 1300);
  }

  function applySettingsToUI() {
    document.getElementById('toggleAOT').checked = !!state.settings.alwaysOnTop;
    document.getElementById('setApiType').value = state.settings.llm?.apiType || 'gemini';
    document.getElementById('setApiKey').value = state.settings.llm?.apiKey || '';
    document.getElementById('setModel').value = state.settings.llm?.model || '';
    document.getElementById('setBaseUrl').value = state.settings.llm?.baseUrl || '';
    { const showBaseUrl = (state.settings.llm?.apiType === 'openai-compatible' || state.settings.llm?.apiType === 'gemini');
      document.getElementById('baseUrlField').style.display = showBaseUrl ? 'flex' : 'none';
      document.getElementById('baseUrlHint').style.display = showBaseUrl ? 'block' : 'none'; }

    document.getElementById('setTtsUrl').value = state.settings.audio?.ttsServerUrl || 'http://127.0.0.1:8009';
    document.getElementById('setTtsSpeaker').value = state.settings.audio?.ttsSpeaker || 'baya';
    const vadT = state.settings.audio?.vadThreshold || 0.03;
    document.getElementById('setVadThreshold').value = vadT;
    document.getElementById('vadVal').textContent = vadT.toFixed(2);
    const vadS = state.settings.audio?.vadSilenceMs ?? 900;
    document.getElementById('setVadSilence').value = vadS;
    document.getElementById('vadSilenceVal').textContent = vadS;
    document.getElementById('toggleAutoSpeak').checked = state.settings.audio?.autoSpeak !== false;
    document.getElementById('toggleBargeIn').checked = !!state.settings.audio?.bargeIn;

    const pct = Math.round((state.settings.scale || 1) * 100);
    document.getElementById('scaleRange').value = pct;
    document.getElementById('scaleVal').textContent = pct + '%';

    document.getElementById('setBraveKey').value = state.settings.tools?.braveApiKey || '';
    document.getElementById('setProactivity').value = state.settings.tools?.proactivity || 'occasional';
    document.getElementById('toggleRobot').checked = !!state.settings.robot?.enabled;

    document.getElementById('toggleScreenwatch').checked = !!state.settings.screenwatch?.enabled;
    document.getElementById('setScreenInterval').value = state.settings.screenwatch?.intervalSeconds || 15;
    document.getElementById('setScreenIdle').value = state.settings.screenwatch?.idleMinutes || 5;

    document.getElementById('toggleOllamaAuto').checked = state.settings.ollama?.autoStart !== false;
    document.getElementById('setOllamaHost').value = state.settings.ollama?.host || 'http://127.0.0.1:11434';
    document.getElementById('setOllamaModel').value = state.settings.ollama?.model || 'gemma3n';

    document.getElementById('toggleSmalltalk').checked = state.settings.smalltalk?.enabled !== false;
    document.getElementById('setSmalltalkFreq').value = state.settings.smalltalk?.frequency || 'medium';

    document.getElementById('setPersonality').value = state.settings.personality || 'standard';

    document.getElementById('setGcalClientId').value = state.settings.googleCalendar?.clientId || '';
    document.getElementById('setGcalClientSecret').value = state.settings.googleCalendar?.clientSecret || '';
    refreshGcalStatus();
  }

  async function refreshGcalStatus() {
    try {
      const res = await window.vh.gcalStatus();
      setGcalStatus(res.connected
        ? 'Подключено ✓ — Асуна читает и пишет в твой настоящий Google Calendar.'
        : 'Не подключён — используется локальный календарь.');
    } catch (e) { /* окно ещё грузится — не критично */ }
  }
})();
