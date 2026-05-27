const dict = {
  en: {
    dash: 'Dashboard', clients: 'Clients', policy: 'Policy', traffic: 'Traffic', sessions: 'Sessions', dns: 'DNS', registry: 'Registry', security: 'Security',
    subDash: 'VPN control summary', subClients: 'Create, rotate and revoke access', subPolicy: 'Effective policy and overrides', subTraffic: 'Traffic counters by client', subSessions: 'Live sessions', subDns: 'DNS logs', subRegistry: 'Signed registry', subSecurity: 'Security status',
    refresh: 'Refresh', logout: 'Logout', create: 'Create client', name: 'Name', note: 'Note', expires: 'Expires unix, optional', createBtn: 'Create voultkey', rotate: 'Rotate', revoke: 'Revoke', devices: 'Devices', load: 'Load', save: 'Save', cleanup: 'Cleanup', rebuild: 'Rebuild registry', add: 'Add log', primaryOk: 'Managed auth is primary.', search: 'Search', details: 'Details', copy: 'Copy', close: 'Close', mode: 'Mode', limit: 'Limit', apply: 'Apply', qrHint: 'Scan this QR on Android to import the client'
  },
  ru: {
    dash: 'Обзор', clients: 'Клиенты', policy: 'Политики', traffic: 'Трафик', sessions: 'Сессии', dns: 'DNS', registry: 'Реестр', security: 'Безопасность',
    subDash: 'Сводка управления VPN', subClients: 'Создание, ротация и отзыв доступа', subPolicy: 'Итоговая политика и переопределения', subTraffic: 'Трафик по клиентам', subSessions: 'Живые сессии', subDns: 'DNS логи', subRegistry: 'Подписанный реестр', subSecurity: 'Статус безопасности',
    refresh: 'Обновить', logout: 'Выйти', create: 'Создать клиента', name: 'Имя', note: 'Заметка', expires: 'Unix expiry, опционально', createBtn: 'Создать voultkey', rotate: 'Ротация', revoke: 'Отозвать', devices: 'Устройства', load: 'Загрузить', save: 'Сохранить', cleanup: 'Очистить', rebuild: 'Пересобрать реестр', add: 'Добавить лог', primaryOk: 'Managed auth основной.', search: 'Поиск', details: 'Детали', copy: 'Копировать', close: 'Закрыть', mode: 'Режим', limit: 'Лимит', apply: 'Применить', qrHint: 'Отсканируй QR на Android для импорта клиента'
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
  <section id="clientsPage" class="page"><div class="split"><div class="card stack"><h2 data-i="create"></h2><input id="newName" data-ph="name"><select id="newGroup"></select><input id="newExp" data-ph="expires"><textarea id="newNote" data-ph="note"></textarea><button class="btn primary" onclick="createClient()" data-i="createBtn"></button><div id="qrBox" class="qr-box"><div class="muted" data-i="qrHint"></div><img id="qrImg" alt="voultkey QR"><div class="row"><button class="btn" onclick="copyVoultKey()" data-i="copy"></button><a class="btn" id="openVoultKey" href="#">Open</a></div><pre id="createdKey" class="qr-key copy"></pre></div></div><div class="card"><div class="toolbar"><h2 data-i="clients"></h2><input id="clientSearch" data-ph="search" oninput="renderClients(state.cl.clients)"></div><div id="clients"></div><div id="clientDetail" class="detail"></div></div></div></section>
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

async function api(url, opt) { let r = await fetch(url, opt); let text = await r.text(); let data = text ? JSON.parse(text) : {}; if (!r.ok) throw Error(data.error || text); return data; }
function pretty(v) { return JSON.stringify(v, null, 2); }
function bytes(n) { if (!n) return '0 B'; let u = ['B','KB','MB','GB','TB']; let i = 0; while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; } return n.toFixed(i ? 1 : 0) + ' ' + u[i]; }
function toast(s) { $('toast').textContent = s; $('toast').style.display = 'block'; setTimeout(() => $('toast').style.display = 'none', 2400); }

async function loadAll() {
  $('clock').textContent = new Date().toLocaleTimeString();
  let [st, cl, gr, tr, se, dn, rg] = await Promise.all([api('/api/v1/status'), api('/api/v1/clients'), api('/api/v1/groups'), api('/api/v1/traffic'), api('/api/v1/sessions'), api('/api/v1/dns/logs?limit=80'), api('/api/v1/cluster/registry')]);
  state = { st, cl, gr, tr, se, dn, rg };
  $('status').textContent = pretty(st); $('mClients').textContent = cl.clients.length; $('mTraffic').textContent = bytes(tr.traffic.rxBytes + tr.traffic.txBytes); $('mSessions').textContent = se.sessions.length; $('mDns').textContent = dn.logs.length;
  $('securityBox').innerHTML = `<p class="blue">${t('primaryOk')}</p><p class="muted">${st.control.listen}:${st.control.port}</p>`;
  $('securityJson').textContent = pretty({ control: st.control });
  let groups = normalizeGroups(gr.groups);
  $('newGroup').innerHTML = groups.map(g => `<option value="${g.id}">${g.name}</option>`).join('');
  renderClients(cl.clients); renderTraffic(tr.traffic); renderSessions(se.sessions); renderDns(dn.logs); renderRegistry(rg.registry);
}

function renderClients(list) { let q = ($('clientSearch')?.value || '').toLowerCase(); let rows = list.filter(c => !q || (c.name + c.id + c.groupId).toLowerCase().includes(q)); $('clients').innerHTML = `<table class="table"><tr><th>${t('name')}</th><th>Group</th><th>Devices</th><th>Traffic</th><th></th></tr>${rows.map(c => `<tr><td><b>${c.name}</b><br><span class="muted">${c.id}</span></td><td><span class="badge">${c.groupId}</span></td><td>${c.deviceMode}:${c.deviceLimit}</td><td>${bytes(c.rxBytes + c.txBytes)}</td><td class="row"><button class="btn small" onclick="clientDetails('${c.id}')">${t('details')}</button><button class="btn small" onclick="rotate('${c.id}')">${t('rotate')}</button><button class="btn small" onclick="setPolicyClient('${c.id}')">${t('policy')}</button><button class="btn small danger" onclick="revoke('${c.id}')">${t('revoke')}</button></td></tr>`).join('')}</table>`; }
function normalizeGroups(list) { let allowed = ['user', 'volunteer', 'admin']; let out = list.filter(g => allowed.includes(g.id)); for (let id of allowed) if (!out.find(g => g.id === id)) out.push({ id, name: id }); return out.sort((a,b) => allowed.indexOf(a.id) - allowed.indexOf(b.id)); }
function renderTraffic(tdata) { let total = tdata.rxBytes + tdata.txBytes; $('trafficRx').textContent = bytes(tdata.rxBytes); $('trafficTx').textContent = bytes(tdata.txBytes); $('trafficTotal').textContent = bytes(total); let max = Math.max(1, ...tdata.clients.map(c => c.rxBytes + c.txBytes)); $('traffic').innerHTML = `<div class="traffic-list">${tdata.clients.map(c => { let n = c.rxBytes + c.txBytes; let w = Math.round(n * 100 / max); return `<div class="traffic-item"><div class="row" style="justify-content:space-between"><b>${c.name}</b><span class="muted">${bytes(n)}</span></div><div class="muted">${c.clientId}</div><div class="meter"><div style="width:${w}%"></div></div><div class="muted">RX ${bytes(c.rxBytes)} · TX ${bytes(c.txBytes)}</div></div>` }).join('') || '<div class="muted">No traffic yet</div>'}</div>`; }
function renderSessions(list) { $('sessions').innerHTML = `<table class="table"><tr><th>ID</th><th>Client</th><th>Device</th><th>Path</th><th>Remote</th><th>Started</th></tr>${list.map(s => `<tr><td class="copy">${s.id}</td><td>${s.clientId}</td><td>${s.deviceId}</td><td>${s.path}</td><td>${s.remoteAddr}</td><td>${new Date(s.startedAt * 1000).toLocaleString()}</td></tr>`).join('') || `<tr><td class="muted" colspan="6">No active sessions</td></tr>`}</table>`; }
function renderDns(list) { $('dns').innerHTML = `<table class="table"><tr><th>Domain</th><th>Action</th><th>Client</th><th>Time</th></tr>${list.map(x => `<tr><td>${x.domain}</td><td><span class="badge">${x.action}</span></td><td>${x.clientId || ''}</td><td>${new Date(x.ts * 1000).toLocaleString()}</td></tr>`).join('') || `<tr><td class="muted" colspan="4">No DNS logs</td></tr>`}</table>`; }
function renderRegistry(r) { let nodes = r?.payload?.nodes || []; $('registry').innerHTML = `<div class="kv"><div>Version</div><div>${r?.version || 0}</div><div>Signature</div><div class="copy">${r?.sig || ''}</div><div>Nodes</div><div>${nodes.length}</div></div><table class="table" style="margin-top:12px"><tr><th>ID</th><th>Endpoints</th><th>Updated</th></tr>${nodes.map(n => `<tr><td>${n.id}</td><td>${(n.endpoints || []).join('<br>')}</td><td>${new Date((n.updatedAt || 0) * 1000).toLocaleString()}</td></tr>`).join('')}</table>`; }

function showVoultKey(uri) { $('qrBox').classList.add('active'); $('createdKey').textContent = uri; $('qrImg').src = '/api/v1/qr?data=' + encodeURIComponent(uri); $('openVoultKey').href = uri; }
async function copyVoultKey() { await navigator.clipboard.writeText($('createdKey').textContent); toast('copied'); }
function serverHostForKey() { return location.hostname || ''; }
async function createClient() { let body = { name: $('newName').value, groupId: $('newGroup').value, note: $('newNote').value, expiresAt: Number($('newExp').value || 0), serverHost: serverHostForKey() }; let r = await api('/api/v1/clients', { method: 'POST', body: JSON.stringify(body) }); showVoultKey(r.voultkey); toast('ok'); loadAll(); }
async function rotate(id) { let r = await api(`/api/v1/clients/${id}/rotate`, { method: 'POST', body: JSON.stringify({ serverHost: serverHostForKey() }) }); showVoultKey(r.voultkey); toast('ok'); }
async function revoke(id) { if (confirm(id)) { await api(`/api/v1/clients/${id}/revoke`, { method: 'POST' }); toast('ok'); loadAll(); } }
async function clientDetails(id) { let c = state.cl.clients.find(x => x.id === id); let d = await api(`/api/v1/clients/${id}/devices`); $('clientDetail').innerHTML = `<div class="row" style="justify-content:space-between"><h2>${c.name}</h2><button class="btn small" onclick="$('clientDetail').innerHTML=''">${t('close')}</button></div><div class="kv"><div>ID</div><div class="copy">${c.id}</div><div>Group</div><div>${c.groupId}</div><div>${t('mode')}</div><div><select id="devMode"><option>single</option><option>multi</option><option>unlimited</option></select></div><div>${t('limit')}</div><div><input id="devLimit" value="${c.deviceLimit}" type="number"></div></div><button class="btn" style="margin-top:10px" onclick="saveDevicePolicy('${c.id}')">${t('apply')}</button><h2 style="margin-top:14px">${t('devices')}</h2><pre>${pretty(d.devices)}</pre>`; $('devMode').value = c.deviceMode; }
async function saveDevicePolicy(id) { await api(`/api/v1/clients/${id}/devices/policy`, { method: 'POST', body: JSON.stringify({ mode: $('devMode').value, limit: Number($('devLimit').value || 1) }) }); toast('ok'); loadAll(); }
function setPolicyClient(id) { $('policyClient').value = id; showPage('policyPage'); loadPolicy(); }
async function loadPolicy() { $('effectivePolicy').textContent = pretty(await api(`/api/v1/clients/${$('policyClient').value}/effective-policy`)); }
async function saveClientPolicy() { await api(`/api/v1/clients/${$('policyClient').value}/policy`, { method: 'POST', body: $('policyJson').value }); toast('ok'); loadPolicy(); }
async function addDnsLog() { await api('/api/v1/dns/logs', { method: 'POST', body: JSON.stringify({ domain: $('dnsDomain').value, clientId: $('dnsClient').value, action: $('dnsAction').value }) }); toast('ok'); loadAll(); }
async function cleanupDns() { let r = await api('/api/v1/dns/logs/cleanup', { method: 'POST', body: '{"retentionDays":30}' }); toast('deleted ' + r.deleted); loadAll(); }
async function rebuildRegistry() { await api('/api/v1/cluster/registry/rebuild', { method: 'POST' }); toast('ok'); loadAll(); }
async function logout() { await fetch('/api/v1/auth/logout', { method: 'POST' }); location = '/'; }

boot();
