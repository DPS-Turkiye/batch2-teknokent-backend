package com.dps.teknokent.util;

public final class TimeFormatUtils {

  private TimeFormatUtils() {}

  public static String formatTime(Double start) {
    if (start == null) {
      return "";
    }
    int m = (int) (start / 60);
    int s = (int) (start % 60);
    return String.format("%02d:%02d", m, s);
  }
}