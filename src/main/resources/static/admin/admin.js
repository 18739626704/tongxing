'use strict';

const byId = id => document.getElementById(id);
const panel = byId('console');
const loginPanel = byId('signin');
const passwordPanel = byId('password-panel');
const latest = new Intl.DateTimeFormat('zh-CN', {dateStyle: 'medium', timeStyle: 'medium'});
const labels = {
    VIEW_STATUS: '查看位置与共享状态', VIEW_ROUTE: '查看足迹',
    VIEW_ACTIVITY: '查看动态', VIEW_PLACES: '查看常去地点', DISABLE_ACCOUNT: '禁用账号', ENABLE_ACCOUNT: '恢复账号',
    WIFI: 'Wi-Fi', MOBILE: '移动网络', ETHERNET: '以太网', VPN: 'VPN', OTHER: '其他', OFFLINE: '离线',
    FAST: '快速移动', MOVING: '移动', STILL: '静止', STOPPED: '停留', TRAVELLING: '移动中',
    OFF: '熄屏', LOCKED: '锁屏', UNLOCKED: '已解锁'
};
let selected;
let selectedDetail;
let section = 'users';
let detailTab = 'overview';
let recordsLoaded = false;
let auditVersion = 0;
let search = '';
let cursors = [''];
let nextCursor;
let requestVersion = 0;
let listVersion = 0;
let routeVersion = 0;
let recordsVersion = 0;
let activityBefore;
let points = [];
let pointPage = 0;
const pointPageSize = 100;

async function request(path, {method = 'GET', body} = {}) {
    const headers = new Headers({Accept: 'application/json'});
    if (method !== 'GET') {
        const csrf = await request('/csrf');
        headers.set(csrf.header, csrf.token);
        if (body && !(body instanceof URLSearchParams)) headers.set('Content-Type', 'application/json');
    }
    const response = await fetch('/admin' + path, {method, headers, body, cache: 'no-store'});
    if (!response.ok) {
        const message = await response.json().catch(() => null);
        const error = new Error(message?.message || (response.status === 403 ? '请求未获授权' : '请求失败'));
        error.status = response.status;
        throw error;
    }
    return response.status === 204 ? null : response.json();
}

function notice(message) {
    const element = byId('notice');
    element.textContent = message;
    element.hidden = !message;
}

function showSignIn(message = '') {
    panel.hidden = true;
    passwordPanel.hidden = true;
    byId('identity').hidden = true;
    byId('primary-nav').hidden = true;
    byId('admin-menu').open = false;
    byId('workspace').classList.remove('account-open');
    auditVersion++;
    loginPanel.hidden = false;
    selected = null;
    selectedDetail = null;
    requestVersion++;
    listVersion++;
    byId('accounts').replaceChildren();
    byId('audit').replaceChildren();
    clearLocation();
    notice(message);
}

function handle(error) {
    if (error.status === 401) showSignIn('请登录管理员账号。');
    else notice(error.message || '无法连接服务器。');
}

function openPasswordForm(mandatory) {
    panel.hidden = true;
    byId('primary-nav').hidden = true;
    byId('admin-menu').open = false;
    byId('password-back').hidden = mandatory;
    loginPanel.hidden = true;
    passwordPanel.hidden = false;
    byId('password-form').reset();
    notice(mandatory ? '首次登录请先设置自己的管理员密码。' : '');
}

function showSection(next, load = true) {
    section = next;
    byId('users-page').hidden = next !== 'users';
    byId('audit-page').hidden = next !== 'audit';
    for (const button of [byId('nav-users'), byId('nav-audit')]) {
        if (button.id === 'nav-' + next) button.setAttribute('aria-current', 'page');
        else button.removeAttribute('aria-current');
    }
    if (next === 'audit' && load) loadAudit();
}

function showDetailTab(next, load = true) {
    detailTab = next;
    for (const button of document.querySelectorAll('[data-detail-tab]')) {
        button.setAttribute('aria-pressed', String(button.dataset.detailTab === next));
    }
    for (const page of document.querySelectorAll('[data-detail-page]')) {
        page.hidden = page.dataset.detailPage !== next;
    }
    if (next === 'records' && load && !recordsLoaded) loadRecords(false);
}

function initials(name) { return Array.from(String(name || '').trim())[0] || '—'; }

