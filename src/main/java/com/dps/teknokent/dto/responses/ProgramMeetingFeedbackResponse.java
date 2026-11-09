package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.MeetingFeedbackAnswers;
import com.dps.teknokent.domain.MeetingFeedbackSide;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingFeedback;

/**
 * One post-meeting feedback for the program report: who rated whom, for which meeting. Names only, no ids or
 * e-mails: the row is read, not acted on. {@code ratings} follow the
 * questions of the author's form, which differ by {@code side}; {@code qualities} are the fixed choices and
 * {@code note} the free text, null when none was written.
 */
public record ProgramMeetingFeedbackResponse(
		UUID id,
		Instant meetingAt,
		String meetingTitle,
		String mentorName,
		String organizationName,
		String authorName,
		MeetingFeedbackSide side,
		List<Integer> ratings,
		List<String> qualities,
		String note,
		Instant submittedAt) {

	public static ProgramMeetingFeedbackResponse from(MeetingFeedback feedback) {
		Meeting meeting = feedback.getMeeting();
		AppUser mentor = meeting.getMentor();
		AppUser author = feedback.getAuthor();
		// The side follows the meeting, not the account: one person can mentor in one program and be mentored in another.
		MeetingFeedbackSide side = mentor != null && mentor.getId().equals(author.getId())
				? MeetingFeedbackSide.MENTOR
				: MeetingFeedbackSide.MENTEE;
		MeetingFeedbackAnswers.Split answers = MeetingFeedbackAnswers.split(feedback.getBenefits(), side);
		return new ProgramMeetingFeedbackResponse(
				feedback.getId(),
				meeting.getScheduledAt(),
				meeting.getAgenda(),
				mentor == null ? null : mentor.getFullName(),
				meeting.getOrganization().getName(),
				author.getFullName(),
				side,
				List.copyOf(feedback.getRatings()),
				answers.qualities(),
				answers.note(),
				feedback.getCreatedAt());
	}
}
