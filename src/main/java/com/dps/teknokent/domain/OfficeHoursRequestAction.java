package com.dps.teknokent.domain;

import java.util.List;

/**
 * What the person looking at a request may do with it next. Responses carry the list so a screen never re-derives
 * the backend's rules from the status.
 */
public enum OfficeHoursRequestAction {
	/** The academic takes one of the student's times. */
	ACCEPT,
	DECLINE,
	/** The academic offers up to three other times. */
	PROPOSE_TIMES,
	/** The academic asks the student something before deciding. */
	ASK_QUESTION,
	/** The student answers the academic's question. */
	REPLY,
	/** The student takes one of the times the academic offered. */
	ACCEPT_PROPOSED,
	WITHDRAW,
	/** Either side calls off the booked meeting before it starts. */
	CANCEL;

	private static final List<OfficeHoursRequestAction> ANSWERS = List.of(ACCEPT, DECLINE, PROPOSE_TIMES,
			ASK_QUESTION);

	/**
	 * The same checks the endpoints make: the academic answers pending ones, the student acts on open ones, and
	 * either side may cancel an accepted meeting while it is still ahead.
	 */
	public static List<OfficeHoursRequestAction> allowedFor(OfficeHoursRole viewer, OfficeHoursRequestStatus status,
			boolean meetingAhead) {
		if (status == OfficeHoursRequestStatus.ACCEPTED && meetingAhead) {
			return List.of(CANCEL);
		}
		return allowedFor(viewer, status);
	}

	/** {@link #allowedFor(OfficeHoursRole, OfficeHoursRequestStatus, boolean)} with no meeting ahead. */
	public static List<OfficeHoursRequestAction> allowedFor(OfficeHoursRole viewer, OfficeHoursRequestStatus status) {
		if (viewer == OfficeHoursRole.INSTRUCTOR) {
			return switch (status) {
			case PENDING, MORE_INFO_REQUESTED -> ANSWERS;
			case ACCEPTED, DECLINED, COUNTER_PROPOSED, WITHDRAWN, CANCELLED -> List.of();
			};
		}
		return switch (status) {
		case PENDING -> List.of(WITHDRAW);
		case MORE_INFO_REQUESTED -> List.of(REPLY, WITHDRAW);
		case COUNTER_PROPOSED -> List.of(ACCEPT_PROPOSED, WITHDRAW);
		case ACCEPTED, DECLINED, WITHDRAWN, CANCELLED -> List.of();
		};
	}
}
