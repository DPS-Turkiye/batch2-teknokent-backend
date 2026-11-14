package com.dps.teknokent.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class MeetingBotStateTest {

  @ParameterizedTest
  @CsvSource({
      "requested, REQUESTED",
      "joining, JOINING",
      "awaiting_admission, WAITING_FOR_ADMISSION",
      "Active, ACTIVE",
      "needs_help, NEEDS_HELP",
      "stopping, STOPPING",
      "completed, COMPLETED",
      "failed, FAILED",
      "something_new, FAILED",
  })
  void mapsEveryProviderStatus(String provider, MeetingBotState expected) {
    assertThat(MeetingBotState.fromProvider(provider)).isEqualTo(expected);
  }

  @Test
  void aMissingStatusMeansNoBot() {
    assertThat(MeetingBotState.fromProvider(null)).isEqualTo(MeetingBotState.NONE);
    assertThat(MeetingBotState.fromProvider(" ")).isEqualTo(MeetingBotState.NONE);
  }

  @Test
  void onlyStatesVexaCountsAsActiveHoldTheMeeting() {
    assertThat(MeetingBotState.WAITING_FOR_ADMISSION.holdsMeeting()).isTrue();
    assertThat(MeetingBotState.STOPPING.holdsMeeting()).isTrue();
    assertThat(MeetingBotState.NEEDS_HELP.holdsMeeting()).isFalse();
    assertThat(MeetingBotState.FAILED.holdsMeeting()).isFalse();
    assertThat(MeetingBotState.NONE.holdsMeeting()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
      "stopped, STOPPED",
      "left_alone, LEFT_ALONE",
      "awaiting_admission_timeout, NOT_ADMITTED_IN_TIME",
      "awaiting_admission_rejected, REJECTED",
      "join_failure, JOIN_FAILED",
      "max_bot_time_exceeded, OTHER",
  })
  void mapsEveryProviderEndReason(String provider, MeetingBotEndReason expected) {
    assertThat(MeetingBotEndReason.fromProvider(provider)).isEqualTo(expected);
  }

  @Test
  void aMissingEndReasonStaysEmpty() {
    assertThat(MeetingBotEndReason.fromProvider(null)).isNull();
  }
}
