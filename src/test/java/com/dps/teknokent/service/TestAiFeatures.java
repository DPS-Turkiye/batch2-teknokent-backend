package com.dps.teknokent.service;

import java.util.Set;

import com.dps.teknokent.domain.AiFeature;

/** {@link AiFeatures} for tests, named by what is switched off rather than by five booleans. */
public final class TestAiFeatures {

	private TestAiFeatures() {
	}

	public static AiFeatures allOn() {
		return allOnExcept();
	}

	public static AiFeatures allOnExcept(AiFeature... off) {
		Set<AiFeature> disabled = Set.of(off);
		return new AiFeatures(
				!disabled.contains(AiFeature.PROFILE_PARSING),
				!disabled.contains(AiFeature.SYLLABUS),
				!disabled.contains(AiFeature.MATCHING),
				!disabled.contains(AiFeature.MEETING_REPORT),
				!disabled.contains(AiFeature.TASK_HANDOVER));
	}
}
