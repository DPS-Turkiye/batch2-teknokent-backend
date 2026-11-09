package com.dps.teknokent.domain;

/**
 * Where a student stands with their peer program's training. BLOCKED: their badge there was taken back, so only a
 * coordinator or admin can award it again (the quiz cannot).
 */
public enum TrainingState {
	NOT_AVAILABLE,
	VIDEO,
	QUIZ,
	BADGE_ACTIVE,
	BLOCKED
}
