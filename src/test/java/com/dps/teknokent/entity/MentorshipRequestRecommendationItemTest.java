package com.dps.teknokent.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.dto.requests.RecommendationItemSubmission;
import com.dps.teknokent.dto.responses.MentorProfileResponse;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class MentorshipRequestRecommendationItemTest {

	private static final UUID MENTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void acceptsAMentorWithoutATitleOrCompany() {
		assertThat(validator.validate(submission(""))).isEmpty();
		assertThat(validator.validate(submission(null))).isEmpty();
	}

	@Test
	void storesAMissingTitleAndCompanyAsEmpty() {
		MentorProfileResponse mentor = new MentorProfileResponse(MENTOR_ID, "Arya Zaeri", "arya@example.com", "AZ",
				null, null, "", "", "Online", "", "", "", "", "", "", List.of(), List.of(), List.of());

		MentorshipRequestRecommendationItem item = MentorshipRequestRecommendationItem.fromSubmission(
				null, submission(null), new AppUser(), mentor, List.of());

		assertThat(item.getMentorName()).isEqualTo("Arya Zaeri");
		assertThat(item.getProfessionalTitle()).isEmpty();
		assertThat(item.getCompany()).isEmpty();
	}

	private static RecommendationItemSubmission submission(String titleAndCompany) {
		return new RecommendationItemSubmission(
				MENTOR_ID, 1, true, "Arya Zaeri", titleAndCompany, titleAndCompany, List.of(), List.of());
	}
}
