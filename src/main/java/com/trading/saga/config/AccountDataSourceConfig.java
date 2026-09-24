package com.trading.saga.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 【職責】帳戶庫 DataSource／EMF／Tx：accounts、tcc_reservations。
 * 【概念】與訂單庫實體完全隔離；TCC 只掛 {@code accountTransactionManager}。
 *
 * <p>【重要·局部全成或全敗 ≠ Global TX】
 * 與訂單庫對稱：本 Bean 管單一帳戶庫內 ACID（Try／Confirm／Cancel 同進同退）。
 * 不是 XA GlobalTransactionManager；不會與 {@code orderTransactionManager} 綁成一筆跨庫事務。
 * 訂單側失敗靠補償；帳戶側還原靠 TCC Cancel——各自局部 TX，靠訊息串起來。
 *
 * <p>【概念·XA（摘要）】XA＝讓多個資源參加同一筆全域事務的協定（常配合 JTA、兩階段提交）。
 * 完整名詞與「為何本專案不用」見 {@link OrderDataSourceConfig} 類別註解【概念·XA 是什麼】。
 * 口訣：Local＝一庫全有或全無；XA Global＝多庫一起全有或全無——本檔只做 Local。
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.trading.saga.account.infrastructure",
        entityManagerFactoryRef = "accountEntityManagerFactory",
        transactionManagerRef = "accountTransactionManager"
)
public class AccountDataSourceConfig {

    /**
     * 帳戶庫連線屬性。
     */
    @Bean
    @ConfigurationProperties("trading.datasource.account")
    public DataSourceProperties accountDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 帳戶庫連線池。
     */
    @Bean
    public DataSource accountDataSource(
            @Qualifier("accountDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * 帳戶庫 EMF，只掃 account 套件。
     */
    @Bean
    public LocalContainerEntityManagerFactoryBean accountEntityManagerFactory(
            @Qualifier("accountDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.trading.saga.account");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(jpaProperties());
        factory.setPersistenceUnitName("accountPU");
        return factory;
    }

    /**
     * 【職責】帳戶庫交易管理器：本庫內「全部成功才成功，否則全部回滾」。
     * 【概念·很重要】局部 TX（只限 accountdb），語感像 Global「全有或全無」，
     * 但不是 JTA／XA 跨訂單庫。
     * <ul>
     *   <li>XA：跨多資源的全域事務協定（2PC）；JTA：Java 裡操作全域事務的 API。</li>
     *   <li>本 Bean＝{@link JpaTransactionManager} 只綁帳戶 EMF → 一庫 Local TX。</li>
     *   <li>與訂單庫如何對齊：靠 Kafka 事件＋Saga 補償，不是同一個全域 commit。</li>
     * </ul>
     * 詳見 {@link OrderDataSourceConfig}【概念·XA 是什麼】與
     * {@link OrderDataSourceConfig#orderTransactionManager}。
     *
     * @param emf 帳戶庫 EntityManagerFactory
     * @return 帳戶庫 PlatformTransactionManager（Local，非 JTA）
     */
    @Bean
    public PlatformTransactionManager accountTransactionManager(
            @Qualifier("accountEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    private static Map<String, Object> jpaProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", "update");
        props.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        return props;
    }
}
