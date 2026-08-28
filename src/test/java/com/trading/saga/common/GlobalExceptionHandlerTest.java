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
 */
@DisplayName("GlobalExceptionHandler unit")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("NoResourceFoundException → 404")
    void staticMissing_404() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/favicon.ico");
        ResponseEntity<Map<String, Object>> response = handler.handleNoResourceFound(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(404);
    }

    @Test
    @DisplayName("TRADE-001: ResourceNotFoundException → 404")
    void resourceMissing_404() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Order not found: missing");
        ResponseEntity<Map<String, Object>> response = handler.handleResourceNotFound(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("message")).isEqualTo("Order not found: missing");
    }
}
