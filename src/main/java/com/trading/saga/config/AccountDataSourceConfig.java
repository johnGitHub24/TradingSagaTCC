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
     * 帳戶庫交易管理器。
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
