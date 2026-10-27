package io.plainround.seatholds.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.infrastructure.SeatStoreContract;
import io.plainround.seatholds.support.PostgresFixture;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** The identical contract class the in-memory adapter passes, against PostgreSQL in a container. */
class JdbcSeatStoreIT extends SeatStoreContract {

  @Override
  protected SeatStore newStore() {
    return new JdbcSeatStore(PostgresFixture.pool());
  }

  @Test
  void theDatabaseItselfRefusesAnImpossibleSeat() throws SQLException {
    new JdbcSeatStore(PostgresFixture.pool()).add(new SeatId("shape-check"));

    try (var connection = PostgresFixture.pool().getConnection();
        var statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "UPDATE seats SET state_type = 'HELD' WHERE seat_id = 'shape-check'"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("seats_state_shape");
    }
  }

  @Test
  void everyChangeBumpsTheVersionColumn() throws SQLException {
    var store = new JdbcSeatStore(PostgresFixture.pool());
    var seat = new SeatId("version-check");
    store.add(seat);
    store.update(
        seat,
        new io.plainround.seatholds.domain.SeatState.Free(),
        new io.plainround.seatholds.domain.SeatState.Sold(
            new io.plainround.seatholds.domain.CustomerId("ada")));

    try (var connection = PostgresFixture.pool().getConnection();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery("SELECT version FROM seats WHERE seat_id = 'version-check'")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getLong(1)).isEqualTo(1L);
    }
  }
}
