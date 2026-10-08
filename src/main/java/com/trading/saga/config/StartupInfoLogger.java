package com.trading.saga.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 【職責】應用就緒後於 Console 印出常用 URL，並對 HTTP 入口探測 UP／DOWN。
 * <p>【技巧】聽 {@link ApplicationReadyEvent}；UTF-8 {@link PrintStream}；雙庫 JDBC 都印出。
 * <p>【概念】開發便利，不是 Gate（Gate 仍是 {@code scripts/check.ps1}）。
 * <p>【邊界】不啟動 Docker／Kafka 外接。
 *
 * <p>【綁定的 property（皆在 {@code application.yml} 的 {@code startup.info.*}）】
 * <ul>
 *   <li>{@code enabled}：總開關；整合測試設 {@code false} 避免洗版與自我探測。</li>
 *   <li>{@code project-name}：框線標題。</li>
 *   <li>{@code frontend}：{@code static} 時多印「前台」區塊（同埠 Vue）；其他值不印。</li>
 *   <li>{@code h2}／{@code api-docs}：是否印 H2 Console／Swagger 連結。</li>
 *   <li>{@code probe}：是否對每個 URL 發 GET 標 UP／DOWN。</li>
 *   <li>{@code home-path}：前台首頁路徑。</li>
 *   <li>另讀 {@code server.port}、{@code springdoc.api-docs.path}、
 *       {@code trading.datasource.order.url}／{@code account.url} 組出實際連結。</li>
 * </ul>
 *
 * <p>【為什麼是 ApplicationReadyEvent】它在內嵌 Tomcat 已開埠、{@code CommandLineRunner}
 * <br>（例如 {@link AccountDataSeeder}）跑完後才發出；此時自我探測才有意義，
 * <br>若改聽 {@code ContextRefreshedEvent}，埠可能尚未開，探測會全部 DOWN。
 */
