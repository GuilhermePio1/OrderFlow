package com.orderflow.order.adapter.persistence.r2dbc;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FlywayConfiguration {

    @Value("${spring.flyway.url}")
    private String url;

    @Value("${spring.flyway.user}")
    private String user;

    @Value("${spring.flyway.password}")
    private String password;

    @Bean(initMethod = "migrate")
    public Flyway flyway() {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
    }
}
