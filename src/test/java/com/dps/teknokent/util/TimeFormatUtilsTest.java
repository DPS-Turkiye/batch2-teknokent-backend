package com.dps.teknokent.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimeFormatUtilsTest {

  @Test
  void returnsEmptyWhenNull() {
    assertThat(TimeFormatUtils.formatTime(null)).isEmpty();
  }

  @Test
  void formatsUnderOneMinute() {
    assertThat(TimeFormatUtils.formatTime(5.0)).isEqualTo("00:05");
  }

  @Test
  void formatsMinutesAndSeconds() {
    assertThat(TimeFormatUtils.formatTime(65.0)).isEqualTo("01:05");
  }

  @Test
  void formatsExactMinute() {
    assertThat(TimeFormatUtils.formatTime(120.0)).isEqualTo("02:00");
  }
}