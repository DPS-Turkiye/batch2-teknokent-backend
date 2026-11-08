package com.dps.teknokent.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.entity.OfficeHoursCourse;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRequest;
import com.dps.teknokent.entity.OfficeHoursUser;
import com.dps.teknokent.repository.OfficeHoursCourseRepository;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.repository.OfficeHoursRequestRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Office-hours people have no Menton account and so no in-app notifications: each step of a request reaches the
 * other side by e-mail, in the language Moodle launched them in. Mail goes out after the change commits, and not at
 * all for someone Moodle shared no e-mail address for. Links go to the Moodle course, since office hours open only
 * from there.
 */
@Service
@RequiredArgsConstructor
public class OfficeHoursMailService {

	/** A step of a request, who hears about it, and which times the e-mail lists. */
	public enum Event {
		NEW_REQUEST("newRequest", true, Times.PREFERRED),
		ACCEPTED("accepted", false, Times.MEETING),
		DECLINED("declined", false, Times.NONE),
		MORE_INFO_REQUESTED("moreInfoRequested", false, Times.NONE),
		COUNTER_PROPOSED("counterProposed", false, Times.PROPOSED),
		REPLIED("replied", true, Times.NONE),
		SLOT_CHOSEN("slotChosen", true, Times.MEETING),
		WITHDRAWN("withdrawn", true, Times.NONE);

		private final String key;
		private final boolean toInstructor;
		private final Times times;

		Event(String key, boolean toInstructor, Times times) {
			this.key = key;
			this.toInstructor = toInstructor;
			this.times = times;
		}
	}

	private enum Times {
		NONE, PREFERRED, PROPOSED, MEETING
	}

	private final MailService mailService;
	private final OfficeHoursUserRepository userRepository;
	private final OfficeHoursCourseRepository courseRepository;
	private final OfficeHoursRequestRepository requestRepository;
	private final OfficeHoursMeetingRepository meetingRepository;

	/** Tells the side that did not act; {@code note} is their message, question, answer or reason, if any. */
	public void requestChanged(OfficeHoursRequest request, Event event, String note) {
		Map<UUID, OfficeHoursUser> people = people(request);
		UUID to = event.toInstructor ? request.getInstructorId() : request.getStudentId();
		UUID from = event.toInstructor ? request.getStudentId() : request.getInstructorId();
		List<Instant> times = switch (event.times) {
			case NONE -> List.of();
			case PREFERRED -> instants(request.getPreferredSlots());
			case PROPOSED -> instants(request.getProposedSlots());
			case MEETING -> meetingRepository.findByRequestId(request.getId())
					.map(meeting -> List.of(meeting.getScheduledAt())).orElse(List.of());
		};
		send(people.get(to), people.get(from), course(request), request.getTopic(), event.key, times, note);
	}

	/** The reminder both sides get shortly before they meet. */
	public void meetingSoon(OfficeHoursMeeting meeting) {
		OfficeHoursRequest request = requestRepository.findById(meeting.getRequestId()).orElse(null);
		if (request == null) {
			return;
		}
		Map<UUID, OfficeHoursUser> people = people(request);
		OfficeHoursUser student = people.get(meeting.getStudentId());
		OfficeHoursUser instructor = people.get(meeting.getInstructorId());
		OfficeHoursCourse course = course(request);
		List<Instant> at = List.of(meeting.getScheduledAt());
		send(student, instructor, course, request.getTopic(), "meetingSoon", at, null);
		send(instructor, student, course, request.getTopic(), "meetingSoon", at, null);
	}

	private void send(OfficeHoursUser to, OfficeHoursUser from, OfficeHoursCourse course, String topic, String event,
			List<Instant> times, String note) {
		if (to == null || from == null || course == null || to.getEmail() == null) {
			return;
		}
		AppLanguage language = to.getPreferredLanguage();
		String key = "officeHours.mail." + event;
		String details = Stream.of(
						times.isEmpty() ? null : times.stream()
								.map(at -> "- " + MeetingSlotTimes.formatForPeople(at, language))
								.collect(Collectors.joining("\n")),
						note == null ? null : Texts.get(language, "officeHours.mail.note", note))
				.filter(Objects::nonNull)
				.collect(Collectors.joining("\n\n"));
		String body = Texts.get(language, "officeHours.mail.body", to.getFullName(),
				Texts.get(language, key + ".line", from.getFullName(), course.getTitle()), details, topic,
				courseLink(course));
		// A step with no times or note leaves an empty paragraph; fold it away.
		mailService.sendAfterCommit(to.getEmail(), Texts.get(language, key + ".subject", course.getTitle()),
				body.replaceAll("\n{3,}", "\n\n"));
	}

	private Map<UUID, OfficeHoursUser> people(OfficeHoursRequest request) {
		return userRepository.findAllById(List.of(request.getStudentId(), request.getInstructorId())).stream()
				.collect(Collectors.toMap(OfficeHoursUser::getId, Function.identity()));
	}

	private OfficeHoursCourse course(OfficeHoursRequest request) {
		return courseRepository.findById(request.getCourseId()).orElse(null);
	}

	private static List<Instant> instants(String joined) {
		return OfficeHoursBookingService.slots(joined).stream().map(MeetingSlotTimes::parseMachineSlot)
				.filter(Objects::nonNull).toList();
	}

	/** The issuer is the Moodle site's address and the context id its course id. */
	static String courseLink(OfficeHoursCourse course) {
		return course.getIssuer().replaceAll("/+$", "") + "/course/view.php?id="
				+ URLEncoder.encode(course.getContextId(), StandardCharsets.UTF_8);
	}
}
