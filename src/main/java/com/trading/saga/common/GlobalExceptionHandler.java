package com.trading.saga.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 【職責】例外 → 穩定 JSON；靜態資源缺失必須 404 而非 500。
 * <p>【技巧】{@code @RestControllerAdvice} 攔截所有 Controller 拋出的例外；
 * <br>Spring 依「最具體型別優先」挑 handler，所以 {@link Exception} 兜底不會搶走前面幾個。
 * <p>【概念】錯誤形狀固定為 {@code { timestamp, status, error, message }}（驗證失敗多一個 {@code fieldErrors}），
 * <br>與 {@code API規格書.md}「錯誤形狀」一致，前台與測試只需解析一種格式。
 * <p>【邊界】不決定何時拋業務例外。
 *
 * <p>【HTTP 狀態對照】
 * <ul>
 *   <li>{@link ResourceNotFoundException}（訂單／Saga／帳戶不存在）→ 404</li>
 *   <li>{@link NoResourceFoundException}（找不到靜態檔，如 favicon）→ 404</li>
 *   <li>{@link HttpMessageNotReadableException}（body 不是合法 JSON、型別轉不過去）→ 400</li>
 *   <li>{@link HttpRequestMethodNotSupportedException}（路徑存在但方法不支援）→ 405＋{@code Allow} header</li>
 *   <li>{@link MethodArgumentNotValidException}（{@code @Valid} 失敗）→ 422</li>
 *   <li>其他未列出的例外 → 500，訊息固定、不外洩內部細節</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 【職責】領域資源不存在 → 404。
     * <p>【概念】{@code message} 直接回傳例外訊息（例如 {@code Account not found: ACC-999}），
     * <br>這類訊息只含查詢鍵，可安全給前端顯示。
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
     * <p>【概念】Spring Boot 3.2 起找不到靜態資源會拋 {@link NoResourceFoundException}；
     * <br>若沒有這個 handler，會落到下方 {@link Exception} 兜底變成 500 並印 error log。
     * <br>記 debug 而非 warn：瀏覽器自動要 favicon 很常見，不該洗版。
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
     * 【職責】請求 body 讀不懂 → 400。
     * <p>【概念】與 422 的分界：400＝連 JSON 都解析不了（語法錯、數字欄位給字串），根本產生不出 {@code TradeRequest}；
     * <br>422＝JSON 正確但內容違反驗證規則。若沒有這個 handler，會落到兜底變成 500，誤導呼叫端以為是伺服器錯。
     * <br>message 固定，不回傳 Jackson 的原始錯誤（內含類別名稱等內部細節）。
     *
     * @param ex Jackson 反序列化失敗
     * @return 400 + 錯誤 JSON
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.BAD_REQUEST, "Bad Request", "Malformed JSON request body");
    }

    /**
     * 【職責】HTTP 方法不支援 → 405，並帶 {@code Allow} header。
     * <p>【技巧】{@link HttpRequestMethodNotSupportedException#getSupportedHttpMethods()} 取該路徑支援的方法，
     * <br>寫進 {@code Allow}（RFC 9110 要求 405 回應附上）；取不到時省略 header。
     * <p>【概念】例如對 {@code /api/v1/trades} 送 DELETE：路徑存在、方法不對，屬呼叫端錯誤而非 500。
     *
     * @param ex Spring MVC 找不到對應方法的 handler
     * @return 405 + 錯誤 JSON（message 含被拒的方法名）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMethod());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(HttpMethod[]::new));
        }
        return builder.body(body(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed",
                "Request method '" + ex.getMethod() + "' is not supported"));
    }

    /**
     * 【職責】{@code @Valid} 失敗 → 422，附欄位層級錯誤。
     * <p>【技巧】把 {@link FieldError} 攤平成 {@code 欄位名 → 訊息} 的 Map，前台可直接標在對應輸入框。
     * <p>【概念】用 422（Unprocessable Entity）而非 400：JSON 格式正確，只是內容不符規則，
     * <br>對齊 {@code API規格書.md}「驗證失敗 → 422」。
     * <br>同一欄位多個違規時 Map 只留最後一則。
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
     * <p>【概念】回應只給固定訊息，避免把 stack trace／SQL 等內部細節外洩給呼叫端；
     * <br>完整例外寫進 error log 供排查。
     * <p>【邊界】JSON 解析失敗（400）、不支援的 HTTP 方法（405）已由上方 handler 認領；
     * <br>其餘未個別處理的 Spring MVC 框架例外（如不支援的 Content-Type）仍會落到這裡成為 500。
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
