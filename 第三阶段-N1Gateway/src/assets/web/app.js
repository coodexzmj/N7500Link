'use strict';

const $ = (id) => document.getElementById(id);
const stateLabels = {
  STOPPED: '已停止',
  STARTING: '正在启动',
  CONNECTING_BLUETOOTH: '连接蓝牙',
  ONLINE: '在线',
  DEGRADED: '降级运行',
  RECONNECTING: '正在重连',
  FAULT: '故障',
  DISCONNECTED: '未连接',
  CONNECTING: '正在连接',
  CONNECTED: '已连接'
};
let refreshTimer = 0;
let eventSource = null;
let lastSnapshotAt = 0;

function text(id, value) {
  const element = $(id);
  if (!element) return;
  if (value === null || value === undefined || value === '') {
    element.textContent = '—';
    element.classList.add('empty');
  } else {
    element.textContent = String(value);
    element.classList.remove('empty');
  }
}

function boolText(value) {
  if (value === null || value === undefined) return null;
  return value ? '是' : '否';
}

function nested(root, name) {
  return root && root[name] && typeof root[name] === 'object' ? root[name] : {};
}

function stateText(value) {
  if (!value) return null;
  return stateLabels[String(value).toUpperCase()] || String(value);
}

function toneFor(connected, stale) {
  if (!connected) return 'offline';
  return stale ? 'stale' : 'online';
}

function setDot(id, tone) {
  const dot = $(id);
  if (dot) dot.className = 'dot ' + tone;
}

function showNotice(message) {
  const notice = $('notice');
  if (!notice) return;
  notice.textContent = message;
  notice.hidden = !message;
}

function formatTime(value) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { hour12: false });
}

function frequency(value) {
  if (value === null || value === undefined) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(4) + ' MHz' : String(value);
}

function pair(first, second) {
  if (first === null || first === undefined || second === null || second === undefined) return null;
  return String(first) + ' / ' + String(second);
}

function render(snapshot) {
  const gateway = nested(snapshot, 'gateway');
  const connection = nested(snapshot, 'connection');
  const radio = nested(snapshot, 'radio');
  const capabilities = nested(radio, 'capabilities');
  const settings = nested(radio, 'settings');
  const status = nested(radio, 'status');
  const channel = nested(snapshot, 'channel');

  const connected = String(connection.state || '').toUpperCase() === 'CONNECTED';
  const stale = snapshot.stale === true;
  const tone = toneFor(connected, stale);
  const connectionLabel = connected
    ? (stale ? '已连接 · 状态已过期' : '已连接 · 实时状态')
    : '电台未连接';
  const device = connection.deviceLabel || gateway.deviceLabel || 'N7500';
  const channelName = channel.name || status.currentChannel || null;
  const updateText = formatTime(snapshot.updatedAt);

  setDot('connection-dot', tone);
  setDot('side-state-dot', tone);
  setDot('diagnostic-dot', tone);
  text('connection-text', connectionLabel);
  text('side-device', device);
  text('side-summary', channelName ? channelName + ' · ' + connectionLabel : connectionLabel);

  const freshness = $('freshness');
  if (freshness) {
    freshness.textContent = stale ? '快照已过期' : '快照有效';
    freshness.className = 'status-pill ' + tone;
  }

  text('overview-device', device);
  text('overview-channel', channelName);
  text('overview-frequency', frequency(channel.rxMHz));
  text('overview-receiving', boolText(status.receiving));
  text('overview-gateway-state', stateText(gateway.state));
  text('overview-updated', updateText);
  text('overview-freshness', stale ? '最后已知状态' : '实时');
  text('revision', snapshot.revision === undefined ? null : 'revision ' + snapshot.revision);

  text('gateway-state', stateText(gateway.state));
  text('connection-state', stateText(connection.state));
  text('gateway-device-label', device);
  text('updated-at', updateText);
  text('source', snapshot.source);
  text('instance-id', snapshot.instanceId ? '实例 ' + snapshot.instanceId : null);

  text('product-id', radio.productId);
  text('software-version', radio.softwareVersion);
  text('channel-count', pair(radio.channelCount, radio.zoneCount));
  text('band-count', radio.bandCount);
  text('device-label', device);

  text('channel-name', channel.name);
  text('rx-mhz', frequency(channel.rxMHz));
  text('tx-mhz', frequency(channel.txMHz));
  text('current-channel', status.currentChannel);
  text('current-zone', status.currentZone);
  text('receiving', boolText(status.receiving));
  text('squelch-open', boolText(status.squelchOpen));
  text('scanning', boolText(status.scanning));
  text('rssi', status.rssiLevel);
  text('volume', snapshot.volume);
  text('squelch', settings.squelch);
  text('dual-mode', settings.dualMode);

  text('fm-receive', boolText(capabilities.fmReceive));
  text('dmr', boolText(capabilities.dmr));
  text('vfo', boolText(capabilities.vfo));

  text('diagnostic-freshness', stale ? '已过期 / 最后已知值' : '有效');
  text('diagnostic-revision', snapshot.revision);
  text('diagnostic-updated', updateText);
  text('diagnostic-source', snapshot.source);
  const errorCode = gateway.lastErrorCode || connection.lastError || null;
  text('last-error', errorCode);
  text('diagnostic-error', errorCode);
  text('diagnostic-title', connected ? (stale ? '网关已连接，但快照过期' : '网关与电台连接正常') : '电台连接中断');
  text('diagnostic-detail', connected
    ? 'N1 前台服务正在持续读取蓝牙状态。'
    : (errorCode ? '最后错误：' + errorCode : '等待网关重新连接蓝牙电台。'));

  if (errorCode === 'RADIO_READ_TIMEOUT') {
    showNotice('蓝牙已连接，但连续读取超时。网关会自动重试；若持续失败，需要重新启动网关。');
  } else {
    showNotice('');
  }
  lastSnapshotAt = Date.now();
}

