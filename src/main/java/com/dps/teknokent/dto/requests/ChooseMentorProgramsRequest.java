package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChooseMentorProgramsRequest(@NotNull @Size(max = 50) List<@NotNull UUID> programIds) {
}
