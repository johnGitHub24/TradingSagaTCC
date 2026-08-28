/**
 * TradingSagaTCC L1 UI Smoke — 純 JS（無 Vue CDN），供瀏覽器與 headless 共用。
 */
const API = '/api/v1';

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

const appEl = document.getElementById('smoke-app');
const runBtn = document.getElementById('run-btn');
const resultsEl = document.getElementById('results');

function setStatus(value, failures) {
    appEl.dataset.value = value;
    appEl.dataset.failures = String(failures ? 1 : 0);
}

async function pollSaga(sagaId, expectStatus, timeoutMs = 15000) {
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
        }],
        ['SAGA-002', '餘額不足 COMPENSATED/100000', async (logs) => {
            await resetAccount();
            const sagaId = await placeTrade(1, 999999, false);
            logs.push('sagaId ' + sagaId.slice(0, 8));
            const saga = await pollSaga(sagaId, 'COMPENSATED');
            logs.push('status ' + saga.status);
            const av = await getAvailable();
            if (av !== 100000) throw new Error('available expected 100000 got ' + av);
        }],
        ['TCC-002', '故意失敗補償 COMPENSATED/100000', async (logs) => {
            await resetAccount();
            const sagaId = await placeTrade(1, 10000, true);
            logs.push('sagaId ' + sagaId.slice(0, 8) + ' forceFail');
            const saga = await pollSaga(sagaId, 'COMPENSATED');
            logs.push('status ' + saga.status);
            const av = await getAvailable();
            if (av !== 100000) throw new Error('available expected 100000 got ' + av);
        }],
        ['TRADE-001', 'GET 未知訂單 404', async (logs) => {
            const res = await fetch(`${API}/trades/missing-order`);
            logs.push('HTTP ' + res.status);
            if (res.status !== 404) throw new Error('expected 404 got ' + res.status);
            const body = await res.json();
            if (!String(body.message || '').includes('missing-order')) {
                throw new Error('message missing missing-order');
            }
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
