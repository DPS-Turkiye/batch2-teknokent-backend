package com.dps.teknokent.dto.responses;

import java.time.Instant;

import com.dps.teknokent.dto.requests.PeerMentorProfileDraft;

/** The peer mentor's saved draft and when it was last saved ("Taslak kaydedildi · 14:05"). */
public record PeerMentorProfileDraftResponse(PeerMentorProfileDraft draft, Instant updatedAt) {
}
