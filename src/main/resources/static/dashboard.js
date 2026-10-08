/**
 * TradingSagaTCC 狀態機 Dashboard 模型：把 Saga／訂單／TCC 預留票三組狀態轉成「階段一 → 階段二 → …」的圖像區塊。
 * 【職責】純函式（不碰 DOM、不發 HTTP）；app.js 傳入快照，回傳可直接 v-for 的 lane／時間軸模型。
 * 【技巧】每條 lane＝「線性階段」＋「終態分岔（成功／補償）」；區塊 status 只用固定詞彙，index.html 的 CSS 依 class 上色。
 * 【概念】三組狀態分屬兩庫：Saga／訂單在 orderdb，預留票在 accountdb；兩庫只靠 Kafka 傳話，
 * 所以「預留票先變、Saga 後跟」（最終一致）。權威定義：SagaStatus／OrderStatus／TccState 三個 enum
 * 與 docs/狀態對照-Saga-TCC-訂單.md；本檔若與其衝突，以 enum 為準。
 * 【使用】app.js：liveSnapshot → buildLanes／buildVerdict；buildTimeline 供時間軸與慢動作重播。
 */

const NUM_ZH = ['一', '二', '三', '四', '五', '六'];

/**
 * 區塊狀態詞彙 → 中文標籤。
 * todo 未到；done 已通過；active 目前（進行中）；warnActive 補償進行中；success 成功終態；
 * warn 補償／失敗終態；branch 在此分岔走向補償；skip 因分岔而略過；dim 終態未走的那一支。
 */
export const STATUS_LEGEND = [
    { status: 'done', zh: '已通過' },
    { status: 'active', zh: '目前' },
    { status: 'success', zh: '成功終態' },
    { status: 'warn', zh: '補償／失敗終態' },
    { status: 'branch', zh: '在此分岔' },
    { status: 'skip', zh: '略過' },
    { status: 'todo', zh: '尚未到達' }
];

const STATUS_TAG = {
    todo: '', done: '已通過', active: '目前', warnActive: '補償中', success: '終態',
    warn: '終態', branch: '在此分岔', skip: '略過', dim: '未走'
};

/** Saga 七個值的中文（SagaStatus）。 */
const SAGA_ZH = {
    STARTED: '已建立',
    ACCOUNT_TRYING: '凍結資金中',
    ACCOUNT_CONFIRMING: '確認扣款中',
    COMPLETED: '交易完成',
    COMPENSATING: '補償中',
    COMPENSATED: '補償完成',
    FAILED: '補償失敗（需人工）'
};

/** 訂單三個值的中文（OrderStatus）。 */
const ORDER_ZH = { PENDING: '待成交', FILLED: '已成交', FAILED: '已失敗（正確收尾）' };

/** 預留票狀態中文；NONE／TRY_FAILED 為前台推導值（帳戶庫「查無票」的兩種語意）。 */
const TCC_ZH = {
    NONE: '尚無預留票',
    TRYING: '已凍結（Try）',
    CONFIRMED: '已扣款（Confirm）',
    CANCELLED: '已退回（Cancel）',
    TRY_FAILED: 'Try 失敗（未凍結）'
};

/** Kafka 訊息型別中文（SagaMessageTypes）；命令由訂單側發件匣寄出，事件由帳戶側直送。 */
const MSG_ZH = {
    RESERVE_FUNDS: '命令：預留資金（Try）',
    CONFIRM_FUNDS: '命令：確認扣款（Confirm）',
    CANCEL_FUNDS: '命令：取消預留（Cancel）',
    FUNDS_RESERVED: '事件：資金已凍結',
    FUNDS_CONFIRMED: '事件：資金已扣款',
    FUNDS_FAILED: '事件：凍結失敗（餘額不足）',
    FUNDS_CANCELLED: '事件：預留已退回'
};

