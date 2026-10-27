package io.plainround.seatholds.interfaces.cli;

import io.plainround.seatholds.application.SeatHoldService;
import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.domain.SeatStoreException;
import io.plainround.seatholds.infrastructure.jdbc.JdbcSeatStore;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;
import io.plainround.seatholds.infrastructure.time.SystemClock;
import java.util.List;
import java.util.stream.IntStream;

/** The composition root: builds the chosen store and the service, and closes what it opened. */
final class Application implements AutoCloseable {

  private final AutoCloseable resource;
  private final SeatStore store;
  private final SeatHoldService service;
  private final List<SeatId> seatIds;

  Application(AppConfig config) {
    if (config.store() == AppConfig.StoreKind.JDBC) {
      JdbcSeatStore jdbc = JdbcSeatStore.open(config.database());
      store = jdbc;
      resource = jdbc;
    } else {
      store = new InMemorySeatStore();
      resource = () -> {};
    }
    seatIds = IntStream.rangeClosed(1, config.seats()).mapToObj(n -> new SeatId("s-" + n)).toList();
    seatIds.forEach(store::add);
    service = new SeatHoldService(store, new SystemClock(), config.holdFor());
  }

  SeatStore store() {
    return store;
  }

  SeatHoldService service() {
    return service;
  }

  List<SeatId> seatIds() {
    return seatIds;
  }

  @Override
  public void close() {
    try {
      resource.close();
    } catch (Exception failure) {
      throw new SeatStoreException("closing the store", failure);
    }
  }
}
