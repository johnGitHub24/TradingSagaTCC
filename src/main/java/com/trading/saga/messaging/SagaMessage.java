package com.trading.saga.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 【職責】跨庫 Saga 的 Kafka／Outbox 信封（命令與事件同一形狀）。
 * 【技巧】Jackson 無型別標頭也可反序列化；key 用 sagaId。
 * {@code application.yml} 關閉 {@code spring.json.add.type.headers}／{@code use.type.headers}，
 * 並以 {@code spring.json.value.default.type} 指定本 record，所以 topic 上是純 JSON、不綁 Java 類名。
 * Outbox 落庫時也是同一份 JSON（{@code outbox_events.payload}），Relay 讀回後原樣送出。
 * 【概念】訂單庫只產 command；帳戶庫只產 event。兩邊都不直寫對方的表。
 * 命令與事件共用一種形狀，差別只在 {@link #type()}（合法值見 {@link SagaMessageTypes}）；
 * 帳戶側回覆事件時會把命令的 sagaId／orderId／accountId／amount／symbol／forceFail 原樣帶回。
 * 【使用】建立一律走 {@link #of}：{@code SagaOrchestrator.start}（RESERVE_FUNDS）、
 * {@code OrderSagaEventHandler.onReserved}（CONFIRM_FUNDS）、{@code AccountCommandHandler.publish}（FUNDS_* 事件）。
 * 【邊界】messageId 目前只作識別與除錯，接收端<b>不</b>以它去重；冪等靠 sagaId 對應的 Saga 狀態／預留票狀態。
 *
 * @param messageId  每則訊息唯一 id（UUID）；同一 Outbox 列重送時 messageId 不變（payload 原樣），可用來辨識重複投遞
 * @param sagaId     流程 id；Kafka key、TCC 預留票主鍵、訂單側查 Saga 的依據
 * @param orderId    對應訂單 id（帳戶側不使用，只原樣帶回）
 * @param accountId  要凍結／扣款的帳戶
 * @param type       訊息型別，{@link SagaMessageTypes} 七個常數之一（命令 XXX_FUNDS／事件 FUNDS_XXX）
 * @param amount     金額＝訂單 quantity×price；Try 凍結此額，Confirm／Cancel 則以預留票上記錄的金額為準
 * @param symbol     商品代號（帳戶側不使用，供軌跡閱讀）
 * @param forceFail  教學開關；只有 CONFIRM_FUNDS 會讀它（true→帳戶側改做 Cancel 並回 FUNDS_CANCELLED）
 * @param occurredAt 訊息建立時間（UTC）；Outbox 延遲送出時，它反映「進匣」時刻而非寄出時刻
 */
public record SagaMessage(
        String messageId,
        String sagaId,
        String orderId,
        String accountId,
        String type,
        BigDecimal amount,
        String symbol,
        boolean forceFail,
        Instant occurredAt
) {

    /**
     * 【職責】組一則新訊息：自動產生 messageId（UUID）與 occurredAt（現在）。
     * 【技巧】靜態工廠把「每則新訊息都要新 id／時間戳」集中在一處，呼叫端只給業務欄位；
     * 反序列化時 Jackson 走 record 正規建構子，不經本方法，所以重送不會換 messageId。
     * <pre>
     * SagaMessage.of(sagaId, orderId, "ACC-001", SagaMessageTypes.RESERVE_FUNDS,
     *         new BigDecimal("10000"), "BTCUSDT", false);
     * </pre>
     *
     * @param sagaId    流程 id
     * @param orderId   訂單 id
     * @param accountId 帳戶
     * @param type      {@link SagaMessageTypes} 常數
     * @param amount    金額
     * @param symbol    商品代號
     * @param forceFail 教學開關
     * @return 帶新 messageId／occurredAt 的訊息
     */
    public static SagaMessage of(String sagaId, String orderId, String accountId, String type,
                                 BigDecimal amount, String symbol, boolean forceFail) {
        return new SagaMessage(
                UUID.randomUUID().toString(),
                sagaId,
                orderId,
                accountId,
                type,
                amount,
                symbol,
                forceFail,
                Instant.now()
        );
    }
}