/** Saga 步驟名稱中文（SagaOrchestrator／OrderSagaEventHandler／OrderMarkFailedAction 的 step name）。 */
const STEP_ZH = {
    ORDER_CREATED: '訂單建立（待成交），Saga 啟動',
    RESERVE_COMMANDED: '發件匣登記 RESERVE_FUNDS → Saga 凍結資金中',
    CONFIRM_COMMANDED: '收到 FUNDS_RESERVED → 登記 CONFIRM_FUNDS',
    SAGA_COMPLETED: '收到 FUNDS_CONFIRMED → Saga 完成＋訂單成交',
    ORDER_MARK_FAILED: '補償：Saga 補償完成＋訂單失敗收尾'
};

const COMPENSATION = new Set(['COMPENSATING', 'COMPENSATED', 'FAILED']);
const SAGA_MAIN_RANK = { STARTED: 0, ACCOUNT_TRYING: 1, ACCOUNT_CONFIRMING: 2 };

/** 尚未選擇任何交易時的空快照（三條 lane 全灰）。 */
export const EMPTY_SNAPSHOT = Object.freeze({
    sagaStatus: null, confirmCommanded: false, orderStatus: null, tcc: null
});

/**
 * 【職責】由 API 即時資料組出快照（Dashboard 唯一輸入）。
 * 【概念】帳戶庫「查無票」有兩種意思：Try 還沒執行（NONE）或 Try 失敗根本不寫票（TRY_FAILED）；
 * 以 Saga 是否已進補償來區分。
 * @param {object|null} saga GET /sagas/{id}
 * @param {object|null} order 訂單（orders 列表中對應的一筆）
 * @param {object|null} reservation GET /tcc/reservations/{sagaId}
 * @returns {{sagaStatus:string|null, confirmCommanded:boolean, orderStatus:string|null, tcc:string|null}}
 */
export function liveSnapshot(saga, order, reservation) {
    if (!saga) return { ...EMPTY_SNAPSHOT };
    let tcc = 'NONE';
    if (reservation?.exists) tcc = reservation.state;
    else if (COMPENSATION.has(saga.status)) tcc = 'TRY_FAILED';
    return {
        sagaStatus: saga.status,
        confirmCommanded: (saga.steps || []).some((s) => s.name === 'CONFIRM_COMMANDED'),
        orderStatus: order?.status ?? 'PENDING',
        tcc
    };
}

/** 【技巧】把定義表展開成帶「階段 N」與狀態標籤的區塊。 */
const stage = (def, index, status, override = {}) => ({
    ...def, ...override, no: '階段' + NUM_ZH[index], status, tag: STATUS_TAG[status]
});

const end = (def, index, kind, status, override = {}) => ({
    ...def, ...override,
    no: '階段' + NUM_ZH[index] + (kind === 'ok' ? '・成功路徑' : '・補償路徑'),
    status, tag: STATUS_TAG[status]
});

const toneOf = (code) => {
    if (['COMPLETED', 'FILLED', 'CONFIRMED'].includes(code)) return 'success';
    if (['COMPENSATING', 'COMPENSATED', 'FAILED', 'CANCELLED', 'TRY_FAILED'].includes(code)) return 'warn';
    if (!code) return 'idle';
    return 'active';
};

/**
 * 【職責】Saga 流程 lane：STARTED → ACCOUNT_TRYING → ACCOUNT_CONFIRMING → {COMPLETED｜COMPENSATED}。
 * 【概念】補償可從 ACCOUNT_TRYING（SAGA-002 餘額不足）或 ACCOUNT_CONFIRMING（TCC-002 forceFail）分岔；
 * 以步驟中是否出現 CONFIRM_COMMANDED 判斷分岔點，分岔點之後的線性階段標「略過」。
 * COMPENSATING 在同一 TX 內緊接 COMPENSATED，外部幾乎看不到，故畫在補償終態的說明裡。
 */
