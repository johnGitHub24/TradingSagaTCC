package com.trading.saga.account;

/**
 * 【職責】訂單側啟動 Saga 前確認帳戶存在（只讀帳戶庫，不是分散式寫入）。
 */
public interface AccountLookup {

    /**
     * @param accountId 帳戶代號
     * @throws com.trading.saga.common.ResourceNotFoundException 不存在
     */
    void requireExists(String accountId);
}
