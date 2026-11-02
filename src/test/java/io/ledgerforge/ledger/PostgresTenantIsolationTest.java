package io.ledgerforge.ledger;

import io.ledgerforge.postgres.PostgresTest;

/** Every test in {@link TenantIsolationTest}, run again on real PostgreSQL. */
@PostgresTest
class PostgresTenantIsolationTest extends TenantIsolationTest {
}
