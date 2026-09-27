package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.Size;

public record DeclineMeetingProposalRequest(UUID actorId, @Size(max = 500) String reason) {
}
