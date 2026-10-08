/**
 * TradingSagaTCC 靜態前台：同埠呼叫 /api/v1，輪詢 Saga 終態，並以圖像化 Dashboard 呈現三組狀態機。
 * 【技巧】終態判斷必須同時看 Saga＋訂單；逾時不得誤報「訂單 FAILED」。
 * 狀態推導全在 dashboard.js（純函式，可單元測試）；本檔只負責拉資料、輪詢、重播與導航。
 * 【使用】由 index.html 以 module 載入；按鈕綁 place(false)／place(true)／placeInsufficient。
 * 網址加 ?autoReplay=0 可關閉「完成後自動慢動作重播」（UI Smoke 用來直接斷言終態）。
 * 滑鼠停在帶 data-trace 的區塊／按鈕／時間軸列上，顯示呼叫鏈 tooltip（DASH-004，資料 /code-trace.json）。
 */
import { createApp, ref, computed, onMounted } from 'https://unpkg.com/vue@3/dist/vue.esm-browser.js';
import {
    liveSnapshot, buildLanes, buildVerdict, buildTimeline, diffLanes, buildTrace, STATUS_LEGEND, EMPTY_SNAPSHOT
} from './dashboard.js';

const API = '/api/v1';
const POLL_MAX = 80;
const POLL_MS = 250;
const SECTIONS = ['console', 'dashboard', 'timeline', 'orders', 'trail'];

/**
 * 【職責】單條狀態機 lane：線性階段（箭頭區塊）＋終態分岔（成功／補償上下兩格）。
 * 【使用】<state-lane :lane="lane" :changed="..."/>；lane 由 dashboard.js buildLanes 產生。
 */
const StateLane = {
    props: { lane: { type: Object, required: true }, changed: { type: Boolean, default: false } },
    template: `
      <div class="sm-lane" :id="'lane-' + lane.key" :class="{ 'sm-lane-changed': changed }"
           :data-testid="'lane-' + lane.key" :data-state="lane.raw ?? ''">
        <div class="sm-lane-head">
          <div>
            <i class="bi me-1" :class="lane.icon"></i><strong>{{ lane.title }}</strong>
            <span class="muted small ms-2">{{ lane.subtitle }}</span>
          </div>
          <div class="small">目前：
            <span class="sm-current" :class="'tone-' + lane.current.tone" data-testid="lane-current">
              {{ lane.current.zh }} <code>{{ lane.current.code }}</code>
            </span>
          </div>
        </div>
        <div class="sm-track">
          <div v-for="st in lane.stages" :key="st.no" class="sm-stage" :class="'st-' + st.status"
               :data-status="st.status" :data-code="st.code" :data-trace="st.trace">
            <div class="sm-no">{{ st.no }}<span v-if="st.tag" class="sm-tag">{{ st.tag }}</span></div>
            <div class="sm-zh"><i class="bi me-1" :class="st.icon"></i>{{ st.zh }}</div>
            <div class="sm-code">{{ st.code }}</div>
            <div class="sm-desc">{{ st.desc }}</div>
          </div>
          <div class="sm-fork">
            <div v-for="op in lane.fork" :key="op.no" class="sm-stage sm-end" :class="'st-' + op.status"
                 :data-status="op.status" :data-code="op.code" :data-trace="op.trace">
              <div class="sm-no">{{ op.no }}<span v-if="op.tag" class="sm-tag">{{ op.tag }}</span></div>
              <div class="sm-zh"><i class="bi me-1" :class="op.icon"></i>{{ op.zh }} <code class="sm-code-inline">{{ op.code }}</code></div>
              <div class="sm-desc">{{ op.desc }}</div>
            </div>
          </div>
        </div>
      </div>`
};

