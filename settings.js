const ScreenRecorder = window.Capacitor ? window.Capacitor.Plugins.ScreenRecorder : null;

const fpsRow = document.getElementById('fpsRow');
const qualityRow = document.getElementById('qualityRow');
const storageRow = document.getElementById('storageRow');
const themeRow = document.getElementById('themeRow');
const storagePathEl = document.getElementById('storagePath');
const micToggle = document.getElementById('micToggle');
const backBtn = document.getElementById('backBtn');

let current = {
  fps: 30,
  quality: 'high',
  storageMode: 'default',
  storagePath: '',
  theme: 'system',
  mic: false
};

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme || 'system');
}

function highlight(row, attr, value) {
  [...row.children].forEach(btn => {
    btn.classList.toggle('active', btn.dataset[attr] === String(value));
  });
}

function refreshUi() {
  highlight(fpsRow, 'fps', current.fps);
  highlight(qualityRow, 'quality', current.quality);
  highlight(storageRow, 'storage', current.storageMode);
  highlight(themeRow, 'theme', current.theme);
  micToggle.checked = !!current.mic;
  storagePathEl.textContent = current.storageMode === 'custom' && current.storagePath
    ? current.storagePath
    : 'Videos are saved to the device Movies/ScreenRecorder folder.';
  applyTheme(current.theme);
}

async function loadSettings() {
  if (!ScreenRecorder) { refreshUi(); return; }
  try {
    const res = await ScreenRecorder.getSettings();
    current = { ...current, ...res };
  } catch (e) {
    console.warn('getSettings failed, using defaults', e);
  }
  refreshUi();
}

async function saveSettings(patch) {
  current = { ...current, ...patch };
  refreshUi();
  try { localStorage.setItem('sr_theme', current.theme); } catch (e) {}
  if (!ScreenRecorder) return;
  try {
    await ScreenRecorder.setSettings(current);
  } catch (e) {
    console.error('setSettings failed', e);
  }
}

fpsRow.addEventListener('click', (e) => {
  const btn = e.target.closest('.chip');
  if (!btn) return;
  saveSettings({ fps: parseInt(btn.dataset.fps, 10) });
});

qualityRow.addEventListener('click', (e) => {
  const btn = e.target.closest('.chip');
  if (!btn) return;
  saveSettings({ quality: btn.dataset.quality });
});

themeRow.addEventListener('click', (e) => {
  const btn = e.target.closest('.chip');
  if (!btn) return;
  saveSettings({ theme: btn.dataset.theme });
});

storageRow.addEventListener('click', async (e) => {
  const btn = e.target.closest('.chip');
  if (!btn) return;
  if (btn.dataset.storage === 'default') {
    saveSettings({ storageMode: 'default', storagePath: '' });
    return;
  }
  // custom: ask native side to open the SAF folder picker
  if (!ScreenRecorder) return;
  try {
    const res = await ScreenRecorder.pickStorageFolder();
    if (res && res.uri) {
      saveSettings({ storageMode: 'custom', storagePath: res.uri });
    }
  } catch (e) {
    console.warn('Folder pick cancelled or failed', e);
  }
});

micToggle.addEventListener('change', () => {
  saveSettings({ mic: micToggle.checked });
});

backBtn.addEventListener('click', () => {
  window.location.href = 'index.html';
});

document.addEventListener('deviceready', loadSettings);
window.addEventListener('load', loadSettings);
