package com.trading.saga.order.dto;

import com.trading.saga.account.dto.AccountResponse;
import com.trading.saga.messaging.EventLogEntry;

import java.util.List;

/**
 * 【職責】前台一次拉取帳戶／訂單／Kafka 軌跡（{@code GET /api/v1/demo/state} 的回應）。
 * 【技巧】純組合型 record，沒有工廠方法；由 {@code DemoController.state} 直接 new，三個欄位各自來自不同 Service。
 * 【概念】三份資料來源不同：帳戶來自 accountdb、訂單來自 orderdb、事件來自記憶體 ring buffer。
 * 它們是分別查詢後拼起來的，<b>不是</b>同一時間點的一致快照（兩庫無 XA），偶爾看到「預留票已變、訂單還 PENDING」屬正常的最終一致。
 * 【使用】前台 {@code app.js} 的 {@code loadState}：刷新按鈕、輪詢每一步、重置後都會呼叫。
 * 【邊界】只服務 Demo 面板；正式 API 請分別查 {@code /accounts}、{@code /trades}、{@code /events}。
 *
 * @param account 種子帳戶 {@code ACC-001} 的 available／frozen／total
 * @param orders  全部訂單，新到舊
 * @param events  最近送出的 Kafka 訊息軌跡（最多 100 筆，新到舊；重啟即清空）
 */
public record DemoStateResponse(
        AccountResponse account,
        List<TradeResponse> orders,
        List<EventLogEntry> events
) {
}
