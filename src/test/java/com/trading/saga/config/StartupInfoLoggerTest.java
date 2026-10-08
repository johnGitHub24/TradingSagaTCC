package com.trading.saga.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 【職責】啟動框線：disabled 不印；static 前台印首頁。
 *
 * <p>【概念·誰呼叫 onApplicationEvent】正式環境與測試不同：</p>
 * <pre>
 * 正式環境（bootRun）                                   本測試
 * ───────────────────────────────────────────────   ─────────────────────────────────────────
 * SpringApplication.run 建好所有 Bean                 沒有 Spring：自己 new StartupInfoLogger()
 * Tomcat 開好、CommandLineRunner 跑完                  沒有 Tomcat（所以 probe 要關，見下）
 * Spring 發布 ApplicationReadyEvent                   自己準備假的 event（@Mock）
 * → 自動呼叫所有 ApplicationListener                  → 手動呼叫 logger.onApplicationEvent(event)
 * → 框線印到 Console                                  → 框線被 captureStdout 導進記憶體再比對
 * </pre>
 * <p>測試方法本身（{@code disabled_printsNothing}／{@code static_printsHome}）沒有人在程式裡呼叫：
 * <br>由 JUnit 5 掃描 {@code @Test} 後用反射執行；每個方法前會 new 一個新的測試實例並重新建立 {@code @Mock}。</p>
 *
 * <p>【技巧】{@code Strictness.LENIENT}：Mockito 預設 STRICT_STUBS 會在兩種情況讓測試失敗——
 * <br>寫了 {@code when} 卻沒被用到（UnnecessaryStubbing），或被測程式用「不同參數」呼叫已 stub 的方法
 * <br>（PotentialStubbingProblem）。這裡 stub 了一大串設定值，放寬後，日後被測程式少讀某個設定
 * <br>或改了預設參數，未命中的呼叫只會回 null，不會讓整支測試因 stub 細節而爆掉。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StartupInfoLogger unit")
class StartupInfoLoggerTest {

    // 【概念】三個假物件對應被測程式第一行的呼叫鏈：
    //   Environment env = event.getApplicationContext().getEnvironment();
    //   event ──getApplicationContext()──▶ applicationContext ──getEnvironment()──▶ env
    // 每個測試用 when(...) 把這三個假物件串起來，被測程式才拿得到 env。

    /** 假的「應用就緒」事件：正式環境由 Spring 發布，這裡由測試手動傳入。 */
    @Mock
    private ApplicationReadyEvent event;
    /** 假的 Spring 容器：只用來回傳下面的 env。 */
    @Mock
    private ConfigurableApplicationContext applicationContext;
    /** 假的設定來源：取代 application.yml，每個 getProperty 的回傳值都由 when(...) 指定。 */
    @Mock
    private ConfigurableEnvironment env;

    /** 被測物件：直接 new（不經 Spring），所以測試要自己扮演 Spring 呼叫 onApplicationEvent。 */
    private final StartupInfoLogger logger = new StartupInfoLogger();

    @Test
    @DisplayName("enabled=false → prints nothing")
    void disabled_printsNothing() {
        // ① 串起呼叫鏈：event → applicationContext → env
        when(event.getApplicationContext()).thenReturn(applicationContext);
        when(applicationContext.getEnvironment()).thenReturn(env);
        // ② 關閉開關：被測程式讀到 false 會在第一個 if 直接 return，後面的設定都不會讀（所以不必 stub）
        when(env.getProperty("startup.info.enabled", Boolean.class, true)).thenReturn(false);

        // ③ 手動呼叫（扮演 Spring），並把 System.out 輸出抓成字串
        String out = captureStdout(() -> logger.onApplicationEvent(event));

        // ④ 什麼都沒印：連 localhost 都不該出現
        assertThat(out).doesNotContain("localhost");
    }

