package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.dto.responses.AiFeaturesResponse;
import com.dps.teknokent.exception.AiFeatureDisabledException;

class AiFeaturesTest {

	@Test
	void everyFeatureIsOnWhenNothingIsSwitchedOff() {
		AiFeatures features = TestAiFeatures.allOn();

		for (AiFeature feature : AiFeature.values()) {
			assertThat(features.isEnabled(feature)).isTrue();
			assertThatCode(() -> features.require(feature)).doesNotThrowAnyException();
		}
		assertThat(features.snapshot()).isEqualTo(new AiFeaturesResponse(true, true, true, true, true));
	}

	@ParameterizedTest
	@EnumSource(AiFeature.class)
	void aSwitchedOffFeatureIsRefusedWhileTheOthersStayOn(AiFeature off) {
		AiFeatures features = TestAiFeatures.allOnExcept(off);

		assertThatThrownBy(() -> features.require(off))
				.isInstanceOf(AiFeatureDisabledException.class)
				.satisfies(error -> assertThat(((AiFeatureDisabledException) error).feature()).isEqualTo(off));
		for (AiFeature other : AiFeature.values()) {
			assertThat(features.isEnabled(other)).isEqualTo(other != off);
		}
	}

	@Test
	void theSnapshotNamesEachSwitch() {
		AiFeaturesResponse snapshot = TestAiFeatures
				.allOnExcept(AiFeature.PROFILE_PARSING, AiFeature.SYLLABUS, AiFeature.TASK_HANDOVER)
				.snapshot();

		assertThat(snapshot).isEqualTo(new AiFeaturesResponse(false, false, true, true, false));
	}
}