function buildSagaLane(snap) {
    const defs = [
        { code: 'STARTED', zh: SAGA_ZH.STARTED, icon: 'bi-flag', desc: '建立 Saga 實例' },
        { code: 'ACCOUNT_TRYING', zh: SAGA_ZH.ACCOUNT_TRYING, icon: 'bi-snow', desc: '已登記 RESERVE_FUNDS，等帳戶 Try' },
        { code: 'ACCOUNT_CONFIRMING', zh: SAGA_ZH.ACCOUNT_CONFIRMING, icon: 'bi-hourglass-split', desc: '已凍結，登記 CONFIRM_FUNDS 等扣款' }
    ];
    const ok = { code: 'COMPLETED', zh: SAGA_ZH.COMPLETED, icon: 'bi-check2-circle', desc: '收到 FUNDS_CONFIRMED' };
    const ng = { code: 'COMPENSATED', zh: SAGA_ZH.COMPENSATED, icon: 'bi-arrow-counterclockwise', desc: '經 COMPENSATING 收尾' };
    const s = snap.sagaStatus;
    const forkIdx = defs.length;
    let stages;
    let fork;

    if (!s) {
        stages = defs.map((d, i) => stage(d, i, 'todo'));
        fork = [end(ok, forkIdx, 'ok', 'todo'), end(ng, forkIdx, 'ng', 'todo')];
    } else if (s in SAGA_MAIN_RANK) {
        const rank = SAGA_MAIN_RANK[s];
        stages = defs.map((d, i) => stage(d, i, i < rank ? 'done' : i === rank ? 'active' : 'todo'));
        fork = [end(ok, forkIdx, 'ok', 'todo'), end(ng, forkIdx, 'ng', 'todo')];
    } else if (s === 'COMPLETED') {
        stages = defs.map((d, i) => stage(d, i, 'done'));
        fork = [end(ok, forkIdx, 'ok', 'success'), end(ng, forkIdx, 'ng', 'dim')];
    } else {
        const branchAt = snap.confirmCommanded ? 2 : 1;
        stages = defs.map((d, i) => stage(d, i, i < branchAt ? 'done' : i === branchAt ? 'branch' : 'skip'));
        let ngStatus = 'warn';
        let ngOverride = {};
        if (s === 'COMPENSATING') {
            ngStatus = 'warnActive';
            ngOverride = { code: 'COMPENSATING', zh: SAGA_ZH.COMPENSATING, desc: '補償動作執行中' };
        } else if (s === 'FAILED') {
            ngOverride = { code: 'FAILED', zh: SAGA_ZH.FAILED, desc: '補償本身失敗（預留狀態）' };
        }
        fork = [end(ok, forkIdx, 'ok', 'dim'), end(ng, forkIdx, 'ng', ngStatus, ngOverride)];
    }

    return {
        key: 'saga',
        title: 'Saga 流程狀態機',
        subtitle: 'orderdb · saga_instances · SagaStatus',
        icon: 'bi-diagram-3',
        current: { zh: s ? SAGA_ZH[s] : '—', code: s ?? '—', tone: toneOf(s) },
        stages,
        fork
    };
}

/**
 * 【職責】訂單結果 lane：PENDING → {FILLED｜FAILED}。
 * 【概念】訂單 FAILED 是「失敗路徑正確收尾」，不是系統出錯；流程細節看 Saga lane。
 */
