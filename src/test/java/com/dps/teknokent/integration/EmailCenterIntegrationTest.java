package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.EmailAudience;
import com.dps.teknokent.domain.EmailCampaignStatus;
import com.dps.teknokent.domain.EmailExclusion;
import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.domain.EmailSuppressionReason;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.EmailCampaignRequest;
import com.dps.teknokent.dto.requests.SendEmailCampaignRequest;
import com.dps.teknokent.dto.responses.EmailCampaignResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.EmailCampaignRecipient;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.OrganizationPendingMember;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.service.EmailCampaignService;
import com.dps.teknokent.service.EmailCampaignWorker;
import com.dps.teknokent.service.EmailSuppressionService;

import jakarta.persistence.EntityManager;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/** A mail from its draft to SES, which is a mock: nothing ever leaves. */
@SpringBootTest
@Transactional
@TestPropertySource(properties = { "app.mail.center.enabled=true", "app.mail.center.interval-ms=86400000",
		"app.mail.center.bounce-source=suppression-list", "app.mail.center.bounce-interval-ms=86400000",
		"app.mail.center.recipient-allowlist=allowed.test,coordinator@staff.test,admin@staff.test",
		"app.mail.from=noreply@menton.com.tr" })
class EmailCenterIntegrationTest {

	private static final String BODY = "<p>Merhaba <strong>ekip</strong></p><script>alert(1)</script>";

	@Autowired
	private EmailCampaignService service;
	@Autowired
	private EmailCampaignWorker worker;
	@Autowired
	private EmailSuppressionService suppressionService;
	@Autowired
	private EntityManager entityManager;
	@MockitoBean(name = "emailCenterSesClient")
	private SesV2Client sesClient;

	private final AtomicInteger messages = new AtomicInteger();
	private AppUser coordinator;
	private AppUser admin;
	private AppUser founder;
	private AppUser mentor;
	private Program program;
	private Organization team;

	@BeforeEach
	void setUp() {
		when(sesClient.sendEmail(any(SendEmailRequest.class))).thenAnswer(
				call -> SendEmailResponse.builder().messageId("ses-" + messages.incrementAndGet()).build());
		coordinator = user("coordinator@staff.test", "Elif Koordinatör", UserRole.COORDINATOR, null);
		admin = user("admin@staff.test", "Yönetici", UserRole.ADMIN, null);
		program = program("Build '26", coordinator);
		team = team("GreenLoop", program);
		founder = user("Founder@allowed.test", "Ayşe Kurucu", UserRole.MENTEE, team);
		team.setFounder(founder);
		mentor = user("mentor@allowed.test", "Mert Mentor", UserRole.MENTOR, null);
		mentorOf(program, mentor, ProgramMentorStatus.ACCEPTED);
		entityManager.flush();
	}

	@Test
	void sendsExactlyTheReviewedListWithoutTheUntickedPeople() {
		AppUser member = user("member@allowed.test", "Can Üye", UserRole.MENTEE, team);
		EmailCampaignResponse.Detail reviewed = reviewed(coordinator, EmailAudience.EVERYONE);
		UUID off = rowOf(reviewed, member).id();

		service.send(coordinator.getId(), idOf(reviewed), confirm(reviewed, Set.of(off), 2));
		worker.run();

		List<SendEmailRequest> sent = sentToPeople();
		assertThat(sent.stream().map(request -> request.destination().toAddresses().getFirst()))
				.containsExactlyInAnyOrder("founder@allowed.test", "mentor@allowed.test");
		assertThat(sent).allSatisfy(request -> {
			assertThat(request.destination().toAddresses()).hasSize(1);
			assertThat(request.destination().ccAddresses()).isEmpty();
			assertThat(request.destination().bccAddresses()).isEmpty();
			assertThat(request.fromEmailAddress()).isEqualTo("Menton <noreply@menton.com.tr>");
			assertThat(request.replyToAddresses()).containsExactly("coordinator@staff.test");
			assertThat(request.content().simple().body().html().data())
					.contains("<strong>ekip</strong>").doesNotContain("script");
		});
		EmailCampaignResponse.Detail done = service.get(coordinator.getId(), idOf(reviewed));
		assertThat(done.campaign().status()).isEqualTo(EmailCampaignStatus.SENT);
		assertThat(done.campaign().counts()).isEqualTo(
				Map.of(EmailRecipientStatus.SENT, 2L, EmailRecipientStatus.UNTICKED, 1L));
	}