async function openConsole(session) {
    byId('administrator').textContent = session.username;
    byId('identity').hidden = false;
    loginPanel.hidden = true;
    byId('change-password').hidden = session.mustChangePassword;
    if (session.mustChangePassword) {
        openPasswordForm(true);
        return;
    }
    passwordPanel.hidden = true;
    panel.hidden = false;
    byId('primary-nav').hidden = false;
    byId('workspace').classList.remove('account-open');
    selected = null;
    selectedDetail = null;
    requestVersion++;
    clearLocation();
    showSection('users', false);
    byId('details').hidden = true;
    byId('selection-status').textContent = '从账号列表选择用户';
    notice('');
    search = '';
    cursors = [''];
    byId('query').value = '';
    await loadAccounts();
}

async function loadAccounts() {
    const version = ++listVersion;
    byId('list-status').textContent = '正在读取…';
    try {
        const query = new URLSearchParams({search, after: cursors[cursors.length - 1]});
        const page = await request('/api/accounts?' + query);
        if (panel.hidden || version !== listVersion) return;
        const list = byId('accounts');
        list.replaceChildren();
        for (const account of page.accounts) {
            const row = document.createElement('li');
            const button = document.createElement('button');
            const name = document.createElement('span');
            const title = document.createElement('strong');
            const username = document.createElement('small');
            const state = document.createElement('span');
            const avatar = document.createElement('span');
            avatar.className = 'avatar';
            avatar.setAttribute('aria-hidden', 'true');
            avatar.textContent = initials(account.displayName || account.username);
            title.textContent = account.displayName || account.username;
            username.textContent = account.username;
            name.className = 'account-name';
            name.append(title);
            if (title.textContent !== username.textContent) name.append(username);
            state.className = account.disabled ? 'chip disabled' : 'chip';
            state.textContent = account.disabled ? '已禁用' : '正常';
            button.type = 'button';
            button.dataset.account = account.id;
            button.setAttribute('aria-pressed', String(account.id === selected));
            button.addEventListener('click', () => selectAccount(account.id));
            button.append(avatar, name, state);
            row.append(button);
            list.append(row);
        }
        byId('list-status').textContent = page.accounts.length ? '' : '没有符合条件的账号。';
        nextCursor = page.next;
        byId('account-pagination').hidden = !nextCursor && cursors.length === 1;
        byId('account-page-number').textContent = page.accounts.length ? '第 ' + cursors.length + ' 页' : '';
        byId('previous').disabled = cursors.length === 1;
        byId('next').disabled = !nextCursor;
    } catch (error) {
        if (version !== listVersion) return;
        byId('list-status').textContent = '账号列表加载失败。';
        handle(error);
    }
}

function facts(target, rows) {
    target.replaceChildren();
    for (const [key, value] of rows) {
        const term = document.createElement('dt');
        const description = document.createElement('dd');
        term.textContent = key;
        description.textContent = value ?? '—';
        target.append(term, description);
    }
}

function displayTime(at) { return at > 0 ? latest.format(at) : '—'; }

function clearRoute(message = '') {
    routeVersion++;
    points = [];
    pointPage = 0;
    byId('route-status').textContent = message;
    byId('route').hidden = true;
    byId('route-table').hidden = true;
    byId('points').replaceChildren();
    const canvas = byId('route');
    canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height);
}

function clearLocation(message = '') {
    byId('location-status').textContent = message;
    byId('location').replaceChildren();
    byId('device').replaceChildren();
    byId('apps').replaceChildren();
    byId('usage').hidden = true;
    byId('route-form').hidden = true;
    recordsVersion++;
    activityBefore = null;
    recordsLoaded = false;
    showDetailTab('overview', false);
    byId('usage').open = false;
    byId('more-activity').hidden = true;
    byId('records-status').textContent = '';
    byId('saved-places').replaceChildren();
    byId('activities').replaceChildren();
    clearRoute();
}

function selectAccount(id) {
    byId('workspace').classList.add('account-open');
    if (matchMedia('(max-width: 720px)').matches) window.scrollTo(0, 0);
    selected = id;
    selectedDetail = null;
    for (const button of byId('accounts').querySelectorAll('button')) {
        button.setAttribute('aria-pressed', String(button.dataset.account === id));
    }
    byId('selection-status').textContent = '正在读取账号…';
    byId('details').hidden = true;
    clearLocation();
    loadDetail(true);
}

