const dict = {
  en: {
    dash: 'Dashboard', clients: 'Clients', policy: 'Policy', traffic: 'Traffic', sessions: 'Sessions', dns: 'DNS', registry: 'Registry', security: 'Security',
    subDash: 'VPN control summary', subClients: 'Create, rotate and revoke access', subPolicy: 'Effective policy and overrides', subTraffic: 'Traffic counters by client', subSessions: 'Live sessions', subDns: 'DNS logs', subRegistry: 'Signed registry', subSecurity: 'Security status',
    refresh: 'Refresh', logout: 'Logout', create: 'Create client', name: 'Name', note: 'Note', expires: 'Expires unix, optional', createBtn: 'Create voultkey', rotate: 'Rotate', revoke: 'Revoke', devices: 'Devices', load: 'Load', save: 'Save', cleanup: 'Cleanup', rebuild: 'Rebuild registry', add: 'Add log', primaryOk: 'Managed auth is primary.', search: 'Search', details: 'Details', copy: 'Copy', close: 'Close', mode: 'Mode', limit: 'Limit', apply: 'Apply', qrHint: 'Scan this QR on Android to import the client', group: 'Group', changeGroup: 'Change group', revokeDevice: 'Revoke device'
  },
  ru: {
    dash: 'Обзор', clients: 'Клиенты', policy: 'Политики', traffic: 'Трафик', sessions: 'Сессии', dns: 'DNS', registry: 'Реестр', security: 'Безопасность',
    subDash: 'Сводка управления VPN', subClients: 'Создание, ротация и отзыв доступа', subPolicy: 'Итоговая политика и переопределения', subTraffic: 'Трафик по клиентам', subSessions: 'Живые сессии', subDns: 'DNS логи', subRegistry: 'Подписанный реестр', subSecurity: 'Статус безопасности',
    refresh: 'Обновить', logout: 'Выйти', create: 'Создать клиента', name: 'Имя', note: 'Заметка', expires: 'Unix expiry, опционально', createBtn: 'Создать voultkey', rotate: 'Ротация', revoke: 'Отозвать', devices: 'Устройства', load: 'Загрузить', save: 'Сохранить', cleanup: 'Очистить', rebuild: 'Пересобрать реестр', add: 'Добавить лог', primaryOk: 'Managed auth основной.', search: 'Поиск', details: 'Детали', copy: 'Копировать', close: 'Закрыть', mode: 'Режим', limit: 'Лимит', apply: 'Применить', qrHint: 'Отсканируй QR на Android для импорта клиента', group: 'Группа', changeGroup: 'Сменить группу', revokeDevice: 'Отозвать устройство'
  }
};

let lang = localStorage.volterLang || 'en';
let state = {};
const $ = id => document.getElementById(id);
const t = k => dict[lang][k] || k;

const pages = [
  ['dashboard', 'dash', 'subDash'], ['clientsPage', 'clients', 'subClients'],
  ['policyPage', 'policy', 'subPolicy'], ['trafficPage', 'traffic', 'subTraffic'], ['sessionsPage', 'sessions', 'subSessions'],
  ['dnsPage', 'dns', 'subDns'], ['registryPage', 'registry', 'subRegistry'], ['securityPage', 'security', 'subSecurity']
];

function boot() {
  $('app').innerHTML = `
  <div class="layout"><aside class="sidebar"><div class="brand">Volter</div><div class="lang"><button class="btn" onclick="setLang('en')">EN</button><button class="btn" onclick="setLang('ru')">RU</button></div><nav class="nav" id="nav"></nav><button class="btn danger" style="width:100%;margin-top:16px" onclick="logout()" data-i="logout"></button></aside><main class="main"><div class="top"><div><h1 id="title"></h1><div class="sub" id="subtitle"></div></div><div class="row"><span class="muted" id="clock"></span><button class="btn" onclick="loadAll()" data-i="refresh"></button></div></div>${pagesHtml()}</main></div><div class="toast" id="toast"></div>`;
  renderNav(); applyText(); showPage('dashboard'); loadAll(); setInterval(loadAll, 5000);
}

