package com.trading.saga.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 【職責】資源不存在（訂單／Saga／帳戶）。
 * 【技巧】繼承 {@link RuntimeException}（unchecked），Service 用
 *         {@code repository.findById(...).orElseThrow(() -> new ResourceNotFoundException(...))} 一行拋出，
 *         不必層層宣告 {@code throws}。
 * 【概念】{@code @ResponseStatus(NOT_FOUND)} 是保險：即使沒有 {@code GlobalExceptionHandler}，
 *         Spring 也會回 404；本專案實際由 handler 先攔截並組出統一 JSON。
 *         拋出點也包含 Kafka Listener 路徑（例如補償時找不到 Saga），那邊沒有 HTTP，例外只會進 Listener 錯誤處理。
 * 【邊界】不決定 JSON 形狀；由 {@code GlobalExceptionHandler} 轉 404。
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {

    /**
     * @param message 人類可讀說明，慣例為 {@code "<資源> not found: <id>"}，會原樣出現在 404 回應的 {@code message}
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
