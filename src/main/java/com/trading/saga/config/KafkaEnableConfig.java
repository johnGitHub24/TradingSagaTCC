package com.trading.saga.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * 【職責】啟用 {@code @KafkaListener}（外接或內嵌 broker 都需要）。
 * <p>【技巧】{@code @EnableKafka} 註冊掃描 {@code @KafkaListener} 的後處理器，
 * <br>把 {@code SagaKafkaListeners} 的 {@code onCommand}／{@code onEvent} 包成 Listener 容器。
 * <p>【概念】Spring Boot 的 Kafka 自動設定在 classpath 有 spring-kafka 時通常也會啟用同一機制；
 * <br>這裡顯式宣告是讓「本專案依賴 Listener」的意圖可見，並與帶 {@code @ConditionalOnProperty} 的
 * <br>{@link EmbeddedKafkaConfig} 分開，不論內嵌或外接（{@code trading.kafka.embedded=false}）都成立。
 * <p>【邊界】無條件生效、不綁 property；topic／groupId 寫在各 {@code @KafkaListener} 上。
 */
@Configuration
@EnableKafka
public class KafkaEnableConfig {
}
