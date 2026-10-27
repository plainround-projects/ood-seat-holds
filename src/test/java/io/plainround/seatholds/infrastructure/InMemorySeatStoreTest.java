package io.plainround.seatholds.infrastructure;

import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.infrastructure.memory.InMemorySeatStore;

class InMemorySeatStoreTest extends SeatStoreContract {

  @Override
  protected SeatStore newStore() {
    return new InMemorySeatStore();
  }
}
