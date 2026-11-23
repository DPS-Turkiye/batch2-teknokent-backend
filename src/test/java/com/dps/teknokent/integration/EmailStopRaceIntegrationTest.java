package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dps.teknokent.domain.EmailAudience;
import com.dps.teknokent.domain.EmailCampaignStatus;
import com.dps.teknokent.domain.EmailRecipientKind;
import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.EmailCampaign;
import com.dps.teknokent.entity.EmailCampaignRecipient;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.service.EmailCampaignService;
import com.dps.teknokent.service.EmailCampaignWorker;
import com.dps.teknokent.service.EmailCenterScheduler;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/** A stop while the worker, on its own thread, is in the middle of asking SES: real transactions, so it commits. */
@SpringBootTest
@TestPropertySource(properties = { "app.mail.center.enabled=true", "app.mail.center.interval-ms=86400000",
		"app.mail.center.bounce-source=ses-events", "app.mail.center.configuration-set=test",
		"app.mail.center.events-queue-url=https://queue.test", "app.mail.center.bounce-interval-ms=86400000",
		"app.mail.center.alert-interval-ms=86400000", "app.mail.center.recipient-allowlist=race.test",
		"app.mail.from=noreply@menton.com.tr" })
class EmailStopRaceIntegrationTest {

	@Autowired
	private EmailCampaignService service;
	@Autowired
	private EmailCampaignWorker worker;
	@Autowired
	private EmailCenterScheduler scheduler;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@MockitoBean(name = "emailCenterSesClient")
	private SesV2Client sesClient;
	@MockitoBean
	private SqsClient sqsClient;

	private final CountDownLatch asked = new CountDownLatch(1);
	private final CountDownLatch answer = new CountDownLatch(1);
	private final List<Object> persisted = new ArrayList<>();
	private AppUser coordinator;
	private UUID campaignId;

	@BeforeEach
	void setUp() {
		when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
				.thenReturn(ReceiveMessageResponse.builder().build());
		scheduler.readBounces();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> persist());
	}

	@AfterEach
	void tearDown() {
		answer.countDown();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			for (int i = persisted.size() - 1; i >= 0; i--) {
				entityManager.remove(entityManager.merge(persisted.get(i)));
			}
		});
	}

	@Test
	void afterAStopOnlyTheMailInFlightArrives() throws Exception {
		when(sesClient.sendEmail(any(SendEmailRequest.class))).thenAnswer(call -> {
			hold();
			return SendEmailResponse.builder().messageId("race-" + UUID.randomUUID()).build();
		});

		stopWhileSesIsAsked();

		verify(sesClient, times(1)).sendEmail(any(SendEmailRequest.class));
		assertThat(statuses()).containsExactlyInAnyOrder(
				EmailRecipientStatus.SENT, EmailRecipientStatus.SKIPPED, EmailRecipientStatus.SKIPPED);
	}

	@Test
	void aMailSesPutOffIsNotTriedAgainAfterAStop() throws Exception {
		when(sesClient.sendEmail(any(SendEmailRequest.class))).thenAnswer(call -> {
			hold();
			throw SesV2Exception.builder().statusCode(429)
					.awsErrorDetails(AwsErrorDetails.builder().errorCode("TooManyRequestsException").build()).build();
		});

		stopWhileSesIsAsked();

		verify(sesClient, times(1)).sendEmail(any(SendEmailRequest.class));
		assertThat(statuses()).containsOnly(EmailRecipientStatus.SKIPPED);
	}

	@Test
	void aStopThatWaitedForTheLockSeesThatTheMailIsAlreadyOut() throws Exception {
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Void> finish = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
				.executeWithoutResult(status -> {
					EmailCampaign campaign = entityManager.find(EmailCampaign.class, campaignId,
							LockModeType.PESSIMISTIC_WRITE);
					campaign.setStatus(EmailCampaignStatus.SENT);
					entityManager.flush();
					locked.countDown();
					await(release);
				}));
		assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
		CompletableFuture<Object> stop = CompletableFuture.supplyAsync(
				() -> service.stop(coordinator.getId(), campaignId));
		Thread.sleep(300);
		release.countDown();
		finish.get(10, TimeUnit.SECONDS);

		assertThatThrownBy(() -> stop.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ConflictException.class);
		assertThat(service.get(coordinator.getId(), campaignId).campaign().status())
				.isEqualTo(EmailCampaignStatus.SENT);
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private void stopWhileSesIsAsked() throws Exception {
		CompletableFuture<Void> run = CompletableFuture.runAsync(worker::run);
		assertThat(asked.await(10, TimeUnit.SECONDS)).isTrue();

		// Returns while SES is still being asked: the stop does not wait for the mail in flight.
		assertThat(service.stop(coordinator.getId(), campaignId).campaign().status())
				.isEqualTo(EmailCampaignStatus.STOPPED);
		answer.countDown();
		run.get(10, TimeUnit.SECONDS);
		worker.run();
	}

	private void hold() throws InterruptedException {
		asked.countDown();
		assertThat(answer.await(10, TimeUnit.SECONDS)).isTrue();
	}

	private List<EmailRecipientStatus> statuses() {
		return service.get(coordinator.getId(), campaignId).recipients().stream()
				.map(row -> row.status()).toList();
	}

	private void persist() {
		coordinator = save(user("coordinator", UserRole.COORDINATOR));
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("Race");
		program.setCreatedBy(coordinator.getId());
		program.setCreatedAt(Instant.now());
		save(program);
		EmailCampaign campaign = new EmailCampaign();
		campaign.setId(UUID.randomUUID());
		campaign.setProgramId(program.getId());
		campaign.setCreatedBy(coordinator.getId());
		campaign.setAudience(EmailAudience.MENTORS);
		campaign.setSubject("Konu");
		campaign.setBodyHtml("<p>Metin</p>");
		campaign.setStatus(EmailCampaignStatus.SENDING);
		campaign.setConfirmedAt(Instant.now().minusSeconds(400));
		campaign.setStartsAt(Instant.now().minusSeconds(100));
		campaign.setCreatedAt(Instant.now());
		save(campaign);
		campaignId = campaign.getId();
		for (int i = 0; i < 3; i++) {
			AppUser mentor = save(user("mentor" + i, UserRole.MENTOR));
			ProgramMentor membership = new ProgramMentor();
			membership.setId(UUID.randomUUID());
			membership.setProgramId(program.getId());
			membership.setMentorId(mentor.getId());
			membership.setStatus(ProgramMentorStatus.ACCEPTED);
			membership.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
			membership.setCreatedAt(Instant.now());
			save(membership);
			EmailCampaignRecipient row = new EmailCampaignRecipient();
			row.setId(UUID.randomUUID());
			row.setCampaignId(campaignId);
			row.setUserId(mentor.getId());
			row.setKind(EmailRecipientKind.MENTOR);
			row.setAddress(mentor.getEmail());
			row.setFullName(mentor.getFullName());
			row.setStatus(EmailRecipientStatus.PENDING);
			row.setNextAttemptAt(campaign.getStartsAt());
			save(row);
		}
	}

	private <T> T save(T entity) {
		entityManager.persist(entity);
		persisted.add(entity);
		return entity;
	}

	private static AppUser user(String name, UserRole role) {
		AppUser user = new AppUser();
		user.setEmail(name + "-" + UUID.randomUUID() + "@race.test");
		user.setFullName(name);
		user.setRole(role);
		return user;
	}
}
