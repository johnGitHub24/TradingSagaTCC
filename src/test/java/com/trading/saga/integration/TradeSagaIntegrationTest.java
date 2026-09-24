package com.trading.saga.integration;

import com.trading.saga.support.SagaTestFixtures;
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

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 【職責】HTTP＋雙 H2＋內嵌 Kafka 整合層；與單元層同一 Case ID。
 * 【技巧】Request body 來自 {@code docs/test-data/}（EOS Fixture）；Awaitility 等終態。
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
        var seed = SagaTestFixtures.loadTree("account", "ACCOUNT-001-SEED");
        mockMvc.perform(get("/api/v1/accounts/ACC-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(seed.get("accountId").asText()))
                .andExpect(jsonPath("$.available").value(seed.get("available").asInt()));
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
    @DisplayName("SAGA-001: POST fixture → FILLED / COMPLETED, available 90000")
    void happyPath_filled() throws Exception {
        String sagaId = placeFixture("SAGA-001-SUCCESS");
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
    @DisplayName("SAGA-002: POST fixture → COMPENSATED, account unchanged")
    void insufficient_compensated() throws Exception {
        String sagaId = placeFixture("SAGA-002-INSUFFICIENT");
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/sagas/" + sagaId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("COMPENSATED")));
        mockMvc.perform(get("/api/v1/accounts/ACC-001"))
                .andExpect(jsonPath("$.available").value(100000))
                .andExpect(jsonPath("$.frozen").value(0));
    }

    @Test
    @DisplayName("TCC-002: forceFail fixture → COMPENSATED, account restored")
    void forceFail_compensated() throws Exception {
        String sagaId = placeFixture("TCC-002-FORCE-FAIL");
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/sagas/" + sagaId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("COMPENSATED")));
        mockMvc.perform(get("/api/v1/accounts/ACC-001"))
                .andExpect(jsonPath("$.available").value(100000))
                .andExpect(jsonPath("$.frozen").value(0));
    }

    @Test
    @DisplayName("OUTBOX-001: fixture place eventually publishes RESERVE_FUNDS")
    void outbox_reachesKafkaTrail() throws Exception {
        placeFixture("OUTBOX-001-RESERVE");
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/events"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[*].type", hasItem("RESERVE_FUNDS"))));
    }

    private String placeFixture(String caseId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SagaTestFixtures.loadJson("trade", caseId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sagaId").exists())
                .andReturn();
        return SagaTestFixtures.mapper().readTree(result.getResponse().getContentAsString())
                .get("sagaId").asText();
    }
}
