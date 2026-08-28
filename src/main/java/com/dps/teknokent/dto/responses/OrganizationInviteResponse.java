package com.dps.teknokent.dto.responses;

import java.util.UUID;

public record OrganizationInviteResponse(UUID organizationId, String inviteCode) {
}
