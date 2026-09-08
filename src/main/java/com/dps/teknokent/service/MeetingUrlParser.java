package com.dps.teknokent.service;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class MeetingUrlParser {

  public record Parsed(String platform, String nativeMeetingId) {}

  private static final Pattern GMEET = Pattern.compile("^[a-z]{3}-[a-z]{4}-[a-z]{3}$");

  private MeetingUrlParser() {}

  public static Optional<Parsed> tryParse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(parse(raw));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  public static Parsed parse(String raw) {
    String input = raw.trim();
    String lower = input.toLowerCase(Locale.ROOT);

    if (GMEET.matcher(lower).matches()) {
      return new Parsed("google_meet", lower);
    }

    URI uri = URI.create(input);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

    if (host.contains("meet.google.com")) {
      String[] parts = uri.getPath().split("/");
      String code = parts[parts.length - 1].toLowerCase(Locale.ROOT);
      if (!GMEET.matcher(code).matches()) {
        throw new IllegalArgumentException("Invalid Google Meet link");
      }
      return new Parsed("google_meet", code);
    }

    throw new IllegalArgumentException("Unsupported meeting link");
  }
}