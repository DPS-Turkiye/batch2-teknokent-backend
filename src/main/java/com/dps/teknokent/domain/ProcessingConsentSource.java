package com.dps.teknokent.domain;

/** Which request the consent came with. */
public enum ProcessingConsentSource {
	/** A parse saved as a profile analysis (the startup profile import). */
	PROFILE_ANALYSIS,
	/** A preview parse of a file sent along (signup, profile editing). */
	PREVIEW_UPLOAD,
	/** A preview parse of a CV the person already stored (profile editing, mentor onboarding). */
	PREVIEW_STORED_CV
}