async function loadDetail(focus = false) {
    const id = selected;
    const version = ++requestVersion;
    try {
        const detail = await request('/api/accounts/' + id);
        if (panel.hidden || version !== requestVersion) return;
        selectedDetail = detail;
        renderDetail(detail);
        if (focus) byId('person-name').focus({preventScroll:true});
    } catch (error) {
        if (version !== requestVersion) return;
        selectedDetail = null;
        byId('details').hidden = true;
        clearLocation();
        byId('selection-status').textContent = '账号详情加载失败。';
        handle(error);
    }
}

function renderDetail(detail) {
    const {account, status} = detail;
    byId('selection-status').textContent = '';
    byId('details').hidden = false;
    const name = account.displayName || account.username;
    byId('person-name').textContent = name;
    byId('person-username').textContent = '登录名 · ' + account.username;
    byId('person-avatar').textContent = initials(name);
    const state = byId('person-status');
    state.classList.toggle('disabled', account.disabled);
    state.textContent = account.disabled ? '已禁用' : '正常';
    facts(byId('profile'), [
        ['账号 ID', account.id], ['伴侣绑定', account.paired ? '已绑定' : '未绑定'],
        ['位置共享', !account.paired ? '未绑定' : account.sharing ? '共享中' : '已暂停']
    ]);
    const accountAction = byId('set-account-state');
    accountAction.textContent = account.disabled ? '恢复账号' : '禁用账号';
    accountAction.classList.toggle('danger', !account.disabled);
    const position = status.lastLocation;
    const age = position ? Math.max(0, Math.round((detail.serverTime - position.recordedAt) / 1000)) : null;
    byId('location-status').textContent = position
        ? (age <= 120 ? '最近上报 · ' + age + ' 秒前' : '上次上报 · ' + displayTime(position.recordedAt))
        : '暂无位置上报。';
    facts(byId('location'), position ? [
        ['坐标', position.latitude.toFixed(6) + ', ' + position.longitude.toFixed(6)],
        ['采集时间', displayTime(position.recordedAt)], ['定位精度', position.accuracy.toFixed(1) + ' 米'],
        ['电量', position.battery < 0 ? null : position.battery + '%'],
        ['充电', position.charging ? '充电中' : '未充电']
    ] : []);
    const device = status.device;
    facts(byId('device'), [
        ...(device ? [['设备', device.model], ['网络', labels[device.network] || device.network],
            ['活动状态', labels[device.motion] || device.motion], ['设备上报', displayTime(device.updatedAt)]] : []),
        ...(status.screenMinutes != null ? [['亮屏时长', status.screenMinutes + ' 分钟 · ' + status.screenDate]] : []),
        ...(status.liveScreen ? [['屏幕状态', labels[status.liveScreen.state] || status.liveScreen.state],
            ['屏幕上报', displayTime(status.liveScreen.updatedAt)]] : []),
        ...(status.journey ? [['行程状态', labels[status.journey.state] || status.journey.state]] : [])
    ]);
    byId('device-status').textContent = byId('device').childElementCount ? '' : '暂无设备状态上报。';
    const apps = byId('apps');
    apps.replaceChildren();
    for (const item of detail.apps?.apps || []) {
        const row = document.createElement('li');
        row.textContent = item.name + ' · ' + Math.round(item.seconds / 60) + ' 分钟';
        apps.append(row);
    }
    byId('usage').hidden = !apps.childElementCount;
    byId('route-form').hidden = false;
}

async function loadRecords(earlier) {
    if (!selectedDetail || (earlier && !activityBefore)) return;
    const account = selected;
    const version = ++recordsVersion;
    const prefix = '/api/accounts/' + account;
    byId('records-status').textContent = '正在读取…';
    try {
        const [activity, places] = await Promise.all([
            request(prefix + '/activity' + (earlier ? '?before=' + activityBefore : '')),
            earlier ? Promise.resolve(null) : request(prefix + '/places')
        ]);
        if (version !== recordsVersion || account !== selected || panel.hidden) return;
        if (places) {
            const lines = places.map(place => (
                place.name + ' · ' + place.latitude.toFixed(6) + ', ' + place.longitude.toFixed(6)
                    + (place.watching ? ' · 到达提醒已开启' : '')
            ));
            renderRecordLines(byId('saved-places'), lines.length ? lines : ['暂无已保存的地点'], true);
        }
        const events = activity.items.map(item => displayTime(item.createdAt) + ' · ' + item.text);
        renderRecordLines(byId('activities'), events.length || earlier ? events : ['暂无动态'], !earlier);
        recordsLoaded = true;
        activityBefore = activity.before;
        byId('more-activity').hidden = !activityBefore;
        byId('records-status').textContent = '';
    } catch (error) {
        if (version !== recordsVersion) return;
        byId('records-status').textContent = '读取记录失败。';
        handle(error);
    }
}