function buildOrderLane(snap) {
    const defs = [
        { code: 'PENDING', zh: ORDER_ZH.PENDING, icon: 'bi-receipt', desc: 'Saga 未終態前都停在這' }
    ];
    const ok = { code: 'FILLED', zh: ORDER_ZH.FILLED, icon: 'bi-bag-check', desc: '收到 FUNDS_CONFIRMED' };
    const ng = { code: 'FAILED', zh: '已失敗', icon: 'bi-bag-x', desc: '補償時標記；非系統錯誤' };
    const s = snap.orderStatus;
    const forkIdx = defs.length;
    let stages;
    let fork;

    if (!s) {
        stages = [stage(defs[0], 0, 'todo')];
        fork = [end(ok, forkIdx, 'ok', 'todo'), end(ng, forkIdx, 'ng', 'todo')];
    } else if (s === 'PENDING') {
        stages = [stage(defs[0], 0, 'active')];
        fork = [end(ok, forkIdx, 'ok', 'todo'), end(ng, forkIdx, 'ng', 'todo')];
    } else if (s === 'FILLED') {
        stages = [stage(defs[0], 0, 'done')];
        fork = [end(ok, forkIdx, 'ok', 'success'), end(ng, forkIdx, 'ng', 'dim')];
    } else {
        stages = [stage(defs[0], 0, 'done')];
        fork = [end(ok, forkIdx, 'ok', 'dim'), end(ng, forkIdx, 'ng', 'warn')];
    }

    return {
        key: 'order',
        title: '訂單結果狀態機',
        subtitle: 'orderdb · trade_orders · OrderStatus',
        icon: 'bi-receipt-cutoff',
        current: { zh: s ? ORDER_ZH[s] : '—', code: s ?? '—', tone: toneOf(s) },
        stages,
        fork
    };
}

/**
 * 【職責】帳戶 TCC 預留票 lane：（無票）→ TRYING → {CONFIRMED｜CANCELLED}。
 * 【概念】Try 失敗（餘額不足）不寫票：階段二標「在此分岔」並改顯示「Try 失敗（未凍結）」，終態兩支都略過。
 * CANCELLED（這筆錢已退）≠ Saga COMPENSATED（整個流程失敗已收尾）。
 */
function buildTccLane(snap) {
    const defs = [
        { code: '（無票）', zh: TCC_ZH.NONE, icon: 'bi-inbox', desc: '等待 RESERVE_FUNDS 命令' },
        { code: 'TRYING', zh: TCC_ZH.TRYING, icon: 'bi-lock', desc: 'available → frozen，寫入預留票' }
    ];
    const ok = { code: 'CONFIRMED', zh: TCC_ZH.CONFIRMED, icon: 'bi-cash-coin', desc: 'frozen 扣掉，total 下降' };
    const ng = { code: 'CANCELLED', zh: TCC_ZH.CANCELLED, icon: 'bi-unlock', desc: 'frozen 退回 available' };
    const s = snap.tcc;
    const forkIdx = defs.length;
    let stages;
    let fork;

    if (!s) {
        stages = defs.map((d, i) => stage(d, i, 'todo'));
        fork = [end(ok, forkIdx, 'ok', 'todo'), end(ng, forkIdx, 'ng', 'todo')];
    } else if (s === 'NONE') {
        stages = [stage(defs[0], 0, 'active'), stage(defs[1], 1, 'todo')];
        fork = [end(ok, forkIdx, 'ok', 'todo'), end(ng, forkIdx, 'ng', 'todo')];
    } else if (s === 'TRYING') {
        stages = [stage(defs[0], 0, 'done'), stage(defs[1], 1, 'active')];
        fork = [end(ok, forkIdx, 'ok', 'todo'), end(ng, forkIdx, 'ng', 'todo')];
    } else if (s === 'CONFIRMED') {
        stages = defs.map((d, i) => stage(d, i, 'done'));
        fork = [end(ok, forkIdx, 'ok', 'success'), end(ng, forkIdx, 'ng', 'dim')];
    } else if (s === 'CANCELLED') {
        stages = defs.map((d, i) => stage(d, i, 'done'));
        fork = [end(ok, forkIdx, 'ok', 'dim'), end(ng, forkIdx, 'ng', 'warn')];
    } else {
        stages = [
            stage(defs[0], 0, 'done'),
            stage(defs[1], 1, 'branch', { code: '（無票）', zh: TCC_ZH.TRY_FAILED, icon: 'bi-x-octagon', desc: '餘額不足，不寫預留票' })
        ];
        fork = [end(ok, forkIdx, 'ok', 'skip'), end(ng, forkIdx, 'ng', 'skip')];
    }

    return {
        key: 'tcc',
        title: '帳戶 TCC 預留票狀態機',
        subtitle: 'accountdb · tcc_reservations · TccState',
        icon: 'bi-safe',
        current: {
            zh: s ? TCC_ZH[s] : '—',
            code: s === 'NONE' || s === 'TRY_FAILED' ? '（無票）' : (s ?? '—'),
            tone: toneOf(s)
        },
        stages,
        fork
    };
}

