package com.dps.teknokent.dto.responses;

import java.util.UUID;

/** {@code cleanupPending} is true while a stored file is still being removed in the background. */
public record StartupDeletionResponse(UUID deletedOrganizationId, boolean cleanupPending) {
}
