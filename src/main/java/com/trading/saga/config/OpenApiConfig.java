package com.trading.saga.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 【職責】OpenAPI 文件中繼資料。
 * <p>【技巧】提供一個 {@link OpenAPI} Bean，springdoc（{@code springdoc-openapi-starter-webmvc-ui}）
 * <br>會以它為底，再掃描各 {@code @RestController} 補上路徑與 schema。
 * <p>【概念】這裡只放標題／說明／版本；端點清單由 springdoc 自動產生，不必手寫。
 * <br>Swagger UI 路徑由 yml {@code springdoc.swagger-ui.path} 決定（{@code /swagger-ui.html}）。
 * <p>【邊界】無條件生效、不綁自訂 property；API 契約權威仍是專案根目錄 {@code API規格書.md}。
 */
@Configuration
public class OpenApiConfig {

    /**
     * 【職責】Swagger UI 標題、說明與版本。
     *
     * @return 帶 {@link Info} 的 OpenAPI 根物件（{@code /v3/api-docs} 的 info 區塊）
     */
    @Bean
    public OpenAPI tradingSagaOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TradingSagaTCC API")
                .description("Saga / TCC / compensation over Kafka with dual H2 databases.")
                .version("0.1.0-SNAPSHOT"));
    }
}