/**
 * 【職責】三條 lane（Saga → 訂單 → TCC）。
 * @param {object} snap liveSnapshot 或時間軸某一格的快照
 */
export function buildLanes(snap) {
    return [buildSagaLane(snap), buildOrderLane(snap), buildTccLane(snap)];
}

/**
 * 【職責】Dashboard 頂部一句話總結（同時看三組狀態下結論）。
 * 【概念】進行中若預留票已領先 Saga，明講「最終一致」，避免誤以為卡住。
 */
export function buildVerdict(snap) {
    const { sagaStatus: s, orderStatus: o, tcc: t } = snap;
    if (!s) {
        return { tone: 'idle', icon: 'bi-info-circle', text: '尚未選擇交易：點上方情境按鈕下單，或點下方訂單列查看該筆狀態機。' };
    }
    if (s === 'COMPLETED' && o === 'FILLED') {
        return { tone: 'success', icon: 'bi-check-circle-fill', text: '成功：Saga 交易完成 ＋ 訂單已成交 ＋ 預留票已扣款（SAGA-001）' };
    }
    if (s === 'COMPENSATED' || s === 'FAILED') {
        const money = t === 'CANCELLED'
            ? '凍結資金已退回 available（TCC-002）'
            : 'Try 失敗，資金從未被凍結（SAGA-002）';
        return { tone: 'warn', icon: 'bi-arrow-counterclockwise', text: '補償完成：訂單失敗已正確收尾；' + money };
    }
    if (t === 'TRYING' && s === 'ACCOUNT_TRYING') {
        return { tone: 'active', icon: 'bi-hourglass-split', text: '進行中：預留票已凍結，Saga 尚未收到 FUNDS_RESERVED — 預留票先變、Saga 後跟（最終一致）' };
    }
    if ((t === 'CONFIRMED' || t === 'CANCELLED') && !COMPENSATION.has(s) && s !== 'COMPLETED') {
        return { tone: 'active', icon: 'bi-hourglass-split', text: '進行中：預留票已到終態（' + TCC_ZH[t] + '），Saga 尚在等事件 — 最終一致中' };
    }
    if (t === 'TRY_FAILED') {
        return { tone: 'active', icon: 'bi-hourglass-split', text: '進行中：帳戶 Try 失敗，Saga 即將補償' };
    }
    return { tone: 'active', icon: 'bi-hourglass-split', text: '進行中：Saga ' + SAGA_ZH[s] + '（' + s + '）' };
}

/** 【技巧】每筆歷史紀錄只改自己那一條 lane，套用順序稍有誤差也不會互相覆蓋。 */
function applyEntry(snap, entry) {
    const next = { ...snap };
    switch (entry.name) {
        case 'ORDER_CREATED': Object.assign(next, { sagaStatus: 'STARTED', orderStatus: 'PENDING', tcc: next.tcc ?? 'NONE' }); break;
        case 'RESERVE_COMMANDED': next.sagaStatus = 'ACCOUNT_TRYING'; break;
        case 'CONFIRM_COMMANDED': Object.assign(next, { sagaStatus: 'ACCOUNT_CONFIRMING', confirmCommanded: true }); break;
        case 'SAGA_COMPLETED': Object.assign(next, { sagaStatus: 'COMPLETED', orderStatus: 'FILLED' }); break;
        case 'ORDER_MARK_FAILED': Object.assign(next, { sagaStatus: 'COMPENSATED', orderStatus: 'FAILED' }); break;
        case 'FUNDS_RESERVED': next.tcc = 'TRYING'; break;
        case 'FUNDS_CONFIRMED': next.tcc = 'CONFIRMED'; break;
        case 'FUNDS_CANCELLED': next.tcc = 'CANCELLED'; break;
        case 'FUNDS_FAILED': next.tcc = 'TRY_FAILED'; break;
        default: break;
    }
    return next;
}

