package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record AddProgramTeamsRequest(UUID actorId, @NotEmpty @Size(max = 200) List<@Valid TeamInviteInput> teams) {
}