@Component
public class StartupInfoLogger implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupInfoLogger.class);

    /**
     * 【職責】讀 {@code startup.info.*} 開關，印出後端工具、雙庫 JDBC、前台首頁的連結框線。
     * <p>【技巧】所有值都從 {@link Environment} 讀並帶預設值，yml 缺鍵也不會 NPE；
     * <br>最後再補一行 {@code log.info}，讓只看 log 檔（不看 Console）的人也知道已就緒。
     * <p>【概念】框線用 {@code System.out} 而非 logger：logger 會加時間／等級前綴，破壞對齊。
     *
     * @param event 就緒事件（從中取得 ApplicationContext 的 Environment）
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        // 預設 true：只有明確設 false（整合測試）才關閉，避免測試 log 被框線洗版
        if (Boolean.FALSE.equals(env.getProperty("startup.info.enabled", Boolean.class, true))) {
            return;
        }

        String project = env.getProperty("startup.info.project-name", "TradingSagaTCC");
        // 用設定的 server.port 而非寫死 8093：IDE 或命令列覆寫埠時連結仍正確
        String port = env.getProperty("server.port", "8093");
        // 固定 localhost：此框線給本機開發者點擊，不處理反向代理／容器對外位址
        String base = "http://localhost:" + port;
        String frontend = env.getProperty("startup.info.frontend", "none");
        boolean h2 = !Boolean.FALSE.equals(env.getProperty("startup.info.h2", Boolean.class, true));
        boolean apiDocs = !Boolean.FALSE.equals(env.getProperty("startup.info.api-docs", Boolean.class, true));
        boolean probe = Boolean.TRUE.equals(env.getProperty("startup.info.probe", Boolean.class, true));

        PrintStream out = utf8Out();
        out.println();
        out.println("╔════════════════════════════════════════════════════════════════════════╗");
        out.printf("║  %-70s║%n", project + " 後端已啟動 — 使用連結");
        out.println("╠════════════════════════════════════════════════════════════════════════╣");
        out.println("║ 【後端 API / 工具】                                                      ║");
        link(out, probe, "健康檢查", base + "/actuator/health");
        link(out, probe, "應用資訊", base + "/actuator/info");
        if (apiDocs) {
            link(out, probe, "Swagger UI", base + "/swagger-ui.html");
            // springdoc 的 JSON 路徑可被改寫；讀同一個 property 才不會印出失效連結
            link(out, probe, "OpenAPI JSON",
                    base + env.getProperty("springdoc.api-docs.path", "/v3/api-docs"));
        }
        if (h2) {
            link(out, probe, "H2 Console", base + "/h2-console");
            // 雙庫：H2 Console 登入頁一次只能連一個庫，故兩條 JDBC URL 都印出來讓人複製貼上；
            // 不探測（不是 HTTP），帳密對應 yml 的 sa／空字串
            out.printf("║   Order JDBC   %s  sa / (blank)%n",
                    env.getProperty("trading.datasource.order.url", "jdbc:h2:mem:orderdb"));
            out.printf("║   Account JDBC %s  sa / (blank)%n",
                    env.getProperty("trading.datasource.account.url", "jdbc:h2:mem:accountdb"));
        }

        // 只有同埠靜態前台（src/main/resources/static/）才有首頁可點；其他值（如 none）不印此區塊
        if ("static".equalsIgnoreCase(frontend)) {
            out.println("╠════════════════════════════════════════════════════════════════════════╣");
            out.println("║ 【前台】同埠靜態 Vue（Saga／TCC／補償練習）                                 ║");
            link(out, probe, "首頁", base + env.getProperty("startup.info.home-path", "/"));
        }

        out.println("╚════════════════════════════════════════════════════════════════════════╝");
        out.println();
        log.info("{} ready - frontend={} | {}", project, frontend, base + "/actuator/health");
    }

    /**
     * 印一行「標籤＋URL」，需要時在尾端附 {@code [UP]}／{@code [DOWN]}。
     * <br>只探測 {@code http} 開頭的字串，非 HTTP 內容（例如 JDBC URL）不會誤發請求。
     */
    private static void link(PrintStream out, boolean probe, String label, String url) {
        String mark = "";
        if (probe && url != null && url.startsWith("http")) {
            mark = "  [" + (isUp(url) ? "UP" : "DOWN") + "]";
        }
        out.printf("║   %-12s %s%s%n", label, url, mark);
    }

    /**
     * 對自身埠發一次 GET，判斷端點是否有回應。
     * <br>例外（連不上、逾時）一律視為 DOWN，不往外拋：探測失敗不該讓啟動流程中斷。
     */
    private static boolean isUp(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            // 短逾時：探測在啟動執行緒同步進行，連結有多條，逾時太長會明顯拖慢「就緒」
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            // /swagger-ui.html 會 302 轉到 /swagger-ui/index.html，跟隨轉址才拿得到最終狀態
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            // 4xx 也算 UP：代表伺服器有在處理請求（只是路徑或參數不對）；只有 5xx 或連不上才是 DOWN
            return code >= 200 && code < 500;
        } catch (Exception ex) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 以 UTF-8 包一層 {@code System.out}。
     * <br>Windows 主控台預設 code page（如 cp950）會把框線字元與中文印成亂碼；autoFlush=true 確保立即輸出。
     */
    private static PrintStream utf8Out() {
        return new PrintStream(System.out, true, StandardCharsets.UTF_8);
    }

    /**
     * 【職責】解析 {@code startup.info.extra-paths}，回傳一律以 {@code /} 開頭的路徑清單。
     * <p>【技巧】同時支援兩種寫法：YAML 清單（Spring 攤平成 {@code extra-paths[0]}、{@code [1]}…）
     * <br>與單一逗號分隔字串；先試 indexed，沒有才退回逗號字串。
     * <p>【概念】保留給測試／擴充 indexed 路徑解析（與公版 StartupInfoLogger 對齊）；
     * <br>本專案 {@link #onApplicationEvent} 目前未呼叫，yml 也未設定此鍵。
     *
     * @param env Spring Environment
     * @return 額外路徑（未設定時為空清單，永不為 null）
     */
    static List<String> extraPaths(Environment env) {
        // YAML 清單在 Environment 中沒有整體值，只能逐一以索引讀取，讀到空值即視為清單結束
        String first = env.getProperty("startup.info.extra-paths[0]");
        if (first != null && !first.isBlank()) {
            List<String> values = new ArrayList<>();
            for (int i = 0; ; i++) {
                String p = env.getProperty("startup.info.extra-paths[" + i + "]");
                if (p == null || p.isBlank()) {
                    break;
                }
                values.add(p.startsWith("/") ? p : "/" + p);
            }
            return values;
        }
        // 退回逗號字串寫法（例如環境變數或命令列 --startup.info.extra-paths=/a,/b）
        String raw = env.getProperty("startup.info.extra-paths");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.startsWith("/") ? s : "/" + s)
                .toList();
    }
}
