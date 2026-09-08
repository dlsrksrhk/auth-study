package com.sweet.referenceapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sweet.referenceapp.support.PostgresContainerConfiguration;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ActiveProfiles("test")
class ReferenceApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DataSource dataSource;

    @Test
    void loadsIndependentApplicationContextWithDedicatedPostgres() throws Exception {
        assertThat(applicationContext).isNotNull();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
            assertThat(connection.getCatalog()).isEqualTo("reference_app_test");
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:postgresql://");
        }
    }
}
