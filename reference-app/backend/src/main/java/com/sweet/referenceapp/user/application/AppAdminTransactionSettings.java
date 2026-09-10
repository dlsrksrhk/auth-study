package com.sweet.referenceapp.user.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AppAdminTransactionSettings {
    private final JdbcTemplate jdbc;

    public AppAdminTransactionSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply() {
        jdbc.execute("SET LOCAL lock_timeout = '3s'");
        jdbc.execute("SET LOCAL statement_timeout = '5s'");
    }
}
