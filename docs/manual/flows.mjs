/**
 * 【職責】操作手冊的「劇本」：依畫面使用順序列出每一步要做的瀏覽器操作、要框起來的畫面元素、旁白文字。
 * 【技巧】run(ctx) 只描述「使用者做什麼」，共用動作（還原帳戶、按劇情鈕等終態、定格、hover）在 build-manual.mjs 的 ctx.ui；
 * callouts 的 selector 找不到就讓產生失敗（畫面改版時手冊不會悄悄過期）；
 * 旁白可寫成 (s) => 字串，s＝執行期抓到的值（sagaId、三條 lane 終態、available），避免手冊寫死會漂移的數字。
 * shot：'viewport'（1440×900，標註超出畫面會失敗）、'full'（整頁）或 'element'（只截 el 那一整塊，可比視窗高）；scrollTo＝截圖前捲到該元素；
 * keepScroll＝截圖前不捲回頂端、不移開滑鼠（hover 提示一捲動或滑鼠離開就收起）。
 * 【概念】劇本是唯一真相：截圖、編號紅框、導覽圖、HTML／Markdown 兩份手冊都由它產生；
 * traceKey 指向 src/main/resources/static/code-trace.json，「背後程式」段落直接重用 hover 呼叫鏈的中文說明。
 */

const lane = (key) => `[data-testid="lane-${key}"]`;
const fmt = (r) => (r ? `Saga \`${r.saga}\`／訂單 \`${r.order}\`／預留票 \`${r.tcc}\`` : '（未取得）');

export const meta = {
  title: 'TradingSagaTCC 操作手冊',
  subtitle: '一頁式 Dashboard：下單啟動 Saga → 三條狀態機看結局 → 重播／呼叫鏈／訂單回看（自動截圖產生）',
  prereq: [
    { item: '帳戶', value: 'ACC-001', note: '種子 available 100000；每輪 Demo 前按「還原種子 100000」' },
    { item: '網址', value: 'http://localhost:8093/', note: 'Gradle `bootRun` 後開啟（內嵌 Kafka，不必 Docker）' },
    { item: '自動重播', value: '?autoReplay=0', note: '截圖時關閉「完成後自動重播」，畫面才會停在終態' }
  ]
};

export const chapters = [
  { id: 'start', title: '開始之前：畫面配置' },
  { id: 'saga', title: '三種劇情：一筆交易的三種結局' },
  { id: 'observe', title: '觀察工具：呼叫鏈、時間軸定格、慢動作重播、訂單回看' }
];

/** 導覽圖「三種結局」：劇情 → 對應步驟與終態 key（build-manual 依 state 填實際終態）。 */
export const outcomes = [
  { key: 's001', label: '成功路徑', step: 'saga-001', tone: 'ok' },
  { key: 's002', label: '餘額不足', step: 'saga-002', tone: 'warn' },
  { key: 'tcc002', label: '故意失敗補償', step: 'tcc-002', tone: 'warn' }
];

