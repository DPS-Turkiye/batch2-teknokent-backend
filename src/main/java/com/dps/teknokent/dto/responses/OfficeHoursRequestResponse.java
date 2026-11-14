package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.OfficeHoursMessageType;
import com.dps.teknokent.domain.OfficeHoursRequestAction;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;

public record OfficeHoursRequestResponse(
		UUID id,
		UUID courseId,
		OfficeHoursRequestStatus status,
		@Nullable OfficeHoursPersonResponse student,
		@Nullable OfficeHoursPersonResponse instructor,
		String topic,
		String expectedOutcome,
		List<String> preferredSlots,
		List<String> proposedSlots,
		String instructorMessage,
		String rejectionReason,
		List<Message> messages,
		@Nullable OfficeHoursSlotResponse meeting,
		Instant createdAt,
		/* The course's title from Moodle. */
		@Nullable String courseName,
		/* {@code preferredSlots} as time ranges with Turkey's offset. */
		List<OfficeHoursTimeResponse> preferredTimes,
		/* {@code proposedSlots} as time ranges with Turkey's offset. */
		List<OfficeHoursTimeResponse> proposedTimes,
		/* What the caller may do next with this request. */
		List<OfficeHoursRequestAction> allowedActions) {

	public record Message(UUID id, UUID authorId, OfficeHoursMessageType type, String body, Instant createdAt) {
	}
}
