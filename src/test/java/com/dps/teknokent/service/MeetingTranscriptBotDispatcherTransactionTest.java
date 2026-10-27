package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.OrganizationRepository;

@SpringBootTest
class MeetingTranscriptBotDispatcherTransactionTest {

	private static final String LINK = "https://meet.google.com/tx-test-link";

	@Autowired
	private MeetingTranscriptBotDispatcher dispatcher;
	@Autowired
	private MeetingRepository meetingRepository;
	@Autowired
	private OrganizationRepository organizationRepository;
	@MockitoBean
	private AssistantService assistantService;
	@MockitoBean
	private StringRedisTemplate redisTemplate;

	private Instant now;
	private Meeting meeting;

	@BeforeEach
	void setUp() {
		now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		Organization organization = new Organization();
		organization.setName("Dispatcher Tx");
		organizationRepository.save(organization);
		meeting = new Meeting();
		meeting.setOrganization(organization);
		meeting.setScheduledAt(now);
		meeting.setDurationMinutes(45);
		meeting.setMeetLink(LINK);
		meeting.setTranscribeRequested(true);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		meeting = meetingRepository.save(meeting);
	}

	@Test
	void callsTheProviderWithNoTransactionOpenAndThenMarksTheMeetingDispatched() {
		AtomicBoolean transactionOpen = new AtomicBoolean(true);
		when(assistantService.addBot(any())).thenAnswer(call -> {
			transactionOpen.set(TransactionSynchronizationManager.isActualTransactionActive());
			return new AddBotResponse("bot-1", "google_meet", "tx-test-link", "joining", "Mentor Bot");
		});

		assertThat(dispatcher.dispatchDueMeetings(now)).isEqualTo(1);

		assertThat(transactionOpen).isFalse();
		Meeting saved = meetingRepository.findById(meeting.getId()).orElseThrow();
		assertThat(saved.getBotDispatchedAt()).isEqualTo(now);
		assertThat(saved.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
	}

	@Test
	void keepsACancellationMadeWhileTheProviderWasAnswering() {
		when(assistantService.addBot(any())).thenAnswer(call -> {
			Meeting cancelled = meetingRepository.findById(meeting.getId()).orElseThrow();
			cancelled.setStatus(MeetingStatus.CANCELLED);
			meetingRepository.save(cancelled);
			throw new IllegalStateException("The meeting has ended");
		});

		assertThat(dispatcher.dispatchDueMeetings(now)).isZero();

		Meeting saved = meetingRepository.findById(meeting.getId()).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(MeetingStatus.CANCELLED);
		assertThat(saved.getBotDispatchedAt()).isNull();
	}
}
