package io.ledgerforge.ledger;

import io.ledgerforge.postgres.PostgresTest;

/** Every test in {@link ConcurrencyTest}, run again on real PostgreSQL. */
@PostgresTest
class PostgresConcurrencyTest extends ConcurrencyTest {
}
