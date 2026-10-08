package com.trading.saga.order.dto;

import com.trading.saga.order.domain.OrderStatus;
import com.trading.saga.order.domain.TradeOrder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 【職責】訂單對外 DTO：把 {@link TradeOrder} 實體投影成 JSON，不讓 JPA 實體直接外露。
 * <p>【技巧】record＋靜態工廠 {@link #from}：欄位一對一複製，不可變，序列化由 Jackson 依 component 名稱輸出。
 * <p>【概念】下單回應（202）拿到的是「當下快照」，status 幾乎一定是 PENDING；
 * <br>要知道最終結果請拿 sagaId 輪詢 {@code GET /api/v1/sagas/{sagaId}}，或重新查訂單。
 * <p>【使用】{@code SagaOrchestrator.start}（下單回應）、{@code TradeQueryService.listOrders／getOrder}（查詢）、
 * <br>{@link DemoStateResponse#orders()}（Demo 面板）。
 *
 * @param orderId   訂單 id（UUID）
 * @param sagaId    對應 Saga id；前台用它輪詢流程狀態
 * @param accountId 下單帳戶
 * @param symbol    商品代號
 * @param side      BUY／SELL
 * @param quantity  數量
 * @param price     單價
 * @param amount    名目金額＝quantity×price，也就是帳戶側要凍結／扣款的金額
 * @param status    訂單狀態：PENDING（進行中）／FILLED（成交）／FAILED（補償收尾）
 * @param forceFail 是否為教學補償路徑下的單（前台表格顯示用）
 * @param createdAt 下單時間（UTC，ISO-8601 字串輸出）
 */
public record TradeResponse(
        String orderId,
        String sagaId,
        String accountId,
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal amount,
        OrderStatus status,
        boolean forceFail,
        Instant createdAt
) {
    /**
     * 【職責】從實體投影成 DTO。
     * <p>【技巧】可當方法參考 {@code TradeResponse::from} 丟進 {@code stream().map(...)}。
     * <p>【邊界】純複製，不查 DB、不改實體；呼叫端需在實體仍可讀取時呼叫（本專案實體欄位皆非 lazy）。
     *
     * @param order 訂單實體
     * @return 對外 DTO
     */
    public static TradeResponse from(TradeOrder order) {
        return new TradeResponse(
                order.getOrderId(),
                order.getSagaId(),
                order.getAccountId(),
                order.getSymbol(),
                order.getSide(),
                order.getQuantity(),
                order.getPrice(),
                order.getAmount(),
                order.getStatus(),
                order.isForceFail(),
                order.getCreatedAt()
        );
    }
}