function pagesHtml() {
  return `
  <section id="dashboard" class="page active"><div class="grid"><div class="card"><div class="label">Clients</div><div class="metric" id="mClients">0</div></div><div class="card"><div class="label">Traffic</div><div class="metric" id="mTraffic">0 B</div></div><div class="card"><div class="label">Sessions</div><div class="metric" id="mSessions">0</div></div><div class="card"><div class="label">DNS</div><div class="metric" id="mDns">0</div></div></div><div class="grid" style="margin-top:12px"><div class="card"><h2>Status</h2><pre id="status"></pre></div><div class="card"><h2>Security</h2><div id="securityBox"></div></div></div></section>
  <section id="clientsPage" class="page"><div class="split"><div class="card stack"><h2 data-i="create"></h2><input id="newName" data-ph="name"><select id="newGroup"></select><input id="newExp" data-ph="expires"><textarea id="newNote" data-ph="note"></textarea><button class="btn primary" onclick="createClient()" data-i="createBtn"></button><div id="qrBox" class="qr-box"><div class="muted" data-i="qrHint"></div><img id="qrImg" alt="voultkey QR"><div class="row"><button class="btn" onclick="copyVoultKey()" data-i="copy"></button><a class="btn" id="openVoultKey" href="#">Open</a></div><pre id="createdKey" class="qr-key copy"></pre></div></div><div class="card"><div class="toolbar"><h2 data-i="clients"></h2><input id="clientSearch" data-ph="search" oninput="renderClients(state.clients)"></div><div id="clients"></div><div id="clientDetail" class="detail"></div></div></div></section>
  <section id="policyPage" class="page"><div class="split"><div class="card stack"><h2>Effective policy</h2><input id="policyClient" placeholder="client id"><button class="btn primary" onclick="loadPolicy()" data-i="load"></button><pre id="effectivePolicy"></pre></div><div class="card stack"><h2>Client policy</h2><textarea id="policyJson">{"speedLimitKbps":2048,"relayAllowed":true,"meshAllowed":true}</textarea><button class="btn" onclick="saveClientPolicy()" data-i="save"></button></div></div></section>
  <section id="trafficPage" class="page"><div class="grid"><div class="card"><div class="label">RX</div><div class="metric" id="trafficRx">0 B</div></div><div class="card"><div class="label">TX</div><div class="metric" id="trafficTx">0 B</div></div><div class="card"><div class="label">Total</div><div class="metric" id="trafficTotal">0 B</div></div></div><div class="card" style="margin-top:12px"><h2 data-i="traffic"></h2><div id="traffic"></div></div></section>
  <section id="sessionsPage" class="page"><div class="card"><h2 data-i="sessions"></h2><div id="sessions"></div></div></section>
  <section id="dnsPage" class="page"><div class="split"><div class="card stack"><h2>DNS</h2><input id="dnsDomain" placeholder="domain"><input id="dnsClient" placeholder="client id"><select id="dnsAction"><option>allow</option><option>block</option></select><button class="btn" onclick="addDnsLog()" data-i="add"></button><button class="btn danger" onclick="cleanupDns()" data-i="cleanup"></button></div><div class="card"><h2>DNS logs</h2><div id="dns"></div></div></div></section>
  <section id="registryPage" class="page"><div class="card stack"><h2 data-i="registry"></h2><button class="btn primary" onclick="rebuildRegistry()" data-i="rebuild"></button><div id="registry"></div></div></section>
  <section id="securityPage" class="page"><div class="card"><h2 data-i="security"></h2><pre id="securityJson"></pre></div></section>`;
}

function renderNav() { $('nav').innerHTML = pages.map(p => `<button onclick="showPage('${p[0]}')" data-page="${p[0]}">${t(p[1])}</button>`).join(''); }
function applyText() { document.querySelectorAll('[data-i]').forEach(e => e.textContent = t(e.dataset.i)); document.querySelectorAll('[data-ph]').forEach(e => e.placeholder = t(e.dataset.ph)); renderNav(); }
function setLang(v) { lang = v; localStorage.volterLang = v; applyText(); let id = document.querySelector('.page.active').id; showPage(id); }
function showPage(id) { document.querySelectorAll('.page').forEach(x => x.classList.remove('active')); $(id).classList.add('active'); document.querySelectorAll('.nav button').forEach(x => x.classList.toggle('active', x.dataset.page === id)); let p = pages.find(x => x[0] === id); $('title').textContent = t(p[1]); $('subtitle').textContent = t(p[2]); }

