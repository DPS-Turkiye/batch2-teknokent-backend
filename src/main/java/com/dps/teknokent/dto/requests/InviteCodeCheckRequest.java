package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Same cap as {@link RegisterMemberRequest#inviteCode()}, so a code the check accepts can be registered with. */
public record InviteCodeCheckRequest(@NotBlank @Size(max = 32) String code) {
}
