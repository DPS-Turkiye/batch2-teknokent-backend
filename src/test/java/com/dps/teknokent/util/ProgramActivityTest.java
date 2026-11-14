package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Program;

class ProgramActivityTest {

	private static final Instant MOVE = Instant.parse("2026-11-13T12:00:00Z");

	private final Program oldProgram = program();
	private final Program newProgram = program();
	private final AppUser mentor = user();
	private final AppUser otherMentor = user();

	@Test
	void aMeetingBookedOnARequestCountsInThatRequestsProgram() {
		Meeting meeting = meeting(mentor, MOVE.plusSeconds(60), request(oldProgram));

		assertThat(ProgramActivity.programOf(meeting, List.of(mentorship(mentor, newProgram, MOVE))))
				.isEqualTo(oldProgram.getId());
	}

	// A follow-up meeting's own request names no program.
	@Test
	void aFollowUpMeetingCountsInTheProgramOfItsMentorsMentorship() {
		Meeting meeting = meeting(mentor, MOVE.plusSeconds(60), request(null));

		assertThat(ProgramActivity.programOf(meeting, List.of(
				mentorship(mentor, oldProgram, MOVE.minusSeconds(3600)),
				mentorship(otherMentor, newProgram, MOVE))))
				.isEqualTo(oldProgram.getId());
	}

	@Test
	void withTheSameMentorInBothProgramsTheMentorshipRunningAtTheTimeDecides() {
		List<Mentorship> both = List.of(
				mentorship(mentor, newProgram, MOVE),
				mentorship(mentor, oldProgram, MOVE.minusSeconds(3600)));

		assertThat(ProgramActivity.programOf(meeting(mentor, MOVE.minusSeconds(60), null), both))
				.isEqualTo(oldProgram.getId());
		assertThat(ProgramActivity.programOf(meeting(mentor, MOVE.plusSeconds(60), null), both))
				.isEqualTo(newProgram.getId());
		assertThat(ProgramActivity.programOf(meeting(mentor, MOVE.minusSeconds(7200), null), both))
				.isEqualTo(oldProgram.getId());
	}

	@Test
	void nothingSaysWhichProgramWhenThePairHasNoMentorshipWithAProgram() {
		assertThat(ProgramActivity.programOf(meeting(mentor, MOVE, null), List.of())).isNull();
		assertThat(ProgramActivity.programOf(meeting(mentor, MOVE, request(null)),
				List.of(mentorship(mentor, null, MOVE), mentorship(otherMentor, oldProgram, MOVE)))).isNull();
		assertThat(ProgramActivity.programOf(meeting(null, MOVE, null), List.of(mentorship(mentor, oldProgram, MOVE))))
				.isNull();
	}

	private static Meeting meeting(AppUser mentor, Instant at, MentorshipRequest request) {
		Meeting meeting = new Meeting();
		meeting.setMentor(mentor);
		meeting.setScheduledAt(at);
		meeting.setMentorshipRequest(request);
		return meeting;
	}

	private static MentorshipRequest request(Program program) {
		MentorshipRequest request = new MentorshipRequest();
		request.setProgram(program);
		return request;
	}

	private static Mentorship mentorship(AppUser mentor, Program program, Instant createdAt) {
		Mentorship mentorship = new Mentorship();
		mentorship.setMentor(mentor);
		mentorship.setProgram(program);
		mentorship.setCreatedAt(createdAt);
		return mentorship;
	}

	private static Program program() {
		Program program = new Program();
		program.setId(UUID.randomUUID());
		return program;
	}

	private static AppUser user() {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		return user;
	}
}