async function api(url, opt) {
  try {
    let r = await fetch(url, opt);
    let text = await r.text();
    let data = text ? JSON.parse(text) : {};
    if (!r.ok) throw new Error(data.error || text);
    return data;
  } catch (e) {
    console.warn('api error', url, e);
    return null;
  }
}
function esc(s) { if (s == null) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }
function pretty(v) { return JSON.stringify(v, null, 2); }
function bytes(n) { if (!n) return '0 B'; let u = ['B','KB','MB','GB','TB']; let i = 0; while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; } return n.toFixed(i ? 1 : 0) + ' ' + u[i]; }
function toast(s) { $('toast').textContent = s; $('toast').style.display = 'block'; setTimeout(() => $('toast').style.display = 'none', 2400); }

async function loadAll() {
  $('clock').textContent = new Date().toLocaleTimeString();
  let st = await api('/api/v1/status');
  let cl = await api('/api/v1/clients');
  let gr = await api('/api/v1/groups');
  let tr = await api('/api/v1/traffic');
  let se = await api('/api/v1/sessions');
  let dn = await api('/api/v1/dns/logs?limit=80');
  let rg = await api('/api/v1/cluster/registry');
  state = { st, cl, gr, tr, se, dn, rg };
  if (st) {
    $('mClients').textContent = (cl?.clients || []).length;
    $('mTraffic').textContent = bytes((tr?.traffic?.rxBytes || 0) + (tr?.traffic?.txBytes || 0));
    $('mSessions').textContent = (se?.sessions || []).length;
    $('mDns').textContent = (dn?.logs || []).length;
    $('status').textContent = pretty(st);
    $('securityBox').innerHTML = `<p class="blue">${t('primaryOk')}</p><p class="muted">${st.control.listen}:${st.control.port}</p>`;
  }
  $('securityJson').textContent = pretty(st ? { control: st.control } : { error: 'unavailable' });
  if (gr?.groups) {
    let groups = gr.groups;
    $('newGroup').innerHTML = groups.map(g => `<option value="${esc(g.id)}">${esc(g.name)}</option>`).join('');
  }
  if (cl?.clients) renderClients(cl.clients);
  if (tr?.traffic) renderTraffic(tr.traffic);
  if (se?.sessions) renderSessions(se.sessions);
  if (dn?.logs) renderDns(dn.logs);
  if (rg?.registry) renderRegistry(rg.registry);
}

function renderClients(list) { let q = ($('clientSearch')?.value || '').toLowerCase(); let rows = list.filter(c => !q || (c.name + c.id + c.groupId).toLowerCase().includes(q)); $('clients').innerHTML = `<table class="table"><tr><th>${t('name')}</th><th>Group</th><th>Devices</th><th>Traffic</th><th></th></tr>${rows.map(c => `<tr><td><b>${esc(c.name)}</b><br><span class="muted">${esc(c.id)}</span></td><td><span class="badge">${esc(c.groupId)}</span></td><td>${esc(c.deviceMode)}:${c.deviceLimit}</td><td>${bytes(c.rxBytes + c.txBytes)}</td><td class="row"><button class="btn small" onclick="clientDetails('${esc(c.id)}')">${t('details')}</button><button class="btn small" onclick="rotate('${esc(c.id)}')">${t('rotate')}</button><button class="btn small" onclick="setPolicyClient('${esc(c.id)}')">${t('policy')}</button><button class="btn small danger" onclick="revoke('${esc(c.id)}','${esc(c.name)}')">${t('revoke')}</button></td></tr>`).join('')}</table>`; }

