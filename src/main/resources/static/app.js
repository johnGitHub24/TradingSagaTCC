/**
 * TradingSagaTCC 靜態前台：同埠呼叫 /api/v1，輪詢 Saga 終態。
 * 【技巧】終態判斷必須同時看 Saga＋訂單；逾時不得誤報「訂單 FAILED」。
 * 【使用】由 index.html 以 module 載入；按鈕綁 place(false)／place(true)／placeInsufficient。
 */
import { createApp, ref, computed, onMounted } from 'https://unpkg.com/vue@3/dist/vue.esm-browser.js';

const API = '/api/v1';
const POLL_MAX = 80;
const POLL_MS = 250;

createApp({
    setup() {
        const loading = ref(false);
        const message = ref('');
        const messageType = ref('alert-info');
        const account = ref({});
        const orders = ref([]);
        const events = ref([]);
        const currentSaga = ref(null);
        const currentOrder = ref(null);
        const form = ref({ quantity: 1, price: 10000 });

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

        /** 【使用】手動「重新整理」。 */
        const refresh = async () => {
            loading.value = true;
            try {
                await loadState();
            } catch (e) {
                toast(String(e), 'alert-danger');
            } finally {
                loading.value = false;
            }
        };

        /**
         * 【職責】輪詢 Saga 直到終態或逾時。
         * 【使用】place 成功拿到 sagaId 後立刻呼叫。
         * @param {string} sagaId
         * @returns {Promise<'COMPLETED'|'COMPENSATED'|'FAILED'|'TIMEOUT'>}
         */
        const pollSaga = async (sagaId) => {
            for (let i = 0; i < POLL_MAX; i += 1) {
                const res = await fetch(`${API}/sagas/${sagaId}`);
                if (res.ok) {
                    currentSaga.value = await res.json();
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
         * 【職責】下單啟動 Saga 並輪詢結果。
         * 【使用】成功路徑 place(false)；TCC-002 place(true)。
         * @param {boolean} forceFail 是否故意補償
         */
        const place = async (forceFail) => {
            loading.value = true;
            message.value = '';
            currentOrder.value = null;
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
                const end = await pollSaga(data.sagaId);
                reportOutcome(end);
            } catch (e) {
                toast(String(e), 'alert-danger');
            } finally {
                loading.value = false;
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
         * 【職責】還原種子帳戶 100000。
         * 【使用】左側「還原種子」；每輪 Demo 前建議先按。
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

        onMounted(() => {
            refresh();
        });

        return {
            loading, message, messageType, account, orders, events, currentSaga, currentOrder, form,
            sagaBadge, orderBadge, orderBadgeClass, refresh, place, placeInsufficient, resetAccount, eventClass
        };
    }
}).mount('#app');
