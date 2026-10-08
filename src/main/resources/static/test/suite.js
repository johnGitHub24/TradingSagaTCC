/**
 * TradingSagaTCC L1 UI Smoke — 純 JS（無 Vue CDN），供瀏覽器與 headless 共用。
 * 【職責】API 劇情（SAGA-001／002、TCC-002、TCC-001、TRADE-001）＋ Dashboard：
 * DASH-001 模型規格在瀏覽器再跑一次；DASH-002／003 在 iframe 開主畫面，真的點按鈕、驗三條狀態機區塊與導航；
 * DASH-004 驗 hover 呼叫鏈 tooltip（區塊／按鈕／時間軸列）與開關。
 * 【技巧】iframe 同源，可直接讀 contentDocument 的 data-testid／data-status；主畫面加 ?autoReplay=0，
 * 避免終態後自動重播改變畫面、干擾斷言（重播另由 DASH-003 主動觸發驗證）。
 */
const API = '/api/v1';

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

const appEl = document.getElementById('smoke-app');
const runBtn = document.getElementById('run-btn');
const resultsEl = document.getElementById('results');
const uiFrame = document.getElementById('ui-frame');

/** DASH-002 成功情境的 sagaId，DASH-003 用來驗「點訂單列導航回該筆」。 */
let saga001Id = null;

/**
 * 【職責】輪詢條件直到成立；逾時丟出帶 label 與最後觀察值的錯誤。
 * @param {Function} fn 回傳 truthy 即成立
 * @param {number} timeoutMs
 * @param {string} label
 * @param {Function} [describe] 逾時時描述目前狀態
 */
async function waitFor(fn, timeoutMs, label, describe) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        try {
            if (fn()) return;
        } catch { /* DOM 尚未就緒 */ }
        await wait(100);
    }
    throw new Error('timeout: ' + label + (describe ? ' — ' + describe() : ''));
}

async function getReservation(sagaId) {
    const res = await fetch(`${API}/tcc/reservations/${sagaId}`);
    if (!res.ok) throw new Error('reservation HTTP ' + res.status);
    return res.json();
}

async function expectReservation(sagaId, exists, state, logs) {
    const r = await getReservation(sagaId);
    if (r.exists !== exists) throw new Error(`TCC-001 exists expected ${exists} got ${r.exists}`);
    if (exists && r.state !== state) throw new Error(`TCC-001 state expected ${state} got ${r.state}`);
    logs.push('reservation ' + (exists ? r.state : '（無票）'));
}

/** 【職責】載入主畫面到 iframe 並等 Vue 掛載完成。 */
async function loadMainPage() {
    uiFrame.src = '/?autoReplay=0';
    await waitFor(() => uiFrame.contentDocument?.querySelector('[data-testid="lane-saga"]'), 20000, 'main page mounted');
    await waitFor(() => !doc().querySelector('[data-testid="btn-saga-001"]').disabled, 10000, 'buttons enabled');
}

const doc = () => uiFrame.contentDocument;
const q = (sel) => doc().querySelector(sel);
const lane = (key) => q(`[data-testid="lane-${key}"]`);
const blockStatuses = (key) => [...lane(key).querySelectorAll('.sm-stage')].map((el) => el.dataset.status).join(',');
const laneState = (key) => lane(key)?.dataset.state;

/**
 * 【職責】DASH-002：在主畫面點情境按鈕，等三條 lane 到終態，逐格比對區塊狀態與中文。
 * @param {Array<string>} logs
 * @param {object} spec 期望值
 */