function renderTraffic(tdata) { let total = tdata.rxBytes + tdata.txBytes; $('trafficRx').textContent = bytes(tdata.rxBytes); $('trafficTx').textContent = bytes(tdata.txBytes); $('trafficTotal').textContent = bytes(total); let max = Math.max(1, ...tdata.clients.map(c => c.rxBytes + c.txBytes)); $('traffic').innerHTML = `<div class="traffic-list">${tdata.clients.map(c => { let n = c.rxBytes + c.txBytes; let w = Math.round(n * 100 / max); return `<div class="traffic-item"><div class="row" style="justify-content:space-between"><b>${esc(c.name)}</b><span class="muted">${bytes(n)}</span></div><div class="muted">${esc(c.clientId)}</div><div class="meter"><div style="width:${w}%"></div></div><div class="muted">RX ${bytes(c.rxBytes)} · TX ${bytes(c.txBytes)}</div></div>` }).join('') || '<div class="muted">No traffic yet</div>'}</div>`; }
function renderSessions(list) { $('sessions').innerHTML = `<table class="table"><tr><th>ID</th><th>Client</th><th>Device</th><th>Path</th><th>Remote</th><th>Started</th></tr>${list.map(s => `<tr><td class="copy">${s.id}</td><td>${s.clientId}</td><td>${s.deviceId}</td><td>${s.path}</td><td>${s.remoteAddr}</td><td>${new Date(s.startedAt * 1000).toLocaleString()}</td></tr>`).join('') || `<tr><td class="muted" colspan="6">No active sessions</td></tr>`}</table>`; }
function renderDns(list) { $('dns').innerHTML = `<table class="table"><tr><th>Domain</th><th>Action</th><th>Client</th><th>Time</th></tr>${list.map(x => `<tr><td>${esc(x.domain)}</td><td><span class="badge">${esc(x.action)}</span></td><td>${esc(x.clientId || '')}</td><td>${new Date(x.ts * 1000).toLocaleString()}</td></tr>`).join('') || `<tr><td class="muted" colspan="4">No DNS logs</td></tr>`}</table>`; }
function renderRegistry(r) { let nodes = r?.payload?.nodes || []; $('registry').innerHTML = `<div class="kv"><div>Version</div><div>${r?.version || 0}</div><div>Signature</div><div class="copy">${esc(r?.sig || '')}</div><div>Nodes</div><div>${nodes.length}</div></div><table class="table" style="margin-top:12px"><tr><th>ID</th><th>Endpoints</th><th>Updated</th></tr>${nodes.map(n => `<tr><td>${esc(n.id)}</td><td>${(n.endpoints || []).map(esc).join('<br>')}</td><td>${new Date((n.updatedAt || 0) * 1000).toLocaleString()}</td></tr>`).join('')}</table>`; }

