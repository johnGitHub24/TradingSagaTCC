package com.trading.saga.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 【職責】下單請求（{@code POST /api/v1/trades} 的 body）。forceFail 僅教學用，用來走 TCC Cancel／Saga 補償。
 * <p>【技巧】Java record＋Bean Validation 註記放在 component 上；{@code TradeController.place} 標 {@code @Valid}，
 * <br>驗證失敗由 {@code GlobalExceptionHandler} 轉 422＋fieldErrors，根本進不到 {@code SagaOrchestrator}。
 * <p>【概念】驗證只擋「格式」；帳戶是否存在由 {@code AccountLookup.requireExists} 檢查（不存在→404），
 * <br>餘額夠不夠則要等帳戶側 TCC Try 才知道（不足→Saga 補償，HTTP 仍是 202）。
 * <p>【邊界】side 只驗 BUY／SELL 字面；本版 TCC 不論方向都凍結 quantity×price 的現金。
 *
 * @param accountId 下單帳戶代號（Demo 種子為 {@code ACC-001}）；不可空白
 * @param symbol    商品代號（如 BTCUSDT），最長 32，對齊 {@code trade_orders.symbol} 欄寬
 * @param side      買賣方向，只接受 {@code BUY} 或 {@code SELL}
 * @param quantity  數量，最小 0.0001（含），對齊 DB scale 4
 * @param price     單價，最小 0.0001（含）；與 quantity 相乘即凍結金額
 * @param forceFail 教學開關，可省略（null 視為 false）；true 時 Try 照常進行（餘額足夠就凍結），但 Confirm 階段帳戶側改做 Cancel（TCC-002）
 */
public record TradeRequest(
        @NotBlank String accountId,
        @NotBlank @Size(max = 32) String symbol,
        @NotBlank @Pattern(regexp = "BUY|SELL") String side,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal price,
        Boolean forceFail
) {
    /**
     * 【職責】把可為 null 的 {@code Boolean forceFail} 收斂成 primitive boolean。
     * <p>【技巧】{@code Boolean.TRUE.equals(x)}：x 為 null 或 false 都回 false，避免自動拆箱 NPE。
     * <p>【概念】component 宣告成包裝型別 {@code Boolean}，前端不傳此欄位時 JSON 才能合法反序列化為 null。
     * <p>【使用】{@code SagaOrchestrator.start} 寫入訂單與 RESERVE_FUNDS 命令時呼叫。
     *
     * @return 是否走故意失敗路徑（null 一律視為 false）
     */
    public boolean forceFailOrFalse() {
        return Boolean.TRUE.equals(forceFail);
    }
}