async function uiScenario(logs, spec) {
    await resetAccount();
    q('[data-testid="btn-refresh"]').click();
    const dash = q('[data-testid="sm-dashboard"]');
    const before = dash.dataset.sagaId;
    q(`[data-testid="${spec.button}"]`).click();
    await waitFor(() => dash.dataset.sagaId && dash.dataset.sagaId !== before, 10000, 'new sagaId on dashboard');
    const sagaId = dash.dataset.sagaId;
    logs.push('sagaId ' + sagaId.slice(0, 8));
    const describe = () => `saga=${laneState('saga')} order=${laneState('order')} tcc=${laneState('tcc')}`;
    await waitFor(() => laneState('saga') === spec.saga && laneState('order') === spec.order
        && laneState('tcc') === spec.tcc && !q(`[data-testid="${spec.button}"]`).disabled,
    20000, 'three lanes terminal', describe);
    logs.push(describe());

    for (const key of ['saga', 'order', 'tcc']) {
        const got = blockStatuses(key);
        if (got !== spec.blocks[key]) throw new Error(`${key} 區塊 expected ${spec.blocks[key]} got ${got}`);
        const current = lane(key).querySelector('[data-testid="lane-current"]').textContent;
        if (!current.includes(spec.zh[key])) throw new Error(`${key} 目前狀態應含「${spec.zh[key]}」got ${current.trim()}`);
    }
    logs.push('blocks OK · 中文 OK');

    const tone = q('[data-testid="sm-verdict"]').dataset.tone;
    if (tone !== spec.tone) throw new Error('verdict tone expected ' + spec.tone + ' got ' + tone);
    await waitFor(() => doc().querySelectorAll('[data-testid="timeline-row"]').length >= spec.minRows, 5000,
        'timeline rows >= ' + spec.minRows, () => 'rows=' + doc().querySelectorAll('[data-testid="timeline-row"]').length);
    logs.push('timeline rows ' + doc().querySelectorAll('[data-testid="timeline-row"]').length);

    await waitFor(() => Number(q('[data-testid="acc-available"]').textContent) === spec.available, 5000,
        'available ' + spec.available, () => q('[data-testid="acc-available"]').textContent);
    logs.push('available ' + spec.available);
    return sagaId;
}

function setStatus(value, failures) {
    appEl.dataset.value = value;
    appEl.dataset.failures = String(failures ? 1 : 0);
}

async function pollSaga(sagaId, expectStatus, timeoutMs = 20000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const res = await fetch(`${API}/sagas/${sagaId}`);
        if (res.ok) {
            const saga = await res.json();
            if (saga.status === expectStatus) return saga;
            if (expectStatus === 'COMPENSATED' && saga.status === 'FAILED') return saga;
        }
        await wait(250);
    }
    throw new Error(`saga ${sagaId} timeout waiting ${expectStatus}`);
}

async function resetAccount() {
    const res = await fetch(`${API}/accounts/ACC-001/reset`, { method: 'POST' });
    if (!res.ok) throw new Error('reset HTTP ' + res.status);
    const body = await res.json();
    if (Number(body.available) !== 100000) {
        throw new Error('reset available expected 100000 got ' + body.available);
    }
}

async function getAvailable() {
    const res = await fetch(`${API}/accounts/ACC-001`);
    if (!res.ok) throw new Error('account HTTP ' + res.status);
    return Number((await res.json()).available);
}

async function placeTrade(quantity, price, forceFail) {
    const res = await fetch(`${API}/trades`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            accountId: 'ACC-001', symbol: 'BTCUSDT', side: 'BUY',
            quantity, price, forceFail
        })
    });
    const data = await res.json();
    if (res.status !== 202) throw new Error(data.message || 'place HTTP ' + res.status);
    return data.sagaId;
}

function renderResult(entry) {
    const col = document.createElement('div');
    col.className = 'col-12';
    col.innerHTML = `
      <div class="card"><div class="card-body">
        <div class="d-flex justify-content-between">
          <strong>${entry.caseId}</strong>
          <span class="${entry.pass ? 'pass' : 'fail'}">${entry.pass ? 'PASS' : 'FAIL'}</span>
        </div>
        <div class="muted small">${entry.name}</div>
        ${entry.logs.length ? `<pre class="mt-2 mb-0">${entry.logs.join('\n')}</pre>` : ''}
      </div></div>`;
    resultsEl.appendChild(col);
}

async function runCase(caseId, name, fn) {
    const entry = { caseId, name, pass: false, logs: [] };
    try {
        await fn(entry.logs);
        entry.pass = true;
    } catch (e) {
        entry.logs.push(String(e));
    }
    renderResult(entry);
    return entry;
}

