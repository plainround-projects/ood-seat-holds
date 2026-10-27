package io.plainround.seatholds.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatholds.interfaces.cli.AppConfig;
import io.plainround.seatholds.interfaces.cli.AppConfig.StoreKind;
import io.plainround.seatholds.interfaces.cli.ConfigurationException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppConfigTest {

  @Test
  void defaultsAreAMemoryStoreTenMinuteHoldsAndTenSeats() {
    var config = AppConfig.from(Map.of());

    assertThat(config.store()).isEqualTo(StoreKind.MEMORY);
    assertThat(config.holdFor()).isEqualTo(Duration.ofMinutes(10));
    assertThat(config.sweepEvery()).isEqualTo(Duration.ofSeconds(30));
    assertThat(config.seats()).isEqualTo(10);
    assertThat(config.database()).isNull();
  }

  @Test
  void theJdbcStoreNeedsADatabaseAndItsToStringHidesThePassword() {
    var config =
        AppConfig.from(
            Map.of(
                "STORE", "jdbc",
                "DATABASE_URL", "jdbc:postgresql://localhost:5432/seatholds",
                "DATABASE_USER", "seatholds",
                "DATABASE_PASSWORD", "hunter2",
                "POOL_SIZE", "4",
                "CONNECTION_TIMEOUT", "PT45S"));

    assertThat(config.store()).isEqualTo(StoreKind.JDBC);
    assertThat(config.database().poolSize()).isEqualTo(4);
    assertThat(config.database().connectionTimeout()).isEqualTo(Duration.ofSeconds(45));
    assertThat(config.database().toString()).doesNotContain("hunter2");
  }

  @Test
  void badValuesAreRefusedWithTheVariableName() {
    assertThatThrownBy(() -> AppConfig.from(Map.of("STORE", "mongo")))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> AppConfig.from(Map.of("HOLD_DURATION", "ten minutes")))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> AppConfig.from(Map.of("HOLD_DURATION", "PT0S")))
        .hasMessageContaining("HOLD_DURATION");
    assertThatThrownBy(() -> AppConfig.from(Map.of("SWEEP_INTERVAL", "-PT1S")))
        .hasMessageContaining("SWEEP_INTERVAL");
    assertThatThrownBy(() -> AppConfig.from(Map.of("SEATS", "0"))).hasMessageContaining("SEATS");
    assertThatThrownBy(() -> AppConfig.from(Map.of("SEATS", "ten")))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> AppConfig.from(Map.of("LOG_LEVEL", "LOUD")))
        .hasMessageContaining("LOG_LEVEL");
  }

  @Test
  void theJdbcStoreWithoutAUrlOrWithAnEmptyPoolIsRefused() {
    assertThatThrownBy(() -> AppConfig.from(Map.of("STORE", "jdbc")))
        .hasMessageContaining("DATABASE_URL");
    assertThatThrownBy(
            () ->
                AppConfig.from(
                    Map.of(
                        "STORE", "jdbc",
                        "DATABASE_URL", "jdbc:postgresql://localhost/x",
                        "DATABASE_USER", "u",
                        "DATABASE_PASSWORD", "p",
                        "POOL_SIZE", "0")))
        .hasMessageContaining("POOL_SIZE");
    assertThatThrownBy(
            () ->
                AppConfig.from(
                    Map.of(
                        "STORE", "jdbc",
                        "DATABASE_URL", "jdbc:postgresql://localhost/x")))
        .hasMessageContaining("DATABASE_USER");
    assertThatThrownBy(
            () ->
                AppConfig.from(
                    Map.of(
                        "STORE", "jdbc",
                        "DATABASE_URL", "jdbc:postgresql://localhost/x",
                        "DATABASE_USER", "u",
                        "DATABASE_PASSWORD", "p",
                        "CONNECTION_TIMEOUT", "PT0.5S")))
        .hasMessageContaining("CONNECTION_TIMEOUT");
  }
}
