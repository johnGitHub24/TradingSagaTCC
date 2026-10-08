package com.trading.saga.account.dto;

import com.trading.saga.account.domain.TccReservation;
import com.trading.saga.account.domain.TccState;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 【職責】TCC 預留票對外 DTO（{@code GET /api/v1/tcc/reservations/{sagaId}} 的回應），供前台 Dashboard 畫 TCC 狀態機。
 * <p>【技巧】兩個靜態工廠：{@link #from} 投影既有票；{@link #none} 表示「查無票」，以 {@code exists=false} 回 200 而非 404。
 * <p>【概念】「無票」是合法的業務狀態，不是錯誤：Try 尚未執行，或 Try 失敗（餘額不足）根本不寫票。
 * <br>前台輪詢時若用 404 表達，瀏覽器 Console 會被紅字洗版，也會和「路徑打錯」混淆。
 * <p>【邊界】只讀帳戶庫；訂單側程式不可依賴本 DTO（兩庫只靠 Kafka 溝通，本端點僅服務人眼觀察）。
 *
 * @param sagaId    流程 id（預留票主鍵）
 * @param exists    是否已有預留票；false 時其餘欄位為 null
 * @param state     TRYING／CONFIRMED／CANCELLED；無票時 null
 * @param accountId 被凍結的帳戶；無票時 null
 * @param amount    凍結金額；無票時 null
 * @param createdAt Try 成功（發票）時間；無票時 null
 */
public record TccReservationResponse(
        String sagaId,
        boolean exists,
        TccState state,
        String accountId,
        BigDecimal amount,
        Instant createdAt
) {
    /**
     * 【職責】從預留票實體投影。
     *
     * @param reservation 預留票實體
     * @return exists=true 的 DTO
     */
    public static TccReservationResponse from(TccReservation reservation) {
        return new TccReservationResponse(
                reservation.getSagaId(),
                true,
                reservation.getState(),
                reservation.getAccountId(),
                reservation.getAmount(),
                reservation.getCreatedAt()
        );
    }

    /**
     * 【職責】查無票時的回應（錢從未被凍結）。
     *
     * @param sagaId 查詢的流程 id
     * @return exists=false、其餘欄位 null 的 DTO
     */
    public static TccReservationResponse none(String sagaId) {
        return new TccReservationResponse(sagaId, false, null, null, null, null);
    }
}
