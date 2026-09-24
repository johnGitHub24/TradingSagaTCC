package com.trading.saga.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 【職責】訂單庫 DataSource／EMF／Tx：orders、saga、outbox。
 * 【技巧】{@code @Primary} 給 H2 Console 與預設 JPA 探測；帳戶庫另檔組裝。
 * 【概念】這條邊界寫入不得出現帳戶 Entity。
 *
 * <p>【重要·局部全成或全敗 ≠ Global TX】
 * {@code orderTransactionManager} 管的是單一訂單庫內的 ACID：
 * 同一 {@code @Transactional("orderTransactionManager")} 裡寫的訂單／Saga／Outbox，
 * 「全部成功才 commit；任一失敗全部 rollback」——語感上像 GlobalTransactionManager，
 * 但範圍只限 orderdb，不是 JTA／XA 跨庫的 Global Transaction。
 * 帳戶庫另有 {@code accountTransactionManager}；兩庫禁止綁成一筆 XA。
 * 跨庫最終一致靠 Saga＋Outbox＋Kafka＋TCC／補償，不是靠全域事務管理員。
 *
 * <p>【概念·XA 是什麼（初學者必讀）】
 * <ul>
 *   <li><b>XA</b>：業界標準協定名稱（常念「X-A」），用來讓「多個資源」
 *       （兩個資料庫、DB＋訊息佇列…）參加<strong>同一筆全域事務</strong>。</li>
 *   <li><b>JTA</b>（Java Transaction API）：Java 裡啟動／提交／回滾「全域事務」的 API；
 *       底層常由支援 XA 的資源＋ Transaction Manager 實作。</li>
 *   <li><b>Global Transaction Manager</b>：全域裁判。典型兩階段提交（2PC）：
 *       ① Prepare——各庫先說「我可以 commit」；② Commit／Rollback——裁判下令全提交或全取消。
 *       目標是跨庫也「全有或全無」（強一致）。</li>
 *   <li><b>代價</b>：鎖持有久、延遲高、協調者故障難處理、微服務難水平擴展——
 *       所以分散式系統常改用 Saga／Outbox／TCC 換「最終一致」。</li>
 *   <li><b>本專案怎麼做</b>：兩個 {@link JpaTransactionManager}（訂單／帳戶各一把），
 *       各自只綁自己的 {@link EntityManagerFactory}＝Local TX。
 *       沒有 Atomikos／Narayana 這類 JTA；規格書寫「禁止 XA 跨庫寫入」＝
 *       禁止在同一個全域事務裡同時改 orderdb 與 accountdb。</li>
 *   <li><b>對照口訣</b>：Local＝「一個庫裡全部成功才成功」；
 *       XA Global＝「多個庫一起全部成功才成功」。本專案只要前者，不要後者。</li>
 * </ul>
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.trading.saga.order.infrastructure",
        entityManagerFactoryRef = "orderEntityManagerFactory",
        transactionManagerRef = "orderTransactionManager"
)
public class OrderDataSourceConfig {

    /**
     * 訂單庫連線屬性。
     */
    @Bean
    @Primary
    @ConfigurationProperties("trading.datasource.order")
    public DataSourceProperties orderDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 訂單庫連線池。
     */
    @Bean
    @Primary
    public DataSource orderDataSource(
            @Qualifier("orderDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * 訂單庫 EMF，只掃 order 套件。
     */
    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean orderEntityManagerFactory(
            @Qualifier("orderDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.trading.saga.order");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(jpaProperties());
        factory.setPersistenceUnitName("orderPU");
        return factory;
    }

    /**
     * 【職責】訂單庫交易管理器：本庫內「全部成功才成功，否則全部回滾」。
     * 【技巧】綁 {@code orderEntityManagerFactory}；業務用
     * {@code @Transactional("orderTransactionManager")} 掛這條。
     * 【概念·很重要】語感類似 GlobalTransactionManager 的「全有或全無」，
     * 但這是局部（Local）TX，只涵蓋訂單庫（含 Outbox 表）。詳見類別上方【概念·XA 是什麼】。
     * <ul>
     *   <li>同方法內：訂單＋Saga＋Outbox append → 同進同退（Outbox 必須掛這條）。</li>
     *   <li>不是 XA：不會把訂單庫＋帳戶庫交給同一個全域裁判做 2PC
     *       （XA＝跨多資源的全域事務協定；JTA＝Java 叫用該協定的 API）。</li>
     *   <li>跨庫怎麼辦：本庫先 commit（含發件匣）→ {@link com.trading.saga.messaging.OutboxRelayJob}
     *       寄 Kafka → 帳戶側用 {@code accountTransactionManager} 另開一筆 Local TX 做 TCC；
     *       失敗再補償。這叫最終一致，不是 XA 強一致。</li>
     * </ul>
     * 若誤把兩庫塞進同一個「全域」事務，就違反本專案「禁止 XA 跨庫寫入」邊界。
     *
     * @param emf 訂單庫 EntityManagerFactory
     * @return 訂單庫 PlatformTransactionManager（Local，非 JTA）
     */
    @Bean
    @Primary
    public PlatformTransactionManager orderTransactionManager(
            @Qualifier("orderEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    private static Map<String, Object> jpaProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", "update");
        props.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        return props;
    }
}
