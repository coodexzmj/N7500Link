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
const controlStateLabels = {
  IDLE: '空闲',
  ACCEPTED: '已接受',
  BASELINE_READ: '读取基线',
  WRITING: '正在写入',
  READBACK: '正在回读',
  READBACK_OK: '回读成功',
  READBACK_MISMATCH: '回读不一致',
  ROLLBACK_WRITING: '正在回滚',
  ROLLBACK_OK: '已安全回滚',
  ROLLBACK_FAILED: '回滚失败',
  TIMEOUT: '操作超时',
  BUSY: '控制忙',
  UNAVAILABLE: '控制不可用',
  LINK_LOST: '蓝牙断链',
  CANCELLED: '已取消'
};
const controlKindLabels = {
  VOLUME: '音量调节',
  SCAN: '扫描切换',
  SIDE: '信道 A/B 切换'
};
const reasonLabels = {
  BASELINE: '正在读取操作前值',
  NO_CHANGE: '目标与当前值一致，无需写入',
  TARGET_MATCHED: '回读与目标一致',
  READBACK_MISMATCH: '回读与目标不一致',
  OPERATION_TIMEOUT: '操作超时',
  ROLLBACK_TIMEOUT: '回滚超时',
  ROLLBACK_BASELINE_MISSING: '缺少可恢复的基线',
  ROLLBACK_LINK_LOST: '回滚时蓝牙链路已断开',
  ROLLBACK_NOT_ACCEPTED_NO_RETRY: '回滚未被接受，未重复发送',
  EPOCH_CHANGED: '蓝牙连接实例已变化',
  CONTROL_BUSY: '已有控制操作正在执行'
};
let refreshTimer = 0;
let eventSource = null;
let lastSnapshotAt = 0;
let latestSnapshot = null;
let latestControl = null;
let volumeDirty = false;
let scanDirty = false;
let sideDirty = false;
let selectedSide = null;
let controlSubmitPending = false;

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

function controlStateText(value) {
  if (!value) return '空闲';
  return controlStateLabels[String(value).toUpperCase()] || String(value);
}

function controlKindText(value) {
  if (!value) return '最近一次控制';
  return controlKindLabels[String(value).toUpperCase()] || String(value);
}

function reasonText(value) {
  if (!value) return null;
  return reasonLabels[String(value).toUpperCase()] || String(value);
}

function settingsSummary(scanning, side) {
  const scan = boolText(scanning);
  if (scan === null && !side) return null;
  return (scan === null ? '扫描 —' : '扫描 ' + scan) + ' · ' + (side || 'A/B —');
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

function radioIsUsable() {
  if (!latestSnapshot) return false;
  const connection = nested(latestSnapshot, 'connection');
  return String(connection.state || '').toUpperCase() === 'CONNECTED'
    && latestSnapshot.stale !== true;
}

function render(snapshot) {
  latestSnapshot = snapshot;
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
  text('overview-volume', snapshot.volume === null || snapshot.volume === undefined ? null : snapshot.volume);
  text('overview-settings', settingsSummary(status.scanning, settings.dualMode));
  text('overview-updated', updateText);
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
  }

  syncControlTargets(snapshot);
  renderControl(latestControl);
  lastSnapshotAt = Date.now();
}

function syncControlTargets(snapshot) {
  const settings = nested(nested(snapshot, 'radio'), 'settings');
  const status = nested(nested(snapshot, 'radio'), 'status');
  const currentVolume = Number(snapshot.volume);
  const volumeRange = $('volume-range');
  const volumeValue = $('volume-value');

  text('control-volume-current', snapshot.volume === null || snapshot.volume === undefined
    ? '当前 —' : '当前 ' + snapshot.volume);
  if (volumeRange && !volumeDirty && Number.isInteger(currentVolume)) {
    volumeRange.value = String(Math.max(0, Math.min(15, currentVolume)));
  }
  if (volumeRange && volumeValue) volumeValue.textContent = volumeRange.value;

  text('control-scan-current', status.scanning === null || status.scanning === undefined
    ? '当前 —' : '当前 ' + (status.scanning ? 'ON' : 'OFF'));
  const scanTarget = $('scan-target');
  if (scanTarget && !scanDirty && typeof status.scanning === 'boolean') {
    scanTarget.checked = status.scanning;
  }
  updateScanTargetText();

  text('control-side-current', settings.dualMode ? '当前 ' + settings.dualMode : '当前 —');
  if (!sideDirty && (settings.dualMode === 'A' || settings.dualMode === 'B')) {
    selectedSide = settings.dualMode;
  }
  updateSideSelection();
}

