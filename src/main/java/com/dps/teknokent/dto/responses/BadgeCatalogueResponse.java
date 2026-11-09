package com.dps.teknokent.dto.responses;

import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeKind;
import com.dps.teknokent.domain.UserRole;

public record BadgeCatalogueResponse(BadgeCode code, BadgeKind kind, UserRole grants) {

	public static BadgeCatalogueResponse of(BadgeCode code) {
		return new BadgeCatalogueResponse(code, code.kind(), code.grants());
	}
}
