/**
 * DASH-001 單元層：dashboard.js 狀態推導純函式的規格（無 DOM、無 HTTP）。
 * 【技巧】本檔不 import 任何東西，由呼叫端傳入 model 模組：
 * 瀏覽器（test/suite.js）傳 import('/dashboard.js')；Node（docs/ui-smoke/run-dashboard-unit.mjs）以 data URL 載入後傳入，
 * 同一份規格兩邊跑，不必為 Node 改寫成 CommonJS。
 * 【概念】三情境的期望對照 docs/狀態對照-Saga-TCC-訂單.md §4 終態組合速查。
 */

const SAGA_ID = 'saga-spec-1';

/** 假的 Saga 步驟；at 以毫秒遞增，模擬伺服器時間戳。 */
const step = (name, ms) => ({ name, detail: '', at: new Date(1_700_000_000_000 + ms).toISOString() });
const evt = (type, ms, sagaId = SAGA_ID) => ({
    topic: type.startsWith('FUNDS_') ? 'trading.saga.events' : 'trading.saga.commands',
    type, sagaId, payload: '', at: new Date(1_700_000_000_000 + ms).toISOString()
});

const saga = (status, steps) => ({ sagaId: SAGA_ID, orderId: 'o-1', status, steps });
const ticket = (state) => (state ? { sagaId: SAGA_ID, exists: true, state } : { sagaId: SAGA_ID, exists: false, state: null });

const SAGA_001_STEPS = [step('ORDER_CREATED', 0), step('RESERVE_COMMANDED', 1), step('CONFIRM_COMMANDED', 40), step('SAGA_COMPLETED', 80)];
const SAGA_001_EVENTS = [evt('FUNDS_CONFIRMED', 70), evt('CONFIRM_FUNDS', 50), evt('FUNDS_RESERVED', 30), evt('RESERVE_FUNDS', 20)];

function eq(actual, expected, msg) {
    if (actual !== expected) {
        throw new Error(`${msg}: expected ${JSON.stringify(expected)} got ${JSON.stringify(actual)}`);
    }
}

function ok(cond, msg) {
    if (!cond) throw new Error(msg);
}

const statuses = (lane) => lane.stages.map((s) => s.status).join(',');
const forkStatuses = (lane) => lane.fork.map((s) => s.status).join(',');
const laneOf = (lanes, key) => lanes.find((l) => l.key === key);

/**
 * 【職責】DASH-001 規格清單。
 * @param {object} m dashboard.js 模組
 * @returns {Array<{name:string, fn:Function}>}
 */
