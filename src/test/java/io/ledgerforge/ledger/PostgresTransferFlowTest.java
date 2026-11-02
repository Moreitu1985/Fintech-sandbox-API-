package io.ledgerforge.ledger;

import io.ledgerforge.postgres.PostgresTest;

/** Every test in {@link TransferFlowTest}, run again on real PostgreSQL. */
@PostgresTest
class PostgresTransferFlowTest extends TransferFlowTest {
}
