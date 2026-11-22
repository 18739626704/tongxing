'use strict';

const byId = id => document.getElementById(id);
const panel = byId('console');
const loginPanel = byId('signin');
const passwordPanel = byId('password-panel');
const timeZone = 'Asia/Shanghai';
const latest = new Intl.DateTimeFormat('zh-CN', {dateStyle: 'medium', timeStyle: 'medium', timeZone});
const routeTime = new Intl.DateTimeFormat('zh-CN', {hour: '2-digit', minute: '2-digit', hour12: false, timeZone});
const dayFormatter = new Intl.DateTimeFormat('sv-SE', {timeZone, year: 'numeric', month: '2-digit', day: '2-digit'});
const dayMillis = 86_400_000;
const dateRange = value => {
    const from = Date.parse(value + 'T00:00:00+08:00');
    return {from, to: from + dayMillis};
};
const labels = {
    VIEW_STATUS: '查看位置与共享状态', VIEW_ROUTE: '查看足迹',
    VIEW_CONVERSATION: '查看绑定对话', VIEW_ACTIVITY: '查看动态', VIEW_PLACES: '查看常去地点', DISABLE_ACCOUNT: '禁用账号', ENABLE_ACCOUNT: '恢复账号',
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
let loadedRouteDate;
let recordsVersion = 0;
let activityBefore;
let conversationVersion = 0;
let conversationBefore;
let conversationPartner;
let points = [];
let processed = {fixes: [], parts: []};
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
    if (next === 'users' && load && detailTab === 'route') showDetailTab('route');
}

function showDetailTab(next, load = true) {
    detailTab = next;
    for (const button of document.querySelectorAll('[data-detail-tab]')) {
        button.setAttribute('aria-pressed', String(button.dataset.detailTab === next));
    }
    for (const page of document.querySelectorAll('[data-detail-page]')) {
        page.hidden = page.dataset.detailPage !== next;
    }
    if (next === 'route' && load) {
        if (loadedRouteDate !== byId('route-date').value) loadRoute();
        else if (processed.fixes.length && byId('route-map-card').hidden && byId('route').hidden) showRouteMap(); else adminRouteMap.fit();
    }
    if (next === 'conversation' && load) loadConversation(false);
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
            avatar.textContent = initials(account.username);
            title.textContent = account.username;
            username.textContent = account.displayName || account.username;
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
    processed = {fixes: [], parts: []};
    byId('timeline').hidden = true;
    byId('timeline-items').replaceChildren();
    pointPage = 0;
    byId('route-status').textContent = message;
    loadedRouteDate = null;
    adminRouteMap.reset();
    byId('route-summary').hidden = true;
    byId('route').hidden = true;
    byId('route-table').hidden = true;
    byId('route-table').open = false;
    byId('points').replaceChildren();
    const canvas = byId('route');
    canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height);
}