function showVoultKey(uri) { $('qrBox').classList.add('active'); $('createdKey').textContent = uri; $('qrImg').src = '/api/v1/qr?data=' + encodeURIComponent(uri); $('openVoultKey').href = uri; }
async function copyVoultKey() { await navigator.clipboard.writeText($('createdKey').textContent); toast('copied'); }
function serverHostForKey() { return location.hostname || ''; }
async function createClient() { let body = { name: $('newName').value, groupId: $('newGroup').value, note: $('newNote').value, expiresAt: Number($('newExp').value || 0), serverHost: serverHostForKey() }; let r = await api('/api/v1/clients', { method: 'POST', body: JSON.stringify(body) }); if (r?.voultkey) { showVoultKey(r.voultkey); toast('ok'); loadAll(); } else { toast('create failed'); } }
async function rotate(id) { let r = await api(`/api/v1/clients/${id}/rotate`, { method: 'POST', body: JSON.stringify({ serverHost: serverHostForKey() }) }); if (r?.voultkey) { showVoultKey(r.voultkey); toast('ok'); } else { toast('rotate failed'); } }
async function revoke(id, name) { if (confirm(`Revoke ${name || id}?`)) { let r = await api(`/api/v1/clients/${id}/revoke`, { method: 'POST' }); if (r?.ok) { toast('ok'); loadAll(); } else { toast('revoke failed'); } } }
async function clientDetails(id) {
  let c = state.cl?.clients?.find(x => x.id === id);
  if (!c) { toast('client not found'); return; }
  let d = await api(`/api/v1/clients/${id}/devices`);
  let groups = state.gr?.groups || [];
  let groupOpts = groups.map(g => `<option value="${esc(g.id)}"${g.id === c.groupId ? ' selected' : ''}>${esc(g.name)}</option>`).join('');
  let devicesHtml = (d?.devices || []).map(dev => `<tr><td>${esc(dev.deviceId)}</td><td>${esc(dev.platform || '')}</td><td>${dev.enabled ? 'active' : 'revoked'}</td><td>${new Date((dev.lastSeen || 0) * 1000).toLocaleString()}</td><td>${dev.enabled ? `<button class="btn small danger" onclick="revokeDevice('${esc(c.id)}','${esc(dev.deviceId)}')">${t('revokeDevice')}</button>` : ''}</td></tr>`).join('');
  $('clientDetail').innerHTML = `<div class="row" style="justify-content:space-between"><h2>${esc(c.name)}</h2><button class="btn small" onclick="$('clientDetail').innerHTML=''">${t('close')}</button></div>
<div class="kv"><div>ID</div><div class="copy">${esc(c.id)}</div>
<div>${t('group')}</div><div><select id="clientGroupSelect" onchange="changeClientGroup('${esc(c.id)}')">${groupOpts}</select></div>
<div>${t('mode')}</div><div><select id="devMode"><option>single</option><option>multi</option><option>unlimited</option></select></div>
<div>${t('limit')}</div><div><input id="devLimit" value="${c.deviceLimit}" type="number"></div></div>
<button class="btn" style="margin-top:10px" onclick="saveDevicePolicy('${esc(c.id)}')">${t('apply')}</button>
<h2 style="margin-top:14px">${t('devices')}</h2>
<table class="table"><tr><th>Device ID</th><th>Platform</th><th>Status</th><th>Last seen</th><th></th></tr>${devicesHtml || '<tr><td class="muted" colspan="5">No devices</td></tr>'}</table>`;
  $('devMode').value = c.deviceMode;
}
async function changeClientGroup(id) {
  let groupId = $('clientGroupSelect').value;
  let r = await api(`/api/v1/clients/${id}/group`, { method: 'POST', body: JSON.stringify({ groupId }) });
  if (r?.ok) { toast(`group changed to ${groupId}`); loadAll(); } else { toast('group change failed'); }
}
async function revokeDevice(clientId, deviceId) {
  if (!confirm(`Revoke device ${deviceId}?`)) return;
  let r = await api(`/api/v1/clients/${clientId}/devices/${encodeURIComponent(deviceId)}/revoke`, { method: 'POST' });
  if (r?.ok) { toast('device revoked'); clientDetails(clientId); } else { toast('revoke device failed'); }
}
async function saveDevicePolicy(id) { let r = await api(`/api/v1/clients/${id}/devices/policy`, { method: 'POST', body: JSON.stringify({ mode: $('devMode').value, limit: Number($('devLimit').value || 1) }) }); if (r?.ok) { toast('ok'); loadAll(); } else { toast('save failed'); } }
function setPolicyClient(id) { $('policyClient').value = id; showPage('policyPage'); loadPolicy(); }
async function loadPolicy() {
  let id = $('policyClient').value;
  if (!id) { toast('enter client id'); return; }
  let r = await api(`/api/v1/clients/${id}/effective-policy`);
  $('effectivePolicy').textContent = r ? pretty(r.policy || r) : 'load failed';
}
async function saveClientPolicy() {
  let id = $('policyClient').value;
  if (!id) { toast('enter client id'); return; }
  let r = await api(`/api/v1/clients/${id}/policy`, { method: 'POST', body: $('policyJson').value });
  if (r?.ok) { toast('ok'); loadPolicy(); } else { toast('save failed'); }
}
async function addDnsLog() { let r = await api('/api/v1/dns/logs', { method: 'POST', body: JSON.stringify({ domain: $('dnsDomain').value, clientId: $('dnsClient').value, action: $('dnsAction').value }) }); if (r?.ok) { toast('ok'); loadAll(); } else { toast('add failed'); } }
async function cleanupDns() { let r = await api('/api/v1/dns/logs/cleanup', { method: 'POST', body: '{"retentionDays":30}' }); if (r) { toast('deleted ' + (r.deleted || 0)); loadAll(); } else { toast('cleanup failed'); } }
async function rebuildRegistry() { let r = await api('/api/v1/cluster/registry/rebuild', { method: 'POST' }); if (r?.ok) { toast('ok'); loadAll(); } else { toast('rebuild failed'); } }
async function logout() { await fetch('/api/v1/auth/logout', { method: 'POST' }); location = '/'; }

boot();