function updateScanTargetText() {
  const scanTarget = $('scan-target');
  const label = $('scan-target-text');
  if (!scanTarget) return;
  const value = scanTarget.checked ? 'ON' : 'OFF';
  if (label) label.textContent = '目标：' + value + ' · 未应用' + (scanDirty ? '' : '（与当前一致）');
}

function updateSideSelection() {
  document.querySelectorAll('[data-side]').forEach((button) => {
    const active = selectedSide === button.dataset.side;
    button.classList.toggle('selected', active);
    button.setAttribute('aria-pressed', active ? 'true' : 'false');
  });
  const label = $('side-target-text');
  if (label) {
    label.textContent = selectedSide
      ? '目标：' + selectedSide + ' · 未应用' + (sideDirty ? '' : '（与当前一致）')
      : '未选择目标';
  }
}

function classifyControlTone(control) {
  if (!control || control.available === false) return 'offline';
  if (control.busy || ['ACCEPTED', 'BASELINE_READ', 'WRITING', 'READBACK',
    'ROLLBACK_WRITING'].includes(String(control.state || ''))) return 'stale';
  if (['READBACK_OK', 'ROLLBACK_OK'].includes(String(control.state || ''))) return 'online';
  if (['READBACK_MISMATCH', 'ROLLBACK_FAILED', 'TIMEOUT', 'LINK_LOST',
    'UNAVAILABLE', 'CANCELLED'].includes(String(control.state || ''))) return 'offline';
  return 'online';
}

function setControlDisabled(disabled) {
  const ids = ['volume-range', 'volume-apply', 'scan-target', 'scan-apply', 'side-apply'];
  ids.forEach((id) => {
    const element = $(id);
    if (element) element.disabled = disabled;
  });
  document.querySelectorAll('[data-side]').forEach((button) => {
    button.disabled = disabled;
  });
  const panel = $('panel-control');
  if (panel) panel.setAttribute('aria-busy', disabled ? 'true' : 'false');
}

function clearCompletedTargets(control) {
  const state = String((control && control.state) || '').toUpperCase();
  if (state !== 'READBACK_OK' && state !== 'ROLLBACK_OK') return;
  const kind = String((control && control.kind) || '').toUpperCase();
  if (kind === 'VOLUME') volumeDirty = false;
  if (kind === 'SCAN') scanDirty = false;
  if (kind === 'SIDE') sideDirty = false;
}

