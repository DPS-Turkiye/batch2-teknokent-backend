package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AppointmentKind;
import com.dps.teknokent.domain.AppointmentStatus;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.UserRole;

/**
 * Frontend Appointment: one booked session in the student workspace's Randevularım and Ana Sayfa. {@code side} is the
 * viewer's side, read from the request (MENTOR when they are its mentor), never from the account.
 */
public record AppointmentResponse(
		UUID id,
		UUID requestId,
		AppointmentKind kind,
		UserRole side,
		AppointmentStatus status,
		@Nullable PeerTopicCategory category,
		String topic,
		Counterpart counterpart,
		Instant startsAt,
		Instant endsAt,
		@Nullable String place,
		String onlineUrl,
		@Nullable Time alternative,
		@Nullable String rejectReason) {

	public record Counterpart(UUID userId, String fullName, @Nullable String title, @Nullable String department) {
	}

	public record Time(Instant startsAt, Instant endsAt) {
	}
}