	@Test
	void anAdminsMailIsAnsweredAtTheSharedAddress() {
		EmailCampaignResponse.Detail reviewed = reviewed(admin, EmailAudience.MENTORS);

		service.send(admin.getId(), idOf(reviewed), confirm(reviewed, Set.of(), 1));
		worker.run();

		assertThat(sentToPeople().getFirst().replyToAddresses()).containsExactly("info@menton.com.tr");
	}

	@Test
	void aSecondRunSendsNobodyAnything() {
		EmailCampaignResponse.Detail reviewed = reviewed(coordinator, EmailAudience.MENTORS);
		service.send(coordinator.getId(), idOf(reviewed), confirm(reviewed, Set.of(), 1));
		worker.run();
		worker.run();

		assertThat(sentToPeople()).hasSize(1);
		assertThatThrownBy(() -> service.send(coordinator.getId(), idOf(reviewed),
				confirm(reviewed, Set.of(), 1))).isInstanceOf(ConflictException.class);
	}

	@Test
	void aListThatChangedSinceTheReviewHasToBeReviewedAgain() {
		EmailCampaignResponse.Detail reviewed = reviewed(coordinator, EmailAudience.PARTICIPANTS);
		user("late@allowed.test", "Geç Gelen", UserRole.MENTEE, team);
		entityManager.flush();

		assertThatThrownBy(() -> service.send(coordinator.getId(), idOf(reviewed), confirm(reviewed, Set.of(), 1)))
				.isInstanceOfSatisfying(ConflictException.class,
						refused -> assertThat(refused.code()).isEqualTo("EMAIL_LIST_CHANGED"));
		assertThat(service.get(coordinator.getId(), idOf(reviewed)).reviewDigest()).isNull();
		worker.run();
		assertThat(sentToPeople()).isEmpty();
	}

