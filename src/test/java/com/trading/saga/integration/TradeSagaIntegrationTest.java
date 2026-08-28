package com.trading.saga.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.saga.order.dto.TradeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

/**
 * 【職責】HTTP＋雙 H2＋內嵌 Kafka 整合層；與單元層同一 Case ID。
 * 【技巧】Awaitility 等 Outbox Relay／Consumer 把 Saga 推到終態。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = { "trading.saga.commands", "trading.saga.events" })
@TestPropertySource(properties = {
        "startup.info.enabled=false",
        "trading.outbox.poll-ms=50",
        "trading.kafka.embedded=false",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@DisplayName("Trade Saga integration (paired cases)")
class TradeSagaIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetSeed() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/ACC-001/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(100000))
                .andExpect(jsonPath("$.frozen").value(0));
    }

    @Test
    @DisplayName("ACCOUNT-001: GET /api/v1/accounts/ACC-001 → 200 seed balance")
    void getAccount_seed_200() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/ACC-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("ACC-001"))
                .andExpect(jsonPath("$.available").value(100000));
    }

    @Test
    @DisplayName("TRADE-001: GET unknown order → 404")
    void getUnknownOrder_404() throws Exception {
        mockMvc.perform(get("/api/v1/trades/missing-order"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message", containsString("missing-order")));
    }

    @Test
    @DisplayName("SAGA-001: POST 1x10000 → FILLED / COMPLETED, available 90000")
    void happyPath_filled() throws Exception {
        String sagaId = place(1, 10000, false);
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/sagas/" + sagaId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("COMPLETED")));
        mockMvc.perform(get("/api/v1/accounts/ACC-001"))
                .andExpect(jsonPath("$.available").value(90000))
                .andExpect(jsonPath("$.frozen").value(0));
        mockMvc.perform(get("/api/v1/events"))
                .andExpect(jsonPath("$[*].type", hasItem("RESERVE_FUNDS")))
                .andExpect(jsonPath("$[*].type", hasItem("FUNDS_CONFIRMED")));
    }

    @Test
    @DisplayName("SAGA-002: POST 1x999999 → COMPENSATED, account unchanged")
    void insufficient_compensated() throws Exception {
        String sagaId = place(1, 999999, false);
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/sagas/" + sagaId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("COMPENSATED")));
        mockMvc.perform(get("/api/v1/accounts/ACC-001"))
                .andExpect(jsonPath("$.available").value(100000))
                .andExpect(jsonPath("$.frozen").value(0));
    }

    @Test
    @DisplayName("TCC-002: forceFail=true → COMPENSATED, account restored")
    void forceFail_compensated() throws Exception {
        String sagaId = place(1, 10000, true);
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/sagas/" + sagaId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("COMPENSATED")));
        mockMvc.perform(get("/api/v1/accounts/ACC-001"))
                .andExpect(jsonPath("$.available").value(100000))
                .andExpect(jsonPath("$.frozen").value(0));
    }

    @Test
    @DisplayName("OUTBOX-001: placing a trade eventually publishes RESERVE_FUNDS on Kafka trail")
    void outbox_reachesKafkaTrail() throws Exception {
        place(1, 10000, false);
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/events"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[*].type", hasItem("RESERVE_FUNDS"))));
    }

    private String place(int quantity, int price, boolean forceFail) throws Exception {
        TradeRequest request = new TradeRequest(
                "ACC-001", "BTCUSDT", "BUY",
                BigDecimal.valueOf(quantity), BigDecimal.valueOf(price), forceFail);
        MvcResult result = mockMvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sagaId").exists())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("sagaId").asText();
    }
}
