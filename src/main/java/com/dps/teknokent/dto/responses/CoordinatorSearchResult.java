package com.dps.teknokent.dto.responses;

import java.util.UUID;

/** One approved coordinator matching the admin's email search. */
public record CoordinatorSearchResult(UUID id, String fullName, String email) {
}
