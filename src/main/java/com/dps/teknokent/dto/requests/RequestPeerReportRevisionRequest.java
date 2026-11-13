package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "Revizyon iste": what the mentor should change, in the coordinator's words. */
public record RequestPeerReportRevisionRequest(UUID actorId, @NotBlank @Size(max = 2000) String note) {
}
