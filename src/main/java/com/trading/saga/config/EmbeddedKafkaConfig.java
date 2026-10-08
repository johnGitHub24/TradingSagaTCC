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
 * <p>【技巧】FactoryCustomizer 依賴 broker bean，強制先啟動再覆寫 bootstrap servers
 * <br>（Spring Boot 預設的 {@code localhost:9092} 不會蓋掉內嵌埠；本專案 yml 未設 bootstrap-servers）。
 * <p>【概念】測試改走 {@code @EmbeddedKafka} + {@code trading.kafka.embedded=false}。
 *
 * <p>【何時生效】{@code trading.kafka.embedded=true}，或完全沒設此鍵（{@code matchIfMissing = true}）。
 * <br>要接外部 Kafka 時設 {@code trading.kafka.embedded=false} 並提供 {@code spring.kafka.bootstrap-servers}。
 *
 * <p>【綁定的 property】{@code trading.kafka.command-topic}、{@code trading.kafka.event-topic}
 * <br>（啟動時預建這兩個 topic）；並在執行期寫入系統屬性 {@code spring.kafka.bootstrap-servers}。
 *
 * <p>【邊界】{@link EmbeddedKafkaZKBroker} 來自 {@code spring-kafka-test}，本專案特意放在
 * <br>{@code implementation}（非僅測試）才能在 bootRun 使用；正式環境不應走這條。
 * <br>內嵌 broker 在 JVM 內、資料不落地，重啟後 topic 內容全部消失。
 */
@Configuration
@ConditionalOnProperty(name = "trading.kafka.embedded", havingValue = "true", matchIfMissing = true)
public class EmbeddedKafkaConfig {

    /**
     * 【職責】單節點內嵌 broker，預建 command／event topic。
     * <p>【技巧】在 {@code @Bean} 方法內就呼叫 {@code afterPropertiesSet()} 啟動，
     * <br>讓回傳時 broker 已可連線，後續依賴它的 Bean 拿到的位址必定有效。
     * <p>【概念】{@code destroyMethod = "destroy"}：Context 關閉時一併停掉 broker 與 ZooKeeper，
     * <br>避免 IDE 重啟應用時殘留埠或執行緒。
     *
     * @param commandTopic {@code trading.kafka.command-topic}（帳戶 TCC 收 command）
     * @param eventTopic   {@code trading.kafka.event-topic}（編排器收 event）
     * @return 已啟動的內嵌 broker
     */
    @Bean(destroyMethod = "destroy")
    public EmbeddedKafkaBroker embeddedKafkaBroker(
            @org.springframework.beans.factory.annotation.Value("${trading.kafka.command-topic}") String commandTopic,
            @org.springframework.beans.factory.annotation.Value("${trading.kafka.event-topic}") String eventTopic) {
        // 參數依序：1 個 broker、controlledShutdown=true、每個 topic 1 個 partition；
        // 單 partition：同一 topic 的所有訊息保序，Demo 量小也不需要平行消費
        EmbeddedKafkaZKBroker broker = new EmbeddedKafkaZKBroker(1, true, 1, commandTopic, eventTopic);
        // 告訴 broker 啟動後要把實際位址寫進哪個系統屬性名稱
        broker.brokerListProperty("spring.kafka.bootstrap-servers");
        // 真正啟動 ZooKeeper + broker 並建立上面兩個 topic（埠為隨機可用埠，不是 9092）
        broker.afterPropertiesSet();
        // 再顯式寫一次系統屬性：讓稍後才解析 ${spring.kafka.bootstrap-servers} 的元件也讀到內嵌位址
        System.setProperty("spring.kafka.bootstrap-servers", broker.getBrokersAsString());
        return broker;
    }

    /**
     * 【職責】Producer 連內嵌 broker。
     * <p>【技巧】注入 broker 參數＝宣告 Bean 依賴，確保 broker 先啟動；
     * <br>用 Supplier（方法參考 {@code broker::getBrokersAsString}）在建立連線時才取位址。
     * <p>【概念】{@code KafkaProperties} 可能在 broker 啟動前就綁定好預設位址，
     * <br>只靠系統屬性不保險，所以在 ProducerFactory 層再覆寫一次。
     *
     * @param broker 已啟動的內嵌 broker
     * @return 覆寫 bootstrap servers 的 customizer
     */
    @Bean
    public DefaultKafkaProducerFactoryCustomizer embeddedProducer(EmbeddedKafkaBroker broker) {
        return factory -> factory.setBootstrapServersSupplier(broker::getBrokersAsString);
    }

    /**
     * 【職責】Consumer 連內嵌 broker（{@code @KafkaListener} 容器使用的 ConsumerFactory）。
     * <p>【技巧】與 {@link #embeddedProducer} 同理：依賴 broker Bean＋Supplier 延遲取位址。
     *
     * @param broker 已啟動的內嵌 broker
     * @return 覆寫 bootstrap servers 的 customizer
     */
    @Bean
    public DefaultKafkaConsumerFactoryCustomizer embeddedConsumer(EmbeddedKafkaBroker broker) {
        return factory -> factory.setBootstrapServersSupplier(broker::getBrokersAsString);
    }
}