function renderControl(control) {
  latestControl = control || null;
  clearCompletedTargets(latestControl);
  const state = latestControl ? String(latestControl.state || 'IDLE') : 'UNAVAILABLE';
  const available = Boolean(latestControl && latestControl.available === true);
  const busy = Boolean(latestControl && latestControl.busy === true);
  const stateLabel = controlStateText(state);
  const tone = classifyControlTone(latestControl);
  const disabled = !available || busy || controlSubmitPending || !radioIsUsable();

  const pill = $('control-state-pill');
  if (pill) {
    pill.textContent = latestControl ? stateLabel : '控制接口不可用';
    pill.className = 'status-pill ' + tone;
  }

  setDot('control-guard-dot', tone);
  text('control-guard-title', !latestControl
    ? '控制接口不可用'
    : busy ? '控制操作进行中' : available ? '控制接口可用' : '电台或控制会话未就绪');
  text('control-guard-detail', !latestControl
    ? '等待 N1RemoteControl 返回控制状态。'
    : busy
      ? '正在执行 ' + controlKindText(latestControl.kind) + '；其它控制会自动拒绝。'
      : available
        ? '同一时刻只允许一个操作。提交后将自动读取基线、写入、回读并按需回滚。'
        : '控制会话尚未就绪，请先确认蓝牙电台在线。');

  text('control-operation-title', latestControl
    ? controlKindText(latestControl.kind) + ' · ' + stateLabel
    : '尚无操作');
  text('control-operation-id', latestControl && latestControl.operationId
    ? latestControl.operationId : null);
  text('control-baseline', latestControl && latestControl.baselineDisplay
    ? latestControl.baselineDisplay : null);
  text('control-expected', latestControl && latestControl.expectedDisplay
    ? latestControl.expectedDisplay : null);
  text('control-observed', latestControl && latestControl.observedDisplay
    ? latestControl.observedDisplay : null);
  text('control-reason', latestControl ? reasonText(latestControl.reason) : null);

  const note = $('control-result-note');
  if (note) {
    let message = '提交控制后，这里会依次显示读取、写入、回读和回滚结果。';
    let noteTone = 'neutral';
    if (busy) {
      message = '操作已受理，正在读取或等待电台回读，请不要重复提交。';
      noteTone = 'warning';
    } else if (state === 'READBACK_OK') {
      message = latestControl.reason === 'NO_CHANGE'
        ? '目标与操作前值相同，没有发送写入命令。'
        : '写入后的回读与目标一致，本次控制完成。';
      noteTone = 'success';
    } else if (state === 'ROLLBACK_OK') {
      message = '本次写入未达到目标，系统已恢复操作前的基线。';
      noteTone = 'warning';
    } else if (state === 'ROLLBACK_FAILED') {
      message = '自动回滚未成功。请立即核对电台状态，不要连续重复操作。';
      noteTone = 'error';
    } else if (state === 'READBACK_MISMATCH' || state === 'TIMEOUT'
      || state === 'LINK_LOST' || state === 'CANCELLED') {
      message = '本次控制未正常完成，请查看结果或回滚状态后再决定下一步。';
      noteTone = 'error';
    } else if (!available) {
      message = '控制接口当前不可用，页面只展示状态，不会发送写入命令。';
      noteTone = 'warning';
    }
    note.textContent = message;
    note.dataset.tone = noteTone;
  }

  text('diagnostic-control', latestControl
    ? stateLabel + (latestControl.reason ? ' / ' + reasonText(latestControl.reason) : '')
    : null);
  setControlDisabled(disabled);
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
    render(body);
  } catch (error) {
    showNotice('与 N1RemoteControl 的连接中断，正在自动重试。');
    setDot('connection-dot', 'offline');
    setDot('side-state-dot', 'offline');
    text('connection-text', '网页离线');
    renderControl(latestControl);
  }
}

