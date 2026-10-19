package com.dps.teknokent.domain;

import java.time.Duration;

public final class TranscriptBotConstants {

	public static final int DISPATCH_LOOKBACK_HOURS = 1;
	public static final int DISPATCH_LOOKAHEAD_MINUTES = 2;
	public static final int DEFAULT_DURATION_MINUTES = 60;
	public static final int BOT_JOIN_LEAD_MINUTES = 5;
	public static final int DISPATCH_GRACE_MINUTES = 60;
	public static final int JOIN_GRACE_MINUTES = 3;
	public static final int SLOT_GRACE_MINUTES = 2;
	public static final int MAX_WAIT_FOR_ADMISSION_MS = 15 * 60 * 1000;
	public static final int MAX_TIME_LEFT_ALONE_MS = 90_000;
	public static final Duration EMPTY_ROOM = Duration.ofMillis(MAX_TIME_LEFT_ALONE_MS);

	private TranscriptBotConstants() {
	}
}
