package com.trading.saga.support;

import com.trading.saga.support.CodeTraceFixtures.CodeTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【職責】DASH-004 單元層：前台呼叫鏈對照表 {@code code-trace.json} 的每個「類別#方法」都真的存在於程式碼。
 * <br>與 TradeSagaIntegrationTest 的 DASH-004（執行期 HandlerMapping／Kafka topic／排程設定）成對。
 * <p>【技巧】不起 Spring，只用反射：
 * <ul>
 *   <li>{@link Class#forName}：類別改名或搬套件 → 紅。</li>
 *   <li>{@code getDeclaredMethods} 找同名方法（含 private，如 {@code OrderSagaEventHandler#onReserved}）→ 方法改名 → 紅。</li>
 *   <li>入口方法要帶對應註解：HTTP→{@code @RequestMapping} 家族、KAFKA→{@code @KafkaListener}、SCHEDULED→{@code @Scheduled}。</li>
 * </ul>
 * <p>【概念】{@code @TestFactory} 每筆 trace 產生一個動態測試，失敗時報告直接寫出是哪個 trace key 漂移。
 * <br>「前台 trace key ↔ JSON」由 {@code static/test/dashboard.spec.js} 的 DASH-004 規格把關。
 */
@DisplayName("Code trace map unit (DASH-004)")
class CodeTraceMapTest {

    private static final List<CodeTrace> TRACES = CodeTraceFixtures.load();

    /** DASH-004：對照表有載到東西，且涵蓋 HTTP／Kafka／排程三種入口。 */
    @Test
    @DisplayName("DASH-004: code-trace.json loads with HTTP / KAFKA / SCHEDULED entries")
    void mapLoads_withAllEntryKinds() {
        assertThat(TRACES).hasSizeGreaterThan(20);
        assertThat(TRACES).extracting(CodeTrace::kind).contains("HTTP", "KAFKA", "SCHEDULED");
    }

    /**
     * DASH-004：Given 每筆 trace，When 反射解析呼叫鏈，Then 每格類別與方法都存在。
     * <br>NONE（預留值／擴增點）不得有呼叫鏈，避免畫面顯示不存在的路徑。
     */
    @TestFactory
    @DisplayName("DASH-004: every chain ref resolves to a real class#method")
    Stream<DynamicTest> everyRef_resolves() {
        return TRACES.stream().map(t -> DynamicTest.dynamicTest(t.key(), () -> {
            if ("NONE".equals(t.kind())) {
                assertThat(t.chain()).as(t.key() + " NONE 不應有呼叫鏈").isEmpty();
                return;
            }
            assertThat(t.chain()).as(t.key() + " 呼叫鏈").hasSizeGreaterThanOrEqualTo(2);
            for (String ref : t.chain()) {
                assertThat(ref).as("ref 格式").matches("com\\.trading\\.saga\\.[\\w.]+\\.[A-Z]\\w*#\\w+");
                assertThat(CodeTraceFixtures.findMethod(ref)).as(t.key() + " → " + ref).isPresent();
            }
        }));
    }

    /**
     * DASH-004：Given 非 NONE 的 trace，When 檢查入口方法（呼叫鏈第一格）的註解，
     * <br>Then HTTP 帶 {@code @RequestMapping} 家族、KAFKA 帶 {@code @KafkaListener}、SCHEDULED 帶 {@code @Scheduled}。
     */
    @TestFactory
    @DisplayName("DASH-004: entry method carries the annotation matching entry.kind")
    Stream<DynamicTest> entryMethod_hasMatchingAnnotation() {
        return TRACES.stream().filter(t -> !"NONE".equals(t.kind()))
                .map(t -> DynamicTest.dynamicTest(t.key() + " " + t.kind(), () -> {
                    Method entry = CodeTraceFixtures.findMethod(t.entryRef()).orElseThrow();
                    Class<? extends java.lang.annotation.Annotation> expected = switch (t.kind()) {
                        case "HTTP" -> RequestMapping.class;
                        case "KAFKA" -> KafkaListener.class;
                        case "SCHEDULED" -> Scheduled.class;
                        default -> throw new IllegalStateException("未知 entry.kind " + t.kind());
                    };
                    assertThat(AnnotatedElementUtils.hasAnnotation(entry, expected))
                            .as(t.entryRef() + " 應帶 @" + expected.getSimpleName()).isTrue();
                }));
    }
}
