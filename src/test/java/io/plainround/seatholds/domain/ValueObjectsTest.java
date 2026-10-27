package io.plainround.seatholds.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.domain.SeatState.Held;
import io.plainround.seatholds.domain.SeatState.Sold;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ValueObjectsTest {

  @Test
  void idsAreNeverBlankAndAreTrimmed() {
    assertThat(new SeatId(" s-9 ")).isEqualTo(new SeatId("s-9")).hasToString("s-9");
    assertThat(new CustomerId(" ada ")).isEqualTo(new CustomerId("ada")).hasToString("ada");
    assertThatThrownBy(() -> new SeatId(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SeatId(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CustomerId("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CustomerId(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aHoldNeedsACustomerAndAnExpiry() {
    assertThatThrownBy(() -> new Held(null, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Held(new CustomerId("ada"), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSaleNeedsACustomer() {
    assertThatThrownBy(() -> new Sold(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anExpiryIsKeptToTheMillisecondSoEveryStoreComparesItTheSame() {
    var precise = new Held(new CustomerId("ada"), Instant.parse("2026-01-01T00:10:00.123456789Z"));

    assertThat(precise.expiresAt()).isEqualTo(Instant.parse("2026-01-01T00:10:00.123Z"));
  }

  @Test
  void theSeatStateIsSealedWithExactlyThreeKinds() {
    assertThat(SeatState.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(SeatState.Free.class, Held.class, Sold.class);
    assertThat(Outcome.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(Outcome.Success.class, Outcome.Reason.class);
  }
}
