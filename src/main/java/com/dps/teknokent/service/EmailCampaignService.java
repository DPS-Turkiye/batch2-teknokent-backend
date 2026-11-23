package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.EmailCenterSettings;
import com.dps.teknokent.domain.EmailAudience;
import com.dps.teknokent.domain.EmailCampaignStatus;
import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.EmailCampaignRequest;
import com.dps.teknokent.dto.requests.SendEmailCampaignRequest;
import com.dps.teknokent.dto.responses.EmailCampaignResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.EmailCampaign;
import com.dps.teknokent.entity.EmailCampaignRecipient;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.EmailCampaignRecipientRepository;
import com.dps.teknokent.repository.EmailCampaignRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.EmailHtml;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Sha256;
import com.dps.teknokent.util.Texts;

/**
 * The email center's drafts. A mail leaves only after its author sent themselves a test of the current text, looked at
 * the list it goes to and confirmed the count; a list that has changed since has to be looked at again.
 */
@Service
public class EmailCampaignService {

	/** Client-visible reasons; the frontend branches on them. */
	static final String LIST_CHANGED = "EMAIL_LIST_CHANGED";
	static final String TEST_NEEDED = "EMAIL_TEST_NEEDED";
	static final String SENDING_OFF = "EMAIL_SENDING_OFF";

	private final EmailCampaignRepository campaignRepository;
	private final EmailCampaignRecipientRepository recipientRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final AppUserRepository appUserRepository;
	private final CoordinatorProgramService programService;
	private final EmailAudienceResolver audienceResolver;
	private final EmailComposer composer;
	private final EmailCenterMailer mailer;
	private final EmailBounceMonitor bounceMonitor;
	private final EmailCenterSettings settings;
	private final PlatformTransactionManager transactionManager;
	private final Clock clock;

	@Autowired
	EmailCampaignService(
			EmailCampaignRepository campaignRepository,
			EmailCampaignRecipientRepository recipientRepository,
			ProgramRepository programRepository,
			ProgramCoordinatorRepository programCoordinatorRepository,
			AppUserRepository appUserRepository,
			CoordinatorProgramService programService,
			EmailAudienceResolver audienceResolver,
			EmailComposer composer,
			EmailCenterMailer mailer,
			EmailBounceMonitor bounceMonitor,
			EmailCenterSettings settings,
			PlatformTransactionManager transactionManager) {
		this(campaignRepository, recipientRepository, programRepository, programCoordinatorRepository,
				appUserRepository, programService, audienceResolver, composer, mailer, bounceMonitor, settings,
				transactionManager,
				Clock.systemUTC());
	}