function renderRecordLines(list, lines, replace) {
    if (replace) list.replaceChildren();
    for (const line of lines) {
        const item = document.createElement('li');
        item.textContent = line;
        list.append(item);
    }
}

function drawRoute() {
    const canvas = byId('route');
    const context = canvas.getContext('2d');
    const center = points[0];
    const coordinates = points.map(point => ({
        x: ((point.longitude - center.longitude + 540) % 360 - 180) * Math.cos(center.latitude * Math.PI / 180),
        y: point.latitude - center.latitude
    }));
    const xs = coordinates.map(point => point.x);
    const ys = coordinates.map(point => point.y);
    const left = Math.min(...xs);
    const bottom = Math.min(...ys);
    const width = Math.max(...xs) - left;
    const height = Math.max(...ys) - bottom;
    const scale = Math.min((canvas.width - 48) / Math.max(width, 1e-7),
        (canvas.height - 48) / Math.max(height, 1e-7));
    const screen = coordinates.map(point => [
        (canvas.width - width * scale) / 2 + (point.x - left) * scale,
        (canvas.height + height * scale) / 2 - (point.y - bottom) * scale
    ]);
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = '#f1f5f1';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.strokeStyle = '#27634d';
    context.lineWidth = 3;
    context.beginPath();
    screen.forEach(([x, y], index) => index ? context.lineTo(x, y) : context.moveTo(x, y));
    context.stroke();
    for (const [[x, y], color, label] of [[screen[0], '#27634d', '起点'], [screen[screen.length - 1], '#a84d2f', '末点']]) {
        context.fillStyle = color;
        context.beginPath();
        context.arc(x, y, 5, 0, 2 * Math.PI);
        context.fill();
        context.font = '13px sans-serif';
        context.fillText(label, x + 8, y - 8);
        if (screen.length === 1) break;
    }
    canvas.hidden = false;
}

function rows(target, data) {
    target.replaceChildren();
    for (const record of data) {
        const row = target.insertRow();
        for (const value of record) row.insertCell().textContent = value;
    }
}

function showPoints() {
    const start = pointPage * pointPageSize;
    rows(byId('points'), points.slice(start, start + pointPageSize).map(point => [
        displayTime(point.recordedAt), point.latitude.toFixed(6), point.longitude.toFixed(6),
        point.accuracy.toFixed(1) + ' 米'
    ]));
    byId('points-prev').disabled = pointPage === 0;
    byId('points-next').disabled = start + pointPageSize >= points.length;
    byId('points-pagination').hidden = points.length <= pointPageSize;
    byId('points-page-number').textContent = '第 ' + (pointPage + 1) + ' / ' + Math.ceil(points.length / pointPageSize) + ' 页';
    byId('route-table').hidden = false;
}

