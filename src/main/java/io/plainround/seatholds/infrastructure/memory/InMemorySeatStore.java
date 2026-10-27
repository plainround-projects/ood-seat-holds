package io.plainround.seatholds.infrastructure.memory;

import io.plainround.seatholds.domain.SeatId;
import io.plainround.seatholds.domain.SeatState;
import io.plainround.seatholds.domain.SeatState.Free;
import io.plainround.seatholds.domain.SeatStore;
import io.plainround.seatholds.domain.UnknownSeatException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps seats in a concurrent map. {@link Map#replace(Object, Object, Object)} is an atomic
 * compare-and-set: it applies only if the current value equals the expected one, which is exactly
 * what the {@link SeatStore} contract asks for.
 */
public final class InMemorySeatStore implements SeatStore {

  private final Map<SeatId, SeatState> seats = new ConcurrentHashMap<>();

  @Override
  public SeatState get(SeatId seat) {
    SeatState state = seats.get(seat);
    if (state == null) {
      throw new UnknownSeatException(seat);
    }
    return state;
  }

  @Override
  public boolean update(SeatId seat, SeatState expected, SeatState next) {
    if (!seats.containsKey(seat)) {
      throw new UnknownSeatException(seat);
    }
    return seats.replace(seat, expected, next);
  }

  @Override
  public boolean add(SeatId seat) {
    return seats.putIfAbsent(seat, new Free()) == null;
  }

  @Override
  public List<SeatId> seats() {
    return seats.keySet().stream().sorted(Comparator.comparing(SeatId::value)).toList();
  }
}