async function loadControl() {
  try {
    const response = await fetch('/api/v1/control', {
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
      renderControl({
        available: false,
        busy: false,
        state: body.code || 'UNAVAILABLE',
        reason: body.message || body.code || 'CONTROL_STATUS_UNAVAILABLE'
      });
      return;
    }
    renderControl(body);
  } catch (error) {
    renderControl({
      available: false,
      busy: false,
      state: 'UNAVAILABLE',
      reason: 'CONTROL_STATUS_UNREACHABLE'
    });
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
  eventSource.addEventListener('snapshot', () => {
    scheduleRefresh();
    loadControl();
  });
  eventSource.addEventListener('ready', () => {
    scheduleRefresh();
    loadControl();
  });
  eventSource.onerror = () => {
    if (eventSource) eventSource.close();
    window.setTimeout(connectEvents, 3000);
  };
}

async function submitControl(action, payload, confirmation) {
  if (controlSubmitPending) return;
  if (!latestControl || latestControl.available !== true) {
    showNotice('控制接口当前不可用，请先确认网关和蓝牙电台在线。');
    return;
  }
  if (!radioIsUsable()) {
    showNotice('电台快照已过期或未连接，已阻止发送控制请求。');
    return;
  }
  if (!window.confirm(confirmation)) return;

  controlSubmitPending = true;
  renderControl(latestControl);
  try {
    const response = await fetch('/api/v1/control/' + action, {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(Object.assign({}, payload, { confirm: true }))
    });
    if (response.status === 401) {
      window.location.replace('/login');
      return;
    }
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      showNotice(body.message || '控制请求未受理：' + (body.code || response.status));
    } else {
      showNotice('控制请求已受理，正在读取基线、写入并等待回读。');
    }
  } catch (error) {
    showNotice('控制请求没有送达 N1，请检查网络后重试。');
  } finally {
    controlSubmitPending = false;
    renderControl(latestControl);
    await loadControl();
  }
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
  const openControl = $('open-control');
  if (openControl) openControl.addEventListener('click', () => selectPanel('control', true));
  const initial = location.hash ? location.hash.substring(1) : 'overview';
  selectPanel(initial, false);

  const volumeRange = $('volume-range');
  const volumeValue = $('volume-value');
  if (volumeRange) {
    volumeRange.addEventListener('input', () => {
      volumeDirty = true;
      if (volumeValue) volumeValue.textContent = volumeRange.value;
    });
  }
  const volumeApply = $('volume-apply');
  if (volumeApply) {
    volumeApply.addEventListener('click', async () => {
      if (!volumeRange) return;
      const target = Number(volumeRange.value);
      const current = latestSnapshot ? latestSnapshot.volume : null;
      await submitControl('volume', { volume: target },
        '确认将音量从 ' + (current === null || current === undefined ? '当前值' : String(current))
        + ' 调整为 ' + target + '？系统会先读取基线，写入后回读；失败时最多回滚一次。');
    });
  }

  const scanTarget = $('scan-target');
  if (scanTarget) {
    scanTarget.addEventListener('change', () => {
      scanDirty = true;
      updateScanTargetText();
    });
  }
  const scanApply = $('scan-apply');
  if (scanApply) {
    scanApply.addEventListener('click', async () => {
      if (!scanTarget) return;
      const target = scanTarget.checked;
      const current = nested(nested(latestSnapshot || {}, 'radio'), 'status').scanning;
      await submitControl('scan', { scan: target },
        '确认将扫描从 ' + (typeof current === 'boolean' ? (current ? 'ON' : 'OFF') : '当前值')
        + ' 切换为 ' + (target ? 'ON' : 'OFF') + '？写入后会自动回读，失败时最多回滚一次。');
    });
  }

  document.querySelectorAll('[data-side]').forEach((button) => {
    button.addEventListener('click', () => {
      selectedSide = button.dataset.side;
      sideDirty = true;
      updateSideSelection();
    });
  });
  const sideApply = $('side-apply');
  if (sideApply) {
    sideApply.addEventListener('click', async () => {
      if (!selectedSide) {
        showNotice('请先选择目标 A 或 B。');
        return;
      }
      const current = nested(nested(latestSnapshot || {}, 'radio'), 'settings').dualMode;
      await submitControl('side', { side: selectedSide },
        '确认将当前信道侧从 ' + (current || '当前值') + ' 切换为 ' + selectedSide
        + '？写入后会自动回读，失败时最多回滚一次。');
    });
  }

  loadSnapshot();
  loadControl();
  connectEvents();
  window.setInterval(loadSnapshot, 5000);
  window.setInterval(loadControl, 2500);
  window.setInterval(() => {
    if (lastSnapshotAt && Date.now() - lastSnapshotAt > 15000) {
      setDot('connection-dot', 'stale');
      setDot('side-state-dot', 'stale');
      renderControl(latestControl);
    }
  }, 1000);
});