export const steps = [
  {
    id: 'overview',
    chapter: 'start',
    title: '首頁全貌與導航列',
    shot: 'full',
    async run({ ui, state }) {
      await ui.open();
      state.availReset = await ui.resetAccount();
    },
    callouts: [
      { sel: '[data-testid="main-nav"] ul.nav', text: '導航列：下單操作／狀態機 Dashboard／時間軸／訂單／Kafka 軌跡，點了平滑捲到該區塊' },
      { sel: '[data-testid="nav-manual"]', text: '操作手冊：新分頁開啟本手冊，邊看邊操作' },
      { sel: '[data-testid="nav-ui-smoke"]', text: 'UI Smoke：瀏覽器內跑 DASH-002～004 自動驗收（會真的下單）' },
      { sel: '#console', text: '下單操作：帳戶餘額、還原種子、三顆劇情按鈕' },
      { sel: '#dashboard', text: '三方狀態機 Dashboard：Saga 流程／訂單結果／TCC 資金三條 lane' },
      { sel: '#timeline', text: '狀態變化時間軸：Saga 步驟＋Kafka 訊息重建，點一列可定格' },
      { sel: '#orders', text: '訂單列表：點一列在 Dashboard 回看那筆交易' },
      { sel: '#trail', text: 'Kafka 軌跡：所有指令與事件訊息' }
    ],
    purpose: '整個系統只有一頁：上面下單、中間看三條狀態機、下面看時間軸與訊息。先還原帳戶，讓 available 回到種子 100000。',
    ops: ['開啟 http://localhost:8093/', '按「還原種子 100000」', '需要對照步驟時點導航列「操作手冊」（新分頁）'],
    expect: (s) => `頂部出現「帳戶已還原 100000」，available＝${s.availReset ?? '?'}；Dashboard 總結列顯示「尚未選擇交易」。`,
    cases: ['ACCOUNT-001', 'DASH-001'],
    api: 'POST /api/v1/accounts/ACC-001/reset、GET /api/v1/demo/state',
    traceKey: 'action:reset'
  },
  {
    id: 'saga-001',
    chapter: 'saga',
    title: '成功路徑（SAGA-001）：Try → Confirm',
    shot: 'element',
    el: '#dashboard',
    async run({ ui, state }) {
      state.s001 = await ui.placeScenario('btn-saga-001');
    },
    callouts: [
      { sel: '[data-testid="sm-verdict"]', text: (s) => `總結列（綠）：${fmt(s.s001)}` },
      { sel: lane('saga'), text: 'Saga 流程：STARTED → ACCOUNT_TRYING → ACCOUNT_CONFIRMING → COMPLETED' },
      { sel: lane('order'), text: '訂單結果：PENDING → FILLED（orderdb）' },
      { sel: lane('tcc'), text: '預留票：TRYING → CONFIRMED（accountdb，Try 凍結、Confirm 扣款）' }
    ],
    purpose: '正常買入：帳戶先凍結金額（Try），Saga 收到 FUNDS_RESERVED 後下 Confirm 扣款，訂單成交。兩個資料庫沒有 XA，靠 Kafka 事件最終一致。',
    ops: ['數量 1、價格 10000（預設值）', '按綠色「成功路徑（SAGA-001）」', '等頂部提示變綠'],
    expect: (s) => `${fmt(s.s001)}；available ${s.s001?.availBefore ?? '?'} → ${s.s001?.availAfter ?? '?'}（扣 10000）。`,
    cases: ['SAGA-001', 'OUTBOX-001', 'TCC-001', 'DASH-002'],
    api: 'POST /api/v1/trades（202＋sagaId）→ 輪詢 GET /api/v1/sagas/{sagaId}',
    traceKey: 'action:place'
  },
  {
    id: 'saga-002',
    chapter: 'saga',
    title: '餘額不足（SAGA-002）：Try 失敗 → 補償',
    shot: 'element',
    el: '#dashboard',
    async run({ ui, state }) {
      state.s002 = await ui.placeScenario('btn-saga-002');
    },
    callouts: [
      { sel: '[data-testid="sm-verdict"]', text: (s) => `總結列（橘）：${fmt(s.s002)}` },
      { sel: lane('saga'), text: 'Saga 走補償分岔：ACCOUNT_TRYING 之後 → COMPENSATING → COMPENSATED' },
      { sel: lane('tcc'), text: '預留票：餘額不足，Try 直接失敗、沒有凍結任何金額' }
    ],
    purpose: '用 999999 的價格下單：帳戶 Try 失敗回 FUNDS_FAILED，Saga 把訂單標記失敗收尾。補償完成是「正確結局」，不是系統錯誤。',
    ops: ['按黃色「餘額不足（SAGA-002）」（自動用 1×999999，結束後價格還原 10000）', '等頂部提示變橘'],
    expect: (s) => `${fmt(s.s002)}；available 維持 ${s.s002?.availAfter ?? '?'}（沒有被扣）。`,
    cases: ['SAGA-002', 'TCC-001', 'DASH-002'],
    api: 'POST /api/v1/trades → Kafka FUNDS_FAILED → GET /api/v1/sagas/{sagaId}'
  },
  {
    id: 'tcc-002',
    chapter: 'saga',
    title: '故意失敗補償（TCC-002）：Try → Cancel',
    shot: 'element',
    el: '#dashboard',
    async run({ ui, state }) {
      state.tcc002 = await ui.placeScenario('btn-tcc-002');
    },
    callouts: [
      { sel: '[data-testid="sm-verdict"]', text: (s) => `總結列（橘）：${fmt(s.tcc002)}` },
      { sel: lane('order'), text: '訂單結果：PENDING → FAILED' },
      { sel: lane('tcc'), text: '預留票：TRYING → CANCELLED（凍結的錢退回 available）' }
    ],
    purpose: 'forceFail=true：帳戶 Try 成功凍結後故意 Cancel，示範 TCC 的「取消」把凍結金額退回。',
    ops: ['按紅色「故意失敗補償（TCC-002）」', '等頂部提示變橘'],
    expect: (s) => `${fmt(s.tcc002)}；available 回到 ${s.tcc002?.availAfter ?? '?'}（Try 凍結後已退回）。`,
    cases: ['TCC-002', 'TCC-001', 'DASH-002'],
    api: 'POST /api/v1/trades（forceFail=true）→ Kafka FUNDS_CANCELLED'
  },
  {
    id: 'hover-trace',
    chapter: 'observe',
    title: '滑過狀態區塊看程式進入點（hover 呼叫鏈）',
    shot: 'viewport',
    keepScroll: true,
    async run({ ui }) {
      await ui.hover(`${lane('saga')} .sm-stage[data-trace="saga:COMPENSATED"]`, 'saga:COMPENSATED');
    },
    callouts: [
      { sel: `${lane('saga')} .sm-stage[data-trace="saga:COMPENSATED"]`, text: '滑鼠停在「補償完成」區塊（按鈕、時間軸列也都可以）' },
      { sel: '[data-testid="trace-tip"]', text: '呼叫鏈：入口（Kafka topic）→ Listener → Handler → Service → 方法，每格附中文說明' },
      { sel: '[data-testid="toggle-trace"]', text: '「顯示呼叫鏈」開關：關掉後滑過不再跳提示' }
    ],
    purpose: '想知道「是哪段程式把狀態改成這樣」：滑過區塊即可看到進入點與方法鏈，資料來自 code-trace.json（測試保證與程式碼一致）。',
    ops: ['確認 Dashboard 右上「顯示呼叫鏈」為開', '滑鼠停在任一狀態區塊、劇情按鈕或時間軸列', '移開滑鼠或捲動頁面，提示自動收起'],
    expect: '出現深色提示框，第一行是入口（HTTP／Kafka／排程），下面依序列出 Controller／Listener → Service → 方法。',
    cases: ['DASH-004'],
    api: 'GET /code-trace.json',
    traceKey: 'saga:COMPENSATED'
  },
  {
    id: 'timeline-freeze',
    chapter: 'observe',
    title: '時間軸：點一列定格',
    shot: 'viewport',
    scrollTo: '#timeline',
    async run({ ui, state }) {
      state.frozen = await ui.freezeAt(1);
    },
    callouts: [
      { sel: 'tr.tl-current', text: (s) => `被點的列（第 ${s.frozen?.no ?? '?'} 格）：${s.frozen?.label ?? ''}` },
      { sel: '#timeline thead', text: '欄位：相對時間、來源（訂單／Kafka／帳戶）、發生什麼、當下三組狀態' }
    ],
    purpose: '時間軸把 Saga 步驟與 Kafka 訊息按時間排好；點一列，Dashboard 就停在「那一刻」三條狀態機的樣子。',
    ops: ['在 Dashboard 看著一筆交易（預設是最後一筆）', '時間軸點任一列'],
    expect: (s) => `該列反白；Dashboard 上方出現「定格 第 ${s.frozen?.no ?? '?'}／${s.frozen?.total ?? '?'} 格」橫幅。`,
    cases: ['DASH-003'],
    api: '—（前端用 GET /api/v1/demo/state 的 Kafka 軌跡重建）'
  },
  {
    id: 'replay',
    chapter: 'observe',
    title: '定格橫幅、慢動作重播、回到即時',
    shot: 'viewport',
    scrollTo: '#dashboard',
    async run() {},
    callouts: [
      { sel: '[data-testid="replay-banner"]', text: '定格橫幅：目前停在第幾格、相對時間、那一格發生什麼' },
      { sel: '[data-testid="btn-replay"]', text: '慢動作重播：從第 1 格逐格播放（旁邊下拉選每格秒數）' },
      { sel: '[data-testid="btn-live"]', text: '回到即時：離開定格，顯示最新狀態' },
      { sel: '#autoReplay', text: '完成後自動重播：開著時，每次下單到終態會自動播一遍' }
    ],
    purpose: '重播讓你看清楚「預留票先變、Saga／訂單後跟上」的順序：兩個資料庫靠事件追上彼此。',
    ops: ['按「慢動作重播」觀察三條 lane 逐格亮起', '按「回到即時」結束定格'],
    expect: '重播時變動的 lane 會發藍光；播完自動回到即時。',
    cases: ['DASH-003'],
    api: '—（純前端）'
  },
  {
    id: 'inspect-order',
    chapter: 'observe',
    title: '訂單列表：回看過去的交易',
    shot: 'full',
    async run({ ui, state }) {
      await ui.inspectOrder(state.s001.sagaId);
    },
    callouts: [
      { sel: 'tr[data-testid="order-row"].table-active', text: (s) => `點的是第一筆成功交易（sagaId ${s.s001?.sagaId?.slice(0, 8) ?? '?'}…），列反白` },
      { sel: '[data-testid="sm-verdict"]', text: 'Dashboard 切回那筆交易：總結列又變綠' },
      { sel: '#trail', text: 'Kafka 軌跡：RESERVE_FUNDS／FUNDS_RESERVED／CONFIRM_FUNDS… 依序累積' }
    ],
    purpose: '每一筆交易都能回看：點訂單列，Dashboard、時間軸、文字明細都切到那筆 Saga。',
    ops: ['在「訂單」表點任一列', '頁面自動捲回 Dashboard'],
    expect: (s) => `Dashboard 顯示 ${fmt(s.s001)}；時間軸換成那筆的步驟。`,
    cases: ['DASH-003', 'TRADE-001'],
    api: 'GET /api/v1/sagas/{sagaId}、GET /api/v1/tcc/reservations/{sagaId}'
  }
];