function clearLocation(message = '') {
    byId('location-status').textContent = message;
    byId('location').replaceChildren();
    byId('show-recent-route').hidden = true;
    byId('device').replaceChildren();
    byId('apps').replaceChildren();
    byId('usage').hidden = true;
    byId('route-form').hidden = true;
    recordsVersion++;
    conversationVersion++;
    resetConversation();
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
    const keepRoute = detailTab === 'route';
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
    if (keepRoute) showDetailTab('route', false);
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
        if (focus) {
            byId('person-name').focus({preventScroll:true});
            if (detailTab === 'route') loadRoute();
        }
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
    const {account, status, partner} = detail;
    byId('selection-status').textContent = '';
    byId('details').hidden = false;
    byId('person-name').textContent = account.username;
    byId('person-username').textContent = account.displayName !== account.username ? account.displayName || '' : '';
    byId('person-avatar').textContent = initials(account.username);
    const state = byId('person-status');
    state.classList.toggle('disabled', account.disabled);
    state.textContent = account.disabled ? '已禁用' : '正常';
    facts(byId('profile'), [
        ['伴侣绑定', partner ? accountLabel(partner) : '未绑定'],
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
    byId('show-recent-route').hidden = !position;
    facts(byId('location'), position ? [
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

function accountLabel(account) {
    return account.displayName && account.displayName !== account.username
        ? account.displayName + '（' + account.username + '）' : account.username;
}

function resetConversation() {
    conversationBefore = null;
    conversationPartner = null;
    byId('conversation-title').textContent = '绑定对话';
    byId('conversation-items').replaceChildren();
    byId('more-conversation').hidden = true;
}

async function loadConversation(earlier) {
    if (!selectedDetail || (earlier && !conversationBefore)) return;
    const version = ++conversationVersion;
    if (!earlier) resetConversation();
    const button = byId('more-conversation');
    button.disabled = true;
    byId('conversation-status').textContent = '正在读取对话…';
    try {
        const path = '/api/accounts/' + selected + '/conversation';
        const {partner, messages, before} = await request(path + (earlier ? '?before=' + conversationBefore : ''));
        if (version !== conversationVersion || panel.hidden) return;
        if (earlier && partner?.id !== conversationPartner) {
            loadConversation(false);
            return;
        }
        if (!partner) {
            byId('conversation-status').textContent = '该用户当前未绑定伴侣。';
            return;
        }
        conversationPartner = partner.id;
        byId('conversation-title').textContent = '与 ' + accountLabel(partner) + ' 的对话';
        const batch = document.createDocumentFragment();
        for (const message of messages.slice().reverse()) {
            const row = document.createElement('li');
            const meta = document.createElement('small');
            const body = document.createElement('p');
            row.className = message.outgoing ? 'outgoing' : 'incoming';
            meta.textContent = accountLabel(message) + ' · ' + displayTime(message.createdAt);
            body.textContent = message.text;
            row.append(meta, body);
            batch.append(row);
        }
        byId('conversation-items').prepend(batch);
        conversationBefore = before;
        button.hidden = !before;
        byId('conversation-status').textContent = messages.length || earlier ? '' : '暂无保留的文字消息。';
    } catch (error) {
        if (version !== conversationVersion) return;
        byId('conversation-status').textContent = '对话读取失败，请重新打开此页。';
        if (error.status === 401) handle(error);
    } finally {
        if (version === conversationVersion) button.disabled = false;
    }
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

function showRouteMap() {
    if (!adminRouteMap.show(processed.fixes, processed.parts, at => routeTime.format(at))) drawRoute();
}

function drawRoute() {
    const points = processed.fixes;
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
    for (const part of processed.parts) {
        if (part.kind !== 'moving') continue;
        context.moveTo(...screen[part.first]);
        for (let index = part.first + 1; index <= part.last; index++) context.lineTo(...screen[index]);
    }
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
byId('more-conversation').addEventListener('click', () => loadConversation(true));

function duration(milliseconds) {
    const minutes = Math.floor(milliseconds / 60_000);
    if (!minutes && milliseconds > 0) return '不足 1 分钟';
    return minutes < 60 ? minutes + ' 分钟' : Math.floor(minutes / 60) + ' 小时 ' + minutes % 60 + ' 分钟';
}

function renderRouteTimeline() {
    const titles = {moving: '移动', stopped: '停留', gap: '定位中断', uncertain: '未能判定'};
    const totals = {moving: 0, stopped: 0, gap: 0, uncertain: 0};
    const items = byId('timeline-items');
    for (const part of processed.parts.slice().reverse()) {
        const elapsed = part.to - part.from;
        totals[part.kind] += elapsed;
        const row = document.createElement('li');
        const active = part.kind === 'moving' || part.kind === 'stopped';
        const element = document.createElement(active ? 'button' : 'div');
        element.className = 'timeline-row ' + part.kind;
        const title = document.createElement('strong');
        const detail = document.createElement('span');
        title.textContent = titles[part.kind] + ' · ' + duration(elapsed);
        detail.textContent = routeTime.format(part.from) + '–' + routeTime.format(part.to)
            + (part.kind === 'moving' ? ' · 约 ' + (part.meters < 1000 ? Math.round(part.meters) + ' 米'
                : (part.meters / 1000).toFixed(1) + ' 公里') : '');
        if (active) {
            element.type = 'button';
            element.addEventListener('click', () => adminRouteMap.focus(part));
        }
        element.append(title, detail);
        row.append(element);
        items.append(row);
    }
    byId('timeline-status').textContent = processed.parts.length ? '' : '有效定位太少，暂无法判断移动或停留。';
    byId('timeline').hidden = false;
    const summary = byId('route-summary');
    summary.replaceChildren();
    for (const [label, value] of [
        ['移动', totals.moving], ['停留', totals.stopped], ['记录不足', totals.gap + totals.uncertain]
    ]) {
        const item = document.createElement('div');
        facts(item, [[label, duration(value)]]);
        summary.append(item);
    }
    summary.hidden = !processed.parts.length;
}

async function loadRoute() {
    const date = byId('route-date');
    if (!selectedDetail || !date.value || !date.validity.valid) return;
    byId('route-next-day').disabled = date.value >= date.max;
    byId('route-previous-day').disabled = date.value <= date.min;
    const value = date.value;
    const {from, to} = dateRange(value);
    clearRoute('正在读取足迹…');
    const version = routeVersion;
    try {
        const route = await request('/api/accounts/' + selected + '/positions?' + new URLSearchParams({from, to}));
        if (version !== routeVersion) return;
        points = route;
        loadedRouteDate = value;
        byId('route-status').textContent = points.length ? '' : '该日期暂无足迹。';
        if (points.length) {
            processed = adminRouteTimeline.process(points);
            renderRouteTimeline();
            showPoints();
            if (processed.fixes.length && detailTab === 'route' && section === 'users') showRouteMap();
        }
    } catch (error) {
        if (version !== routeVersion) return;
        clearRoute('足迹读取失败，请重新选择日期。');
        if (error.status === 401) handle(error);
    }
}

function shiftRouteDay(amount) {
    const date = byId('route-date');
    if (!date.value) return;
    const next = dayFormatter.format(dateRange(date.value).from + amount * dayMillis);
    if (next < date.min || next > date.max) return;
    date.value = next;
    loadRoute();
}

byId('route-form').addEventListener('submit', event => { event.preventDefault(); loadRoute(); });
byId('route-date').addEventListener('change', loadRoute);
byId('route-previous-day').addEventListener('click', () => shiftRouteDay(-1));
byId('route-next-day').addEventListener('click', () => shiftRouteDay(1));
byId('show-recent-route').addEventListener('click', () => {
    const position = selectedDetail?.status.lastLocation;
    if (!position) return;
    const date = byId('route-date');
    const recordedDay = dayFormatter.format(position.recordedAt);
    date.value = recordedDay < date.min ? date.min : recordedDay > date.max ? date.max : recordedDay;
    showDetailTab('route');
});
byId('points-prev').addEventListener('click', () => { pointPage--; showPoints(); });
byId('points-next').addEventListener('click', () => { pointPage++; showPoints(); });
async function loadAudit() {
    const version = ++auditVersion;
    byId('audit-status').textContent = '正在读取…';
    try {
        const username = byId('audit-username').value.trim();
        const date = byId('audit-date').value;
        const filter = new URLSearchParams({username, ...(date ? dateRange(date) : {})});
        const records = await request('/api/audit?' + filter);
        if (panel.hidden || version !== auditVersion) return;
        rows(byId('audit'), records.map(entry => [
            displayTime(entry.occurredAt), labels[entry.action] || entry.action, entry.username || '账号已不存在'
        ]));
        byId('audit-status').textContent = records.length === 100 ? '显示最近 100 条匹配记录。' : records.length ? '' : '暂无匹配的操作记录。';
    } catch (error) {
        if (version !== auditVersion) return;
        byId('audit-status').textContent = '读取失败，请重试。';
        handle(error);
    }
}
byId('audit-form').addEventListener('submit', event => { event.preventDefault(); loadAudit(); });
byId('audit-clear').addEventListener('click', () => { byId('audit-form').reset(); loadAudit(); });
const today = dayFormatter.format(Date.now());
byId('route-date').max = today;
byId('route-date').min = dayFormatter.format(Date.now() - 30 * dayMillis);
byId('route-date').value = today;
byId('audit-date').max = today;
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
