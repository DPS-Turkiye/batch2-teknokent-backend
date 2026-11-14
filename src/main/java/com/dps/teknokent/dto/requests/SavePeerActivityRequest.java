package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.PeerActivityType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** "Görüşme dışı faaliyet ekle": the work, its day and how long it took; the mentee is optional. */
public record SavePeerActivityRequest(
		UUID actorId,
		@NotNull LocalDate activityDate,
		@NotNull PeerActivityType type,
		@NotBlank @Size(max = 200) String title,
		@Min(1) @Max(720) int durationMinutes,
		UUID menteeId,
		@Size(max = 2000) String description) {
}
