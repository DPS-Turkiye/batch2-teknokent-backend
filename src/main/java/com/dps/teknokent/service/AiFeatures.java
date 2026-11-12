package com.dps.teknokent.service;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.dto.responses.AiFeaturesResponse;
import com.dps.teknokent.exception.AiFeatureDisabledException;

/**
 * Which {@link AiFeature}s this deployment may use. Every switch defaults to on, so a deployment that sets
 * none of them behaves as before; ODTÜ's turns them off one by one.
 */
@Component
public class AiFeatures {

	private final Set<AiFeature> enabled = EnumSet.noneOf(AiFeature.class);

	public AiFeatures(
			@Value("${ai.features.profile-parsing:true}") boolean profileParsing,
			@Value("${ai.features.syllabus:true}") boolean syllabus,
			@Value("${ai.features.matching:true}") boolean matching,
			@Value("${ai.features.meeting-report:true}") boolean meetingReport,
			@Value("${ai.features.task-handover:true}") boolean taskHandover) {
		if (profileParsing) enabled.add(AiFeature.PROFILE_PARSING);
		if (syllabus) enabled.add(AiFeature.SYLLABUS);
		if (matching) enabled.add(AiFeature.MATCHING);
		if (meetingReport) enabled.add(AiFeature.MEETING_REPORT);
		if (taskHandover) enabled.add(AiFeature.TASK_HANDOVER);
	}

	public boolean isEnabled(AiFeature feature) {
		return enabled.contains(feature);
	}

	/** Refuses the request with {@code AI_FEATURE_DISABLED} when {@code feature} is off. */
	public void require(AiFeature feature) {
		if (!isEnabled(feature)) {
			throw new AiFeatureDisabledException(feature);
		}
	}

	public AiFeaturesResponse snapshot() {
		return new AiFeaturesResponse(
				isEnabled(AiFeature.PROFILE_PARSING),
				isEnabled(AiFeature.SYLLABUS),
				isEnabled(AiFeature.MATCHING),
				isEnabled(AiFeature.MEETING_REPORT),
				isEnabled(AiFeature.TASK_HANDOVER));
	}
}
