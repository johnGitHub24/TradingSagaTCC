/**
 * TradingSagaTCC 靜態前台：同埠呼叫 /api/v1，輪詢 Saga 終態。
 */
import { createApp, ref, computed, onMounted } from 'https://unpkg.com/vue@3/dist/vue.esm-browser.js';

const API = '/api/v1';

createApp({
    setup() {
        const loading = ref(false);
        const message = ref('');
        const messageType = ref('alert-info');
        const account = ref({});
        const orders = ref([]);
        const events = ref([]);
        const currentSaga = ref(null);
        const form = ref({ quantity: 1, price: 10000 });
        let pollTimer = null;

        const sagaBadge = computed(() => {
            const s = currentSaga.value?.status;
            if (s === 'COMPLETED') return 'bg-success';
            if (s === 'COMPENSATED' || s === 'FAILED') return 'bg-warning text-dark';
            return 'bg-info text-dark';
        });

        const toast = (text, type = 'alert-info') => {
            message.value = text;
            messageType.value = type;
        };

        const eventClass = (type) => {
            if (!type) return '';
            if (type.includes('FAIL') || type.includes('CANCEL')) return 'event-fail';
            return 'event-ok';
        };

        const loadState = async () => {
            const res = await fetch(`${API}/demo/state`);
            if (!res.ok) {
                throw new Error('demo state HTTP ' + res.status);
            }
            const data = await res.json();
            account.value = data.account || {};
            orders.value = data.orders || [];
            events.value = data.events || [];
        };

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

        const pollSaga = async (sagaId) => {
            for (let i = 0; i < 40; i += 1) {
                const res = await fetch(`${API}/sagas/${sagaId}`);
                if (res.ok) {
                    currentSaga.value = await res.json();
                    await loadState();
                    const status = currentSaga.value.status;
                    if (status === 'COMPLETED' || status === 'COMPENSATED' || status === 'FAILED') {
                        return;
                    }
                }
                await new Promise((r) => setTimeout(r, 250));
            }
            toast('輪詢逾時：可按重新整理，或看 Kafka 軌跡', 'alert-warning');
        };

        const place = async (forceFail) => {
            loading.value = true;
            message.value = '';
            try {
                const body = {
                    accountId: 'ACC-001',
                    symbol: 'BTCUSDT',
                    side: 'BUY',
                    quantity: form.value.quantity,
                    price: form.value.price,
                    forceFail
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
                await pollSaga(data.sagaId);
                const end = currentSaga.value?.status;
                if (end === 'COMPLETED') {
                    toast('成功：訂單 FILLED，帳戶已扣款', 'alert-success');
                } else {
                    toast('補償完成：訂單 FAILED，帳戶應已還原', 'alert-warning');
                }
            } catch (e) {
                toast(String(e), 'alert-danger');
            } finally {
                loading.value = false;
            }
        };

        const placeInsufficient = async () => {
            form.value.quantity = 1;
            form.value.price = 999999;
            await place(false);
            form.value.price = 10000;
        };

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
            loading, message, messageType, account, orders, events, currentSaga, form,
            sagaBadge, refresh, place, placeInsufficient, resetAccount, eventClass
        };
    }
}).mount('#app');
