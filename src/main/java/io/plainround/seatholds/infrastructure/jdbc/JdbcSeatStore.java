package io.plainround.seatholds.infrastructure.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatholds.domain.CustomerId;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.domain.SeatStoreException;
import io.plainround.seatholds.domain.UnknownSeatException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Stores seats in PostgreSQL ({@code sql/001_schema.sql}). {@link #update} is one conditional
 * {@code UPDATE}: the {@code WHERE} clause matches the state the caller read, so a write based on
 * stale data changes zero rows and returns false, however many servers race. The port speaks in
 * states, not versions, so the clause compares the state columns; the {@code version} column is
 * bumped on every change for auditing.
 */
public final class JdbcSeatStore implements SeatStore, AutoCloseable {

  private static final int QUERY_TIMEOUT_SECONDS = 10;

  private static final String SELECT_STATE =
      "SELECT state_type, holder, expires_at FROM seats WHERE seat_id = ?";
  private static final String COMPARE_AND_SET =
      """
      UPDATE seats
         SET state_type = ?, holder = ?::text, expires_at = ?::timestamptz, version = version + 1
       WHERE seat_id = ?
         AND state_type = ?
         AND holder IS NOT DISTINCT FROM ?::text
         AND expires_at IS NOT DISTINCT FROM ?::timestamptz
      """;
  private static final String INSERT_FREE =
      "INSERT INTO seats (seat_id, state_type) VALUES (?, 'FREE') ON CONFLICT (seat_id) DO NOTHING";
  private static final String LIST_SEATS =
      "SELECT seat_id FROM seats ORDER BY seat_id COLLATE \"C\"";

  private final DataSource dataSource;
  private final AutoCloseable pool;

  /**
   * Creates the store over a data source the caller owns and closes.
   *
   * @param dataSource the connection pool
   */
  public JdbcSeatStore(DataSource dataSource) {
    this.dataSource = dataSource;
    this.pool = () -> {};
  }

  private JdbcSeatStore(HikariDataSource dataSource) {
    this.dataSource = dataSource;
    this.pool = dataSource;
  }

  /**
   * Opens a connection pool and a store over it; {@link #close} closes the pool.
   *
   * @param settings where the database is
   * @return the store
   */
  public static JdbcSeatStore open(DatabaseSettings settings) {
    return new JdbcSeatStore(PostgresDataSource.open(settings));
  }

  @Override
  public void close() {
    try {
      pool.close();
    } catch (Exception failure) {
      throw new SeatStoreException("closing the connection pool", failure);
    }
  }

  @Override
  public SeatState get(SeatId seat) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = prepare(connection, SELECT_STATE)) {
      statement.setString(1, seat.value());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new UnknownSeatException(seat);
        }
        return decode(row);
      }
    } catch (SQLException failure) {
      throw new SeatStoreException("reading seat " + seat, failure);
    }
  }

  @Override
  public boolean update(SeatId seat, SeatState expected, SeatState next) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = prepare(connection, COMPARE_AND_SET)) {
      Columns after = Columns.of(next);
      Columns before = Columns.of(expected);
      statement.setString(1, after.stateType());
      setText(statement, 2, after.holder());
      setTimestamp(statement, 3, after.expiresAt());
      statement.setString(4, seat.value());
      statement.setString(5, before.stateType());
      setText(statement, 6, before.holder());
      setTimestamp(statement, 7, before.expiresAt());
      if (statement.executeUpdate() == 1) {
        return true;
      }
      requireKnown(connection, seat);
      return false;
    } catch (SQLException failure) {
      throw new SeatStoreException("updating seat " + seat, failure);
    }
  }

  @Override
  public boolean add(SeatId seat) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = prepare(connection, INSERT_FREE)) {
      statement.setString(1, seat.value());
      return statement.executeUpdate() == 1;
    } catch (SQLException failure) {
      throw new SeatStoreException("adding seat " + seat, failure);
    }
  }

  @Override
  public List<SeatId> seats() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = prepare(connection, LIST_SEATS);
        ResultSet rows = statement.executeQuery()) {
      List<SeatId> seats = new ArrayList<>();
      while (rows.next()) {
        seats.add(new SeatId(rows.getString(1)));
      }
      return List.copyOf(seats);
    } catch (SQLException failure) {
      throw new SeatStoreException("listing seats", failure);
    }
  }

  private void requireKnown(Connection connection, SeatId seat) throws SQLException {
    try (PreparedStatement statement = prepare(connection, SELECT_STATE)) {
      statement.setString(1, seat.value());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new UnknownSeatException(seat);
        }
      }
    }
  }

  private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    return statement;
  }

  private static void setText(PreparedStatement statement, int index, String value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.VARCHAR);
    } else {
      statement.setString(index, value);
    }
  }

  private static void setTimestamp(PreparedStatement statement, int index, Instant value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
    } else {
      statement.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
    }
  }

  private static SeatState decode(ResultSet row) throws SQLException {
    String type = row.getString("state_type");
    String holder = row.getString("holder");
    return switch (type) {
      case "FREE" -> new Free();
      case "HELD" ->
          new Held(
              new CustomerId(holder),
              row.getObject("expires_at", OffsetDateTime.class).toInstant());
      case "SOLD" -> new Sold(new CustomerId(holder));
      default -> throw new SeatStoreException("unknown state type in the database: " + type, null);
    };
  }

  /** A state flattened into the table's columns; the database's CHECK constraint mirrors it. */
  private record Columns(String stateType, String holder, Instant expiresAt) {

    static Columns of(SeatState state) {
      return switch (state) {
        case Free free -> new Columns("FREE", null, null);
        case Held held -> new Columns("HELD", held.customer().value(), held.expiresAt());
        case Sold sold -> new Columns("SOLD", sold.customer().value(), null);
      };
    }
  }
}
