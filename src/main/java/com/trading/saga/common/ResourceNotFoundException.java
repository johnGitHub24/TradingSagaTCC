package com.trading.saga.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 【職責】資源不存在（訂單／Saga／帳戶）。
 * 【邊界】不決定 JSON 形狀；由 {@code GlobalExceptionHandler} 轉 404。
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {

    /**
     * @param message 人類可讀說明
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
