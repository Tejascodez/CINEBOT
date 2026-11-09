package com.cinebot.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Full infrastructure for integration tests: PostgreSQL + Redis. */
@TestConfiguration(proxyBeanMethods = false)
@Import({PostgresTestContainer.class, RedisTestContainer.class})
public class TestcontainersConfiguration {
}
