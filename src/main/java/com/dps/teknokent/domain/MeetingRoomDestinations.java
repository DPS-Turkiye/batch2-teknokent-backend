package com.dps.teknokent.domain;

public final class MeetingRoomDestinations {

	public static final String BROKER_PREFIX = "/topic";
	public static final String APPLICATION_PREFIX = "/app";

	public static final String TOPIC_PREFIX = BROKER_PREFIX + "/meetings/";
	public static final String INBOUND_PREFIX = APPLICATION_PREFIX + "/meetings/";

	public static final String DOCUMENT_SUFFIX = "/document";
	public static final String DOCUMENT_MAPPING = "/meetings/{meetingId}" + DOCUMENT_SUFFIX;

	private MeetingRoomDestinations() {
	}
}