const sameSnap = (a, b) => a.sagaStatus === b.sagaStatus && a.orderStatus === b.orderStatus && a.tcc === b.tcc;

/**
 * 【職責】列出兩格快照之間有變動的 lane key（重播時讓該 lane 發光）。
 * @returns {string[]} 'saga'｜'order'｜'tcc'
 */
export function diffLanes(prev, next) {
    const changed = [];
    if (prev.sagaStatus !== next.sagaStatus) changed.push('saga');
    if (prev.orderStatus !== next.orderStatus) changed.push('order');
    if (prev.tcc !== next.tcc) changed.push('tcc');
    return changed;
}

/** 時間軸一格的三組狀態中文摘要。 */
export function summarize(snap) {
    return 'Saga ' + (SAGA_ZH[snap.sagaStatus] ?? '—')
        + '｜訂單 ' + (ORDER_ZH[snap.orderStatus] ?? '—')
        + '｜預留票 ' + (TCC_ZH[snap.tcc] ?? '—');
}

/**
 * 【職責】由「Saga 步驟（orderdb）＋Kafka 軌跡（記憶體）」的時間戳重建狀態變化時間軸。
 * 【技巧】輪詢每 250ms 一次，常常一眨眼就終態；改用伺服器端時間戳重建，慢動作重播才看得到每一格。
 * 最後一格若與即時狀態不同（例如軌跡 ring buffer 已被擠掉），補一格「目前即時狀態」。
 * 【邊界】Kafka 軌跡不持久化、上限 100 筆；舊交易可能只剩 Saga 步驟，TCC 只能看最後即時值。
 * @param {object} saga GET /sagas/{id}（含 steps）
 * @param {Array} events demo state 的 events（新到舊）
 * @param {object} live liveSnapshot
 * @returns {Array<{at:number, offsetMs:number, side:string, sideZh:string, name:string, label:string, snap:object, summary:string}>}
 */
export function buildTimeline(saga, events, live) {
    if (!saga) return [];
    const entries = [];
    for (const st of saga.steps || []) {
        entries.push({ at: Date.parse(st.at), side: 'order', sideZh: '訂單側', name: st.name, label: STEP_ZH[st.name] ?? st.name });
    }
    for (const e of events || []) {
        if (e.sagaId !== saga.sagaId) continue;
        const isCommand = e.type in MSG_ZH && !e.type.startsWith('FUNDS_');
        entries.push({
            at: Date.parse(e.at),
            side: isCommand ? 'kafka' : 'account',
            sideZh: isCommand ? 'Kafka 命令' : '帳戶側 TCC',
            name: e.type,
            label: MSG_ZH[e.type] ?? e.type
        });
    }
    entries.sort((a, b) => a.at - b.at);

    const t0 = entries.length ? entries[0].at : Date.now();
    let snap = { ...EMPTY_SNAPSHOT };
    const frames = entries.map((en) => {
        snap = applyEntry(snap, en);
        return { ...en, offsetMs: en.at - t0, snap: { ...snap }, summary: summarize(snap) };
    });
    if (live.sagaStatus && (!frames.length || !sameSnap(frames[frames.length - 1].snap, live))) {
        const last = frames.length ? frames[frames.length - 1].at : t0;
        frames.push({
            at: last, offsetMs: last - t0, side: 'live', sideZh: '即時', name: 'LIVE',
            label: '目前即時狀態（API 查詢結果）', snap: { ...live }, summary: summarize(live)
        });
    }
    return frames;
}
