// SAO Player — логика
(function() {
  const audio = document.getElementById('audioPlayer');
  const playlistEl = document.getElementById('playlist');
  const playBtn = document.getElementById('playBtn');
  const prevBtn = document.getElementById('prevBtn');
  const nextBtn = document.getElementById('nextBtn');
  const volumeSlider = document.getElementById('volumeSlider');
  const volumeLabel = document.getElementById('volumeLabel');

  let playlist = [];
  let currentIndex = 0;
  let isPlaying = false;

  // Получение списка песен из main
  async function loadPlaylist() {
    try {
      const result = await window.vh.getPlaylist();
      playlist = result.playlist || [];
      currentIndex = result.currentIndex || 0;
      renderPlaylist();
      if (playlist.length > 0) {
        loadTrack(currentIndex);
      } else {
        playlistEl.innerHTML = '<div class="sao-empty">Нет песен в папке music</div>';
      }
    } catch (e) {
      console.error('Ошибка загрузки плейлиста:', e);
      playlistEl.innerHTML = '<div class="sao-empty">Ошибка загрузки плейлиста</div>';
    }
  }

  function renderPlaylist() {
    if (!playlist.length) {
      playlistEl.innerHTML = '<div class="sao-empty">Нет песен в папке music</div>';
      return;
    }
    let html = '';
    playlist.forEach((track, idx) => {
      const active = idx === currentIndex ? 'active' : '';
      const name = track.replace(/\.[^.]+$/, ''); // убираем расширение
      html += `<div class="sao-track ${active}" data-index="${idx}">
        <span class="sao-track-name">${idx + 1}. ${name}</span>
        <span class="sao-track-index">${idx === currentIndex ? '▶' : ''}</span>
      </div>`;
    });
    playlistEl.innerHTML = html;
    // Клик по треку
    document.querySelectorAll('.sao-track').forEach(el => {
      el.addEventListener('click', function() {
        const idx = parseInt(this.dataset.index);
        if (!isNaN(idx) && idx !== currentIndex) {
          currentIndex = idx;
          loadTrack(currentIndex);
          renderPlaylist();
          play();
        }
      });
    });
  }

  function loadTrack(index) {
    if (!playlist.length || index < 0 || index >= playlist.length) return;
    const track = playlist[index];
    // Путь к файлу через file:// протокол
    const filePath = `file://${window.location.origin}/music/${encodeURIComponent(track)}`;
    audio.src = filePath;
    audio.load();
    // Обновляем громкость
    const vol = parseInt(volumeSlider.value) || 80;
    audio.volume = vol / 100;
    volumeLabel.textContent = vol + '%';
    // Отправляем в main
    window.vh.setVolume(vol);
  }

  function play() {
    if (!audio.src) return;
    audio.play().then(() => {
      isPlaying = true;
      playBtn.textContent = '⏸';
    }).catch(err => {
      console.warn('Не удалось воспроизвести:', err);
    });
  }

  function pause() {
    audio.pause();
    isPlaying = false;
    playBtn.textContent = '▶';
  }

  function togglePlay() {
    if (isPlaying) {
      pause();
    } else {
      play();
    }
  }

  function next() {
    if (!playlist.length) return;
    currentIndex = (currentIndex + 1) % playlist.length;
    loadTrack(currentIndex);
    renderPlaylist();
    if (isPlaying) play();
  }

  function prev() {
    if (!playlist.length) return;
    currentIndex = (currentIndex - 1 + playlist.length) % playlist.length;
    loadTrack(currentIndex);
    renderPlaylist();
    if (isPlaying) play();
  }

  // Обработчики
  playBtn.addEventListener('click', togglePlay);
  nextBtn.addEventListener('click', next);
  prevBtn.addEventListener('click', prev);

  volumeSlider.addEventListener('input', function() {
    const val = parseInt(this.value);
    audio.volume = val / 100;
    volumeLabel.textContent = val + '%';
    window.vh.setVolume(val);
  });

  audio.addEventListener('ended', () => {
    next();
  });

  audio.addEventListener('error', (e) => {
    console.warn('Ошибка аудио:', e);
    // Попробуем следующий трек
    if (playlist.length > 0) {
      next();
    }
  });

  // Получаем громкость при старте
  async function loadVolume() {
    try {
      const vol = await window.vh.getVolume();
      const v = Math.min(100, Math.max(0, vol || 80));
      volumeSlider.value = v;
      volumeLabel.textContent = v + '%';
      audio.volume = v / 100;
    } catch (e) { /* ignore */ }
  }

  // Инициализация
  loadVolume();
  loadPlaylist();

  // Обработка обновлений состояния музыки из main
  window.vh.onMusicStateUpdate((state) => {
    if (state && state.currentIndex !== undefined) {
      currentIndex = state.currentIndex;
      renderPlaylist();
    }
  });
})();