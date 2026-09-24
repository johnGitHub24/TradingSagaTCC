package com.trading.saga.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 【職責】從 {@code docs/test-data/{domain}/{caseId}.json} 載入測試素材（EOS Fixture／texture）。
 * 【技巧】路徑相對專案根；單元與整合層共用同一份 JSON。
 * 【概念】Case ID 與檔名對齊，契約變更時只改 JSON + 成對測試。
 */
public final class SagaTestFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SagaTestFixtures() {
    }

    /**
     * 【職責】載入指定 domain 與 Case ID 的 JSON 字串。
     *
     * @param domain 子目錄（trade／account）
     * @param caseId 不含副檔名的案例檔名（如 SAGA-001-SUCCESS）
     */
    public static String loadJson(String domain, String caseId) {
        Path path = Paths.get("docs", "test-data", domain, caseId + ".json");
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load fixture: " + domain + "/" + caseId, e);
        }
    }

    /**
     * 【職責】載入 JSON 並反序列化為 DTO（單元層；忽略 description 等多餘欄）。
     */
    public static <T> T loadDto(String domain, String caseId, Class<T> type) {
        try {
            return MAPPER.readValue(loadJson(domain, caseId), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse fixture: " + domain + "/" + caseId, e);
        }
    }

    /**
     * 【職責】載入為樹，方便讀取期望值（如 ACCOUNT-001 available）。
     */
    public static JsonNode loadTree(String domain, String caseId) {
        try {
            return MAPPER.readTree(loadJson(domain, caseId));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse fixture tree: " + domain + "/" + caseId, e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
