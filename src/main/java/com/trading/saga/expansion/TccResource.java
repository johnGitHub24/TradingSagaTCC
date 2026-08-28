package com.trading.saga.expansion;

import java.math.BigDecimal;

/**
 * 【職責】TCC 資源契約：新增參與者（庫存等）時實作此介面，不必改雙庫邊界。
 * 【技巧】Try／Confirm／Cancel 皆以 sagaId 冪等。
 * 【概念】TCC 管「單資源兩階段」；Saga 管「多資源先後與補償」。
 * 【邊界】實作只能碰自己的庫。
 */
public interface TccResource {

    /**
     * Try：檢查並預留。
     *
     * @return true 預留成功；false 應走補償（例如餘額不足）
     */
    boolean tryReserve(String sagaId, String accountId, BigDecimal amount);

    /**
     * Confirm：消耗預留。
     *
     * @param forceFail true 時本版改走 Cancel（教學用）
     * @return true 確認成功；false 已改 Cancel／失敗
     */
    boolean confirm(String sagaId, boolean forceFail);

    /**
     * Cancel：釋放預留（冪等）。
     */
    void cancel(String sagaId);
}