	@Test
	void anOlderReviewOrAWrongCountIsRefused() {
		EmailCampaignResponse.Detail reviewed = reviewed(coordinator, EmailAudience.PARTICIPANTS);

		assertThatThrownBy(() -> service.send(coordinator.getId(), idOf(reviewed),
				confirm(reviewed, Set.of(), 2))).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.send(coordinator.getId(), idOf(reviewed),
				confirm(reviewed, Set.of(UUID.randomUUID()), 1))).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.send(coordinator.getId(), idOf(reviewed),
				new SendEmailCampaignRequest(null, "older", Set.of(), 1))).isInstanceOf(ConflictException.class);
	}

	@Test
	void editingTheTextUndoesItsTestAndItsReview() {
		EmailCampaignResponse.Detail reviewed = reviewed(coordinator, EmailAudience.PARTICIPANTS);

		EmailCampaignResponse.Detail edited = service.update(coordinator.getId(), idOf(reviewed),
				request(EmailAudience.PARTICIPANTS, "<p>Yeni metin</p>"));

		assertThat(edited.campaign().testSentAt()).isNull();
		assertThat(edited.reviewDigest()).isNull();
		assertThat(edited.recipients()).isEmpty();
		EmailCampaignResponse.Detail again = service.review(coordinator.getId(), idOf(reviewed));
		assertThatThrownBy(() -> service.send(coordinator.getId(), idOf(reviewed), confirm(again, Set.of(), 1)))
				.isInstanceOfSatisfying(ConflictException.class,
						refused -> assertThat(refused.code()).isEqualTo("EMAIL_TEST_NEEDED"));
	}

	@Test
	void aTestGoesOnlyToItsAuthor() {
		EmailCampaignResponse.Detail draft = service.create(coordinator.getId(),
				request(EmailAudience.EVERYONE, BODY));

		service.sendTest(coordinator.getId(), idOf(draft));

		ArgumentCaptor<SendEmailRequest> mail = ArgumentCaptor.forClass(SendEmailRequest.class);
		verify(sesClient, times(1)).sendEmail(mail.capture());
		assertThat(mail.getValue().destination().toAddresses()).containsExactly("coordinator@staff.test");
		assertThat(mail.getValue().content().simple().subject().data()).startsWith("[TEST]");
		assertThat(mail.getValue().content().simple().headers()).isEmpty();
	}

	@Test
	void aCoordinatorWritesOnlyToProgramsTheyManage() {
		AppUser other = user("other@staff.test", "Başka", UserRole.COORDINATOR, null);
		entityManager.flush();
		EmailCampaignResponse.Detail mine = service.create(coordinator.getId(),
				request(EmailAudience.EVERYONE, BODY));

		assertThatThrownBy(() -> service.create(other.getId(), request(EmailAudience.EVERYONE, BODY)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.get(other.getId(), idOf(mine)))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.review(other.getId(), idOf(mine)))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.templates(other.getId(), program.getId()))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.options(founder.getId())).isInstanceOf(InvalidRequestException.class);
		assertThat(service.list(other.getId())).isEmpty();
		assertThat(service.options(other.getId()).programs()).isEmpty();
		assertThat(service.list(admin.getId())).hasSize(1);
	}

	@Test
	void anAddedCoordinatorMaySendUntilTheyAreRemoved() {
		AppUser added = user("added@allowed.test", "Eklenen", UserRole.COORDINATOR, null);
		ProgramCoordinator row = new ProgramCoordinator();
		row.setId(UUID.randomUUID());
		row.setProgramId(program.getId());
		row.setUserId(added.getId());
		row.setAddedBy(coordinator.getId());
		row.setCreatedAt(Instant.now());
		entityManager.persist(row);
		entityManager.flush();
		EmailCampaignResponse.Detail reviewed = reviewed(added, EmailAudience.MENTORS);
		service.send(added.getId(), idOf(reviewed), confirm(reviewed, Set.of(), 1));

		entityManager.remove(entityManager.find(ProgramCoordinator.class, row.getId()));
		entityManager.flush();
		worker.run();

		assertThat(sentToPeople()).isEmpty();
		assertThat(service.get(admin.getId(), idOf(reviewed)).recipients().getFirst().status())
				.isEqualTo(EmailRecipientStatus.SKIPPED);
		assertThatThrownBy(() -> service.get(added.getId(), idOf(reviewed)))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void theReviewShowsWhoIsLeftOutAndWhy() {
		user("pending@allowed.test", "Bekleyen", UserRole.MENTEE, team).setStatus(UserAccountStatus.PENDING);
		user("gone@allowed.test", "Ayrılan", UserRole.MENTEE, team);
		suppressionService.suppress("gone@allowed.test", EmailSuppressionReason.UNSUBSCRIBED);
		user("bounced@allowed.test", "Ulaşmayan", UserRole.MENTEE, team);
		suppressionService.suppress("bounced@allowed.test", EmailSuppressionReason.HARD_BOUNCE);
		user("real@elsewhere.test", "Dışarıda", UserRole.MENTEE, team);
		entityManager.flush();

		Map<String, EmailExclusion> reasons = reviewed(coordinator, EmailAudience.PARTICIPANTS).recipients().stream()
				.filter(row -> row.exclusion() != null)
				.collect(Collectors.toMap(EmailCampaignResponse.Recipient::fullName,
						EmailCampaignResponse.Recipient::exclusion));

		assertThat(reasons).isEqualTo(Map.of(
				"Bekleyen", EmailExclusion.ACCOUNT_NOT_APPROVED,
				"Ayrılan", EmailExclusion.UNSUBSCRIBED,
				"Ulaşmayan", EmailExclusion.UNDELIVERABLE,
				"Dışarıda", EmailExclusion.NOT_ON_ALLOWLIST));
	}

	@Test
	void unjoinedAreThePeopleFoundersOfTheProgramsTeamsListed() {
		listed(team, "Davetli Kişi", "Invited@allowed.test");
		listed(team, "Adressiz", null);
		listed(team, "Kayıtlı", "mentor@allowed.test");
		Organization elsewhere = team("Başka takım", program("Growth", admin));
		listed(elsewhere, "Yabancı", "stranger@allowed.test");
		Organization archived = team("Arşiv", program);
		archived.setArchivedAt(Instant.now());
		listed(archived, "Arşivdeki", "archived@allowed.test");
		entityManager.flush();

		EmailCampaignResponse.Detail reviewed = reviewed(coordinator, EmailAudience.UNJOINED);

		assertThat(reviewed.recipients()).extracting(EmailCampaignResponse.Recipient::fullName)
				.containsExactlyInAnyOrder("Davetli Kişi", "Adressiz", "Kayıtlı");
		assertThat(reviewed.recipients()).extracting(EmailCampaignResponse.Recipient::exclusion)
				.containsExactlyInAnyOrder(null, EmailExclusion.NO_ADDRESS, EmailExclusion.NOT_ELIGIBLE);
		service.send(coordinator.getId(), idOf(reviewed), confirm(reviewed, Set.of(), 1));
		worker.run();
		assertThat(sentToPeople().getFirst().destination().toAddresses()).containsExactly("invited@allowed.test");
	}

	@Test
	void somebodyInTwoRolesIsWrittenToOnce() {
		mentorOf(program, founder, ProgramMentorStatus.ACCEPTED);
		mentorOf(program, user("waiting@allowed.test", "Bekleyen mentor", UserRole.MENTOR, null),
				ProgramMentorStatus.PENDING);
		entityManager.flush();

		assertThat(reviewed(coordinator, EmailAudience.EVERYONE).recipients())
				.extracting(EmailCampaignResponse.Recipient::address)
				.containsExactlyInAnyOrder("founder@allowed.test", "mentor@allowed.test");
	}

	@Test
	void theLinkInAMailUnsubscribesItsReaderFromLaterMails() {
		EmailCampaignResponse.Detail reviewed = reviewed(coordinator, EmailAudience.MENTORS);
		service.send(coordinator.getId(), idOf(reviewed), confirm(reviewed, Set.of(), 1));
		worker.run();
		SendEmailRequest mail = sentToPeople().getFirst();
		Map<String, String> headers = mail.content().simple().headers().stream()
				.collect(Collectors.toMap(MessageHeader::name, MessageHeader::value));
		assertThat(headers).containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
		Matcher token = Pattern.compile("\\?t=([A-Za-z0-9_-]{43})>").matcher(headers.get("List-Unsubscribe"));
		assertThat(token.find()).isTrue();
		assertThat(mail.content().simple().body().text().data()).contains("/unsubscribe?t=" + token.group(1));

		suppressionService.unsubscribe("unknown");
		suppressionService.unsubscribe(token.group(1));
		suppressionService.unsubscribe(token.group(1));
		entityManager.flush();

		assertThat(reviewed(coordinator, EmailAudience.MENTORS).recipients())
				.extracting(EmailCampaignResponse.Recipient::exclusion)
				.containsExactly(EmailExclusion.UNSUBSCRIBED);
	}

	@Test
	void somebodyWhoUnsubscribesAfterTheConfirmationIsSkipped() {
		EmailCampaignResponse.Detail reviewed = reviewed(coordinator, EmailAudience.MENTORS);
		service.send(coordinator.getId(), idOf(reviewed), confirm(reviewed, Set.of(), 1));
		suppressionService.suppress("mentor@allowed.test", EmailSuppressionReason.UNSUBSCRIBED);
		worker.run();

		assertThat(sentToPeople()).isEmpty();
		assertThat(service.get(coordinator.getId(), idOf(reviewed)).recipients().getFirst().errorCode())
				.isEqualTo("SUPPRESSED");
	}

	@Test
	void aThrottledMailWaitsAndARefusedOneFails() {
		EmailCampaignResponse.Detail throttled = reviewed(coordinator, EmailAudience.MENTORS);
		service.send(coordinator.getId(), idOf(throttled), confirm(throttled, Set.of(), 1));
		when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(ses(429, "TooManyRequestsException"));
		worker.run();
		assertThat(only(throttled).getStatus()).isEqualTo(EmailRecipientStatus.PENDING);
		worker.run();
		assertThat(only(throttled).getStatus()).isEqualTo(EmailRecipientStatus.PENDING);

		entityManager.createQuery("update EmailCampaignRecipient r set r.nextAttemptAt = :past")
				.setParameter("past", Instant.EPOCH).executeUpdate();
		entityManager.clear();
		doThrow(ses(400, "MessageRejected")).when(sesClient)
				.sendEmail(any(SendEmailRequest.class));
		worker.run();
		assertThat(only(throttled).getStatus()).isEqualTo(EmailRecipientStatus.FAILED);
		assertThat(only(throttled).getErrorCode()).isEqualTo("MessageRejected");
	}

	@Test
	void anUnansweredMailIsNeverSentAgain() {
		EmailCampaignResponse.Detail reviewed = reviewed(coordinator, EmailAudience.MENTORS);
		service.send(coordinator.getId(), idOf(reviewed), confirm(reviewed, Set.of(), 1));
		doThrow(SdkClientException.create("timed out")).when(sesClient)
				.sendEmail(any(SendEmailRequest.class));
		worker.run();

		assertThat(only(reviewed).getStatus()).isEqualTo(EmailRecipientStatus.FAILED);
		assertThat(only(reviewed).getErrorCode()).isEqualTo("NO_ANSWER");
	}

	@Test
	void aMailACrashLeftInDoubtIsFailedInsteadOfSent() {
		EmailCampaignResponse.Detail reviewed = reviewed(coordinator, EmailAudience.MENTORS);
		service.send(coordinator.getId(), idOf(reviewed), confirm(reviewed, Set.of(), 1));
		entityManager.createQuery("update EmailCampaignRecipient r set r.status = :sending, r.claimedAt = :past")
				.setParameter("sending", EmailRecipientStatus.SENDING)
				.setParameter("past", Instant.EPOCH).executeUpdate();
		entityManager.clear();
		worker.run();

		assertThat(only(reviewed).getStatus()).isEqualTo(EmailRecipientStatus.FAILED);
		assertThat(sentToPeople()).isEmpty();
	}

	@Test
	void templatesAreFilledWithTheProgramAndSafeHtml() {
		assertThat(service.templates(coordinator.getId(), program.getId())).hasSize(4).allSatisfy(template -> {
			assertThat(template.subject()).contains("Build '26");
			assertThat(template.bodyHtml()).contains("Elif Koordinatör").doesNotContain("{");
		});
	}

	@Test
	void aDraftCanBeDeletedByItsAuthorOnly() {
		EmailCampaignResponse.Detail reviewed = reviewed(coordinator, EmailAudience.EVERYONE);

		assertThatThrownBy(() -> service.delete(admin.getId(), idOf(reviewed)))
				.isInstanceOf(ResourceNotFoundException.class);
		service.delete(coordinator.getId(), idOf(reviewed));

		assertThat(service.list(coordinator.getId())).isEmpty();
		verify(sesClient, atLeast(1)).sendEmail(any(SendEmailRequest.class));
	}

	@Test
	void aTextThatIsOnlyMarkupIsRefused() {
		assertThatThrownBy(() -> service.create(coordinator.getId(),
				request(EmailAudience.EVERYONE, "<script>x()</script><p> </p>")))
				.isInstanceOf(InvalidRequestException.class);
		verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
	}

	private EmailCampaignResponse.Detail reviewed(AppUser sender, EmailAudience audience) {
		EmailCampaignResponse.Detail draft = service.create(sender.getId(), request(audience, BODY));
		service.sendTest(sender.getId(), idOf(draft));
		return service.review(sender.getId(), idOf(draft));
	}

	private EmailCampaignRequest request(EmailAudience audience, String body) {
		return new EmailCampaignRequest(null, program.getId(), audience, "Konu", body);
	}

	private static SendEmailCampaignRequest confirm(EmailCampaignResponse.Detail reviewed, Set<UUID> unticked,
			int count) {
		return new SendEmailCampaignRequest(null, reviewed.reviewDigest(), unticked, count);
	}

	private static UUID idOf(EmailCampaignResponse.Detail detail) {
		return detail.campaign().id();
	}

	private static EmailCampaignResponse.Recipient rowOf(EmailCampaignResponse.Detail detail, AppUser user) {
		return detail.recipients().stream().filter(row -> row.fullName().equals(user.getFullName())).findFirst()
				.orElseThrow();
	}

	private EmailCampaignRecipient only(EmailCampaignResponse.Detail detail) {
		entityManager.flush();
		entityManager.clear();
		return entityManager.createQuery("select r from EmailCampaignRecipient r where r.campaignId = :id",
				EmailCampaignRecipient.class).setParameter("id", idOf(detail)).getSingleResult();
	}

	/** Everything SES was asked to send except the tests people sent themselves. */
	private List<SendEmailRequest> sentToPeople() {
		ArgumentCaptor<SendEmailRequest> mail = ArgumentCaptor.forClass(SendEmailRequest.class);
		verify(sesClient, atLeast(0)).sendEmail(mail.capture());
		return mail.getAllValues().stream()
				.filter(request -> !request.content().simple().subject().data().startsWith("[TEST]"))
				.toList();
	}

	private static SesV2Exception ses(int statusCode, String code) {
		return (SesV2Exception) SesV2Exception.builder()
				.statusCode(statusCode)
				.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
				.build();
	}

	private AppUser user(String email, String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(name);
		user.setRole(role);
		user.setOrganization(organization);
		entityManager.persist(user);
		return user;
	}

	private Program program(String name, AppUser creator) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName(name);
		created.setCreatedBy(creator.getId());
		created.setCreatedAt(Instant.now());
		entityManager.persist(created);
		return created;
	}

	private Organization team(String name, Program in) {
		Organization organization = new Organization();
		organization.setName(name);
		entityManager.persist(organization);
		ProgramStartup row = new ProgramStartup();
		row.setId(UUID.randomUUID());
		row.setProgramId(in.getId());
		row.setOrganizationId(organization.getId());
		row.setCreatedAt(Instant.now());
		entityManager.persist(row);
		return organization;
	}

	private void mentorOf(Program in, AppUser person, ProgramMentorStatus status) {
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(in.getId());
		membership.setMentorId(person.getId());
		membership.setStatus(status);
		membership.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
		membership.setCreatedAt(Instant.now());
		entityManager.persist(membership);
	}

	private void listed(Organization in, String name, String email) {
		OrganizationPendingMember person = new OrganizationPendingMember();
		person.setId(UUID.randomUUID());
		person.setOrganizationId(in.getId());
		person.setFullName(name);
		person.setEmail(email);
		person.setCreatedAt(Instant.now());
		entityManager.persist(person);
	}
}
