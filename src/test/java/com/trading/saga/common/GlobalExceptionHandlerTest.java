package com.trading.saga.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【職責】靜態 404 不可被兜成 500；領域 404 穩定。
 * 覆蓋 {@link GlobalExceptionHandler}（Web 層例外轉換）；第二個 Test 對應 Case TRADE-001 的 404 JSON 契約。
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
}
