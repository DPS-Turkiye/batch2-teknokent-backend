package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.Size;

/** The optional note is read by the program's coordinators and admins, never by the team. */
public record MarkMeetingNoShowRequest(@Size(max = 255) String note) {
}
