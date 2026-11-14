package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.dps.teknokent.service.OfficeHoursMailService.Delivery;
import com.dps.teknokent.service.OfficeHoursMailService.Event;
import com.dps.teknokent.service.OfficeHoursMailService.Side;
import com.dps.teknokent.util.MeetingSlotTimes;

@ExtendWith(MockitoExtension.class)
class OfficeHoursMailServiceTest {

	private static final Instant TUESDAY_2PM = Instant.parse("2026-11-10T11:00:00Z");
	private static final Instant TUESDAY_3PM = Instant.parse("2026-11-10T12:00:00Z");

	@Mock
	private MailService mailService;
	@Mock
	private OfficeHoursUserRepository userRepository;
	@Mock
	private OfficeHoursCourseRepository courseRepository;
	@Mock
	private OfficeHoursRequestRepository requestRepository;
	@Mock
	private OfficeHoursMeetingRepository meetingRepository;
	@Mock
	private OfficeHoursMailOutboxService outbox;
	@Mock
	private AppUserRepository appUserRepository;
	@InjectMocks
	private OfficeHoursMailService service;

	private final OfficeHoursUser student = person("Ayşe Yılmaz", "ayse@metu.edu.tr", AppLanguage.TR);
	private final OfficeHoursUser academic = person("Ali Kaya", "ali@metu.edu.tr", AppLanguage.EN);
	private final OfficeHoursRequest request = new OfficeHoursRequest();

	@BeforeEach
	void setUp() {
		OfficeHoursCourse course = new OfficeHoursCourse();
		course.setId(UUID.randomUUID());
		course.setIssuer("https://odtuclass.metu.edu.tr/");
		course.setContextId("7");
		course.setTitle("CENG 101");
		request.setId(UUID.randomUUID());
		request.setCourseId(course.getId());
		request.setStudentId(student.getId());
		request.setInstructorId(academic.getId());
		request.setTopic("Ödev 2");
		request.setPreferredSlots("2026-11-10T14:00,2026-11-10T15:00");
		lenient().when(userRepository.findAllById(any())).thenReturn(List.of(student, academic));
		lenient().when(courseRepository.findById(course.getId())).thenReturn(Optional.of(course));
	}

	private static OfficeHoursUser person(String name, String email, AppLanguage language) {
		OfficeHoursUser user = new OfficeHoursUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setEmail(email);
		user.setPreferredLanguage(language);
		return user;
	}

	private String[] sentTo(String address) {
		ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(outbox).enqueue(eq(address), subject.capture(), body.capture());
		return new String[] { subject.getValue(), body.getValue() };
	}

	@Test
	void aNewRequestReachesTheAcademicInTheirLanguageWithTheStudentsTimesAndTheMoodleCourse() {
		service.requestChanged(request, Event.NEW_REQUEST, null);

		String[] mail = sentTo("ali@metu.edu.tr");
		assertThat(mail[0]).isEqualTo("New office hours request · CENG 101");
		assertThat(mail[1]).startsWith("Hello Ali Kaya,\n\nAyşe Yılmaz asked you for office hours in CENG 101.")
				.contains("- " + MeetingSlotTimes.formatForPeople(TUESDAY_2PM, AppLanguage.EN) + "\n- "
						+ MeetingSlotTimes.formatForPeople(TUESDAY_3PM, AppLanguage.EN))
				.contains("Topic: Ödev 2")
				.contains("https://odtuclass.metu.edu.tr/course/view.php?id=7");
	}

	@Test
	void aDeclineReachesTheStudentInTurkishWithTheReasonAndNoEmptyParagraph() {
		service.requestChanged(request, Event.DECLINED, "Bu hafta yokum");

		String[] mail = sentTo("ayse@metu.edu.tr");
		assertThat(mail[0]).isEqualTo("Ofis saati talebiniz reddedildi · CENG 101");
		assertThat(mail[1])
				.contains("Ali Kaya, CENG 101 dersi için ofis saati talebinizi reddetti.\n\nMesaj: Bu hafta yokum")
				.doesNotContain("\n\n\n");
	}

	@Test
	void anAcceptanceGivesTheMeetingTime() {
		OfficeHoursMeeting meeting = new OfficeHoursMeeting();
		meeting.setScheduledAt(TUESDAY_3PM);
		lenient().when(meetingRepository.findByRequestId(request.getId())).thenReturn(Optional.of(meeting));

		service.requestChanged(request, Event.ACCEPTED, null);

		assertThat(sentTo("ayse@metu.edu.tr")[1])
				.contains("Görüşme saati:\n\n- " + MeetingSlotTimes.formatForPeople(TUESDAY_3PM, AppLanguage.TR))
				.doesNotContain("Mesaj:");
	}

