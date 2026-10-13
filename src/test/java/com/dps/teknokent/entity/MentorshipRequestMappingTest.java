package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MentorshipRequestMappingTest {

	@Test
	void createsRequestWithNormalizedValuesAndPendingStatus() {
		AppUser requester = user("Mentee");
		AppUser mentor = user("Mentor");
		Organization organization = organization("NovaMed");
		CreateMentorshipRequestSubmission submission = new CreateMentorshipRequestSubmission(
				requester.getId(), organization.getId(), mentor.getId(),
				"  NovaMed ekibi  ", "  İş modeli ve strateji  ", "  Gelir modeli desteği  ",
				MentorshipRequestUrgency.THIS_WEEK, MentorshipRequestFormat.SINGLE_MEETING,
				MeetingMode.ONLINE, "   ", null, null);

		MentorshipRequest request = MentorshipRequest.fromRequest(
				submission, requester, organization, mentor);

		assertThat(request.getRequestedForName()).isEqualTo("NovaMed ekibi");
		assertThat(request.getSupportArea()).isEqualTo("İş modeli ve strateji");
		assertThat(request.getSummary()).isEqualTo("Gelir modeli desteği");
		assertThat(request.getRequesterMessage()).isNull();
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.PENDING);
	}

	@Test
	void mapsEntityAndRelatedValuesToResponse() {
		AppUser requester = user("Ayşe Mentee");
		AppUser mentor = user("Kemal Mentor");
		Organization organization = organization("NovaMed");
		MentorshipRequest request = MentorshipRequest.fromRequest(
				new CreateMentorshipRequestSubmission(
						requester.getId(), organization.getId(), mentor.getId(), "NovaMed", "Ürün",
						"MVP testi", MentorshipRequestUrgency.THIS_WEEK,
						MentorshipRequestFormat.SINGLE_MEETING, MeetingMode.ONLINE, "Merhaba", null, null),
				requester, organization, mentor);
		request.setId(UUID.randomUUID());
		request.setCreatedAt(Instant.parse("2026-08-17T10:00:00Z"));
		request.setUpdatedAt(Instant.parse("2026-08-17T10:01:00Z"));

		MentorshipRequestResponse response = MentorshipRequestResponse.fromEntity(request, List.of(), null);

		assertThat(response.id()).isEqualTo(request.getId());
		assertThat(response.requesterName()).isEqualTo("Ayşe Mentee");
		assertThat(response.mentorName()).isEqualTo("Kemal Mentor");
		assertThat(response.organizationName()).isEqualTo("NovaMed");
		assertThat(response.messages()).isEmpty();
		assertThat(response.recommendationSnapshot()).isNull();
	}

	@Test
	void keepsTheExpectedOutcomeAsItsOwnField() {
		AppUser requester = user("Mentee");
		AppUser mentor = user("Mentor");
		Organization organization = organization("NovaMed");
		MentorshipRequest request = MentorshipRequest.fromRequest(
				new CreateMentorshipRequestSubmission(
						requester.getId(), organization.getId(), mentor.getId(), "NovaMed", "Finans",
						"Finansal model", MentorshipRequestUrgency.THIS_WEEK,
						MentorshipRequestFormat.SINGLE_MEETING, MeetingMode.ONLINE, "Merhaba", null,
						"  İlk finansal modelin çerçevesi  "),
				requester, organization, mentor);

		assertThat(request.getExpectedOutcome()).isEqualTo("İlk finansal modelin çerçevesi");
		assertThat(MentorshipRequestResponse.fromEntity(request, List.of(), null).expectedOutcome())
				.isEqualTo("İlk finansal modelin çerçevesi");

		MentorshipRequest blank = MentorshipRequest.fromRequest(
				new CreateMentorshipRequestSubmission(
						requester.getId(), organization.getId(), mentor.getId(), "NovaMed", "Finans",
						"Finansal model", MentorshipRequestUrgency.THIS_WEEK,
						MentorshipRequestFormat.SINGLE_MEETING, MeetingMode.ONLINE, null, null, "   "),
				requester, organization, mentor);
		assertThat(blank.getExpectedOutcome()).isNull();
	}

	private AppUser user(String fullName) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(fullName);
		user.setEmail(fullName.toLowerCase().replace(' ', '.') + "@example.com");
		return user;
	}

	private Organization organization(String name) {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName(name);
		return organization;
	}
}
