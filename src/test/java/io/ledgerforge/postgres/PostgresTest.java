package io.ledgerforge.postgres;

import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a test class against a real PostgreSQL 16 database in Docker instead of H2, so the
 * production schema (including the append-only triggers) and real locking are exercised.
 * All classes with this annotation share one Spring context and one container.
 * Skipped when Docker is not available (e.g. a laptop without Docker); CI requires them to run.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Import(PostgresContainerConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@Tag("postgres")
public @interface PostgresTest {
}
