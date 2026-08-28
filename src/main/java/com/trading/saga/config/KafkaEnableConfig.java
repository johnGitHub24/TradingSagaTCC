package com.trading.saga.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * 【職責】啟用 {@code @KafkaListener}（外接或內嵌 broker 都需要）。
 */
@Configuration
@EnableKafka
public class KafkaEnableConfig {
}
