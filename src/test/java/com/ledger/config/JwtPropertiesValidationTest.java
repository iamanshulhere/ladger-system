package com.ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class JwtPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtPropertiesTestConfiguration.class);

    @Test
    void missingSecretFailsConfigurationBinding() {
        contextRunner.withPropertyValues("jwt.secret=").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void configuredSecretBindsSuccessfully() {
        contextRunner.withPropertyValues("jwt.secret=unit-test-secret-with-at-least-32-bytes").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JwtProperties.class).getSecret())
                    .isEqualTo("unit-test-secret-with-at-least-32-bytes");
        });
    }

    @Test
    void expirationCannotExceedBlacklistRetentionWindow() {
        contextRunner.withPropertyValues(
                "jwt.secret=unit-test-secret-with-at-least-32-bytes",
                "jwt.expiration-days=4").run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesTestConfiguration {
    }
}