byId('login').addEventListener('submit', async event => {
    event.preventDefault();
    const form = event.currentTarget;
    const submit = form.querySelector('button');
    submit.disabled = true;
    try {
        await request('/login', {method: 'POST', body: new URLSearchParams(new FormData(form))});
        form.reset();
        await openConsole(await request('/api/session'));
    } catch (error) {
        notice(error.status === 401 ? '管理员账号或密码不正确。' : error.message);
    } finally {
        submit.disabled = false;
    }
});
byId('change-password').addEventListener('click', () => openPasswordForm(false));
byId('password-back').addEventListener('click', () => {
    passwordPanel.hidden = true;
    panel.hidden = false;
    byId('primary-nav').hidden = false;
    notice('');
});
byId('nav-users').addEventListener('click', () => showSection('users'));
byId('nav-audit').addEventListener('click', () => showSection('audit'));
byId('back-to-accounts').addEventListener('click', () => {
    byId('workspace').classList.remove('account-open');
    window.scrollTo(0, 0);
    byId('accounts').querySelector('[aria-pressed="true"]')?.focus({preventScroll:true});
});
for (const button of document.querySelectorAll('[data-detail-tab]')) {
    button.addEventListener('click', () => showDetailTab(button.dataset.detailTab));
}
document.addEventListener('click', event => {
    const menu = byId('admin-menu');
    if (!menu.contains(event.target)) menu.open = false;
});
byId('password-form').addEventListener('submit', async event => {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    const newPassword = values.get('newPassword');
    if (newPassword !== values.get('confirmation')) {
        notice('两次输入的新密码不一致。');
        return;
    }
    const submit = form.querySelector('button');
    submit.disabled = true;
    try {
        await request('/api/password', {method: 'PUT', body: JSON.stringify({
            currentPassword: values.get('currentPassword'), newPassword
        })});
        form.reset();
        showSignIn('密码已更新，请用新密码登录。');
    } catch (error) { handle(error); }
    finally { submit.disabled = false; }
});
byId('logout').addEventListener('click', async () => {
    try {
        await request('/logout', {method: 'POST'});
        showSignIn();
    } catch (error) { handle(error); }
});
byId('search').addEventListener('submit', event => {
    event.preventDefault();
    search = byId('query').value.trim();
    cursors = [''];
    loadAccounts();
});
byId('previous').addEventListener('click', () => {
    if (cursors.length > 1) cursors.pop();
    loadAccounts();
});
byId('next').addEventListener('click', () => {
    if (!nextCursor) return;
    cursors.push(nextCursor);
    nextCursor = null;
    loadAccounts();
});
byId('set-account-state').addEventListener('click', async () => {
    const account = selectedDetail?.account;
    if (!account) return;
    const disabled = !account.disabled;
    if (!window.confirm(disabled ? '禁用 ' + account.username + '？当前登录及位置共享会失效。'
        : '恢复 ' + account.username + '？账号可重新登录，历史记录按服务器的保留规则显示。')) return;
    const button = byId('set-account-state');
    button.disabled = true;
    try {
        await request('/api/accounts/' + account.id, {method: 'PUT', body: JSON.stringify({disabled})});
        if (selected === account.id) await loadDetail();
        await loadAccounts();
    } catch (error) { handle(error); }
    finally { button.disabled = false; }
});
byId('load-records').addEventListener('click', () => loadRecords(false));
byId('more-activity').addEventListener('click', () => loadRecords(true));
byId('route-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (!selectedDetail?.status) return;
    const [year, month, day] = byId('route-date').value.split('-').map(Number);
    const from = new Date(year, month - 1, day).getTime();
    const to = new Date(year, month - 1, day + 1).getTime();
    clearRoute('正在读取足迹…');
    const version = routeVersion;
    try {
        const route = await request('/api/accounts/' + selected + '/positions?' + new URLSearchParams({from, to}));
        if (version !== routeVersion) return;
        points = route;
        byId('route-status').textContent = points.length ? points.length + ' 个足迹采样点，日期按浏览器时区计算。' : '该日期暂无足迹。';
        if (points.length) {
            drawRoute();
            showPoints();
        }
    } catch (error) {
        if (version !== routeVersion) return;
        clearRoute('足迹读取失败。');
        if (error.status === 401) handle(error);
    }
});
byId('points-prev').addEventListener('click', () => { pointPage--; showPoints(); });
byId('points-next').addEventListener('click', () => { pointPage++; showPoints(); });
async function loadAudit() {
    const version = ++auditVersion;
    byId('audit-status').textContent = '正在读取…';
    try {
        const records = await request('/api/audit');
        if (panel.hidden || version !== auditVersion) return;
        rows(byId('audit'), records.map(entry => [
            displayTime(entry.occurredAt), labels[entry.action] || entry.action, entry.targetId
        ]));
        byId('audit-status').textContent = records.length ? '' : '暂无操作记录。';
    } catch (error) {
        if (version !== auditVersion) return;
        byId('audit-status').textContent = '读取失败，请重试。';
        handle(error);
    }
}
byId('audit-refresh').addEventListener('click', loadAudit);
const today = new Date();
byId('route-date').value = [today.getFullYear(), today.getMonth() + 1, today.getDate()]
    .map(part => String(part).padStart(2, '0')).join('-');
window.setInterval(() => {
    if (selected && section === 'users' && detailTab === 'overview' && !panel.hidden && !document.hidden
        && (!matchMedia('(max-width: 720px)').matches || byId('workspace').classList.contains('account-open'))) loadDetail();
}, 15000);
(async () => {
    try {
        const settings = await request('/csrf');
        if (!settings.enabled) {
            notice('此实例尚未启用管理控制台。');
            return;
        }
        await openConsole(await request('/api/session'));
    } catch (error) {
        if (error.status === 401 || error.status === 403) showSignIn();
        else notice(error.message || '无法连接服务器。');
    }
})();
