package com.trading.saga.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【職責】靜態 404 不可被兜成 500；領域 404 穩定。
 * 覆蓋 {@link GlobalExceptionHandler}（Web 層例外轉換）；對應 Case TRADE-001（404 JSON 契約）
 * 與 TRADE-002（JSON 壞掉 400、方法不支援 405，不可落到兜底 500）。
 * 【技巧】不啟動 Spring、不用 MockMvc：直接 new 出 Handler，手動建立例外後呼叫對應的
 * {@code @ExceptionHandler} 方法，只驗證「例外 → HTTP 狀態碼＋JSON body」這段轉換。
 * 【概念】{@code @RestControllerAdvice} 會攔截 Controller 丟出的例外並回傳統一格式
 * {@code {timestamp, status, error, message}}。若沒有專屬的 {@code NoResourceFoundException} 處理器，
 * 瀏覽器自動請求的 {@code /favicon.ico} 會落到兜底 {@code handleGeneric} 變成 500，故需此測試守住 404。
 */
// 測試報告／IDE 上顯示的名稱
@DisplayName("GlobalExceptionHandler unit")
class GlobalExceptionHandlerTest {

    // 被測物件：Handler 沒有任何依賴，直接 new 即可；final 欄位在每個 Test 都會因新測試實例而重建
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * Given 找不到靜態資源 {@code /favicon.ico}，When 交給 Handler，Then 回 404 且 body.status 為 404。
     */
    @Test
    @DisplayName("NoResourceFoundException → 404")
    void staticMissing_404() {
        // ===== Given：模擬 Spring MVC 找不到靜態資源時丟出的例外 =====
        // GET /favicon.ico 是瀏覽器最常自動發出、而本專案沒有提供的靜態資源
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/favicon.ico");

        // ===== When：直接呼叫專屬的靜態 404 處理方法 =====
        // 回傳 ResponseEntity，body 是 Handler 組出的 Map（timestamp／status／error／message）
        ResponseEntity<Map<String, Object>> response = handler.handleNoResourceFound(ex);

        // ===== Then①：HTTP 狀態碼 =====
        // 必須是 404 NOT_FOUND，而不是被兜底處理器轉成 500
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ===== Then②：JSON body =====
        // body 一定要存在；先斷言非 null，下一行 get() 才不會 NPE
        assertThat(response.getBody()).isNotNull();
        // body 的 status 欄位放的是 HttpStatus.value()（int 404 自動裝箱成 Integer），故與 404 比對
        assertThat(response.getBody().get("status")).isEqualTo(404);
    }

    /**
     * TRADE-001：Given 領域例外「Order not found: missing」，When 交給 Handler，
     * Then 回 404 且 body.message 原樣帶出例外訊息。
     */
    @Test
    @DisplayName("TRADE-001: ResourceNotFoundException → 404")
    void resourceMissing_404() {
        // ===== Given：模擬查單找不到時丟出的領域例外 =====
        // 訊息格式與 TradeQueryService.getOrder 的 "Order not found: " + orderId 相同
        ResourceNotFoundException ex = new ResourceNotFoundException("Order not found: missing");

        // ===== When：呼叫領域資源不存在的處理方法 =====
        // Handler 會以 404 + "Not Found" + ex.getMessage() 組出回應
        ResponseEntity<Map<String, Object>> response = handler.handleResourceNotFound(ex);

        // ===== Then①：HTTP 狀態碼 =====
        // 領域「找不到」一律對外呈現 404
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ===== Then②：JSON body 的 message =====
        // message 直接取自例外訊息，前端／整合測試（jsonPath $.message）依此顯示與斷言
        assertThat(response.getBody().get("message")).isEqualTo("Order not found: missing");
    }

    /**
     * TRADE-002：Given Jackson 解析 body 失敗，When 交給 Handler，
     * Then 回 400、error 為 "Bad Request"，且 message 固定、不外洩解析細節。
     */
    @Test
    @DisplayName("TRADE-002: HttpMessageNotReadableException → 400")
    void malformedBody_400() {
        // ===== Given：模擬 Spring MVC 讀 body 失敗時丟出的例外 =====
        // 第 2 個參數是原始請求內容；MockHttpInputMessage（spring-test）以空 byte[] 充當即可
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character", new MockHttpInputMessage(new byte[0]));

        // ===== When：呼叫 body 解析失敗的處理方法 =====
        ResponseEntity<Map<String, Object>> response = handler.handleNotReadable(ex);

        // ===== Then①：HTTP 狀態碼 =====
        // 呼叫端送錯格式 → 400，而不是被兜底轉成 500
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // ===== Then②：JSON body =====
        // error 欄位固定為 HTTP 狀態的標準短語
        assertThat(response.getBody().get("error")).isEqualTo("Bad Request");
        // message 是固定文字，不帶 Jackson 原始訊息（避免外洩類別名稱等內部細節）
        assertThat(response.getBody().get("message")).isEqualTo("Malformed JSON request body");
    }

    /**
     * TRADE-002：Given 對只支援 GET／POST 的路徑送 DELETE，When 交給 Handler，
     * Then 回 405、{@code Allow} header 列出 GET 與 POST，message 含被拒的方法名。
     */
    @Test
    @DisplayName("TRADE-002: HttpRequestMethodNotSupportedException → 405 + Allow")
    void methodNotSupported_405() {
        // ===== Given：模擬 /api/v1/trades 收到 DELETE =====
        // 建構子參數：被拒的方法、該路徑實際支援的方法（TradeController 對 /api/v1/trades 只有 GET 與 POST）
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));

        // ===== When：呼叫方法不支援的處理方法 =====
        ResponseEntity<Map<String, Object>> response = handler.handleMethodNotSupported(ex);

        // ===== Then①：HTTP 狀態碼 =====
        // 路徑存在但方法不對 → 405
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        // ===== Then②：Allow header =====
        // getAllow() 讀回 Allow header 解析出的方法集合；必須正好是 GET、POST（順序不拘）
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        // ===== Then③：JSON body =====
        // message 帶出被拒的方法名，方便呼叫端定位錯誤
        assertThat(response.getBody().get("message")).isEqualTo("Request method 'DELETE' is not supported");
    }
}