async function loadSnapshot() {
  refreshTimer = 0;
  try {
    const response = await fetch('/api/v1/snapshot', {
      cache: 'no-store',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' }
    });
    if (response.status === 401) {
      window.location.replace('/login');
      return;
    }
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      showNotice(body.message || '暂时没有可用快照，等待电台连接。');
      return;
    }
    showNotice('');
    render(body);
  } catch (error) {
    showNotice('与 N1Gateway 的连接中断，正在自动重试。');
    setDot('connection-dot', 'offline');
    setDot('side-state-dot', 'offline');
    text('connection-text', '网页离线');
  }
}

function scheduleRefresh() {
  if (refreshTimer) return;
  refreshTimer = window.setTimeout(loadSnapshot, 120);
}

function connectEvents() {
  if (!window.EventSource) return;
  if (eventSource) eventSource.close();
  eventSource = new EventSource('/api/v1/events');
  eventSource.addEventListener('snapshot', scheduleRefresh);
  eventSource.addEventListener('ready', scheduleRefresh);
  eventSource.onerror = () => {
    if (eventSource) eventSource.close();
    window.setTimeout(connectEvents, 3000);
  };
}

async function logout() {
  try {
    await fetch('/api/v1/session', {
      method: 'DELETE',
      credentials: 'same-origin',
      cache: 'no-store'
    });
  } finally {
    window.location.replace('/login');
  }
}

function selectPanel(name, updateHash) {
  const valid = document.querySelector('[data-panel="' + name + '"].nav-item');
  if (!valid) name = 'overview';
  document.querySelectorAll('.nav-item').forEach((button) => {
    const active = button.dataset.panel === name;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', active ? 'true' : 'false');
  });
  document.querySelectorAll('.panel').forEach((panel) => {
    panel.hidden = panel.dataset.panel !== name;
  });
  if (updateHash) history.replaceState(null, '', '#' + name);
}

document.addEventListener('DOMContentLoaded', () => {
  const logoutButton = $('logout');
  if (logoutButton) logoutButton.addEventListener('click', logout);

  document.querySelectorAll('.nav-item').forEach((button) => {
    button.addEventListener('click', () => selectPanel(button.dataset.panel, true));
  });
  const initial = location.hash ? location.hash.substring(1) : 'overview';
  selectPanel(initial, false);

  loadSnapshot();
  connectEvents();
  window.setInterval(loadSnapshot, 5000);
  window.setInterval(() => {
    if (lastSnapshotAt && Date.now() - lastSnapshotAt > 15000) {
      setDot('connection-dot', 'stale');
      setDot('side-state-dot', 'stale');
    }
  }, 1000);
});
