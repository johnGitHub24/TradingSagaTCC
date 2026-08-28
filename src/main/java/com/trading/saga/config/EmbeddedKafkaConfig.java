package com.trading.saga.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;

/**
 * 【職責】本機內嵌 Kafka（bootRun 不必 Docker）。
 * 【技巧】FactoryCustomizer 依賴 broker bean，強制先啟動再覆寫 bootstrap servers
 *         （yml 的 localhost:9092 不會蓋掉內嵌埠）。
 * 【概念】測試改走 {@code @EmbeddedKafka} + {@code trading.kafka.embedded=false}。
 */
@Configuration
@ConditionalOnProperty(name = "trading.kafka.embedded", havingValue = "true", matchIfMissing = true)
public class EmbeddedKafkaConfig {

    /**
     * 單節點內嵌 broker，預建 command／event topic。
     */
    @Bean(destroyMethod = "destroy")
    public EmbeddedKafkaBroker embeddedKafkaBroker(
            @org.springframework.beans.factory.annotation.Value("${trading.kafka.command-topic}") String commandTopic,
            @org.springframework.beans.factory.annotation.Value("${trading.kafka.event-topic}") String eventTopic) {
        EmbeddedKafkaZKBroker broker = new EmbeddedKafkaZKBroker(1, true, 1, commandTopic, eventTopic);
        broker.brokerListProperty("spring.kafka.bootstrap-servers");
        broker.afterPropertiesSet();
        System.setProperty("spring.kafka.bootstrap-servers", broker.getBrokersAsString());
        return broker;
    }

    /**
     * Producer 連內嵌 broker。
     */
    @Bean
    public DefaultKafkaProducerFactoryCustomizer embeddedProducer(EmbeddedKafkaBroker broker) {
        return factory -> factory.setBootstrapServersSupplier(broker::getBrokersAsString);
    }

    /**
     * Consumer 連內嵌 broker。
     */
    @Bean
    public DefaultKafkaConsumerFactoryCustomizer embeddedConsumer(EmbeddedKafkaBroker broker) {
        return factory -> factory.setBootstrapServersSupplier(broker::getBrokersAsString);
    }
}
