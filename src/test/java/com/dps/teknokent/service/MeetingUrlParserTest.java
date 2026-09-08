package com.dps.teknokent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingUrlParserTest {

  @Test
  void parsesGoogleMeetUrl() {
    var parsed = MeetingUrlParser.parse("https://meet.google.com/abc-defg-hij");

    assertThat(parsed.platform()).isEqualTo("google_meet");
    assertThat(parsed.nativeMeetingId()).isEqualTo("abc-defg-hij");
  }

  @Test
  void parsesBareMeetingCode() {
    var parsed = MeetingUrlParser.parse("abc-defg-hij");

    assertThat(parsed.platform()).isEqualTo("google_meet");
    assertThat(parsed.nativeMeetingId()).isEqualTo("abc-defg-hij");
  }

  @Test
  void rejectsInvalidGoogleMeetCodeInUrl() {
    assertThatThrownBy(() -> MeetingUrlParser.parse("https://meet.google.com/not-valid"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid Google Meet");
  }

  @Test
  void tryParseIgnoresQueryParametersOnGoogleMeetLinks() {
    assertThat(MeetingUrlParser.tryParse("https://meet.google.com/abc-defg-hij?authuser=0"))
        .hasValueSatisfying(parsed -> assertThat(parsed.nativeMeetingId()).isEqualTo("abc-defg-hij"));
  }

  @Test
  void tryParseReturnsEmptyForBlankInput() {
    assertThat(MeetingUrlParser.tryParse("  ")).isEmpty();
  }

  @Test
  void rejectsUnsupportedPlatform() {
    assertThatThrownBy(() -> MeetingUrlParser.parse("https://zoom.us/j/123456789"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported");
  }
}