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
 * 【技巧】聽 {@link ApplicationReadyEvent}；UTF-8 {@link PrintStream}；雙庫 JDBC 都印出。
 * 【概念】開發便利，不是 Gate（Gate 仍是 {@code scripts/check.ps1}）。
 * 【邊界】不啟動 Docker／Kafka 外接。
 */
@Component
public class StartupInfoLogger implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupInfoLogger.class);

    /**
     * 印出啟動框線。
     *
     * @param event 就緒事件
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        if (Boolean.FALSE.equals(env.getProperty("startup.info.enabled", Boolean.class, true))) {
            return;
        }

        String project = env.getProperty("startup.info.project-name", "TradingSagaTCC");
        String port = env.getProperty("server.port", "8093");
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
            link(out, probe, "OpenAPI JSON",
                    base + env.getProperty("springdoc.api-docs.path", "/v3/api-docs"));
        }
        if (h2) {
            link(out, probe, "H2 Console", base + "/h2-console");
            out.printf("║   Order JDBC   %s  sa / (blank)%n",
                    env.getProperty("trading.datasource.order.url", "jdbc:h2:mem:orderdb"));
            out.printf("║   Account JDBC %s  sa / (blank)%n",
                    env.getProperty("trading.datasource.account.url", "jdbc:h2:mem:accountdb"));
        }

        if ("static".equalsIgnoreCase(frontend)) {
            out.println("╠════════════════════════════════════════════════════════════════════════╣");
            out.println("║ 【前台】同埠靜態 Vue（Saga／TCC／補償練習）                                 ║");
            link(out, probe, "首頁", base + env.getProperty("startup.info.home-path", "/"));
        }

        out.println("╚════════════════════════════════════════════════════════════════════════╝");
        out.println();
        log.info("{} ready - frontend={} | {}", project, frontend, base + "/actuator/health");
    }

    private static void link(PrintStream out, boolean probe, String label, String url) {
        String mark = "";
        if (probe && url != null && url.startsWith("http")) {
            mark = "  [" + (isUp(url) ? "UP" : "DOWN") + "]";
        }
        out.printf("║   %-12s %s%s%n", label, url, mark);
    }

    private static boolean isUp(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception ex) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static PrintStream utf8Out() {
        return new PrintStream(System.out, true, StandardCharsets.UTF_8);
    }

    /**
     * 保留給測試／擴充 indexed 路徑解析（與公版 StartupInfoLogger 對齊）。
     *
     * @param env Spring Environment
     * @return extra paths
     */
    static List<String> extraPaths(Environment env) {
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