	EmailCampaignService(
			EmailCampaignRepository campaignRepository,
			EmailCampaignRecipientRepository recipientRepository,
			ProgramRepository programRepository,
			ProgramCoordinatorRepository programCoordinatorRepository,
			AppUserRepository appUserRepository,
			CoordinatorProgramService programService,
			EmailAudienceResolver audienceResolver,
			EmailComposer composer,
			EmailCenterMailer mailer,
			EmailBounceMonitor bounceMonitor,
			EmailCenterSettings settings,
			PlatformTransactionManager transactionManager,
			Clock clock) {
		this.campaignRepository = campaignRepository;
		this.recipientRepository = recipientRepository;
		this.programRepository = programRepository;
		this.programCoordinatorRepository = programCoordinatorRepository;
		this.appUserRepository = appUserRepository;
		this.programService = programService;
		this.audienceResolver = audienceResolver;
		this.composer = composer;
		this.mailer = mailer;
		this.bounceMonitor = bounceMonitor;
		this.settings = settings;
		this.transactionManager = transactionManager;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public EmailCampaignResponse.Options options(UUID actorId) {
		AppUser actor = requireSender(actorId);
		List<Program> programs = actor.has(UserRole.ADMIN)
				? programRepository.findAllByOrderByCreatedAtDesc()
				: ProgramCoordination.programsOf(actorId, programRepository, programCoordinatorRepository);
		return new EmailCampaignResponse.Options(sendingOn(), programs.stream()
				.map(program -> new EmailCampaignResponse.ProgramOption(program.getId(), program.getName()))
				.toList());
	}

	/**
	 * Ready texts with the program's and the sender's names filled in; the sender edits them before anything leaves.
	 */
	@Transactional(readOnly = true)
	public List<EmailCampaignResponse.Template> templates(UUID actorId, UUID programId) {
		AppUser actor = requireSender(actorId);
		Program program = programService.requireManagedProgram(actorId, programId);
		return Arrays.stream(EmailTemplate.values())
				.map(template -> template.fill(actor, program))
				.toList();
	}

	/** An admin sees every mail, a coordinator their own. */
	@Transactional(readOnly = true)
	public List<EmailCampaignResponse> list(UUID actorId) {
		AppUser actor = requireSender(actorId);
		return responses(actor.has(UserRole.ADMIN)
				? campaignRepository.findTop100ByOrderByCreatedAtDesc()
				: campaignRepository.findTop100ByCreatedByOrderByCreatedAtDesc(actorId));
	}

	@Transactional(readOnly = true)
	public EmailCampaignResponse.Detail get(UUID actorId, UUID id) {
		return detail(requireReadable(actorId, id));
	}

	@Transactional
	public EmailCampaignResponse.Detail create(UUID actorId, EmailCampaignRequest request) {
		requireSender(actorId);
		programService.requireManagedProgram(actorId, request.programId());
		EmailCampaign campaign = new EmailCampaign();
		campaign.setId(UUID.randomUUID());
		campaign.setCreatedBy(actorId);
		campaign.setCreatedAt(clock.instant());
		write(campaign, request);
		return detail(campaignRepository.save(campaign));
	}

	/** Changing a draft undoes its test and its review: both were of the old mail. */
	@Transactional
	public EmailCampaignResponse.Detail update(UUID actorId, UUID id, EmailCampaignRequest request) {
		EmailCampaign campaign = requireOwnDraft(actorId, id);
		programService.requireManagedProgram(actorId, request.programId());
		write(campaign, request);
		campaign.setTestSentAt(null);
		dropReview(campaign);
		return detail(campaignRepository.save(campaign));
	}

	@Transactional
	public void delete(UUID actorId, UUID id) {
		EmailCampaign campaign = requireOwnDraft(actorId, id);
		recipientRepository.deleteByCampaignId(id);
		campaignRepository.delete(campaign);
	}

	/**
	 * Sends the current text to its author's own address. SES runs between two transactions, so its round trip does
	 * not hold a pool connection; text edited meanwhile stays untested.
	 */
	public EmailCampaignResponse.Detail sendTest(UUID actorId, UUID id) {
		TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
		readOnly.setReadOnly(true);
		record Prepared(EmailCenterMailer.Mail mail, String text) {
		}
		Prepared prepared = readOnly.execute(status -> {
			EmailCampaign campaign = requireOwn(actorId, id);
			requireDraft(campaign);
			Program program = programService.requireManagedProgram(actorId, campaign.getProgramId());
			if (!settings.canSend()) {
				throw new ConflictException(Texts.forRequest("error.emailSendingOff"), SENDING_OFF);
			}
			return new Prepared(composer.test(campaign, requireSender(actorId), program.getName()),
					textDigest(campaign));
		});
		if (!settings.mayWriteTo(prepared.mail().to())) {
			throw new ConflictException(Texts.forRequest("error.emailTestNotAllowed"));
		}
		if (mailer.send(prepared.mail()).result() != EmailCenterMailer.Result.ACCEPTED) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, Texts.forRequest("error.emailTestFailed"));
		}
		return new TransactionTemplate(transactionManager).execute(status -> {
			EmailCampaign campaign = requireOwnDraft(actorId, id);
			if (textDigest(campaign).equals(prepared.text())) {
				campaign.setTestSentAt(clock.instant());
			}
			return detail(campaignRepository.save(campaign));
		});
	}

	/** Takes the list as it is now, for the sender to look at. */
	@Transactional
	public EmailCampaignResponse.Detail review(UUID actorId, UUID id) {
		EmailCampaign campaign = requireOwnDraft(actorId, id);
		programService.requireManagedProgram(actorId, campaign.getProgramId());
		List<EmailAudienceResolver.Candidate> candidates = resolve(campaign);
		dropReview(campaign);
		recipientRepository.saveAll(candidates.stream().map(candidate -> recipient(id, candidate)).toList());
		campaign.setReviewDigest(digest(campaign, candidates));
		campaign.setReviewedAt(clock.instant());
		return detail(campaignRepository.save(campaign));
	}

	/** Confirms exactly the list that was looked at, minus the people taken off it. The worker sends it. */
	@Transactional(noRollbackFor = ConflictException.class)
	public EmailCampaignResponse.Detail send(UUID actorId, UUID id, SendEmailCampaignRequest request) {
		EmailCampaign campaign = requireOwnDraft(actorId, id);
		programService.requireManagedProgram(actorId, campaign.getProgramId());
		if (!sendingOn()) {
			throw new ConflictException(Texts.forRequest("error.emailSendingOff"), SENDING_OFF);
		}
		if (campaign.getTestSentAt() == null) {
			throw new ConflictException(Texts.forRequest("error.emailTestNeeded"), TEST_NEEDED);
		}
		String reviewed = campaign.getReviewDigest();
		if (reviewed == null || !reviewed.equals(request.reviewDigest())
				|| !reviewed.equals(digest(campaign, resolve(campaign)))) {
			dropReview(campaign);
			campaignRepository.save(campaign);
			throw new ConflictException(Texts.forRequest("error.emailListChanged"), LIST_CHANGED);
		}
		confirm(campaign, request);
		return detail(campaign);
	}

	private void confirm(EmailCampaign campaign, SendEmailCampaignRequest request) {
		List<EmailCampaignRecipient> offered = recipientRepository
				.findByCampaignIdOrderByFullNameAsc(campaign.getId()).stream()
				.filter(row -> row.getStatus() == EmailRecipientStatus.REVIEWED)
				.toList();
		Set<UUID> offeredIds = offered.stream().map(EmailCampaignRecipient::getId).collect(Collectors.toSet());
		Set<UUID> unticked = new HashSet<>(request.untickedIds());
		if (!offeredIds.containsAll(unticked) || offered.size() - unticked.size() != request.expectedCount()) {
			throw new InvalidRequestException(Texts.forRequest("error.emailCountMismatch"));
		}
		Instant now = clock.instant();
		for (EmailCampaignRecipient row : offered) {
			boolean off = unticked.contains(row.getId());
			row.setStatus(off ? EmailRecipientStatus.UNTICKED : EmailRecipientStatus.PENDING);
			row.setNextAttemptAt(off ? null : now);
		}
		recipientRepository.saveAll(offered);
		campaign.setStatus(EmailCampaignStatus.SENDING);
		campaign.setConfirmedAt(now);
		campaignRepository.save(campaign);
	}

	private List<EmailAudienceResolver.Candidate> resolve(EmailCampaign campaign) {
		List<EmailAudienceResolver.Candidate> candidates = audienceResolver.resolve(
				campaign.getProgramId(), campaign.getAudience());
		if (candidates.size() > settings.getMaxRecipients()) {
			throw new InvalidRequestException(
					Texts.forRequest("error.emailListTooLong", settings.getMaxRecipients()));
		}
		return candidates;
	}

	/** Bounces nobody hears about are the reason a mass mail does not leave without them being read. */
	private boolean sendingOn() {
		return settings.canSend() && bounceMonitor.hearing();
	}

	private static void write(EmailCampaign campaign, EmailCampaignRequest request) {
		String html = EmailHtml.sanitize(request.bodyHtml());
		if (EmailHtml.toText(html).isEmpty()) {
			throw new InvalidRequestException(Texts.forRequest("error.emailEmpty"));
		}
		campaign.setProgramId(request.programId());
		campaign.setAudience(request.audience());
		campaign.setSubject(request.subject().trim());
		campaign.setBodyHtml(html);
	}

	private void dropReview(EmailCampaign campaign) {
		recipientRepository.deleteByCampaignId(campaign.getId());
		campaign.setReviewDigest(null);
		campaign.setReviewedAt(null);
	}

	private static EmailCampaignRecipient recipient(UUID campaignId, EmailAudienceResolver.Candidate candidate) {
		EmailCampaignRecipient row = new EmailCampaignRecipient();
		row.setId(UUID.randomUUID());
		row.setCampaignId(campaignId);
		row.setUserId(candidate.userId());
		row.setOrganizationId(candidate.organizationId());
		row.setPendingMemberId(candidate.pendingMemberId());
		row.setKind(candidate.kind());
		row.setAddress(candidate.address());
		row.setFullName(candidate.fullName());
		row.setTeamName(candidate.teamName());
		row.setAccountStatus(candidate.accountStatus());
		row.setExclusion(candidate.exclusion());
		row.setStatus(candidate.exclusion() == null ? EmailRecipientStatus.REVIEWED : EmailRecipientStatus.EXCLUDED);
		return row;
	}

	private static String textDigest(EmailCampaign campaign) {
		return Sha256.hex(campaign.getSubject() + '\u0000' + campaign.getBodyHtml());
	}

	private static String digest(EmailCampaign campaign, List<EmailAudienceResolver.Candidate> candidates) {
		String people = candidates.stream()
				.map(candidate -> String.join("\u0000", String.valueOf(candidate.userId()),
						String.valueOf(candidate.pendingMemberId()), String.valueOf(candidate.address()),
						candidate.fullName(), String.valueOf(candidate.teamName()),
						String.valueOf(candidate.accountStatus()), String.valueOf(candidate.exclusion())))
				.collect(Collectors.joining("\u0001"));
		return Sha256.hex(campaign.getId() + textDigest(campaign) + campaign.getProgramId() + campaign.getAudience()
				+ '\u0001' + people);
	}

	private AppUser requireSender(UUID actorId) {
		if (!settings.isEnabled()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.emailCenterOff"));
		}
		return programService.requireCoordinatorOrAdmin(actorId);
	}

	private EmailCampaign requireReadable(UUID actorId, UUID id) {
		AppUser actor = requireSender(actorId);
		if (actor.has(UserRole.ADMIN)) {
			return campaignRepository.findById(id).orElseThrow(() -> notFound(id));
		}
		EmailCampaign campaign = requireOwn(actorId, id);
		// The log holds addresses a coordinator may see only while they run the program.
		programService.requireManagedProgram(actorId, campaign.getProgramId());
		return campaign;
	}

	private EmailCampaign requireOwn(UUID actorId, UUID id) {
		requireSender(actorId);
		return campaignRepository.findById(id)
				.filter(campaign -> campaign.getCreatedBy().equals(actorId))
				.orElseThrow(() -> notFound(id));
	}

	/** Locked: only its author works on a draft, one change at a time. */
	private EmailCampaign requireOwnDraft(UUID actorId, UUID id) {
		requireSender(actorId);
		EmailCampaign campaign = campaignRepository.findByIdForUpdate(id)
				.filter(found -> found.getCreatedBy().equals(actorId))
				.orElseThrow(() -> notFound(id));
		requireDraft(campaign);
		return campaign;
	}

	private static void requireDraft(EmailCampaign campaign) {
		if (campaign.getStatus() != EmailCampaignStatus.DRAFT) {
			throw new ConflictException(Texts.forRequest("error.emailAlreadySent"));
		}
	}

	private static ResourceNotFoundException notFound(UUID id) {
		return new ResourceNotFoundException("E-mail not found: " + id);
	}

	private EmailCampaignResponse.Detail detail(EmailCampaign campaign) {
		List<EmailCampaignResponse.Recipient> recipients = recipientRepository
				.findByCampaignIdOrderByFullNameAsc(campaign.getId()).stream()
				.map(EmailCampaignService::toRecipient)
				.toList();
		return new EmailCampaignResponse.Detail(
				responses(List.of(campaign)).getFirst(),
				campaign.getBodyHtml(),
				appUserRepository.findById(campaign.getCreatedBy()).map(composer::replyTo).orElse(null),
				campaign.getReviewDigest(),
				recipients);
	}

	private static EmailCampaignResponse.Recipient toRecipient(EmailCampaignRecipient row) {
		return new EmailCampaignResponse.Recipient(row.getId(), row.getFullName(), row.getAddress(),
				row.getTeamName(), row.getKind(), row.getAccountStatus(), row.getStatus(), row.getExclusion(),
				row.getErrorCode(), row.getSentAt());
	}

	private List<EmailCampaignResponse> responses(Collection<EmailCampaign> campaigns) {
		if (campaigns.isEmpty()) {
			return List.of();
		}
		Map<UUID, Map<EmailRecipientStatus, Long>> counts = new HashMap<>();
		recipientRepository.countByStatus(campaigns.stream().map(EmailCampaign::getId).toList())
				.forEach(row -> counts
						.computeIfAbsent(row.getCampaignId(), key -> new EnumMap<>(EmailRecipientStatus.class))
						.put(row.getStatus(), row.getTotal()));
		Map<UUID, String> programs = programRepository
				.findAllById(campaigns.stream().map(EmailCampaign::getProgramId).collect(Collectors.toSet())).stream()
				.collect(Collectors.toMap(Program::getId, Program::getName));
		Map<UUID, AppUser> senders = appUserRepository
				.findAllById(campaigns.stream().map(EmailCampaign::getCreatedBy).collect(Collectors.toSet())).stream()
				.collect(Collectors.toMap(AppUser::getId, Function.identity()));
		return campaigns.stream()
				.map(campaign -> new EmailCampaignResponse(
						campaign.getId(),
						campaign.getProgramId(),
						programs.getOrDefault(campaign.getProgramId(), ""),
						nameOf(senders.get(campaign.getCreatedBy())),
						campaign.getAudience(),
						campaign.getSubject(),
						campaign.getStatus(),
						campaign.getTestSentAt(),
						campaign.getConfirmedAt(),
						campaign.getFinishedAt(),
						campaign.getCreatedAt(),
						counts.getOrDefault(campaign.getId(), Map.of())))
				.toList();
	}

	private static String nameOf(@Nullable AppUser user) {
		return user == null ? AppUser.DELETED_FULL_NAME : user.getFullName();
	}

	/** The built-in texts; an author changes them in the draft. */
	enum EmailTemplate {
		INVITE_REMINDER("inviteReminder", EmailAudience.UNJOINED),
		ANNOUNCEMENT("announcement", EmailAudience.EVERYONE),
		SESSION_REMINDER("sessionReminder", EmailAudience.PARTICIPANTS),
		MENTOR_INFORMATION("mentorInformation", EmailAudience.MENTORS);

		private final String key;
		private final EmailAudience audience;

		EmailTemplate(String key, EmailAudience audience) {
			this.key = key;
			this.audience = audience;
		}

		EmailCampaignResponse.Template fill(AppUser sender, Program program) {
			String prefix = "emailCenter.template." + key;
			return new EmailCampaignResponse.Template(
					key,
					Texts.get(sender, prefix + ".name"),
					audience,
					Texts.get(sender, prefix + ".subject", program.getName()),
					EmailHtml.sanitize(Texts.get(sender, prefix + ".body",
							EmailHtml.escape(program.getName()), EmailHtml.escape(sender.getFullName()))));
		}
	}
}
