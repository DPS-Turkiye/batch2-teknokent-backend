package com.dps.teknokent.dto.requests;

import java.util.UUID;

public record InviteMeetingGuestRequest(UUID organizationId, UUID mentorId) {
}