async function runTests() {
    if (runBtn.disabled) return;
    runBtn.disabled = true;
    setStatus('running', false);
    resultsEl.innerHTML = '';
    runBtn.textContent = 'TESTING...';
    runBtn.className = 'btn btn-lg w-100 mb-4 btn-run btn-secondary';

    const cases = [
        ['SAGA-001', '成功路徑 COMPLETED/90000', async (logs) => {
            await resetAccount();
            const sagaId = await placeTrade(1, 10000, false);
            logs.push('sagaId ' + sagaId.slice(0, 8));
            const saga = await pollSaga(sagaId, 'COMPLETED');
            logs.push('status ' + saga.status);
            const av = await getAvailable();
            if (av !== 90000) throw new Error('available expected 90000 got ' + av);
            logs.push('available ' + av);
            await expectReservation(sagaId, true, 'CONFIRMED', logs);
        }],
        ['SAGA-002', '餘額不足 COMPENSATED/100000', async (logs) => {
            await resetAccount();
            const sagaId = await placeTrade(1, 999999, false);
            logs.push('sagaId ' + sagaId.slice(0, 8));
            const saga = await pollSaga(sagaId, 'COMPENSATED');
            logs.push('status ' + saga.status);
            const av = await getAvailable();
            if (av !== 100000) throw new Error('available expected 100000 got ' + av);
            await expectReservation(sagaId, false, null, logs);
        }],
        ['TCC-002', '故意失敗補償 COMPENSATED/100000', async (logs) => {
            await resetAccount();
            const sagaId = await placeTrade(1, 10000, true);
            logs.push('sagaId ' + sagaId.slice(0, 8) + ' forceFail');
            const saga = await pollSaga(sagaId, 'COMPENSATED');
            logs.push('status ' + saga.status);
            const av = await getAvailable();
            if (av !== 100000) throw new Error('available expected 100000 got ' + av);
            await expectReservation(sagaId, true, 'CANCELLED', logs);
        }],
        ['TCC-001', '預留票查詢：未知 sagaId → 200 exists=false', async (logs) => {
            const r = await getReservation('no-such-saga');
            logs.push('exists ' + r.exists);
            if (r.exists !== false || r.state !== null) throw new Error('expected exists=false state=null');
        }],
        ['TRADE-001', 'GET 未知訂單 404', async (logs) => {
            const res = await fetch(`${API}/trades/missing-order`);
            logs.push('HTTP ' + res.status);
            if (res.status !== 404) throw new Error('expected 404 got ' + res.status);
            const body = await res.json();
            if (!String(body.message || '').includes('missing-order')) {
                throw new Error('message missing missing-order');
            }
        }],
        ['DASH-001', 'Dashboard 狀態推導規格（瀏覽器內）', async (logs) => {
            const model = await import('/dashboard.js');
            const { runDashboardSpecs } = await import('/test/dashboard.spec.js');
            const { passed, failed, results } = runDashboardSpecs(model);
            results.filter((r) => !r.pass).forEach((r) => logs.push('FAIL ' + r.name + ' — ' + r.error));
            logs.push(`${passed} passed, ${failed} failed`);
            if (failed > 0) throw new Error(failed + ' spec(s) failed');
        }],
        ['DASH-002', '主畫面 SAGA-001：三條狀態機到成功終態', async (logs) => {
            await loadMainPage();
            saga001Id = await uiScenario(logs, {
                button: 'btn-saga-001', saga: 'COMPLETED', order: 'FILLED', tcc: 'CONFIRMED', tone: 'success',
                available: 90000, minRows: 8,
                blocks: { saga: 'done,done,done,success,dim', order: 'done,success,dim', tcc: 'done,done,success,dim' },
                zh: { saga: '交易完成', order: '已成交', tcc: '已扣款' }
            });
        }],
        ['DASH-002', '主畫面 SAGA-002：階段二分岔、TCC Try 失敗無票', async (logs) => {
            await uiScenario(logs, {
                button: 'btn-saga-002', saga: 'COMPENSATED', order: 'FAILED', tcc: 'TRY_FAILED', tone: 'warn',
                available: 100000, minRows: 5,
                blocks: { saga: 'done,branch,skip,dim,warn', order: 'done,dim,warn', tcc: 'done,branch,skip,skip' },
                zh: { saga: '補償完成', order: '已失敗', tcc: 'Try 失敗' }
            });
        }],
        ['DASH-002', '主畫面 TCC-002：階段三分岔、預留票已退回', async (logs) => {
            await uiScenario(logs, {
                button: 'btn-tcc-002', saga: 'COMPENSATED', order: 'FAILED', tcc: 'CANCELLED', tone: 'warn',
                available: 100000, minRows: 8,
                blocks: { saga: 'done,done,branch,dim,warn', order: 'done,dim,warn', tcc: 'done,done,dim,warn' },
                zh: { saga: '補償完成', order: '已失敗', tcc: '已退回' }
            });
        }],
        ['DASH-003', '導航：點訂單列切換交易、時間軸定格、慢動作重播、導航列捲動', async (logs) => {
            if (!saga001Id) throw new Error('需先通過 DASH-002 SAGA-001');
            const dash = q('[data-testid="sm-dashboard"]');

            q(`[data-testid="order-row"][data-saga-id="${saga001Id}"]`).click();
            await waitFor(() => dash.dataset.sagaId === saga001Id && laneState('saga') === 'COMPLETED'
                && laneState('tcc') === 'CONFIRMED', 10000, 'order row → SAGA-001 dashboard');
            logs.push('訂單列導航 OK');

            await waitFor(() => doc().querySelectorAll('[data-testid="timeline-row"]').length >= 8, 5000, 'timeline loaded');
            doc().querySelectorAll('[data-testid="timeline-row"]')[0].click();
            await waitFor(() => dash.dataset.replaying === 'true' && q('[data-testid="replay-banner"]')
                && laneState('saga') === 'STARTED' && laneState('tcc') === 'NONE', 5000, 'jump to frame 1');
            logs.push('時間軸定格第 1 格 OK（Saga 已建立／尚無預留票）');

            q('[data-testid="btn-live"]').click();
            await waitFor(() => dash.dataset.replaying === 'false' && laneState('saga') === 'COMPLETED', 5000, 'back to live');
            logs.push('回到即時 OK');

            const speed = q('select[title="重播速度"]');
            speed.value = '500';
            speed.dispatchEvent(new Event('change'));
            const seen = new Set();
            q('[data-testid="btn-replay"]').click();
            await waitFor(() => dash.dataset.replaying === 'true', 3000, 'replay started');
            await waitFor(() => {
                seen.add(laneState('saga'));
                return dash.dataset.replaying === 'false';
            }, 20000, 'replay finished', () => 'seen=' + [...seen].join('>'));
            for (const s of ['STARTED', 'ACCOUNT_TRYING', 'ACCOUNT_CONFIRMING', 'COMPLETED']) {
                if (!seen.has(s)) throw new Error('重播未經過 ' + s + '（seen=' + [...seen].join('>') + '）');
            }
            logs.push('慢動作重播經過 ' + [...seen].join(' > '));

            q('[data-testid="nav-trail"]').click();
            await waitFor(() => q('#trail').getBoundingClientRect().top < uiFrame.contentWindow.innerHeight,
                5000, 'nav → Kafka 軌跡可見');
            q('[data-testid="nav-dashboard"]').click();
            await waitFor(() => Math.abs(q('#dashboard').getBoundingClientRect().top) < 120, 5000, 'nav → Dashboard 置頂',
                () => 'top=' + q('#dashboard').getBoundingClientRect().top);
            logs.push('導航列捲動 OK');
        }],
        ['DASH-004', '呼叫鏈對照規格（瀏覽器內，code-trace.json ↔ dashboard.js）', async (logs) => {
            const model = await import('/dashboard.js');
            const { runDashboardSpecs } = await import('/test/dashboard.spec.js');
            const res = await fetch('/code-trace.json');
            if (!res.ok) throw new Error('code-trace.json HTTP ' + res.status);
            const { traces } = await res.json();
            const results = runDashboardSpecs(model, traces).results.filter((r) => r.id === 'DASH-004');
            results.filter((r) => !r.pass).forEach((r) => logs.push('FAIL ' + r.name + ' — ' + r.error));
            const failed = results.filter((r) => !r.pass).length;
            logs.push(`${results.length - failed} passed, ${failed} failed`);
            if (failed > 0) throw new Error(failed + ' spec(s) failed');
        }],
        ['DASH-004', '主畫面 hover：狀態區塊／按鈕／時間軸列顯示呼叫鏈；關閉開關後不顯示', async (logs) => {
            if (!saga001Id) throw new Error('需先通過 DASH-002 SAGA-001');
            const Mouse = uiFrame.contentWindow.MouseEvent;
            const tip = () => q('[data-testid="trace-tip"]');
            // 捲動中 tooltip 會自動收起（目標已移位）；DASH-003 的 smooth scroll 可能尚未停，先等 scrollY 連續穩定
            const scrollIdle = async () => {
                const w = uiFrame.contentWindow;
                let last = -1;
                let stable = 0;
                await waitFor(() => {
                    stable = w.scrollY === last ? stable + 1 : 0;
                    last = w.scrollY;
                    return stable >= 3;
                }, 5000, 'scroll idle');
            };
            const hoverExpect = async (el, key, mustContain) => {
                if (!el) throw new Error('找不到 hover 目標 ' + key);
                await scrollIdle();
                el.dispatchEvent(new Mouse('mouseover', { bubbles: true }));
                await waitFor(() => tip()?.dataset.traceKey === key
                    && mustContain.every((s) => tip().textContent.includes(s)), 3000, 'tooltip ' + key,
                () => 'tip=' + (tip()?.textContent || '(none)').replace(/\s+/g, ' ').slice(0, 160));
                el.dispatchEvent(new Mouse('mouseout', { bubbles: true }));
                await waitFor(() => !tip(), 3000, 'tooltip ' + key + ' hidden');
                logs.push(key + ' → ' + mustContain.join(' > '));
            };

            await hoverExpect(lane('saga').querySelector('[data-trace="saga:COMPLETED"]'), 'saga:COMPLETED',
                ['Kafka', 'SagaKafkaListeners.onEvent', 'OrderSagaEventHandler.onConfirmed']);
            await hoverExpect(lane('tcc').querySelector('[data-trace="tcc:TRYING"]'), 'tcc:TRYING',
                ['SagaKafkaListeners.onCommand', 'AccountTccService.tryReserve']);
            await hoverExpect(q('[data-testid="btn-saga-001"]'), 'action:place',
                ['POST /api/v1/trades', 'TradeController.place', 'SagaOrchestrator.start']);
            await hoverExpect(q('[data-testid="timeline-row"][data-trace="msg:RESERVE_FUNDS"]'), 'msg:RESERVE_FUNDS',
                ['排程', 'OutboxRelayJob.tick', 'KafkaTemplateMessageSender.send']);

            const toggle = q('[data-testid="toggle-trace"]');
            toggle.click();
            q('[data-testid="btn-reset"]').dispatchEvent(new Mouse('mouseover', { bubbles: true }));
            await wait(300);
            if (tip()) throw new Error('關閉「顯示呼叫鏈」後仍出現 tooltip');
            toggle.click();
            logs.push('開關關閉時不顯示 OK');
        }]
    ];

    const results = [];
    for (const [id, name, fn] of cases) {
        results.push(await runCase(id, name, fn));
    }

    const allPass = results.every((r) => r.pass);
    setStatus(allPass ? 'completed' : 'failed', !allPass);
    runBtn.disabled = false;
    runBtn.textContent = allPass ? 'SERVICE COMPLETED' : 'SERVICE FAILED';
    runBtn.className = 'btn btn-lg w-100 mb-4 btn-run ' + (allPass ? 'btn-success' : 'btn-danger');
}

runBtn.addEventListener('click', () => runTests().catch((e) => {
    setStatus('failed', true);
    runBtn.disabled = false;
    runBtn.textContent = 'SERVICE FAILED';
    runBtn.className = 'btn btn-lg w-100 mb-4 btn-run btn-danger';
    console.error(e);
}));
