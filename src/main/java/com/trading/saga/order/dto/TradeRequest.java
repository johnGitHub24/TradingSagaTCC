package com.trading.saga.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 【職責】下單請求。forceFail 僅教學用，用來走 TCC Cancel／Saga 補償。
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
     * @return 是否走故意失敗路徑
     */
    public boolean forceFailOrFalse() {
        return Boolean.TRUE.equals(forceFail);
    }
}
