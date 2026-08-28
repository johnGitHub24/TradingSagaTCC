package com.trading.saga.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 【職責】OpenAPI 文件中繼資料。
 */
@Configuration
public class OpenApiConfig {

    /**
     * Swagger UI 標題。
     */
    @Bean
    public OpenAPI tradingSagaOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TradingSagaTCC API")
                .description("Saga / TCC / compensation over Kafka with dual H2 databases.")
                .version("0.1.0-SNAPSHOT"));
    }
}
