'use strict';

const byId = id => document.getElementById(id);
const panel = byId('console');
const loginPanel = byId('signin');
const passwordPanel = byId('password-panel');
const latest = new Intl.DateTimeFormat('zh-CN', {dateStyle: 'medium', timeStyle: 'medium'});
const labels = {
    VIEW_STATUS: '查看位置与共享状态', VIEW_ROUTE: '查看足迹', DISABLE_ACCOUNT: '禁用账号', ENABLE_ACCOUNT: '恢复账号',
    WIFI: 'Wi-Fi', MOBILE: '移动网络', ETHERNET: '以太网', VPN: 'VPN', OTHER: '其他', OFFLINE: '离线',
    FAST: '快速移动', MOVING: '移动', STILL: '静止', STOPPED: '停留', TRAVELLING: '移动中',
    OFF: '熄屏', LOCKED: '锁屏', UNLOCKED: '已解锁'
};
let selected;
let selectedDetail;
let search = '';
let cursors = [''];
let nextCursor;
let requestVersion = 0;
let listVersion = 0;
let routeVersion = 0;
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
    updateBootstrapField().catch(() => {});
    byId('identity').hidden = true;
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

async function updateBootstrapField() {
    const settings = await request('/csrf');
    const setup = byId('setup-code');
    setup.hidden = !settings.initialLogin;
    setup.querySelector('input').required = settings.initialLogin;
    return settings;
}

function openPasswordForm(mandatory) {
    panel.hidden = true;
    loginPanel.hidden = true;
    passwordPanel.hidden = false;
    byId('password-form').reset();
    notice(mandatory ? '首次登录请先设置自己的管理员密码。' : '');
}

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
    byId('details').hidden = true;
    byId('selection-status').textContent = '选择一个账号查看详情。';
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
            const state = document.createElement('small');
            name.textContent = account.displayName === account.username ? account.username
                : account.displayName + ' · ' + account.username;
            state.textContent = account.disabled ? '已禁用' : '启用';
            button.type = 'button';
            button.dataset.account = account.id;
            button.setAttribute('aria-pressed', String(account.id === selected));
            button.addEventListener('click', () => selectAccount(account.id));
            button.append(name, state);
            row.append(button);
            list.append(row);
        }
        byId('list-status').textContent = page.accounts.length ? '' : '没有符合条件的账号。';
        nextCursor = page.next;
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
    clearRoute();
}

function selectAccount(id) {
    selected = id;
    selectedDetail = null;
    for (const button of byId('accounts').querySelectorAll('button')) {
        button.setAttribute('aria-pressed', String(button.dataset.account === id));
    }
    byId('selection-status').textContent = '正在读取账号…';
    byId('details').hidden = true;
    clearLocation();
    loadDetail();
}

async function loadDetail() {
    const id = selected;
    const version = ++requestVersion;
    try {
        const detail = await request('/api/accounts/' + id);
        if (panel.hidden || version !== requestVersion) return;
        selectedDetail = detail;
        renderDetail(detail);
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
    facts(byId('profile'), [
        ['登录名', account.username], ['显示名称', account.displayName], ['账号 ID', account.id],
        ['账号状态', account.disabled ? '已禁用' : '启用'], ['伴侣绑定', account.paired ? '已绑定' : '未绑定'],
        ['位置共享', account.sharing ? '共享中' : '已暂停'],
        ['管理员查看授权', account.adminLocationAllowed ? '已授权' : '未授权']
    ]);
    byId('set-account-state').textContent = account.disabled ? '恢复账号' : '禁用账号';
    if (!status) {
        clearLocation(account.adminLocationAllowed ? '当前没有启用位置共享。' : '用户尚未授权管理员查看位置和足迹。');
        return;
    }
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
        notice(error.status === 401 ? '管理员账号、密码或首次设置码不正确。' : error.message);
    } finally {
        submit.disabled = false;
    }
});
byId('change-password').addEventListener('click', () => openPasswordForm(false));
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
        : '恢复 ' + account.username + '？账号可重新登录，控制台查看需要用户重新授权。')) return;
    const button = byId('set-account-state');
    button.disabled = true;
    try {
        await request('/api/accounts/' + account.id, {method: 'PUT', body: JSON.stringify({disabled})});
        if (selected === account.id) await loadDetail();
        await loadAccounts();
    } catch (error) { handle(error); }
    finally { button.disabled = false; }
});
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
        clearRoute(error.status === 403 ? '位置共享已暂停或授权已撤回。' : '足迹读取失败。');
        if (error.status === 401) handle(error);
    }
});
byId('points-prev').addEventListener('click', () => { pointPage--; showPoints(); });
byId('points-next').addEventListener('click', () => { pointPage++; showPoints(); });
byId('audit-refresh').addEventListener('click', async () => {
    try {
        const records = await request('/api/audit');
        if (!panel.hidden) rows(byId('audit'), records.map(entry => [
            displayTime(entry.occurredAt), labels[entry.action] || entry.action, entry.targetId
        ]));
    } catch (error) { handle(error); }
});
const today = new Date();
byId('route-date').value = [today.getFullYear(), today.getMonth() + 1, today.getDate()]
    .map(part => String(part).padStart(2, '0')).join('-');
window.setInterval(() => {
    if (selected && !panel.hidden && !document.hidden) loadDetail();
}, 15000);
(async () => {
    try {
        const settings = await updateBootstrapField();
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