	@Test
	void someoneMoodleSharedNoAddressForGetsNoMail() {
		student.setEmail(null);

		service.requestChanged(request, Event.DECLINED, null);

		verifyNoInteractions(mailService, outbox);
	}

	private OfficeHoursMeeting meeting() {
		OfficeHoursMeeting meeting = new OfficeHoursMeeting();
		meeting.setRequestId(request.getId());
		meeting.setStudentId(student.getId());
		meeting.setInstructorId(academic.getId());
		meeting.setScheduledAt(TUESDAY_3PM);
		lenient().when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
		lenient().when(mailService.isConfigured()).thenReturn(true);
		return meeting;
	}

	@Test
	void eachSideIsRemindedOfTheMeetingWithTheOtherOneAndHearsWhetherItWent() {
		OfficeHoursMeeting meeting = meeting();
		when(mailService.sendNow(eq("ayse@metu.edu.tr"), any(), any())).thenReturn(true);
		when(mailService.sendNow(eq("ali@metu.edu.tr"), any(), any())).thenReturn(false);

		assertThat(service.meetingSoon(meeting, Side.STUDENT)).isEqualTo(Delivery.SENT);
		assertThat(service.meetingSoon(meeting, Side.INSTRUCTOR)).isEqualTo(Delivery.FAILED);

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(mailService).sendNow(eq("ayse@metu.edu.tr"), any(), body.capture());
		assertThat(body.getValue()).contains("Ali Kaya ile CENG 101 dersi için ofis saati görüşmeniz");
		verify(mailService).sendNow(eq("ali@metu.edu.tr"), any(), body.capture());
		assertThat(body.getValue()).contains("Your office hours meeting with Ayşe Yılmaz for CENG 101");
	}

	@Test
	void aReminderWithNoAddressOrWithMailSwitchedOffHasNothingToSendRatherThanFailing() {
		OfficeHoursMeeting meeting = meeting();
		student.setEmail(null);
		assertThat(service.meetingSoon(meeting, Side.STUDENT)).isEqualTo(Delivery.NOTHING_TO_SEND);

		when(mailService.isConfigured()).thenReturn(false);
		assertThat(service.meetingSoon(meeting, Side.INSTRUCTOR)).isEqualTo(Delivery.NOTHING_TO_SEND);
		verify(mailService, never()).sendNow(any(), any(), any());
	}

	/** Both sides signed in to Menton through ODTÜ: no course, and Moodle never shared an address or language. */
	private AppUser mentonRequest() {
		request.setCourseId(null);
		student.setEmail(null);
		academic.setEmail(null);
		AppUser account = new AppUser();
		account.setEmail("ali.kaya@metu.edu.tr");
		account.setPreferredLanguage(AppLanguage.TR);
		academic.setAppUserId(UUID.randomUUID());
		lenient().when(appUserRepository.findById(academic.getAppUserId())).thenReturn(Optional.of(account));
		lenient().when(mailService.link(any())).thenAnswer(call -> "https://menton.com.tr" + call.getArgument(0));
		return account;
	}

	@Test
	void aMentonRequestReachesTheAccountsAddressInItsLanguageNamingNoCourseAndLinkingToMenton() {
		mentonRequest();

		service.requestChanged(request, Event.NEW_REQUEST, null);

		String[] mail = sentTo("ali.kaya@metu.edu.tr");
		assertThat(mail[0]).isEqualTo("Yeni ofis saati talebi · Ayşe Yılmaz");
		assertThat(mail[1]).startsWith("Merhaba Ali Kaya,\n\nAyşe Yılmaz sizden ofis saati istedi.")
				.contains("https://menton.com.tr/academician/appointments")
				.doesNotContain("Moodle");
		verify(courseRepository, never()).findById(any());
	}

	@Test
	void aDeletedMentonAccountGetsNoMail() {
		mentonRequest().setStatus(UserAccountStatus.DELETED);

		service.requestChanged(request, Event.NEW_REQUEST, null);

		verifyNoInteractions(outbox);
	}

	@Test
	void aMentonMeetingIsRemindedWithoutACourseRatherThanFailingTheRun() {
		mentonRequest();
		OfficeHoursMeeting meeting = meeting();
		when(mailService.sendNow(eq("ali.kaya@metu.edu.tr"), any(), any())).thenReturn(true);

		assertThat(service.meetingSoon(meeting, Side.INSTRUCTOR)).isEqualTo(Delivery.SENT);
		// The student's account has no Menton link in this test and Moodle shared no address: nothing to send.
		assertThat(service.meetingSoon(meeting, Side.STUDENT)).isEqualTo(Delivery.NOTHING_TO_SEND);
	}
}
