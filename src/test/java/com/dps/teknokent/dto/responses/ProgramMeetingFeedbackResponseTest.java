package com.dps.teknokent.dto.responses;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.domain.MeetingFeedbackSide;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.entity.Organization;

class ProgramMeetingFeedbackResponseTest {

	private final Organization team = organization("Döngü");
	private final AppUser mentor = user("Leyla Aksoy", UserRole.MENTOR);
	private final AppUser founder = user("Gülse Buran", UserRole.MENTEE);
	private final Instant meetingAt = Instant.parse("2026-11-05T11:00:00Z");
	private final Instant submittedAt = Instant.parse("2026-11-05T12:30:00Z");

	@Test
	void aMenteesFeedbackNamesTheMeetingTheMentorAndTheTeam() {
		Meeting meeting = meeting(mentor);
		MeetingFeedback feedback = feedback(meeting, founder, List.of(5, 4, 4, 3),
				List.of("Somut aksiyonlar belirlemek", "Bir dahaki sefere satış hunisine bakalım."));

		var row = ProgramMeetingFeedbackResponse.from(feedback);

		assertThat(row.id()).isEqualTo(feedback.getId());
		assertThat(row.meetingAt()).isEqualTo(meetingAt);
		assertThat(row.meetingTitle()).isEqualTo("UX Flow Review");
		assertThat(row.mentorName()).isEqualTo("Leyla Aksoy");
		assertThat(row.organizationName()).isEqualTo("Döngü");
		assertThat(row.authorName()).isEqualTo("Gülse Buran");
		assertThat(row.side()).isEqualTo(MeetingFeedbackSide.MENTEE);
		assertThat(row.ratings()).containsExactly(5, 4, 4, 3);
		assertThat(row.qualities()).containsExactly("Somut aksiyonlar belirlemek");
		assertThat(row.note()).isEqualTo("Bir dahaki sefere satış hunisine bakalım.");
		assertThat(row.submittedAt()).isEqualTo(submittedAt);
	}

	@Test
	void theMeetingsMentorWritesOnTheMentorSide() {
		var row = ProgramMeetingFeedbackResponse.from(
				feedback(meeting(mentor), mentor, List.of(4, 5), List.of("Hazırlıklı")));

		assertThat(row.side()).isEqualTo(MeetingFeedbackSide.MENTOR);
		assertThat(row.qualities()).containsExactly("Hazırlıklı");
		assertThat(row.note()).isNull();
	}

	// A peer mentor is a MENTEE by role; on the meeting they mentor, their feedback is still the mentor's.
	@Test
	void theSideFollowsTheMeetingRatherThanTheAccountsRole() {
		AppUser peerMentor = user("Zeynep Demir", UserRole.MENTEE);

		var row = ProgramMeetingFeedbackResponse.from(feedback(meeting(peerMentor), peerMentor, List.of(3, 3), List.of()));

		assertThat(row.side()).isEqualTo(MeetingFeedbackSide.MENTOR);
	}

	@Test
	void aMeetingWithoutAMentorStillMaps() {
		var row = ProgramMeetingFeedbackResponse.from(feedback(meeting(null), founder, List.of(4), List.of()));

		assertThat(row.mentorName()).isNull();
		assertThat(row.side()).isEqualTo(MeetingFeedbackSide.MENTEE);
	}

	private Meeting meeting(AppUser meetingMentor) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setOrganization(team);
		meeting.setMentor(meetingMentor);
		meeting.setScheduledAt(meetingAt);
		meeting.setAgenda("UX Flow Review");
		return meeting;
	}

	private MeetingFeedback feedback(Meeting meeting, AppUser author, List<Integer> ratings, List<String> benefits) {
		MeetingFeedback feedback = new MeetingFeedback();
		feedback.setId(UUID.randomUUID());
		feedback.setMeeting(meeting);
		feedback.setAuthor(author);
		feedback.setCreatedAt(submittedAt);
		feedback.getRatings().addAll(ratings);
		feedback.getBenefits().addAll(benefits);
		return feedback;
	}

	private static Organization organization(String name) {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName(name);
		return organization;
	}

	private static AppUser user(String name, UserRole role) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setRole(role);
		return user;
	}
}
