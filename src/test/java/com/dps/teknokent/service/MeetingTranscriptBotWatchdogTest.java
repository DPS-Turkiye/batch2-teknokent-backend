package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.dto.provider.ProviderBotDetails;
import com.dps.teknokent.dto.provider.ProviderBotStatusResponse;
import com.dps.teknokent.dto.provider.ProviderRunningBot;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptSegment;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.repository.MeetingRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeetingTranscriptBotWatchdogTest {

	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private MeetingService meetingService;
	@Mock
	private MeetingProviderClient providerClient;
	@Mock
	private MeetingReportService meetingReportService;
	@Mock
	private MeetingAssistantShareService shareService;
	@Mock
	private TranscriptCleanupService transcriptCleanupService;

	private MeetingTranscriptBotWatchdog watchdog;

	private final Instant now = Instant.parse("2026-09-02T12:30:00Z");
	private final UUID meetingId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		when(transcriptCleanupService.clean(any())).thenAnswer(call -> call.getArgument(0));
		watchdog = watchdogWith(TestAiFeatures.allOn());
	}

	private MeetingTranscriptBotWatchdog watchdogWith(AiFeatures aiFeatures) {
		return watchdogWith(aiFeatures, Runnable::run);
	}

	private MeetingTranscriptBotWatchdog watchdogWith(AiFeatures aiFeatures, Executor reportExecutor) {
		AssistantService assistantService = new AssistantService(providerClient, meetingReportService, null, null,
				aiFeatures, meetingService);
		return new MeetingTranscriptBotWatchdog(
				meetingRepository, meetingService, assistantService, shareService, transcriptCleanupService,
				aiFeatures, reportExecutor);
	}

	@Test
	void aMeetingThatCannotBeClosedDoesNotKeepTheOthersOpen() {
		Meeting broken = meeting(now.minus(20, ChronoUnit.MINUTES), 60);
		Meeting other = meeting(now.minus(20, ChronoUnit.MINUTES), 60);
		other.setId(UUID.randomUUID());
		other.setMeetLink("https://meet.google.com/ddd-eeee-fff");
		liveMeetings(broken, other);
		runningBots();
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenThrow(new IllegalStateException("database down"));
		when(meetingService.markBotStopped("ddd-eeee-fff")).thenReturn(List.of(other.getId()));
		when(providerClient.getTranscript("google_meet", "ddd-eeee-fff"))
				.thenReturn(new ProviderTranscriptResponse(List.of()));

		assertThat(watchdog.closeFinishedMeetings(now)).isEqualTo(1);

		verify(meetingService).markBotStopped("ddd-eeee-fff");
		verify(shareService).storeGeneratedTranscript(other.getId(), List.of());
	}

	@Test
	void writesTheTranscriptAndReportOffTheSchedulerThread() {
		List<Runnable> queued = new ArrayList<>();
		watchdog = watchdogWith(TestAiFeatures.allOn(), queued::add);
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots();
		providerTranscript();
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));
		when(meetingReportService.generate(any())).thenReturn(report());

		assertThat(watchdog.closeFinishedMeetings(now)).isEqualTo(1);

		verify(providerClient, never()).getTranscript(any(), any());
		verify(meetingReportService, never()).generate(any());

		queued.forEach(Runnable::run);

		verify(shareService).storeGeneratedTranscript(eq(meetingId), any());
		verify(shareService).storeGeneratedReport(meetingId, report());
	}

	@Test
	void keepsOnlyTheTranscriptWhenMeetingReportsAreSwitchedOff() {
		watchdog = watchdogWith(TestAiFeatures.allOnExcept(AiFeature.MEETING_REPORT));
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots();
		providerTranscript();
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));

		watchdog.closeFinishedMeetings(now);

		verify(shareService).storeGeneratedTranscript(eq(meetingId), any());
		verify(meetingReportService, never()).generate(any());
		verify(shareService, never()).storeGeneratedReport(any(), any());
	}

	@Test
	void closesTheMeetingAndStopsALeftoverBotOnceTheBotIsGone() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots();
		providerTranscript();
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));
		when(meetingReportService.generate(any())).thenReturn(report());

		assertThat(watchdog.closeFinishedMeetings(now)).isEqualTo(1);

		verify(providerClient).stopBot("google_meet", "aaa-bbbb-ccc");
		verify(shareService).storeGeneratedTranscript(eq(meetingId), any());
		verify(shareService).storeGeneratedReport(meetingId, report());
	}

	@Test
	void storesTheCleanedTranscriptAndReportsFromIt() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots();
		providerTranscript();
		List<TranscriptSegmentResponse> cleaned = List
				.of(new TranscriptSegmentResponse("s1", "Arya", "Düzeltilmiş metin", "00:00"));
		when(transcriptCleanupService.clean(any())).thenReturn(cleaned);
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));
		when(meetingReportService.generate(any())).thenReturn(report());

		watchdog.closeFinishedMeetings(now);

		verify(shareService).storeGeneratedTranscript(meetingId, cleaned);
		verify(meetingReportService).generate(eq(cleaned));
	}

	@Test
	void leavesTheMeetingAloneWhilePeopleAreStillThere() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots(new ProviderRunningBot("google_meet", "aaa-bbbb-ccc", "running"));
		when(providerClient.getBot("google_meet", "aaa-bbbb-ccc"))
				.thenReturn(details("active", List.of("Sudenaz Yılmaz")));

		assertThat(watchdog.closeFinishedMeetings(now)).isZero();

		verify(meetingService, never()).markBotStopped(any());
		verify(providerClient, never()).stopBot(any(), any());
	}

	@Test
	void waitsUntilPeopleHaveBeenGoneBeforeKickingALeftoverBot() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots(new ProviderRunningBot("google_meet", "aaa-bbbb-ccc", "running"));
		when(providerClient.getBot("google_meet", "aaa-bbbb-ccc"))
				.thenReturn(details("active", List.of("Sudenaz Yılmaz")))
				.thenReturn(details("active", List.of("Mentor Bot")));
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));
		when(meetingReportService.generate(any())).thenReturn(report());

		assertThat(watchdog.closeFinishedMeetings(now)).isZero();
		assertThat(watchdog.closeFinishedMeetings(now.plus(30, ChronoUnit.SECONDS))).isZero();
		assertThat(watchdog.closeFinishedMeetings(now.plus(120, ChronoUnit.SECONDS))).isEqualTo(1);

		verify(providerClient).stopBot("google_meet", "aaa-bbbb-ccc");
	}

	@Test
	void doesNotTreatAWaitingRoomAsAnEndedMeeting() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots(new ProviderRunningBot("google_meet", "aaa-bbbb-ccc", "running"));
		when(providerClient.getBot("google_meet", "aaa-bbbb-ccc"))
				.thenReturn(details("active", List.of()));

		assertThat(watchdog.closeFinishedMeetings(now.plus(2, ChronoUnit.MINUTES))).isZero();

		verify(meetingService, never()).markBotStopped(any());
	}

	@Test
	void kicksALeftoverBotAfterTheCalendarSlotHasEnded() {
		liveMeetings(meeting(now.minus(90, ChronoUnit.MINUTES), 60));
		runningBots(new ProviderRunningBot("google_meet", "aaa-bbbb-ccc", "running"));
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));
		when(meetingReportService.generate(any())).thenReturn(report());

		assertThat(watchdog.closeFinishedMeetings(now)).isEqualTo(1);

		verify(providerClient).stopBot("google_meet", "aaa-bbbb-ccc");
	}

	@Test
	void waitsOutTheJoinGraceBeforeJudgingAFreshlyDispatchedBot() {
		liveMeetings(meeting(now.minus(1, ChronoUnit.MINUTES), 60));
		runningBots();

		assertThat(watchdog.closeFinishedMeetings(now)).isZero();

		verify(meetingService, never()).markBotStopped(any());
		verify(providerClient, never()).getRunningBots();
	}

	@Test
	void doesNothingWhenTheProviderAnswerCannotBeRead() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		when(providerClient.getRunningBots()).thenReturn(new ProviderBotStatusResponse(null));

		assertThat(watchdog.closeFinishedMeetings(now)).isZero();

		verify(meetingService, never()).markBotStopped(any());
	}

	@Test
	void doesNothingWhenTheProviderCannotBeReached() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		when(providerClient.getRunningBots()).thenThrow(new IllegalStateException("provider down"));

		assertThat(watchdog.closeFinishedMeetings(now)).isZero();

		verify(meetingService, never()).markBotStopped(any());
	}

	@Test
	void stillClosesTheMeetingWhenTheReportCannotBeProduced() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots();
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));
		when(providerClient.getTranscript(eq("google_meet"), eq("aaa-bbbb-ccc")))
				.thenThrow(new IllegalStateException("transcript gone"));

		assertThat(watchdog.closeFinishedMeetings(now)).isEqualTo(1);

		verify(shareService, never()).storeGeneratedTranscript(any(), any());
		verify(shareService, never()).storeGeneratedReport(any(), any());
	}

	@Test
	void closesTheMeetingWhenTheProviderSaysTheBotHasFinished() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots(new ProviderRunningBot("google_meet", "aaa-bbbb-ccc", "running"));
		when(providerClient.getBot("google_meet", "aaa-bbbb-ccc"))
				.thenReturn(details(" Completed ", List.of("Sudenaz Yılmaz")));
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));

		assertThat(watchdog.closeFinishedMeetings(now)).isEqualTo(1);
	}

	@Test
	void stillClosesTheMeetingWhenTheLeftoverBotCannotBeStopped() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots();
		providerTranscript();
		doThrow(new IllegalStateException("provider down")).when(providerClient).stopBot("google_meet", "aaa-bbbb-ccc");
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));

		assertThat(watchdog.closeFinishedMeetings(now)).isEqualTo(1);

		verify(shareService).storeGeneratedTranscript(eq(meetingId), any());
	}

	@Test
	void countsNothingAndFetchesNoTranscriptWhenNoMeetingWasLeftToClose() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots();
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of());

		assertThat(watchdog.closeFinishedMeetings(now)).isZero();

		verify(providerClient, never()).getTranscript(any(), any());
		verify(shareService, never()).storeGeneratedTranscript(any(), any());
	}

	@Test
	void closesEveryTeamsCopyOfAGroupSessionAndStoresTheTranscriptOnEach() {
		UUID guestCopyId = UUID.randomUUID();
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots();
		providerTranscript();
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId, guestCopyId));
		when(meetingReportService.generate(any())).thenReturn(report());

		assertThat(watchdog.closeFinishedMeetings(now)).isEqualTo(2);

		verify(shareService).storeGeneratedTranscript(eq(meetingId), any());
		verify(shareService).storeGeneratedTranscript(eq(guestCopyId), any());
		verify(shareService).storeGeneratedReport(meetingId, report());
		verify(shareService).storeGeneratedReport(guestCopyId, report());
		verify(meetingReportService).generate(any());
	}

	@Test
	void keepsWaitingWhenTheBotsDetailsCannotBeRead() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots(new ProviderRunningBot("google_meet", "aaa-bbbb-ccc", "running"));
		when(providerClient.getBot("google_meet", "aaa-bbbb-ccc")).thenThrow(new IllegalStateException("timeout"));

		assertThat(watchdog.closeFinishedMeetings(now)).isZero();

		verify(meetingService, never()).markBotStopped(any());
		verify(providerClient, never()).stopBot(any(), any());
	}

	@Test
	void skipsMeetingsWhoseLinkTheBotCouldNotHaveJoined() {
		Meeting withoutLink = meeting(now.minus(20, ChronoUnit.MINUTES), 60);
		withoutLink.setMeetLink(" ");
		Meeting anotherSite = meeting(now.minus(20, ChronoUnit.MINUTES), 60);
		anotherSite.setMeetLink("https://zoom.us/j/123456");
		liveMeetings(withoutLink, anotherSite);
		runningBots();

		assertThat(watchdog.closeFinishedMeetings(now)).isZero();

		verify(providerClient, never()).getRunningBots();
		verify(meetingService, never()).markBotStopped(any());
	}

	@Test
	void treatsAMeetingWithoutALengthAsOneHourWhenJudgingItsSlot() {
		Meeting meeting = meeting(now.minus(50, ChronoUnit.MINUTES), 60);
		meeting.setDurationMinutes(null);
		liveMeetings(meeting);
		runningBots(new ProviderRunningBot("google_meet", "aaa-bbbb-ccc", "running"));
		when(providerClient.getBot("google_meet", "aaa-bbbb-ccc"))
				.thenReturn(details("active", List.of("Sudenaz Yılmaz")));
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));

		assertThat(watchdog.closeFinishedMeetings(now)).isZero();
		assertThat(watchdog.closeFinishedMeetings(now.plus(13, ChronoUnit.MINUTES))).isEqualTo(1);
	}

	@Test
	void aGroupSessionsBotStaysUntilItsLongestSeatEnds() {
		Meeting shortSeat = meeting(now.minus(50, ChronoUnit.MINUTES), 30);
		Meeting longSeat = meeting(now.minus(50, ChronoUnit.MINUTES), 120);
		longSeat.setId(UUID.randomUUID());
		runningBots(new ProviderRunningBot("google_meet", "aaa-bbbb-ccc", "running"));
		when(providerClient.getBot("google_meet", "aaa-bbbb-ccc"))
				.thenReturn(details("active", List.of("Sudenaz Yılmaz")));

		liveMeetings(shortSeat, longSeat);
		assertThat(watchdog.closeFinishedMeetings(now)).isZero();
		liveMeetings(longSeat, shortSeat);
		assertThat(watchdog.closeFinishedMeetings(now)).isZero();

		verify(meetingService, never()).markBotStopped(any());
	}

	@Test
	void anEmptyTranscriptIsRecordedButNoReportIsWrittenFromIt() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots();
		when(providerClient.getTranscript("google_meet", "aaa-bbbb-ccc"))
				.thenReturn(new ProviderTranscriptResponse(List.of()));
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));

		assertThat(watchdog.closeFinishedMeetings(now)).isEqualTo(1);

		verify(shareService).storeGeneratedTranscript(meetingId, List.of());
		verify(meetingReportService, never()).generate(any());
		verify(shareService, never()).storeGeneratedReport(any(), any());
	}

	@Test
	void keepsTheTranscriptWhenTheReportCannotBeWritten() {
		liveMeetings(meeting(now.minus(20, ChronoUnit.MINUTES), 60));
		runningBots();
		providerTranscript();
		when(meetingService.markBotStopped("aaa-bbbb-ccc")).thenReturn(List.of(meetingId));
		when(meetingReportService.generate(any())).thenThrow(new IllegalStateException("model down"));

		assertThat(watchdog.closeFinishedMeetings(now)).isEqualTo(1);

		verify(shareService).storeGeneratedTranscript(eq(meetingId), any());
		verify(shareService, never()).storeGeneratedReport(any(), any());
	}

	private void liveMeetings(Meeting... meetings) {
		when(meetingRepository.findByTranscribeRequestedTrueAndBotDispatchedAtIsNotNullAndStatus(
				MeetingStatus.SCHEDULED)).thenReturn(List.of(meetings));
	}

	private void runningBots(ProviderRunningBot... bots) {
		when(providerClient.getRunningBots()).thenReturn(new ProviderBotStatusResponse(List.of(bots)));
	}

	private void providerTranscript() {
		when(providerClient.getTranscript("google_meet", "aaa-bbbb-ccc"))
				.thenReturn(new ProviderTranscriptResponse(List.of(
						new ProviderTranscriptSegment("s1", "Ayşe", "Merhaba", 10.0))));
	}

	private Meeting meeting(Instant botDispatchedAt, int durationMinutes) {
		Meeting meeting = new Meeting();
		meeting.setId(meetingId);
		meeting.setMeetLink("https://meet.google.com/aaa-bbbb-ccc");
		meeting.setTranscribeRequested(true);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		meeting.setScheduledAt(botDispatchedAt);
		meeting.setDurationMinutes(durationMinutes);
		meeting.setBotDispatchedAt(botDispatchedAt);
		return meeting;
	}

	private static ProviderBotDetails details(String status, List<Object> participants) {
		return new ProviderBotDetails(status, "google_meet", "aaa-bbbb-ccc", participants, null);
	}

	private static MeetingReportResponse report() {
		return new MeetingReportResponse(
				"Mentorluk Görüşmesi", "2026-09-02", "Ürün", "Özet",
				List.of(), List.of(), List.of(), List.of(), List.of(), "gpt-5.4");
	}
}
