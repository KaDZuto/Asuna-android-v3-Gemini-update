/**
 * Отдельное окно музыкального плеера
 * Использует IPC через window.vh для управления и получения обновлений
 */

let playlist = [];
let currentTrackIndex = -1;
let isPlaying = false;
let currentVolume = 80;

// DOM-элементы
const trackTitle = document.getElementById('trackTitle');
const trackArtist = document.getElementById('trackArtist');
const currentTimeEl = document.getElementById('currentTime');
const durationTimeEl = document.getElementById('durationTime');
const progressFill = document.getElementById('progressFill');
const progressBar = document.getElementById('progressBar');
const playBtn = document.getElementById('playBtn');
const prevBtn = document.getElementById('prevBtn');
const nextBtn = document.getElementById('nextBtn');
const volumeSlider = document.getElementById('volumeSlider');
const playlistContainer = document.getElementById('playlistContainer');
const closeBtn = document.getElementById('closeBtn');

// Инициализация
function init() {
  // Подписка на обновления состояния от main
  if (window.vh && window.vh.onMusicStateUpdate) {
    window.vh.onMusicStateUpdate((state) => {
      playlist = state.playlist || [];
      currentTrackIndex = state.currentIndex;
      isPlaying = state.isPlaying;
      currentVolume = state.volume;
      const track = state.track;

      renderPlaylist();
      updateTrackInfo(track);
      updatePlayButton();
      updateVolume();
      updateProgress(state.currentTime, state.duration);
    });
  }

  // Кнопки управления
  playBtn.addEventListener('click', togglePlay);
  prevBtn.addEventListener('click', () => window.vh?.prevTrack?.());
  nextBtn.addEventListener('click', () => window.vh?.nextTrack?.());
  volumeSlider.addEventListener('input', (e) => {
    const vol = parseInt(e.target.value, 10);
    window.vh?.setVolume?.(vol);
  });
  progressBar.addEventListener('click', (e) => {
    const rect = progressBar.getBoundingClientRect();
    const x = (e.clientX - rect.left) / rect.width;
    // Seek не реализован в текущей версии, но можно добавить позже
    console.log('Seek to:', x);
  });
  closeBtn.addEventListener('click', () => window.close());

  // Запрашиваем текущий плейлист при загрузке
  window.vh?.getPlaylist?.().then((data) => {
    if (data) {
      playlist = data.playlist || [];
      currentTrackIndex = data.currentIndex;
      renderPlaylist();
      if (currentTrackIndex >= 0 && currentTrackIndex < playlist.length) {
        updateTrackInfo(playlist[currentTrackIndex]);
      }
    }
  }).catch(console.warn);

  // Запрашиваем текущее состояние громкости
  window.vh?.getVolume?.().then((vol) => {
    if (vol !== undefined) {
      currentVolume = vol;
      updateVolume();
    }
  }).catch(console.warn);
}

function togglePlay() {
  if (isPlaying) {
    window.vh?.pauseMusic?.();
  } else {
    window.vh?.resumeMusic?.();
  }
}

function updateTrackInfo(track) {
  if (track) {
    trackTitle.textContent = track.name || 'Без названия';
    trackArtist.textContent = track.artist || 'Неизвестный исполнитель';
  } else {
    trackTitle.textContent = 'Нет трека';
    trackArtist.textContent = '—';
  }
}

function updatePlayButton() {
  playBtn.textContent = isPlaying ? '⏸' : '▶';
}

function updateProgress(current, duration) {
  if (duration > 0) {
    const percent = (current / duration) * 100;
    progressFill.style.width = Math.min(percent, 100) + '%';
    currentTimeEl.textContent = formatTime(current);
    durationTimeEl.textContent = formatTime(duration);
  } else {
    progressFill.style.width = '0%';
    currentTimeEl.textContent = '0:00';
    durationTimeEl.textContent = '0:00';
  }
}

function updateVolume() {
  volumeSlider.value = currentVolume;
}

function formatTime(seconds) {
  if (!seconds || isNaN(seconds)) return '0:00';
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return mins + ':' + (secs < 10 ? '0' : '') + secs;
}

function renderPlaylist() {
  if (!playlist || playlist.length === 0) {
    playlistContainer.innerHTML = '<div class="empty-playlist">Плейлист пуст. Добавьте музыку через панель управления.</div>';
    return;
  }
  let html = '';
  playlist.forEach((track, idx) => {
    const activeClass = idx === currentTrackIndex ? 'active' : '';
    html += `
      <div class="playlist-item ${activeClass}" data-index="${idx}">
        <span class="idx">${idx + 1}</span>
        <span class="name">${track.name || 'Без названия'}</span>
        <span class="duration">${track.duration ? formatTime(track.duration) : ''}</span>
      </div>
    `;
  });
  playlistContainer.innerHTML = html;
  // Клик по элементу плейлиста
  playlistContainer.querySelectorAll('.playlist-item').forEach(el => {
    el.addEventListener('click', () => {
      const idx = parseInt(el.dataset.index, 10);
      if (!isNaN(idx) && idx >= 0 && idx < playlist.length) {
        window.vh?.playMusic?.(playlist[idx].path);
      }
    });
  });
}

// Запуск
init();
