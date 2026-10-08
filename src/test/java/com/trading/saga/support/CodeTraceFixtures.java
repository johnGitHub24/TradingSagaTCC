package com.trading.saga.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 【職責】載入前台呼叫鏈對照表 {@code static/code-trace.json}（DASH-004），轉成 Java 端好斷言的 {@link CodeTrace}。
 * <p>【技巧】從 classpath 讀（main resources 在測試 classpath 上），單元層與整合層共用同一份解析邏輯。
 * <p>【概念】前台 tooltip 顯示的「入口 → Controller／Listener → Service → 方法」全部來自這份 JSON；
 * <br>Java 測試反過來驗證 JSON 裡每個「類別#方法」真的存在，重構改名時測試會紅，不會讓畫面說謊。
 */
public final class CodeTraceFixtures {

    private static final String RESOURCE = "static/code-trace.json";

    private CodeTraceFixtures() {
    }

    /**
     * 【職責】對照表中的一筆 trace。
     *
     * @param key      前台 trace key，如 {@code saga:COMPLETED}
     * @param kind     入口種類 HTTP／KAFKA／SCHEDULED／NONE
     * @param method   HTTP 方法（僅 HTTP）
     * @param path     HTTP 路徑樣板（僅 HTTP）
     * @param topic    Kafka topic 實際名稱（僅 KAFKA）
     * @param property 對應的設定鍵（KAFKA：topic 屬性；SCHEDULED：間隔屬性）
     * @param chain    呼叫鏈，每格為 {@code 完整類別名#方法名}
     */
    public record CodeTrace(String key, String kind, String method, String path,
                            String topic, String property, List<String> chain) {

        /** 呼叫鏈第一格（入口方法）；NONE 時為 null。 */
        public String entryRef() {
            return chain.isEmpty() ? null : chain.get(0);
        }
    }

    /**
     * 【職責】讀出全部 trace（依 JSON 順序）。
     */
    public static List<CodeTrace> load() {
        try (InputStream in = CodeTraceFixtures.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 找不到 " + RESOURCE);
            }
            JsonNode traces = SagaTestFixtures.mapper().readTree(in).path("traces");
            List<CodeTrace> result = new ArrayList<>();
            traces.fields().forEachRemaining(e -> result.add(toTrace(e.getKey(), e.getValue())));
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse " + RESOURCE, e);
        }
    }

    private static CodeTrace toTrace(String key, JsonNode node) {
        JsonNode entry = node.path("entry");
        List<String> chain = new ArrayList<>();
        node.path("chain").forEach(n -> chain.add(n.asText()));
        String property = entry.hasNonNull("property") ? entry.get("property").asText() : entry.path("every").asText(null);
        return new CodeTrace(key, entry.path("kind").asText("NONE"),
                entry.path("method").asText(null), entry.path("path").asText(null),
                entry.path("topic").asText(null), property, List.copyOf(chain));
    }

    /**
     * 【職責】把 {@code 完整類別名#方法名} 解析成類別。
     *
     * @throws ClassNotFoundException 類別已改名／搬套件
     */
    public static Class<?> classOf(String ref) throws ClassNotFoundException {
        return Class.forName(ref.substring(0, ref.indexOf('#')));
    }

    /** 取 ref 的方法名部分。 */
    public static String methodNameOf(String ref) {
        return ref.substring(ref.indexOf('#') + 1);
    }

    /**
     * 【職責】在類別（含父類別）找第一個同名方法，不分 public／private。
     * <p>【概念】呼叫鏈只記方法名不記參數；同名多載（如 Account.cancel）任一存在即可。
     */
    public static Optional<Method> findMethod(String ref) throws ClassNotFoundException {
        String name = methodNameOf(ref);
        for (Class<?> c = classOf(ref); c != null && c != Object.class; c = c.getSuperclass()) {
            Optional<Method> m = Arrays.stream(c.getDeclaredMethods())
                    .filter(it -> it.getName().equals(name) && !it.isSynthetic())
                    .findFirst();
            if (m.isPresent()) {
                return m;
            }
        }
        return Optional.empty();
    }
}