    @Test
    @DisplayName("static frontend → prints homepage on :8093")
    void static_printsHome() {
        // ① 串起呼叫鏈：event → applicationContext → env（見欄位上方說明）
        when(event.getApplicationContext()).thenReturn(applicationContext);
        when(applicationContext.getEnvironment()).thenReturn(env);

        // ② 預先寫好每個設定的「答案」。when(...) 不是在呼叫，而是登記：
        //    「等一下被測程式呼叫 env.getProperty(這組參數)，就回傳這個值」。
        //    真正呼叫 getProperty 的是 StartupInfoLogger.onApplicationEvent 裡的程式。
        //    【注意】參數必須和被測程式完全一致才會命中：被測程式寫 getProperty("server.port", "8093")，
        //    這裡也要寫同樣兩個參數；寫成 getProperty("server.port") 就對不上，會回 null。

        // 開關打開，才會往下印框線
        when(env.getProperty("startup.info.enabled", Boolean.class, true)).thenReturn(true);
        // 框線標題「TradingSagaTCC 後端已啟動」
        when(env.getProperty("startup.info.project-name", "TradingSagaTCC")).thenReturn("TradingSagaTCC");
        // 組出 base URL：http://localhost:8093
        when(env.getProperty("server.port", "8093")).thenReturn("8093");
        // "static" → 會多印【前台】首頁那一段（本測試要驗的重點）
        when(env.getProperty("startup.info.frontend", "none")).thenReturn("static");
        // true → 印 H2 Console 與兩條 JDBC（Order／Account）
        when(env.getProperty("startup.info.h2", Boolean.class, true)).thenReturn(true);
        // true → 印 Swagger UI／OpenAPI JSON
        when(env.getProperty("startup.info.api-docs", Boolean.class, true)).thenReturn(true);
        // false → 【重要】不做 HTTP 探測。若為 true，isUp() 會真的連 http://localhost:8093，
        //   但測試沒有啟動伺服器，每個連結都要等 800ms 逾時才回 DOWN：測試變慢且結果不穩定。
        when(env.getProperty("startup.info.probe", Boolean.class, true)).thenReturn(false);
        // 首頁路徑 → 組出 http://localhost:8093/
        when(env.getProperty("startup.info.home-path", "/")).thenReturn("/");
        // OpenAPI JSON 路徑
        when(env.getProperty("springdoc.api-docs.path", "/v3/api-docs")).thenReturn("/v3/api-docs");
        // 雙庫各印一條 JDBC URL（本專案雙 DataSource）
        when(env.getProperty("trading.datasource.order.url", "jdbc:h2:mem:orderdb"))
                .thenReturn("jdbc:h2:mem:orderdb");
        when(env.getProperty("trading.datasource.account.url", "jdbc:h2:mem:accountdb"))
                .thenReturn("jdbc:h2:mem:accountdb");

        // ③ 手動呼叫 onApplicationEvent（正式環境是 Spring 在 ApplicationReadyEvent 時自動呼叫）。
        //    lambda 交給 captureStdout 執行：執行期間 System.out 被換成記憶體 buffer，框線全寫進去。
        String out = captureStdout(() -> logger.onApplicationEvent(event));

        // ④ 在抓到的整段框線文字裡找關鍵字
        assertThat(out).contains("TradingSagaTCC 後端已啟動");  // 來自 project-name
        assertThat(out).contains("http://localhost:8093/");     // 來自 server.port＋home-path（frontend=static 才印）
        assertThat(out).contains("Order JDBC");                  // 來自 h2=true
        assertThat(out).contains("Account JDBC");                // 雙庫都要印
    }

    /**
     * 【職責】執行 action，並把期間印到 System.out 的內容抓成字串回傳。
     * <pre>
     * ① 記住原本的 System.out（Console）
     * ② System.setOut(ps)：換成寫進記憶體 buffer 的 PrintStream
     * ③ action.run()：執行 logger.onApplicationEvent(event)
     *    → 被測程式的 utf8Out() 包的是「當下的」System.out，也就是 buffer，所以框線不會出現在 Console
     * ④ finally 換回原本的 System.out（中途丟例外也一定還原，避免影響後續測試的輸出）
     * ⑤ 回傳 buffer 內容（UTF-8，框線中的中文才不會變亂碼）
     * </pre>
     */
    private static String captureStdout(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(ps);
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