createApp({
    components: { StateLane },
    setup() {
        const loading = ref(false);
        const message = ref('');
        const messageType = ref('alert-info');
        const account = ref({});
        const orders = ref([]);
        const events = ref([]);
        const currentSaga = ref(null);
        const currentOrder = ref(null);
        const currentTcc = ref(null);
        const form = ref({ quantity: 1, price: 10000 });

        /** 重播：replayIndex＝null 表示看即時；數字表示正在看時間軸第幾格。 */
        const replayIndex = ref(null);
        const replaying = ref(false);
        const autoReplay = ref(new URLSearchParams(location.search).get('autoReplay') !== '0');
        const replaySpeed = ref(1000);
        let replayToken = 0;

        const activeSection = ref('dashboard');

        /** DASH-004 呼叫鏈：traces＝code-trace.json；tip＝目前顯示的 tooltip（null 為隱藏）。 */
        const traces = ref(null);
        const showTrace = ref(true);
        const tip = ref(null);
        let hideTimer = null;

        /** 【使用】依 Saga 終態選 badge 色：COMPLETED 綠；COMPENSATED／FAILED 黃。 */
        const sagaBadge = computed(() => {
            const s = currentSaga.value?.status;
            if (s === 'COMPLETED') return 'bg-success';
            if (s === 'COMPENSATED' || s === 'FAILED') return 'bg-warning text-dark';
            return 'bg-info text-dark';
        });

        /** 【使用】依訂單終態選 badge：FILLED 綠；FAILED 黃。 */
        const orderBadge = computed(() => {
            const s = currentOrder.value?.status;
            if (s === 'FILLED') return 'bg-success';
            if (s === 'FAILED') return 'bg-warning text-dark';
            return 'bg-info text-dark';
        });

        /** 【職責】即時快照（三組狀態）→ 時間軸 → 目前要畫的那一格。 */
        const liveSnap = computed(() => liveSnapshot(currentSaga.value, currentOrder.value, currentTcc.value));
        const timeline = computed(() => buildTimeline(currentSaga.value, events.value, liveSnap.value));
        const viewSnap = computed(() => {
            const i = replayIndex.value;
            return i !== null && timeline.value[i] ? timeline.value[i].snap : liveSnap.value;
        });
        const lanes = computed(() => buildLanes(viewSnap.value).map((lane) => ({
            ...lane,
            raw: { saga: viewSnap.value.sagaStatus, order: viewSnap.value.orderStatus, tcc: viewSnap.value.tcc }[lane.key]
        })));
        const verdict = computed(() => buildVerdict(viewSnap.value));

        /** 【使用】重播時，本格相對上一格有變動的 lane 發光。 */
        const changedLanes = computed(() => {
            const i = replayIndex.value;
            if (i === null || !timeline.value[i]) return [];
            const prev = i > 0 ? timeline.value[i - 1].snap : EMPTY_SNAPSHOT;
            return diffLanes(prev, timeline.value[i].snap);
        });

        /** 【使用】頂部提示；type 為 Bootstrap alert-*。 */
        const toast = (text, type = 'alert-info') => {
            message.value = text;
            messageType.value = type;
        };

        /** 【使用】Kafka 軌跡列上色：含 FAIL／CANCEL → 警示色。 */
        const eventClass = (type) => {
            if (!type) return '';
            if (type.includes('FAIL') || type.includes('CANCEL')) return 'event-fail';
            return 'event-ok';
        };

        /** 【使用】訂單表 status 欄 badge class。 */
        const orderBadgeClass = (status) => {
            if (status === 'FILLED') return 'bg-success';
            if (status === 'FAILED') return 'bg-warning text-dark';
            return 'bg-secondary';
        };

        /** 【使用】時間軸偏移：+0 ms／+1.234 s。 */
        const fmtOffset = (ms) => (ms < 1000 ? '+' + ms + ' ms' : '+' + (ms / 1000).toFixed(3) + ' s');

        /** 【使用】依 orderId 從目前 orders 列表找一筆。 */
        const findOrder = (orderId) => {
            if (!orderId) return null;
            return orders.value.find((o) => o.orderId === orderId) || null;
        };

        /** 【使用】把 currentOrder 對齊 currentSaga.orderId。 */
        const syncCurrentOrder = () => {
            const orderId = currentSaga.value?.orderId;
            currentOrder.value = findOrder(orderId);
        };

        /**
         * 【職責】拉取 Demo 聚合狀態（帳戶＋訂單＋事件）。
         * 【使用】刷新按鈕／輪詢每步／重置後呼叫。
         */
        const loadState = async () => {
            const res = await fetch(`${API}/demo/state`);
            if (!res.ok) {
                throw new Error('demo state HTTP ' + res.status);
            }
            const data = await res.json();
            account.value = data.account || {};
            orders.value = data.orders || [];
            events.value = data.events || [];
            syncCurrentOrder();
        };

        /**
         * 【職責】拉取帳戶庫的預留票（TCC lane 的資料來源）。
         * 【概念】一律 200；exists=false 代表「無票」，不是錯誤。
         * @param {string} sagaId
         */
        const loadTcc = async (sagaId) => {
            const res = await fetch(`${API}/tcc/reservations/${sagaId}`);
            if (res.ok) {
                currentTcc.value = await res.json();
            }
        };

        /** 【使用】手動「重新整理」；若正在看某筆 Saga，一併刷新它與預留票。 */
        const refresh = async () => {
            loading.value = true;
            try {
                const sagaId = currentSaga.value?.sagaId;
                if (sagaId) {
                    const res = await fetch(`${API}/sagas/${sagaId}`);
                    if (res.ok) currentSaga.value = await res.json();
                    await loadTcc(sagaId);
                }
                await loadState();
            } catch (e) {
                toast(String(e), 'alert-danger');
            } finally {
                loading.value = false;
            }
        };

        /** 【使用】停止重播並回到即時畫面。 */
        const backToLive = () => {
            replayToken += 1;
            replaying.value = false;
            replayIndex.value = null;
        };

        /**
         * 【職責】慢動作重播時間軸：每格停 replaySpeed 毫秒，讓三條 lane 逐格亮起。
         * 【技巧】以 token 取消：新的重播／下單／跳格都會讓舊迴圈自行結束。
         */
        const startReplay = async () => {
            if (timeline.value.length === 0) return;
            replayToken += 1;
            const token = replayToken;
            replaying.value = true;
            for (let i = 0; i < timeline.value.length; i += 1) {
                if (token !== replayToken) return;
                replayIndex.value = i;
                await new Promise((r) => setTimeout(r, replaySpeed.value));
            }
            if (token === replayToken) backToLive();
        };

        /** 【使用】點時間軸某一列 → 停止重播並定格在該格。 */
        const jumpTo = (i) => {
            replayToken += 1;
            replaying.value = false;
            replayIndex.value = i;
        };

        /** 【使用】平滑捲動到指定區塊（導航列／lane 快捷鍵）。 */
        const scrollTo = (id) => {
            document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
        };

        /**
         * 【職責】輪詢 Saga 直到終態或逾時；每輪同時刷新預留票與 Demo 狀態。
         * 【使用】place 成功拿到 sagaId 後立刻呼叫。
         * @param {string} sagaId
         * @returns {Promise<'COMPLETED'|'COMPENSATED'|'FAILED'|'TIMEOUT'>}
         */
        const pollSaga = async (sagaId) => {
            for (let i = 0; i < POLL_MAX; i += 1) {
                const res = await fetch(`${API}/sagas/${sagaId}`);
                if (res.ok) {
                    currentSaga.value = await res.json();
                    await loadTcc(sagaId);
                    await loadState();
                    const status = currentSaga.value.status;
                    if (status === 'COMPLETED' || status === 'COMPENSATED' || status === 'FAILED') {
                        return status;
                    }
                }
                await new Promise((r) => setTimeout(r, POLL_MS));
            }
            return 'TIMEOUT';
        };

        /**
         * 【職責】依終態＋訂單狀態顯示正確提示（避免逾時誤報 FAILED）。
         * 【使用】pollSaga 回傳後呼叫。
         * @param {string} end pollSaga 結果
         */
        const reportOutcome = (end) => {
            const orderStatus = currentOrder.value?.status;
            const available = account.value?.available;
            if (end === 'COMPLETED' && orderStatus === 'FILLED') {
                toast('成功：Saga COMPLETED／訂單 FILLED，帳戶已扣款（available=' + available + '）', 'alert-success');
                return;
            }
            if (end === 'COMPLETED') {
                toast('Saga COMPLETED，但訂單狀態為 ' + (orderStatus || '—') + '（請重新整理）', 'alert-warning');
                return;
            }
            if (end === 'COMPENSATED' || end === 'FAILED') {
                toast('補償完成：Saga ' + end + '／訂單 ' + (orderStatus || 'FAILED') + '，帳戶應已還原（available=' + available + '）', 'alert-warning');
                return;
            }
            if (end === 'TIMEOUT') {
                toast('輪詢逾時：Saga 尚未終態（目前 ' + (currentSaga.value?.status || '—') + '）。請重新整理，勿視為失敗。', 'alert-warning');
                return;
            }
            toast('未知結果：Saga=' + end + ' 訂單=' + (orderStatus || '—'), 'alert-danger');
        };

        /**
         * 【職責】下單啟動 Saga 並輪詢結果；終態後可自動慢動作重播。
         * 【使用】成功路徑 place(false)；TCC-002 place(true)。
         * @param {boolean} forceFail 是否故意補償
         */
        const place = async (forceFail) => {
            backToLive();
            loading.value = true;
            message.value = '';
            currentOrder.value = null;
            currentTcc.value = null;
            let end = null;
            try {
                const body = {
                    accountId: 'ACC-001',
                    symbol: 'BTCUSDT',
                    side: 'BUY',
                    quantity: form.value.quantity,
                    price: form.value.price,
                    forceFail: !!forceFail
                };
                const res = await fetch(`${API}/trades`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const data = await res.json();
                if (res.status !== 202) {
                    toast(data.message || '下單失敗', 'alert-danger');
                    return;
                }
                toast('Saga 已啟動 ' + data.sagaId.slice(0, 8) + '… 輪詢中', 'alert-info');
                end = await pollSaga(data.sagaId);
                reportOutcome(end);
            } catch (e) {
                toast(String(e), 'alert-danger');
            } finally {
                loading.value = false;
            }
            if (end && end !== 'TIMEOUT' && autoReplay.value) {
                startReplay();
            }
        };

        /**
         * 【職責】SAGA-002：暫時改價 999999 走餘額不足。
         * 【使用】「餘額不足」按鈕；結束後還原 price=10000。
         */
        const placeInsufficient = async () => {
            form.value.quantity = 1;
            form.value.price = 999999;
            await place(false);
            form.value.price = 10000;
        };

        /**
         * 【職責】點訂單列 → 在 Dashboard 檢視該筆的三組狀態機（導航到過去的交易）。
         * @param {object} order 訂單列
         */
        const inspectOrder = async (order) => {
            backToLive();
            try {
                const res = await fetch(`${API}/sagas/${order.sagaId}`);
                if (!res.ok) {
                    toast('查無 Saga ' + order.sagaId, 'alert-danger');
                    return;
                }
                currentSaga.value = await res.json();
                await loadTcc(order.sagaId);
                syncCurrentOrder();
                scrollTo('dashboard');
            } catch (e) {
                toast(String(e), 'alert-danger');
            }
        };

        /**
         * 【職責】還原種子帳戶 100000。
         * 【使用】「還原種子」；每輪 Demo 前建議先按。
         */
        const resetAccount = async () => {
            loading.value = true;
            try {
                const res = await fetch(`${API}/accounts/ACC-001/reset`, { method: 'POST' });
                if (!res.ok) {
                    toast('重置失敗', 'alert-danger');
                    return;
                }
                await loadState();
                toast('帳戶已還原 100000', 'alert-success');
            } finally {
                loading.value = false;
            }
        };

        /** 【技巧】IntersectionObserver 追蹤目前捲到哪個區塊，讓導航列高亮對應項目。 */
        const watchSections = () => {
            if (!('IntersectionObserver' in window)) return;
            const io = new IntersectionObserver((entries) => {
                for (const en of entries) {
                    if (en.isIntersecting) activeSection.value = en.target.id;
                }
            }, { rootMargin: '-35% 0px -60% 0px' });
            SECTIONS.forEach((id) => {
                const el = document.getElementById(id);
                if (el) io.observe(el);
            });
        };

        /** 【使用】滑鼠移進 tooltip 本身時取消隱藏，方便選取／複製方法名。 */
        const keepTip = () => clearTimeout(hideTimer);

        /** 【技巧】延遲 150ms 隱藏：滑鼠從區塊移到 tooltip 的途中不會閃掉。 */
        const hideTip = () => {
            clearTimeout(hideTimer);
            hideTimer = setTimeout(() => { tip.value = null; }, 150);
        };

        /**
         * 【職責】把 tooltip 定位在目標元素下方；下方空間不足時改放上方。
         * @param {Element} el 帶 data-trace 的元素
         */
        const showTipFor = (el) => {
            const trace = buildTrace(traces.value, el.dataset.trace);
            if (!trace) return;
            clearTimeout(hideTimer);
            const r = el.getBoundingClientRect();
            const width = Math.min(440, window.innerWidth - 16);
            const above = r.bottom + 230 > window.innerHeight && r.top > 230;
            tip.value = {
                trace,
                above,
                x: Math.max(8, Math.min(r.left, window.innerWidth - width - 8)),
                y: above ? r.top - 6 : r.bottom + 6
            };
        };

        /**
         * 【技巧】事件委派：整頁只掛一組 mouseover／mouseout，找最近的 [data-trace] 祖先；
         * lane 區塊、按鈕、時間軸列都是 v-for 動態產生，不必逐一綁事件。
         */
        const watchTraceHover = () => {
            document.addEventListener('mouseover', (e) => {
                if (!showTrace.value) {
                    tip.value = null;
                    return;
                }
                const el = e.target.closest?.('[data-trace]');
                if (el) showTipFor(el);
            });
            document.addEventListener('mouseout', (e) => {
                const el = e.target.closest?.('[data-trace]');
                if (el && !el.contains(e.relatedTarget)) hideTip();
            });
            // tooltip 是 position:fixed；頁面捲動（含 smooth scroll）時目標已移走，直接收起避免指錯列
            window.addEventListener('scroll', () => { tip.value = null; }, { passive: true });
        };

        /** 【概念】對照表載入失敗只影響 tooltip，不影響下單與狀態機；故吞錯不 toast。 */
        const loadTraces = async () => {
            try {
                const res = await fetch('/code-trace.json');
                if (res.ok) traces.value = (await res.json()).traces;
            } catch {
                traces.value = null;
            }
        };

        onMounted(() => {
            refresh();
            watchSections();
            loadTraces();
            watchTraceHover();
        });

        return {
            loading, message, messageType, account, orders, events, currentSaga, currentOrder, currentTcc, form,
            sagaBadge, orderBadge, orderBadgeClass, refresh, place, placeInsufficient, resetAccount, eventClass,
            lanes, verdict, timeline, replayIndex, replaying, autoReplay, replaySpeed, changedLanes,
            startReplay, backToLive, jumpTo, inspectOrder, scrollTo, fmtOffset, activeSection,
            showTrace, tip, keepTip, hideTip,
            legend: STATUS_LEGEND
        };
    }
}).mount('#app');