export function dashboardSpecs(m) {
    const lanesFor = (s, o, t) => m.buildLanes(m.liveSnapshot(s, o, t));
    return [
        {
            name: '未選交易：三條 lane 全部尚未到達、總結 idle',
            fn: () => {
                const lanes = lanesFor(null, null, null);
                eq(lanes.map((l) => l.key).join(','), 'saga,order,tcc', 'lane 順序');
                lanes.forEach((l) => {
                    ok(l.stages.every((s) => s.status === 'todo'), l.key + ' 線性階段應全 todo');
                    ok(l.fork.every((s) => s.status === 'todo'), l.key + ' 終態應全 todo');
                });
                eq(m.buildVerdict(m.liveSnapshot(null, null, null)).tone, 'idle', 'verdict');
            }
        },
        {
            name: '階段編號與中文：階段一～三＋階段四分岔',
            fn: () => {
                const sagaLane = laneOf(lanesFor(null, null, null), 'saga');
                eq(sagaLane.stages.map((s) => s.no).join(','), '階段一,階段二,階段三', 'Saga 階段編號');
                eq(sagaLane.fork[0].no, '階段四・成功路徑', '成功分岔');
                eq(sagaLane.fork[1].no, '階段四・補償路徑', '補償分岔');
                eq(sagaLane.stages[1].zh, '凍結資金中', 'ACCOUNT_TRYING 中文');
                eq(sagaLane.stages[1].code, 'ACCOUNT_TRYING', 'ACCOUNT_TRYING 代碼');
            }
        },
        {
            name: 'SAGA-001 成功：Saga 交易完成／訂單已成交／預留票已扣款',
            fn: () => {
                const lanes = lanesFor(saga('COMPLETED', SAGA_001_STEPS), { status: 'FILLED' }, ticket('CONFIRMED'));
                const [s, o, t] = ['saga', 'order', 'tcc'].map((k) => laneOf(lanes, k));
                eq(statuses(s), 'done,done,done', 'Saga 階段');
                eq(forkStatuses(s), 'success,dim', 'Saga 終態');
                eq(s.current.zh, '交易完成', 'Saga 中文');
                eq(forkStatuses(o), 'success,dim', '訂單終態');
                eq(o.current.zh, '已成交', '訂單中文');
                eq(statuses(t), 'done,done', 'TCC 階段');
                eq(forkStatuses(t), 'success,dim', 'TCC 終態');
                eq(t.current.code, 'CONFIRMED', 'TCC 代碼');
                const v = m.buildVerdict(m.liveSnapshot(saga('COMPLETED', SAGA_001_STEPS), { status: 'FILLED' }, ticket('CONFIRMED')));
                eq(v.tone, 'success', 'verdict tone');
            }
        },
        {
            name: 'SAGA-002 餘額不足：階段二分岔、階段三略過、TCC Try 失敗無票',
            fn: () => {
                const steps = [step('ORDER_CREATED', 0), step('RESERVE_COMMANDED', 1), step('ORDER_MARK_FAILED', 60)];
                const snap = m.liveSnapshot(saga('COMPENSATED', steps), { status: 'FAILED' }, ticket(null));
                eq(snap.tcc, 'TRY_FAILED', '無票＋補償 → TRY_FAILED');
                const lanes = m.buildLanes(snap);
                const [s, o, t] = ['saga', 'order', 'tcc'].map((k) => laneOf(lanes, k));
                eq(statuses(s), 'done,branch,skip', 'Saga 階段');
                eq(forkStatuses(s), 'dim,warn', 'Saga 終態');
                eq(s.current.zh, '補償完成', 'Saga 中文');
                eq(forkStatuses(o), 'dim,warn', '訂單終態');
                eq(statuses(t), 'done,branch', 'TCC 階段');
                eq(t.stages[1].zh, 'Try 失敗（未凍結）', 'TCC 分岔中文');
                eq(forkStatuses(t), 'skip,skip', 'TCC 終態全略過');
                const v = m.buildVerdict(snap);
                eq(v.tone, 'warn', 'verdict tone');
                ok(v.text.includes('SAGA-002'), 'verdict 應點出 SAGA-002');
            }
        },
        {
            name: 'TCC-002 forceFail：階段三分岔、預留票已退回',
            fn: () => {
                const steps = [step('ORDER_CREATED', 0), step('RESERVE_COMMANDED', 1), step('CONFIRM_COMMANDED', 40), step('ORDER_MARK_FAILED', 80)];
                const snap = m.liveSnapshot(saga('COMPENSATED', steps), { status: 'FAILED' }, ticket('CANCELLED'));
                const lanes = m.buildLanes(snap);
                const [s, t] = ['saga', 'tcc'].map((k) => laneOf(lanes, k));
                eq(statuses(s), 'done,done,branch', 'Saga 階段');
                eq(forkStatuses(s), 'dim,warn', 'Saga 終態');
                eq(forkStatuses(t), 'dim,warn', 'TCC 終態');
                eq(t.current.zh, '已退回（Cancel）', 'TCC 中文');
                ok(m.buildVerdict(snap).text.includes('TCC-002'), 'verdict 應點出 TCC-002');
            }
        },
        {
            name: '進行中：預留票已凍結但 Saga 仍凍結資金中 → 提示最終一致',
            fn: () => {
                const snap = m.liveSnapshot(saga('ACCOUNT_TRYING', SAGA_001_STEPS.slice(0, 2)), { status: 'PENDING' }, ticket('TRYING'));
                const lanes = m.buildLanes(snap);
                eq(statuses(laneOf(lanes, 'saga')), 'done,active,todo', 'Saga 階段');
                eq(statuses(laneOf(lanes, 'order')), 'active', '訂單待成交為目前');
                eq(statuses(laneOf(lanes, 'tcc')), 'done,active', 'TCC 已凍結為目前');
                const v = m.buildVerdict(snap);
                eq(v.tone, 'active', 'verdict tone');
                ok(v.text.includes('最終一致'), 'verdict 應提示最終一致');
            }
        },
        {
            name: '進行中：確認扣款中 → 階段三為目前；預留票尚無 → 階段一為目前',
            fn: () => {
                const lanes = lanesFor(saga('ACCOUNT_CONFIRMING', SAGA_001_STEPS.slice(0, 3)), { status: 'PENDING' }, ticket('TRYING'));
                eq(statuses(laneOf(lanes, 'saga')), 'done,done,active', 'Saga 階段');
                const early = lanesFor(saga('ACCOUNT_TRYING', SAGA_001_STEPS.slice(0, 2)), { status: 'PENDING' }, ticket(null));
                eq(statuses(laneOf(early, 'tcc')), 'active,todo', 'TCC 尚無票');
            }
        },
        {
            name: 'COMPENSATING／FAILED：補償終態改顯示對應代碼',
            fn: () => {
                const comp = laneOf(lanesFor(saga('COMPENSATING', SAGA_001_STEPS.slice(0, 2)), { status: 'PENDING' }, ticket(null)), 'saga');
                eq(comp.fork[1].status, 'warnActive', 'COMPENSATING 為補償進行中');
                eq(comp.fork[1].code, 'COMPENSATING', 'COMPENSATING 代碼');
                const failed = laneOf(lanesFor(saga('FAILED', SAGA_001_STEPS.slice(0, 2)), { status: 'FAILED' }, ticket(null)), 'saga');
                eq(failed.fork[1].code, 'FAILED', 'FAILED 代碼');
                eq(failed.fork[1].status, 'warn', 'FAILED 為補償終態');
            }
        },
        {
            name: '時間軸重建：依伺服器時間排序、預留票先變 Saga 後跟、終格＝即時狀態',
            fn: () => {
                const s = saga('COMPLETED', SAGA_001_STEPS);
                const live = m.liveSnapshot(s, { status: 'FILLED' }, ticket('CONFIRMED'));
                const frames = m.buildTimeline(s, SAGA_001_EVENTS, live);
                eq(frames.length, 8, '4 步驟＋4 訊息');
                eq(frames.map((f) => f.name).join(','),
                    'ORDER_CREATED,RESERVE_COMMANDED,RESERVE_FUNDS,FUNDS_RESERVED,CONFIRM_COMMANDED,CONFIRM_FUNDS,FUNDS_CONFIRMED,SAGA_COMPLETED',
                    '排序');
                const reserved = frames.findIndex((f) => f.snap.tcc === 'TRYING');
                const confirming = frames.findIndex((f) => f.snap.sagaStatus === 'ACCOUNT_CONFIRMING');
                ok(reserved >= 0 && reserved < confirming, '預留票 TRYING 應早於 Saga ACCOUNT_CONFIRMING');
                const last = frames[frames.length - 1];
                eq(last.snap.sagaStatus, 'COMPLETED', '終格 Saga');
                eq(last.snap.orderStatus, 'FILLED', '終格訂單');
                eq(last.snap.tcc, 'CONFIRMED', '終格 TCC');
                eq(frames[0].offsetMs, 0, '第一格偏移 0');
                ok(frames[2].label.includes('預留資金'), '訊息應有中文說明');
                ok(last.summary.includes('交易完成'), '摘要應含中文狀態');
            }
        },
        {
            name: '時間軸：只取本 sagaId 的訊息；軌跡缺漏時補「目前即時狀態」格',
            fn: () => {
                const s = saga('COMPLETED', SAGA_001_STEPS);
                const live = m.liveSnapshot(s, { status: 'FILLED' }, ticket('CONFIRMED'));
                const others = [evt('FUNDS_FAILED', 10, 'other-saga')];
                const frames = m.buildTimeline(s, others, live);
                ok(frames.every((f) => f.name !== 'FUNDS_FAILED'), '不應混入其他 saga 的訊息');
                const last = frames[frames.length - 1];
                eq(last.side, 'live', '缺 FUNDS_CONFIRMED 時補即時格');
                eq(last.snap.tcc, 'CONFIRMED', '即時格 TCC');
                eq(m.buildTimeline(null, [], live).length, 0, '無 Saga → 空時間軸');
            }
        },
        {
            name: 'diffLanes：只列出有變動的 lane',
            fn: () => {
                const a = { sagaStatus: 'ACCOUNT_TRYING', confirmCommanded: false, orderStatus: 'PENDING', tcc: 'NONE' };
                eq(m.diffLanes(a, { ...a, tcc: 'TRYING' }).join(','), 'tcc', '只有 TCC');
                eq(m.diffLanes(a, { ...a, sagaStatus: 'COMPLETED', orderStatus: 'FILLED' }).join(','), 'saga,order', 'Saga＋訂單');
                eq(m.diffLanes(a, a).length, 0, '無變動');
            }
        }
    ];
}

/**
 * 【職責】執行全部規格並收集結果（不丟例外，方便兩種執行環境各自輸出）。
 * @param {object} m dashboard.js 模組
 * @returns {{passed:number, failed:number, results:Array<{name:string, pass:boolean, error?:string}>}}
 */
export function runDashboardSpecs(m) {
    const results = dashboardSpecs(m).map(({ name, fn }) => {
        try {
            fn();
            return { name, pass: true };
        } catch (e) {
            return { name, pass: false, error: String(e.message || e) };
        }
    });
    const failed = results.filter((r) => !r.pass).length;
    return { passed: results.length - failed, failed, results };
}
