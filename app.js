const { Capacitor } = window.CapacitorApp || window.Capacitor ? window : {};
const ScreenRecorder = window.Capacitor ? window.Capacitor.Plugins.ScreenRecorder : null;

const statusDot = document.getElementById('statusDot');
const statusText = document.getElementById('statusText');
const timerEl = document.getElementById('timer');
const recordBtn = document.getElementById('recordBtn');
const recordBtnLabel = document.getElementById('recordBtnLabel');
const lastFileEl = document.getElementById('lastFile');
const settingsBtn = document.getElementById('settingsBtn');

let isRecording = false;
let startTime = null;
let timerInterval = null;

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme || 'system');
}

function fmt(t) {
  const h = String(Math.floor(t / 3600)).padStart(2, '0');
  const m = String(Math.floor((t % 3600) / 60)).padStart(2, '0');
  const s = String(Math.floor(t % 60)).padStart(2, '0');
  return `${h}:${m}:${s}`;
}

function startTimer() {
  startTime = Date.now();
  timerInterval = setInterval(() => {
    timerEl.textContent = fmt((Date.now() - startTime) / 1000);
  }, 1000);
}

function stopTimer() {
  clearInterval(timerInterval);
  timerEl.textContent = '00:00:00';
}

function setUiState(recording) {
  isRecording = recording;
  statusDot.className = 'status-dot' + (recording ? ' recording' : ' idle');
  statusText.textContent = recording ? 'Recording' : 'Idle';
  recordBtn.className = 'record-btn' + (recording ? ' recording' : '');
  recordBtnLabel.textContent = recording ? 'Stop Recording' : 'Start Recording';
  if (recording) startTimer(); else stopTimer();
}

async function refreshState() {
  if (!ScreenRecorder) return;
  try {
    const res = await ScreenRecorder.getState();
    setUiState(!!res.recording);
  } catch (e) {
    console.warn('getState failed', e);
  }
}

recordBtn.addEventListener('click', async () => {
  if (!ScreenRecorder) {
    alert('Native plugin not available (are you running this in a browser instead of the installed app?)');
    return;
  }
  try {
    if (!isRecording) {
      const res = await ScreenRecorder.startRecording();
      if (res && res.started) setUiState(true);
    } else {
      const res = await ScreenRecorder.stopRecording();
      setUiState(false);
      if (res && res.filePath) {
        lastFileEl.textContent = 'Saved: ' + res.filePath;
      }
    }
  } catch (e) {
    console.error(e);
    alert('Action failed: ' + (e.message || e));
  }
});

settingsBtn.addEventListener('click', () => {
  window.location.href = 'settings.html';
});

document.addEventListener('deviceready', () => {
  refreshState();
  if (ScreenRecorder && ScreenRecorder.addListener) {
    // Corrects the UI whenever the native side's real state changes async
    // (e.g. the service failed to start after startRecording() already
    // resolved, or recording was stopped from the Quick Settings tile).
    ScreenRecorder.addListener('stateChanged', (data) => {
      setUiState(!!data.recording);
    });
  }
});

// Load saved theme immediately (before deviceready) so there's no flash
(function preloadTheme() {
  try {
    const t = localStorage.getItem('sr_theme') || 'system';
    applyTheme(t);
  } catch (e) {}
})();

window.addEventListener('load', refreshState);
