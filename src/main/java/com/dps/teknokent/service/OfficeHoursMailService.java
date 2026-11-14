package com.dps.teknokent.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.OfficeHoursCourse;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRequest;
import com.dps.teknokent.entity.OfficeHoursUser;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OfficeHoursCourseRepository;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.repository.OfficeHoursRequestRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Each step of a request reaches the other side by e-mail. Mail is queued with the change and sent once it commits
 * ({@link OfficeHoursMailOutboxService}). A Moodle person gets it at the address and in the language Moodle launched
 * them with, linking to the course; a Menton account (no course) at its own address and language, linking to its
 * office hours in Menton. Nothing goes to someone with no address or a deleted account.
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
	private final OfficeHoursMailOutboxService outbox;
	private final OfficeHoursUserRepository userRepository;
	private final OfficeHoursCourseRepository courseRepository;
	private final OfficeHoursRequestRepository requestRepository;
	private final OfficeHoursMeetingRepository meetingRepository;
	private final AppUserRepository appUserRepository;

	/** Tells the side that did not act; {@code note} is their message, question, answer or reason, if any. */
	public void requestChanged(OfficeHoursRequest request, Event event, @Nullable String note) {
		Map<UUID, OfficeHoursUser> people = people(request);
		UUID to = request.getStudentId();
		UUID from = request.getInstructorId();
		if (event.toInstructor) {
			to = request.getInstructorId();
			from = request.getStudentId();
		}
		List<Instant> times = switch (event.times) {
		case NONE -> List.of();
		case PREFERRED -> instants(request.getPreferredSlots());
		case PROPOSED -> instants(request.getProposedSlots());
		case MEETING -> meetingRepository.findByRequestId(request.getId())
				.map(meeting -> List.of(meeting.getScheduledAt())).orElse(List.of());
		};
		compose(people.get(to), people.get(from), course(request), request.getTopic(), event.key, times, note,
				event.toInstructor)
				.ifPresent(mail -> outbox.enqueue(mail.to(), mail.subject(), mail.body()));
	}

	/** Which side of a meeting a reminder is for. */
	public enum Side {
		STUDENT, INSTRUCTOR
	}

	/** How a reminder went: sent, nothing to send (no address, mail switched off), or failed and worth retrying. */
	public enum Delivery {
		SENT, NOTHING_TO_SEND, FAILED
	}

	/**
	 * The reminder one side gets before they meet, sent now rather than after a commit, so the caller learns whether
	 * it went and can try again later.
	 */
	public Delivery meetingSoon(OfficeHoursMeeting meeting, Side side) {
		OfficeHoursRequest request = requestRepository.findById(meeting.getRequestId()).orElse(null);
		if (request == null || !mailService.isConfigured()) {
			return Delivery.NOTHING_TO_SEND;
		}
		Map<UUID, OfficeHoursUser> people = people(request);
		OfficeHoursUser to = people.get(meeting.getStudentId());
		OfficeHoursUser from = people.get(meeting.getInstructorId());
		boolean toInstructor = side == Side.INSTRUCTOR;
		if (toInstructor) {
			to = people.get(meeting.getInstructorId());
			from = people.get(meeting.getStudentId());
		}
		Optional<Mail> mail = compose(to, from, course(request), request.getTopic(), "meetingSoon",
				List.of(meeting.getScheduledAt()), null, toInstructor);
		if (mail.isEmpty()) {
			return Delivery.NOTHING_TO_SEND;
		}
		if (mailService.sendNow(mail.get().to(), mail.get().subject(), mail.get().body())) {
			return Delivery.SENT;
		}
		return Delivery.FAILED;
	}

	private record Mail(String to, String subject, String body) {
	}

	/** Where and how an e-mail reaches one person, and the page it sends them to. */
	private record Recipient(String name, String email, AppLanguage language, String link) {
	}

	/**
	 * The e-mail for one person, or nothing when either side is gone or there is no address. A request from Menton has
	 * no course: its text names no course and its subject names the other person.
	 */
	private Optional<Mail> compose(@Nullable OfficeHoursUser to, @Nullable OfficeHoursUser from,
			@Nullable OfficeHoursCourse course, String topic, String event, List<Instant> times, @Nullable String note,
			boolean toInstructor) {
		if (to == null || from == null) {
			return Optional.empty();
		}
		Optional<Recipient> found = recipient(to, course, toInstructor);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		Recipient recipient = found.get();
		AppLanguage language = recipient.language();
		String key = "officeHours.mail." + event;
		String line = Texts.get(language, key + ".mentonLine", from.getFullName());
		String subjectName = from.getFullName();
		String body = "officeHours.mail.mentonBody";
		if (course != null) {
			line = Texts.get(language, key + ".line", from.getFullName(), course.getTitle());
			subjectName = course.getTitle();
			body = "officeHours.mail.body";
		}
		// The times, then the other side's message: whichever the step has.
		List<String> details = new ArrayList<>();
		if (!times.isEmpty()) {
			details.add(times.stream().map(at -> "- " + MeetingSlotTimes.formatForPeople(at, language))
					.collect(Collectors.joining("\n")));
		}
		if (note != null) {
			details.add(Texts.get(language, "officeHours.mail.note", note));
		}
		String text = Texts.get(language, body, recipient.name(), line, String.join("\n\n", details), topic,
				recipient.link());
		// A step with no times or note leaves an empty paragraph; fold it away.
		return Optional.of(new Mail(recipient.email(), Texts.get(language, key + ".subject", subjectName),
				text.replaceAll("\n{3,}", "\n\n")));
	}

	/**
	 * A Menton account's own address and language win over what Moodle shared, since the account is where they read
	 * Menton's mail; a Moodle-only person needs a course to be sent to.
	 */
	private Optional<Recipient> recipient(OfficeHoursUser person, @Nullable OfficeHoursCourse course,
			boolean instructor) {
		if (person.getAppUserId() != null) {
			Optional<AppUser> account = appUserRepository.findById(person.getAppUserId())
					.filter(found -> found.getStatus() != UserAccountStatus.DELETED);
			if (account.isEmpty()) {
				return Optional.empty();
			}
			String link = mentonLink(instructor);
			if (course != null) {
				link = courseLink(course);
			}
			return Optional.of(new Recipient(person.getFullName(), account.get().getEmail(),
					account.get().getPreferredLanguage(), link));
		}
		if (person.getEmail() == null || course == null) {
			return Optional.empty();
		}
		return Optional.of(new Recipient(person.getFullName(), person.getEmail(), person.getPreferredLanguage(),
				courseLink(course)));
	}

	/** The academician's Randevularım or the student's Ofis Saatleri page in Menton. */
	private String mentonLink(boolean instructor) {
		if (instructor) {
			return mailService.link("/academician/appointments");
		}
		return mailService.link("/student/office-hours");
	}

	private Map<UUID, OfficeHoursUser> people(OfficeHoursRequest request) {
		return userRepository.findAllById(List.of(request.getStudentId(), request.getInstructorId())).stream()
				.collect(Collectors.toMap(OfficeHoursUser::getId, Function.identity()));
	}

	/** None for a request made from Menton. */
	private @Nullable OfficeHoursCourse course(OfficeHoursRequest request) {
		if (request.getCourseId() == null) {
			return null;
		}
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
