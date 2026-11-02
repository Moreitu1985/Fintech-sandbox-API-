package io.ledgerforge.ledger;

import io.ledgerforge.postgres.PostgresTest;

/** Every test in {@link IdempotencyTest}, run again on real PostgreSQL. */
@PostgresTest
class PostgresIdempotencyTest extends IdempotencyTest {
}
