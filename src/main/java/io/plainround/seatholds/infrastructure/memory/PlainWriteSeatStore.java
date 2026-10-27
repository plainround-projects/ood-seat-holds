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
 * The mistake the lesson warns about, kept as a counter-example: {@code update} is a plain write
 * that ignores {@code expected} and always reports success. Two callers who both read a free seat
 * both think they won it, so the seat sells twice. It exists only so the scenario and the tests can
 * show that failure; no composition root wires it in.
 */
public final class PlainWriteSeatStore implements SeatStore {

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
    seats.put(seat, next);
    return true;
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
