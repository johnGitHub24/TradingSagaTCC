package com.trading.saga.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【職責】保護 {@link TradeOrder#pending} 的金額契約（領域層）；對應 Case SAGA-001（下單金額＝凍結金額）。
 * <p>【技巧】純物件斷言，不啟動 Spring、不連 DB；用 {@code getScale()} 直接檢查 BigDecimal 的小數位數。
 * <p>【概念】amount 會被帶進 RESERVE_FUNDS 命令，帳戶側據此凍結資金；它必須和落庫後的值完全相同
 * <br>（欄位 scale 4），否則 202 回應、Kafka 命令與 DB 三處金額會不一致。
 */
// 測試報告／IDE 上顯示的名稱
@DisplayName("TradeOrder unit")
class TradeOrderTest {

    /** SAGA-001：Given 1 × 10000，When 建立 PENDING 訂單，Then amount 數值 10000 且固定 4 位小數、狀態 PENDING。 */
    @Test
    @DisplayName("SAGA-001: pending amount = qty × price with scale 4")
    void pending_amountScale4() {
        // ===== When：以 SAGA-001 fixture 相同的數量與單價建立訂單 =====
        // quantity=1、price=10000，相乘的 BigDecimal 原本 scale 為 0
        TradeOrder order = TradeOrder.pending(
                "o1", "s1", "ACC-001", "BTCUSDT", "BUY",
                new BigDecimal("1"), new BigDecimal("10000"), false);

        // ===== Then①：金額數值 =====
        // isEqualByComparingTo 只比數值，10000 與 10000.0000 視為相等
        assertThat(order.getAmount()).isEqualByComparingTo("10000");
        // ===== Then②：金額小數位 =====
        // 建構時已 setScale(4)，與 trade_orders.amount 欄位 scale 一致
        assertThat(order.getAmount().scale()).isEqualTo(TradeOrder.AMOUNT_SCALE);
        // ===== Then③：初始狀態 =====
        // 靜態工廠保證建出來就是 PENDING
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    /** SAGA-001：Given 0.3333 × 0.3333（= 0.11108889，8 位小數），When 建立訂單，Then amount 以 HALF_UP 捨入為 0.1111。 */
    @Test
    @DisplayName("SAGA-001: pending amount rounds HALF_UP to 4 decimals")
    void pending_amountRoundsHalfUp() {
        // ===== When：數量與單價都帶 4 位小數，相乘得 8 位 =====
        // 0.3333 × 0.3333 = 0.11108889
        TradeOrder order = TradeOrder.pending(
                "o2", "s2", "ACC-001", "BTCUSDT", "BUY",
                new BigDecimal("0.3333"), new BigDecimal("0.3333"), false);

        // ===== Then：捨入到 4 位小數 =====
        // 第 5 位是 8（≥ 5）→ HALF_UP 進位：0.11108889 → 0.1111；用 isEqualTo 連 scale 一起比對
        assertThat(order.getAmount()).isEqualTo(new BigDecimal("0.1111"));
    }
}
