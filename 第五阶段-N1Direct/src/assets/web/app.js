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
const catalogStateLabels = {
  EMPTY: '尚未读取',
  REFRESHING: '正在读取',
  COMPLETE: '读取完成',
  PARTIAL: '部分失败',
  OFFLINE: '电台离线'
};
const channelStatusLabels = {
  PENDING: '待读取',
  OK: '已读取',
  TIMEOUT: '读取超时',
  ERROR: '读取失败'
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
let activePanel = 'overview';
let latestCatalog = null;
let catalogInFlight = false;
let catalogPollTimer = 0;
let catalogRefreshPending = false;
let catalogEverLoaded = false;
let catalogError = null;
let latestAudioRooms = null;
let audioRoomsError = null;
let audioRoomsInFlight = false;
let audioClipsInFlight = false;
let selectedAudioRoomId = null;
let latestAudioClips = null;
let audioClipsError = null;
let audioLiveAbort = null;
let audioContext = null;
let audioGain = null;
let audioScheduleAt = 0;
let audioLiveRunId = 0;
let audioLiveActive = false;
let audioPcmRemainder = null;
let audioClipPlayingId = null;


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

function catalogFrequency(value) {
  if (value === null || value === undefined) return null;
  const number = Number(value);
  return Number.isFinite(number) ? number.toFixed(6) + ' MHz' : String(value);
}

function catalogStateText(value) {
  if (!value) return '尚未读取';
  return catalogStateLabels[String(value).toUpperCase()] || String(value);
}

function channelStatusText(channel) {
  const status = String((channel && channel.status) || 'PENDING').toUpperCase();
  if (status === 'OK' && channel && channel.rxMHz === null && channel.txMHz === null) {
    return '空信道';
  }
  return channelStatusLabels[status] || status;
}

function catalogTone(catalog) {
  if (catalogError) return 'offline';
  const state = String((catalog && catalog.state) || 'EMPTY').toUpperCase();
  if (state === 'COMPLETE') return 'online';
  if (state === 'REFRESHING' || state === 'PARTIAL') return 'stale';
  if (state === 'OFFLINE') return 'offline';
  return null;
}

function channelNumber(id) {
  const number = Number(id);
  if (!Number.isFinite(number)) return String(id || '—');
  return number < 10 ? '0' + number : String(number);
}

function catalogMetadataBadges(channel) {
  const settings = nested(nested(latestSnapshot || {}, 'radio'), 'settings');
  const status = nested(nested(latestSnapshot || {}, 'radio'), 'status');
  const badges = [];
  const id = String(channel.id);
  if (settings.channelA !== null && settings.channelA !== undefined
      && id === String(settings.channelA)) {
    badges.push({ label: 'A', tone: 'side-a' });
  }
  if (settings.channelB !== null && settings.channelB !== undefined
      && id === String(settings.channelB)) {
    badges.push({ label: 'B', tone: 'side-b' });
  }
  if (status.currentChannel !== null && status.currentChannel !== undefined
      && id === String(status.currentChannel)) {
    const side = String(settings.dualMode || '').toUpperCase();
    badges.push({
      label: side === 'A' || side === 'B' ? '当前 · ' + side : '当前',
      tone: 'current'
    });
  }
  return badges;
}

function catalogMatches(channel, query) {
  if (!query) return true;
  const frequencies = [channel.rxMHz, channel.txMHz]
    .filter((value) => value !== null && value !== undefined)
    .map((value) => Number(value).toFixed(6));
  const haystack = [
    String(channel.id),
    channelNumber(channel.id),
    channel.name || '',
    frequencies.join(' '),
    channel.status || '',
    channel.errorCode || ''
  ].join(' ').toLowerCase();
  return query.split(/\s+/).filter(Boolean).every((part) => haystack.includes(part));
}

function catalogBadge(label, tone) {
  const badge = document.createElement('span');
  badge.className = 'channel-badge ' + tone;
  badge.textContent = label;
  return badge;
}

function catalogStatusTone(channel) {
  const status = String((channel && channel.status) || 'PENDING').toUpperCase();
  if (channel && channel.stale) return 'stale';
  if (status === 'OK') return 'ok';
  if (status === 'TIMEOUT' || status === 'ERROR') return 'error';
  return 'pending';
}

function renderCatalog(catalog) {
  if (catalog) latestCatalog = catalog;
  const summary = $('channel-summary');
  const summaryDetail = $('channel-summary-detail');
  const list = $('channel-list');
  const refresh = $('channel-refresh');
  const search = $('channel-search');
  const hasCatalog = Boolean(latestCatalog && Array.isArray(latestCatalog.channels));
  const state = String((latestCatalog && latestCatalog.state) || 'EMPTY').toUpperCase();
  const total = latestCatalog && Number.isFinite(Number(latestCatalog.total))
    ? Number(latestCatalog.total) : 32;
  const completed = latestCatalog && Number.isFinite(Number(latestCatalog.completedCount))
    ? Number(latestCatalog.completedCount) : 0;
  const success = latestCatalog && Number.isFinite(Number(latestCatalog.successCount))
    ? Number(latestCatalog.successCount) : 0;
  const refreshing = state === 'REFRESHING';

  if (summary) {
    let label = catalogStateText(state);
    if (refreshing) label += ' ' + completed + '/' + total;
    summary.textContent = catalogError ? '目录不可用' : label;
    const tone = catalogTone(latestCatalog);
    summary.className = tone ? 'status-pill ' + tone : 'status-pill';
  }

  if (summaryDetail) {
    const parts = [];
    if (catalogError) {
      parts.push('接口错误：' + catalogError);
    } else if (!hasCatalog) {
      parts.push('首次打开会自动读取；刷新期间页面会自动更新进度。');
    } else {
      parts.push('成功 ' + success + ' / ' + total + ' · 已完成 ' + completed + ' / ' + total);
      if (latestCatalog.lastRefreshAtMs) {
        parts.push('最近完成 ' + formatTime(latestCatalog.lastRefreshAtMs));
      }
      if (latestCatalog.lastErrorCode) {
        parts.push('最后错误 ' + latestCatalog.lastErrorCode);
      }
    }
    summaryDetail.textContent = parts.join(' · ');
    summaryDetail.dataset.tone = catalogError || latestCatalog && latestCatalog.lastErrorCode ? 'warning' : 'neutral';
  }

  if (refresh) {
    refresh.disabled = catalogRefreshPending || catalogInFlight || refreshing;
    refresh.textContent = catalogRefreshPending
      ? '正在提交…' : refreshing ? '正在读取…' : '刷新目录';
  }

  if (!list) return;
  list.textContent = '';
  if (!hasCatalog) {
    const empty = document.createElement('div');
    empty.className = 'channel-empty';
    empty.textContent = catalogError
      ? '暂时无法读取信道目录，请检查 N1 与 N7500 的连接。'
      : '正在准备信道目录。首次读取会在 N1 蓝牙链路就绪后自动开始。';
    list.appendChild(empty);
    return;
  }

  const query = search ? search.value.trim().toLowerCase() : '';
  const channels = latestCatalog.channels
    .slice()
    .sort((left, right) => Number(left.id) - Number(right.id))
    .filter((channel) => catalogMatches(channel, query));
  if (!channels.length) {
    const empty = document.createElement('div');
    empty.className = 'channel-empty';
    empty.textContent = '没有与“' + (search ? search.value.trim() : '') + '”匹配的信道。';
    list.appendChild(empty);
    return;
  }

  channels.forEach((channel) => {
    const row = document.createElement('div');
    row.className = 'channel-row';
    row.setAttribute('role', 'listitem');
    row.dataset.status = catalogStatusTone(channel);

    const identity = document.createElement('div');
    identity.className = 'channel-identity';
    const number = document.createElement('span');
    number.className = 'channel-number';
    number.textContent = 'CH ' + channelNumber(channel.id);
    const name = document.createElement('strong');
    name.className = 'channel-name';
    name.textContent = channel.name || (String(channel.status || '').toUpperCase() === 'OK' ? '空信道' : '未命名信道');
    const badges = document.createElement('div');
    badges.className = 'channel-badges';
    catalogMetadataBadges(channel).forEach((badge) => {
      badges.appendChild(catalogBadge(badge.label, badge.tone));
    });
    identity.appendChild(number);
    identity.appendChild(name);
    identity.appendChild(badges);

    const rx = document.createElement('div');
    rx.className = 'channel-frequency';
    const rxLabel = document.createElement('span');
    rxLabel.textContent = 'RX';
    const rxValue = document.createElement('strong');
    rxValue.textContent = catalogFrequency(channel.rxMHz) || '—';
    rx.appendChild(rxLabel);
    rx.appendChild(rxValue);

    const tx = document.createElement('div');
    tx.className = 'channel-frequency';
    const txLabel = document.createElement('span');
    txLabel.textContent = 'TX';
    const txValue = document.createElement('strong');
    txValue.textContent = catalogFrequency(channel.txMHz) || '—';
    tx.appendChild(txLabel);
    tx.appendChild(txValue);

    const statusBox = document.createElement('div');
    statusBox.className = 'channel-status';
    const statusLine = document.createElement('div');
    statusLine.className = 'channel-status-line';
    const statusBadge = document.createElement('span');
    statusBadge.className = 'channel-status-badge ' + catalogStatusTone(channel);
    statusBadge.textContent = channelStatusText(channel);
    statusLine.appendChild(statusBadge);
    if (channel.stale) statusLine.appendChild(catalogBadge('旧值 · 过期', 'stale'));
    statusBox.appendChild(statusLine);

    const detail = document.createElement('small');
    const detailParts = [];
    if (channel.errorCode) detailParts.push(channel.errorCode);
    if (channel.readAtMs) detailParts.push('读过 ' + formatTime(channel.readAtMs));
    if (!detailParts.length && channel.attemptedAtMs) {
      detailParts.push('尝试 ' + formatTime(channel.attemptedAtMs));
    }
    detail.textContent = detailParts.join(' · ') || '尚未读取';
    statusBox.appendChild(detail);

    row.appendChild(identity);
    row.appendChild(rx);
    row.appendChild(tx);
    row.appendChild(statusBox);
    list.appendChild(row);
  });
}

function scheduleCatalogPoll() {
  if (catalogPollTimer || activePanel !== 'channels') return;
  if (!latestCatalog || String(latestCatalog.state || '').toUpperCase() !== 'REFRESHING') return;
  catalogPollTimer = window.setTimeout(async () => {
    catalogPollTimer = 0;
    if (activePanel !== 'channels') return;
    await loadChannels(false);
    scheduleCatalogPoll();
  }, 900);
}

async function loadChannels(showErrors) {
  if (catalogInFlight) return;
  catalogInFlight = true;
  renderCatalog(latestCatalog);
  try {
    const response = await fetch('/api/v1/channels', {
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
      catalogError = body.message || body.code || ('HTTP ' + response.status);
      renderCatalog(latestCatalog);
      if (showErrors !== false) {
        showNotice('信道目录暂时不可用：' + catalogError);
      }
      return;
    }
    latestCatalog = body;
    catalogEverLoaded = true;
    catalogError = null;
    renderCatalog(latestCatalog);
  } catch (error) {
    catalogError = '网络连接中断';
    renderCatalog(latestCatalog);
    if (showErrors !== false) showNotice('信道目录读取中断，正在等待网络恢复。');
  } finally {
    catalogInFlight = false;
    renderCatalog(latestCatalog);
    scheduleCatalogPoll();
  }
}

async function refreshChannels() {
  if (catalogRefreshPending || catalogInFlight) return;
  if (latestCatalog && String(latestCatalog.state || '').toUpperCase() === 'REFRESHING') return;
  catalogRefreshPending = true;
  renderCatalog(latestCatalog);
  try {
    const response = await fetch('/api/v1/channels/refresh', {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { Accept: 'application/json' }
    });
    if (response.status === 401) {
      window.location.replace('/login');
      return;
    }
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      showNotice(body.message || '目录刷新未受理：' + (body.code || response.status));
      catalogError = body.message || body.code || ('HTTP ' + response.status);
    } else {
      showNotice('信道目录刷新已受理，页面会自动更新进度。');
      catalogError = null;
    }
  } catch (error) {
    catalogError = '网络连接中断';
    showNotice('目录刷新请求没有送达 N1，请检查网络后重试。');
  } finally {
    catalogRefreshPending = false;
    await loadChannels(false);
    scheduleCatalogPoll();
  }
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
  renderCatalog(latestCatalog);
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
    ? '等待 N1Direct 返回控制状态。'
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
    showNotice('与 N1Direct 的连接中断，正在自动重试。');
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

async function loadTokenStatus() {
  const pill = $('token-status-pill');
  const configured = $('token-configured');
  const created = $('token-created');
  try {
    const response = await fetch('/api/v1/security/token', {
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { 'Accept': 'application/json' }
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || '令牌状态读取失败');
    if (pill) {
      pill.textContent = body.configured ? '令牌已配置' : '令牌未配置';
    }
    if (configured) configured.textContent = body.configured
      ? '已配置，网页不显示明文'
      : '未配置';
    if (created) created.textContent = body.createdAt ? formatTime(body.createdAt) : '—';
  } catch (error) {
    if (pill) pill.textContent = '状态不可用';
    if (configured) configured.textContent = error.message || '请重新登录后再试';
    if (created) created.textContent = '—';
  }
}

async function submitCustomToken(event) {
  event.preventDefault();
  const input = $('token-input');
  const confirm = $('token-confirm');
  const button = $('token-apply');
  if (!input || !confirm || !button) return;
  const value = input.value.trim();
  if (!/^[A-Za-z0-9]{10,64}$/.test(value)) {
    showNotice('新令牌须为 10–64 位 ASCII 字母或数字，不能含空格或符号。');
    input.focus();
    return;
  }
  if (!confirm.checked) {
    showNotice('请先勾选“替换令牌并让所有浏览器退出登录”。');
    confirm.focus();
    return;
  }
  if (!window.confirm('确认设置新令牌？当前及所有浏览器会话将立即失效，随后需要重新登录。')) return;
  button.disabled = true;
  try {
    const response = await fetch('/api/v1/security/token', {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify({ token: value, confirm: true })
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.message || '令牌设置失败');
    }
    input.value = '';
    window.location.replace('/login?changed=1');
  } catch (error) {
    showNotice(error.message || '令牌设置失败，请重试。');
    button.disabled = false;
  }
}

async function clearLoginLimit() {
  const button = $('rate-limit-clear');
  if (button) button.disabled = true;
  try {
    const response = await fetch('/api/v1/security/rate-limit/clear', {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { 'Accept': 'application/json' }
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || '清除失败');
    showNotice('登录失败限流已清除。');
  } catch (error) {
    showNotice(error.message || '清除登录限流失败。');
  } finally {
    if (button) button.disabled = false;
  }
}

function formatDuration(value) {
  const totalSeconds = Math.max(0, Math.floor(Number(value || 0) / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return String(minutes).padStart(2, '0') + ':' + String(seconds).padStart(2, '0');
}

function formatBytes(value) {
  const bytes = Number(value || 0);
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function audioRoomById(id) {
  const rooms = latestAudioRooms && Array.isArray(latestAudioRooms.rooms)
    ? latestAudioRooms.rooms : [];
  return rooms.find((room) => String(room.id) === String(id)) || null;
}

function audioStateLabel(value) {
  const state = String(value || '').toUpperCase();
  if (state === 'CONNECTED') return '正在接收';
  if (state === 'CONNECTING') return '正在连接';
  if (state === 'RECONNECTING') return '正在重连';
  if (state === 'STOPPED') return '音频已停止';
  if (state === 'DISCONNECTED') return '音频未连接';
  return state || '音频状态未知';
}

function audioStateTone(value) {
  const state = String(value || '').toUpperCase();
  if (state === 'CONNECTED') return 'online';
  if (state === 'CONNECTING' || state === 'RECONNECTING') return 'stale';
  return 'offline';
}

function audioClipUrl(roomId, clipId) {
  return '/api/v1/audio/rooms/' + encodeURIComponent(roomId)
    + '/clips/' + encodeURIComponent(clipId) + '/wav';
}

function renderAudioStatus() {
  const pill = $('audio-state-pill');
  const stateText = $('audio-state-text');
  const techText = $('audio-tech-text');
  const lastPcm = $('audio-last-pcm');
  if (!latestAudioRooms) {
    if (pill) {
      pill.textContent = audioRoomsError ? '读取失败' : '读取状态';
      pill.className = 'status-pill ' + (audioRoomsError ? 'offline' : '');
    }
    if (stateText) stateText.textContent = audioRoomsError || '正在读取音频状态';
    return;
  }
  const state = String(latestAudioRooms.audioState || '').toUpperCase();
  if (pill) {
    pill.textContent = audioStateLabel(state);
    pill.className = 'status-pill ' + audioStateTone(state);
  }
  if (stateText) {
    stateText.textContent = audioStateLabel(state)
      + (latestAudioRooms.lastError ? ' · ' + latestAudioRooms.lastError : '');
  }
  if (techText) {
    techText.textContent = String(latestAudioRooms.sampleRate || 32000) + ' Hz · '
      + String(latestAudioRooms.bitsPerSample || 16) + '-bit · '
      + (Number(latestAudioRooms.channels || 1) === 1 ? '单声道' : String(latestAudioRooms.channels) + ' 声道');
  }
  if (lastPcm) {
    lastPcm.textContent = latestAudioRooms.lastPcmAt
      ? '最近 PCM：' + formatTime(latestAudioRooms.lastPcmAt)
      : '尚未收到 PCM';
  }
}

function renderAudioSelection() {
  const room = audioRoomById(selectedAudioRoomId);
  const title = $('audio-room-title');
  const historyTitle = $('audio-history-title');
  const start = $('audio-live-start');
  if (title) title.textContent = room ? room.name : '尚未选择';
  if (historyTitle) historyTitle.textContent = room ? room.name + ' · 历史音频' : '历史音频';
  if (start) start.disabled = !room || audioLiveActive;
}

function renderAudioRooms() {
  renderAudioStatus();
  const list = $('audio-room-list');
  if (!list) return;
  list.textContent = '';
  const rooms = latestAudioRooms && Array.isArray(latestAudioRooms.rooms)
    ? latestAudioRooms.rooms : [];
  if (!rooms.length) {
    const empty = document.createElement('div');
    empty.className = 'channel-empty';
    empty.textContent = audioRoomsError || '还没有本地音频房间。请先创建一个房间。';
    list.appendChild(empty);
    selectedAudioRoomId = null;
    renderAudioSelection();
    return;
  }
  if (!audioRoomById(selectedAudioRoomId)) {
    const preferred = rooms.find((room) => room.default === true) || rooms[0];
    selectedAudioRoomId = String(preferred.id);
  }
  rooms.forEach((room) => {
    const card = document.createElement('button');
    card.type = 'button';
    card.className = 'audio-room-card';
    if (String(room.id) === selectedAudioRoomId) card.classList.add('selected');
    card.setAttribute('role', 'listitem');
    card.setAttribute('aria-pressed', String(String(room.id) === selectedAudioRoomId));
    card.addEventListener('click', () => selectAudioRoom(String(room.id)));

    const identity = document.createElement('span');
    identity.className = 'audio-room-identity';
    const name = document.createElement('strong');
    name.textContent = room.name || room.id;
    identity.appendChild(name);
    const meta = document.createElement('span');
    meta.textContent = (room.default ? 'N1 持续录音' : (room.recording ? '正在记录' : '进入直播时记录'))
      + ' · ' + String(room.clipCount || 0) + ' 段';
    identity.appendChild(meta);

    const badge = document.createElement('span');
    badge.className = 'audio-room-badge';
    badge.textContent = room.default ? '默认' : (room.liveClients ? '直播 ' + room.liveClients : '本地');
    card.appendChild(identity);
    card.appendChild(badge);
    list.appendChild(card);
  });
  renderAudioSelection();
}

function selectAudioRoom(roomId) {
  if (!audioRoomById(roomId)) return;
  if (selectedAudioRoomId === roomId) return;
  if (audioLiveActive) stopAudioLive('已切换房间，请重新点击开始直播。');
  selectedAudioRoomId = roomId;
  renderAudioRooms();
  loadAudioClips(true);
}

async function loadAudioRooms(showErrors) {
  if (audioRoomsInFlight) return;
  audioRoomsInFlight = true;
  renderAudioStatus();
  try {
    const response = await fetch('/api/v1/audio/rooms', {
      cache: 'no-store',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' }
    });
    if (response.status === 401) {
      window.location.replace('/login');
      return;
    }
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || body.code || ('HTTP ' + response.status));
    const previousSelection = selectedAudioRoomId;
    latestAudioRooms = body;
    audioRoomsError = null;
    renderAudioRooms();
    if (selectedAudioRoomId !== previousSelection) loadAudioClips(false);
  } catch (error) {
    audioRoomsError = error.message || '网络连接中断';
    renderAudioRooms();
    if (showErrors !== false) showNotice('音频房间暂时不可用：' + audioRoomsError);
  } finally {
    audioRoomsInFlight = false;
  }
}

function renderAudioClips() {
  const list = $('audio-history-list');
  const player = $('audio-clip-player');
  if (!list) return;
  list.textContent = '';
  const currentRoom = latestAudioClips && String(latestAudioClips.roomId) === String(selectedAudioRoomId)
    ? latestAudioClips : null;
  const clips = currentRoom && Array.isArray(currentRoom.clips) ? currentRoom.clips : [];
  if (!audioRoomById(selectedAudioRoomId)) {
    const empty = document.createElement('div');
    empty.className = 'channel-empty';
    empty.textContent = '选择房间后读取历史音频。';
    list.appendChild(empty);
    return;
  }
  if (!clips.length) {
    const empty = document.createElement('div');
    empty.className = 'channel-empty';
    empty.textContent = audioClipsError || '这个房间还没有历史录音。新片段约每 60 秒生成。';
    list.appendChild(empty);
    if (player && audioClipPlayingId) {
      player.pause();
      player.removeAttribute('src');
      audioClipPlayingId = null;
    }
    return;
  }
  clips.forEach((clip) => {
    const row = document.createElement('div');
    row.className = 'audio-clip-row';
    row.setAttribute('role', 'listitem');
    if (String(clip.id) === audioClipPlayingId) row.classList.add('playing');

    const time = document.createElement('div');
    time.className = 'audio-clip-time';
    const strong = document.createElement('strong');
    strong.textContent = formatTime(clip.createdAt) || '—';
    const span = document.createElement('span');
    span.textContent = formatDuration(clip.durationMs) + ' · ' + formatBytes(clip.sizeBytes);
    time.appendChild(strong);
    time.appendChild(span);

    const play = document.createElement('button');
    play.type = 'button';
    play.className = 'wx-secondary-button audio-clip-play';
    play.textContent = String(clip.id) === audioClipPlayingId ? '正在播放' : '播放';
    play.addEventListener('click', () => playAudioClip(clip));

    const download = document.createElement('a');
    download.className = 'wx-secondary-button audio-clip-download';
    download.href = audioClipUrl(selectedAudioRoomId, clip.id);
    download.setAttribute('download', String(clip.id) + '.wav');
    download.textContent = '下载';

    const actions = document.createElement('div');
    actions.className = 'audio-clip-actions';
    actions.appendChild(play);
    actions.appendChild(download);
    row.appendChild(time);
    row.appendChild(actions);
    list.appendChild(row);
  });
}

async function loadAudioClips(showErrors) {
  if (audioClipsInFlight || !selectedAudioRoomId) return;
  const roomId = selectedAudioRoomId;
  audioClipsInFlight = true;
  try {
    const response = await fetch('/api/v1/audio/rooms/' + encodeURIComponent(roomId) + '/clips', {
      cache: 'no-store',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' }
    });
    if (response.status === 401) {
      window.location.replace('/login');
      return;
    }
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || body.code || ('HTTP ' + response.status));
    if (selectedAudioRoomId !== roomId) return;
    latestAudioClips = body;
    audioClipsError = null;
    renderAudioClips();
  } catch (error) {
    if (selectedAudioRoomId !== roomId) return;
    audioClipsError = error.message || '网络连接中断';
    renderAudioClips();
    if (showErrors === true) showNotice('历史音频读取失败：' + audioClipsError);
  } finally {
    audioClipsInFlight = false;
  }
}

function playAudioClip(clip) {
  const player = $('audio-clip-player');
  if (!player || !selectedAudioRoomId) return;
  if (audioClipPlayingId === String(clip.id) && !player.paused) {
    player.pause();
    return;
  }
  audioClipPlayingId = String(clip.id);
  player.src = audioClipUrl(selectedAudioRoomId, clip.id);
  player.load();
  renderAudioClips();
  const promise = player.play();
  if (promise && typeof promise.catch === 'function') {
    promise.catch(() => showNotice('浏览器未允许播放，请再次点击播放按钮。'));
  }
}

function setAudioLiveUi(message, tone) {
  const start = $('audio-live-start');
  const stop = $('audio-live-stop');
  const dot = $('audio-live-dot');
  const help = $('audio-live-help');
  if (start) start.disabled = audioLiveActive || !audioRoomById(selectedAudioRoomId);
  if (stop) stop.disabled = !audioLiveActive;
  if (dot) dot.className = 'dot ' + (tone || (audioLiveActive ? 'online' : 'unknown'));
  if (help) {
    const room = audioRoomById(selectedAudioRoomId);
    help.textContent = message || (audioLiveActive
      ? '直播已连接。手机离开本页也可以继续收听，返回后可随时停止。'
      : (room && room.default
        ? '这是默认持续录音房间。点击开始后收听实时音频，历史片段约每 60 秒生成。'
        : '自定义房间会在开始收听后记录音频，历史片段约每 60 秒生成。'));
  }
}

function isAbortError(error) {
  return error && String(error.name || '').toLowerCase() === 'aborterror';
}

function ensureAudioContext() {
  const Context = window.AudioContext || window.webkitAudioContext;
  if (!Context) throw new Error('当前浏览器不支持网页音频播放');
  if (!audioContext || audioContext.state === 'closed') {
    try {
      audioContext = new Context({ latencyHint: 'interactive', sampleRate: 32000 });
    } catch (error) {
      audioContext = new Context();
    }
    audioGain = audioContext.createGain();
    audioGain.gain.value = Number($('audio-live-volume') ? $('audio-live-volume').value : 80) / 100;
    audioGain.connect(audioContext.destination);
  }
  return audioContext;
}

function scheduleAudioChunk(bytes) {
  if (!bytes || !bytes.length || !audioContext || !audioGain) return;
  let merged;
  if (audioPcmRemainder && audioPcmRemainder.length) {
    merged = new Uint8Array(audioPcmRemainder.length + bytes.length);
    merged.set(audioPcmRemainder, 0);
    merged.set(bytes, audioPcmRemainder.length);
  } else {
    merged = bytes;
  }
  const sampleCount = Math.floor(merged.length / 2);
  if (!sampleCount) {
    audioPcmRemainder = merged;
    return;
  }
  const usedBytes = sampleCount * 2;
  audioPcmRemainder = usedBytes < merged.length ? merged.slice(usedBytes) : null;
  const floats = new Float32Array(sampleCount);
  for (let i = 0; i < sampleCount; i += 1) {
    const offset = i * 2;
    const sample = (merged[offset] | (merged[offset + 1] << 8)) << 16 >> 16;
    floats[i] = sample / 32768;
  }
  const buffer = audioContext.createBuffer(1, sampleCount, 32000);
  if (typeof buffer.copyToChannel === 'function') {
    buffer.copyToChannel(floats, 0);
  } else {
    buffer.getChannelData(0).set(floats);
  }
  const source = audioContext.createBufferSource();
  source.buffer = buffer;
  source.connect(audioGain);
  const now = audioContext.currentTime;
  let startAt = Math.max(audioScheduleAt, now + 0.08);
  if (startAt > now + 0.8) {
    startAt = now + 0.08;
    showNotice('手机音频播放落后，已丢弃少量积压以保证实时收听。');
  }
  source.start(startAt);
  audioScheduleAt = startAt + buffer.duration;
}

async function startAudioLive() {
  if (audioLiveActive) return;
  const room = audioRoomById(selectedAudioRoomId);
  if (!room) {
    showNotice('请先选择一个音频房间。');
    return;
  }
  const start = $('audio-live-start');
  if (start) start.disabled = true;
  try {
    const context = ensureAudioContext();
    if (context.state === 'suspended' && typeof context.resume === 'function') await context.resume();
    if (typeof fetch !== 'function' || !window.AbortController) {
      throw new Error('当前浏览器不支持直播流播放');
    }
    const runId = ++audioLiveRunId;
    const controller = new AbortController();
    audioLiveAbort = controller;
    audioPcmRemainder = null;
    audioScheduleAt = 0;
    const response = await fetch('/api/v1/audio/rooms/' + encodeURIComponent(room.id) + '/live', {
      cache: 'no-store',
      credentials: 'same-origin',
      headers: { Accept: 'application/octet-stream' },
      signal: controller.signal
    });
    if (response.status === 401) {
      window.location.replace('/login');
      return;
    }
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.message || body.code || ('HTTP ' + response.status));
    }
    if (!response.body || typeof response.body.getReader !== 'function') {
      throw new Error('当前浏览器不支持流式 PCM 解码');
    }
    audioLiveActive = true;
    setAudioLiveUi('直播已连接，等待电台音频…', 'online');
    const reader = response.body.getReader();
    try {
      while (audioLiveRunId === runId) {
        const result = await reader.read();
        if (result.done) break;
        if (result.value) scheduleAudioChunk(new Uint8Array(result.value));
        if (!audioLiveActive) break;
      }
    } finally {
      try { reader.releaseLock(); } catch (error) {}
    }
    if (audioLiveRunId === runId) setAudioLiveUi('直播已结束，可以重新点击开始。', 'stale');
  } catch (error) {
    if (!isAbortError(error) && audioLiveRunId > 0) {
      setAudioLiveUi('直播不可用：' + (error.message || '连接中断'), 'offline');
      showNotice('直播音频没有建立：' + (error.message || '请检查网络后重试。'));
    }
  } finally {
    if (audioLiveActive) audioLiveActive = false;
    audioLiveAbort = null;
    if (start) start.disabled = !audioRoomById(selectedAudioRoomId);
    const stop = $('audio-live-stop');
    if (stop) stop.disabled = true;
  }
}

function stopAudioLive(message) {
  audioLiveRunId += 1;
  audioLiveActive = false;
  audioPcmRemainder = null;
  if (audioLiveAbort) {
    try { audioLiveAbort.abort(); } catch (error) {}
    audioLiveAbort = null;
  }
  if (audioContext && audioContext.state !== 'closed') {
    try { audioContext.close(); } catch (error) {}
  }
  audioContext = null;
  audioGain = null;
  setAudioLiveUi(message || '直播已停止。', 'stale');
  loadAudioRooms(false);
}

async function createAudioRoom(event) {
  event.preventDefault();
  const input = $('audio-room-name');
  const button = $('audio-room-create');
  if (!input || !button) return;
  const name = input.value.trim();
  if (!name) {
    showNotice('请输入房间名称。');
    input.focus();
    return;
  }
  const suffix = Math.random().toString(36).slice(2, 8) || 'local';
  const id = 'room-' + Date.now().toString(36) + '-' + suffix;
  button.disabled = true;
  try {
    const response = await fetch('/api/v1/audio/rooms', {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: id, name: name })
    });
    if (response.status === 401) {
      window.location.replace('/login');
      return;
    }
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || body.code || ('HTTP ' + response.status));
    input.value = '';
    showNotice('本地音频房间已创建。');
    selectedAudioRoomId = String(body.id || id);
    await loadAudioRooms(true);
    loadAudioClips(true);
  } catch (error) {
    showNotice('创建音频房间失败：' + (error.message || '请稍后重试。'));
  } finally {
    button.disabled = false;
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
  activePanel = name;
  document.querySelectorAll('.nav-item').forEach((button) => {
    const active = button.dataset.panel === name;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', active ? 'true' : 'false');
  });
  document.querySelectorAll('.panel').forEach((panel) => {
    panel.hidden = panel.dataset.panel !== name;
  });
  if (name === 'security') loadTokenStatus();
  if (name === 'audio') loadAudioRooms(true);
  if (name === 'channels') {
    loadChannels(true);
    scheduleCatalogPoll();
  } else if (catalogPollTimer) {
    window.clearTimeout(catalogPollTimer);
    catalogPollTimer = 0;
  }
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
  const tokenForm = $('token-form');
  if (tokenForm) tokenForm.addEventListener('submit', submitCustomToken);
  const rateLimitClear = $('rate-limit-clear');
  if (rateLimitClear) rateLimitClear.addEventListener('click', clearLoginLimit);
  const channelSearch = $('channel-search');
  if (channelSearch) {
    channelSearch.addEventListener('input', () => renderCatalog(latestCatalog));
  }
  const channelRefresh = $('channel-refresh');
  if (channelRefresh) channelRefresh.addEventListener('click', refreshChannels);
  const audioRefresh = $('audio-refresh');
  if (audioRefresh) audioRefresh.addEventListener('click', () => loadAudioRooms(true));
  const audioRoomForm = $('audio-room-form');
  if (audioRoomForm) audioRoomForm.addEventListener('submit', createAudioRoom);
  const audioLiveStart = $('audio-live-start');
  if (audioLiveStart) audioLiveStart.addEventListener('click', startAudioLive);
  const audioLiveStop = $('audio-live-stop');
  if (audioLiveStop) audioLiveStop.addEventListener('click', () => stopAudioLive());
  const audioClipsRefresh = $('audio-clips-refresh');
  if (audioClipsRefresh) audioClipsRefresh.addEventListener('click', () => loadAudioClips(true));
  const audioLiveVolume = $('audio-live-volume');
  const audioLiveVolumeValue = $('audio-live-volume-value');
  if (audioLiveVolume) {
    audioLiveVolume.addEventListener('input', () => {
      const level = Number(audioLiveVolume.value) / 100;
      if (audioLiveVolumeValue) audioLiveVolumeValue.textContent = Math.round(level * 100) + '%';
      if (audioGain) audioGain.gain.value = level;
    });
  }
  const audioClipPlayer = $('audio-clip-player');
  if (audioClipPlayer) {
    audioClipPlayer.addEventListener('play', () => renderAudioClips());
    audioClipPlayer.addEventListener('pause', () => renderAudioClips());
    audioClipPlayer.addEventListener('ended', () => {
      audioClipPlayingId = null;
      renderAudioClips();
    });
    audioClipPlayer.addEventListener('error', () => {
      audioClipPlayingId = null;
      renderAudioClips();
      showNotice('历史音频无法播放或下载，请刷新后重试。');
    });
  }
  window.addEventListener('beforeunload', () => {
    if (audioLiveAbort) audioLiveAbort.abort();
    if (audioContext && audioContext.state !== 'closed') audioContext.close();
  });

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
  window.setInterval(() => {
    if (activePanel === 'audio') {
      loadAudioRooms(false);
      loadAudioClips(false);
    }
  }, 10000);

  window.setInterval(loadControl, 2500);
  window.setInterval(() => {
    if (lastSnapshotAt && Date.now() - lastSnapshotAt > 15000) {
      setDot('connection-dot', 'stale');
      setDot('side-state-dot', 'stale');
      renderControl(latestControl);
    }
  }, 1000);
});
