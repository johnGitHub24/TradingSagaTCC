package com.trading.saga.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 【職責】例外 → 穩定 JSON；靜態資源缺失必須 404 而非 500。
 * 【技巧】{@code @RestControllerAdvice} 攔截所有 Controller 拋出的例外；
 *         Spring 依「最具體型別優先」挑 handler，所以 {@link Exception} 兜底不會搶走前面幾個。
 * 【概念】錯誤形狀固定為 {@code { timestamp, status, error, message }}（驗證失敗多一個 {@code fieldErrors}），
 *         與 {@code API規格書.md}「錯誤形狀」一致，前台與測試只需解析一種格式。
 * 【邊界】不決定何時拋業務例外。
 *
 * <p>【HTTP 狀態對照】
 * <ul>
 *   <li>{@link ResourceNotFoundException}（訂單／Saga／帳戶不存在）→ 404</li>
 *   <li>{@link NoResourceFoundException}（找不到靜態檔，如 favicon）→ 404</li>
 *   <li>{@link MethodArgumentNotValidException}（{@code @Valid} 失敗）→ 422</li>
 *   <li>其他未列出的例外 → 500，訊息固定、不外洩內部細節</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 【職責】領域資源不存在 → 404。
     * 【概念】{@code message} 直接回傳例外訊息（例如 {@code Account not found: ACC-999}），
     *         這類訊息只含查詢鍵，可安全給前端顯示。
     *
     * @param ex 由 Service 查無資料時拋出
     * @return 404 + 錯誤 JSON
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    /**
     * 【職責】favicon 等靜態 404。
     * 【概念】Spring Boot 3.2 起找不到靜態資源會拋 {@link NoResourceFoundException}；
     *         若沒有這個 handler，會落到下方 {@link Exception} 兜底變成 500 並印 error log。
     *         記 debug 而非 warn：瀏覽器自動要 favicon 很常見，不該洗版。
     *
     * @param ex 靜態資源解析失敗
     * @return 404 + 錯誤 JSON
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("Static resource not found: {}", ex.getResourcePath());
        return build(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    /**
     * 【職責】{@code @Valid} 失敗 → 422，附欄位層級錯誤。
     * 【技巧】把 {@link FieldError} 攤平成 {@code 欄位名 → 訊息} 的 Map，前台可直接標在對應輸入框。
     * 【概念】用 422（Unprocessable Entity）而非 400：JSON 格式正確，只是內容不符規則，
     *         對齊 {@code API規格書.md}「驗證失敗 → 422」。
     *         同一欄位多個違規時 Map 只留最後一則。
     *
     * @param ex Bean Validation 結果
     * @return 422 + 錯誤 JSON（含 {@code fieldErrors}）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> body = body(HttpStatus.UNPROCESSABLE_ENTITY,
                "Validation Failed", "Request body contains invalid fields");
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /**
     * 【職責】兜底 → 500。
     * 【概念】回應只給固定訊息，避免把 stack trace／SQL 等內部細節外洩給呼叫端；
     *         完整例外寫進 error log 供排查。
     * 【邊界】本類沒有個別處理 Spring MVC 的框架例外（例如 JSON 解析失敗、不支援的 HTTP 方法），
     *         它們也會落到這裡成為 500。
     *
     * @param ex 未被上方 handler 認領的任何例外
     * @return 500 + 錯誤 JSON
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(body(status, error, message));
    }

    /** 組統一錯誤欄位；回傳可變 Map，讓呼叫端（如驗證 handler）再追加欄位。 */
    private Map<String, Object> body(HttpStatus status, String error, String message) {
        Map<String, Object> map = new HashMap<>();
        // ISO-8601 字串：與 jackson write-dates-as-timestamps=false 的其他時間欄位格式一致
        map.put("timestamp", Instant.now().toString());
        map.put("status", status.value());
        map.put("error", error);
        map.put("message", message);
        return map;
    }
}
