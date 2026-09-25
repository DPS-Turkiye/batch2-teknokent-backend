package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.CoordinatorConversationCounterpart;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.SendCoordinatorMessageRequest;
import com.dps.teknokent.dto.requests.StartCoordinatorConversationRequest;
import com.dps.teknokent.dto.responses.CoordinatorConversationResponse;
import com.dps.teknokent.dto.responses.CoordinatorMessageResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.CoordinatorConversation;
import com.dps.teknokent.entity.CoordinatorMessage;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.CoordinatorConversationRepository;
import com.dps.teknokent.repository.CoordinatorMessageRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.ProgramCoordination;

import lombok.RequiredArgsConstructor;

/**
 * Conversations between a program's coordinator and one team or one mentor of that program. The coordinator
 * side is the program's creator (or an admin); the other side is that team's members or that mentor.
 */
@Service
@RequiredArgsConstructor
public class CoordinatorConversationService {

	private static final int PREVIEW_LENGTH = 200;

	private final DomainLookupService domainLookupService;
	private final CoordinatorConversationRepository conversationRepository;
	private final CoordinatorMessageRepository messageRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final OrganizationRepository organizationRepository;
	private final AppUserRepository appUserRepository;
	private final NotificationService notificationService;

	enum Side {
		COORDINATOR,
		COUNTERPART
	}

	@Transactional(readOnly = true)
	public List<CoordinatorConversationResponse> list(UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		List<CoordinatorConversation> conversations = switch (actor.getRole()) {
			case ADMIN -> conversationRepository.findAll();
			case COORDINATOR -> {
				List<UUID> programIds = ProgramCoordination.programsOf(
						actor.getId(), programRepository, programCoordinatorRepository).stream()
						.map(Program::getId)
						.toList();
				yield programIds.isEmpty() ? List.of() : conversationRepository.findByProgramIdIn(programIds);
			}
			case MENTOR -> conversationRepository.findByMentorId(actor.getId());
			case MENTEE -> actor.getOrganization() == null
					? List.of()
					: conversationRepository.findByOrganizationId(actor.getOrganization().getId());
		};
		Side side = actor.getRole() == UserRole.ADMIN || actor.getRole() == UserRole.COORDINATOR
				? Side.COORDINATOR
				: Side.COUNTERPART;
		return toResponses(conversations, side);
	}

	@Transactional
	public CoordinatorConversationResponse start(UUID actorId, StartCoordinatorConversationRequest request) {
		AppUser actor = domainLookupService.requireUser(actorId);
		Program program = programRepository.findById(request.programId())
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + request.programId()));
		if (!coordinates(actor, program)) {
			throw new InvalidRequestException("Only the program coordinator can start a conversation.");
		}
		if ((request.organizationId() == null) == (request.mentorId() == null)) {
			throw new InvalidRequestException("Choose either a team or a mentor.");
		}
		CoordinatorConversation conversation;
		if (request.organizationId() != null) {
			if (programStartupRepository.findByProgramIdAndOrganizationId(program.getId(), request.organizationId())
					.isEmpty()) {
				throw new InvalidRequestException("This team is not in the program.");
			}
			conversation = conversationRepository
					.findByProgramIdAndOrganizationId(program.getId(), request.organizationId())
					.orElseGet(() -> newConversation(program.getId(), request.organizationId(), null));
		} else {
			if (programMentorRepository.findByProgramIdAndMentorId(program.getId(), request.mentorId()).isEmpty()) {
				throw new InvalidRequestException("This mentor is not in the program.");
			}
			conversation = conversationRepository.findByProgramIdAndMentorId(program.getId(), request.mentorId())
					.orElseGet(() -> newConversation(program.getId(), null, request.mentorId()));
		}
		return toResponses(List.of(conversation), Side.COORDINATOR).getFirst();
	}

	@Transactional
	public List<CoordinatorMessageResponse> messages(UUID actorId, UUID conversationId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		CoordinatorConversation conversation = requireConversation(conversationId);
		Side side = requireSide(actor, conversation);
		markRead(conversation, side, Instant.now());
		List<CoordinatorMessage> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
		Map<UUID, AppUser> senders = appUserRepository
				.findAllById(messages.stream().map(CoordinatorMessage::getSenderId).filter(id -> id != null)
						.collect(Collectors.toSet()))
				.stream()
				.collect(Collectors.toMap(AppUser::getId, Function.identity()));
		return messages.stream()
				.map(message -> toMessage(message, senders.get(message.getSenderId()), actor.getId()))
				.toList();
	}

	@Transactional
	public CoordinatorMessageResponse send(UUID actorId, UUID conversationId, SendCoordinatorMessageRequest request) {
		AppUser actor = domainLookupService.requireUser(actorId);
		CoordinatorConversation conversation = requireConversation(conversationId);
		Side side = requireSide(actor, conversation);
		Instant now = Instant.now();
		String body = request.body().trim();
		CoordinatorMessage message = new CoordinatorMessage();
		message.setId(UUID.randomUUID());
		message.setConversationId(conversationId);
		message.setSenderId(actor.getId());
		message.setBody(body);
		message.setCreatedAt(now);
		messageRepository.save(message);
		conversation.setLastMessageAt(now);
		conversation.setLastMessagePreview(body.length() > PREVIEW_LENGTH ? body.substring(0, PREVIEW_LENGTH) : body);
		markRead(conversation, side, now);
		notifyOtherSide(conversation, side, actor);
		return toMessage(message, actor, actor.getId());
	}

	private void notifyOtherSide(CoordinatorConversation conversation, Side senderSide, AppUser sender) {
		if (senderSide == Side.COUNTERPART) {
			programRepository.findById(conversation.getProgramId())
					.map(program -> appUserRepository.findAllById(
							ProgramCoordination.coordinatorIds(program, programCoordinatorRepository)))
					.orElse(List.of())
					.forEach(coordinator -> notificationService.create(
							coordinator,
							NotificationCategory.MESSAGE,
							"Yeni mesaj",
							sender.getFullName() + " sana bir mesaj gönderdi.",
							"/coordinator/messages?conversation=" + conversation.getId()));
			return;
		}
		String body = sender.getFullName() + " (koordinatör) sana bir mesaj gönderdi.";
		if (conversation.getMentorId() != null) {
			appUserRepository.findById(conversation.getMentorId())
					.ifPresent(mentor -> notificationService.create(
							mentor, NotificationCategory.MESSAGE, "Koordinatörden mesaj", body, "/mentor-dashboard/messages"));
			return;
		}
		for (AppUser member : appUserRepository.findByOrganizationIdOrderByFullNameAsc(conversation.getOrganizationId())) {
			notificationService.create(member, NotificationCategory.MESSAGE, "Koordinatörden mesaj", body, "/messages");
		}
	}

	private CoordinatorConversation newConversation(UUID programId, UUID organizationId, UUID mentorId) {
		CoordinatorConversation conversation = new CoordinatorConversation();
		conversation.setId(UUID.randomUUID());
		conversation.setProgramId(programId);
		conversation.setOrganizationId(organizationId);
		conversation.setMentorId(mentorId);
		conversation.setCreatedAt(Instant.now());
		return conversationRepository.save(conversation);
	}

	private CoordinatorConversation requireConversation(UUID conversationId) {
		return conversationRepository.findById(conversationId)
				.orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + conversationId));
	}

	private Side requireSide(AppUser actor, CoordinatorConversation conversation) {
		Program program = programRepository.findById(conversation.getProgramId())
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + conversation.getProgramId()));
		if (coordinates(actor, program)) {
			return Side.COORDINATOR;
		}
		if (actor.getId().equals(conversation.getMentorId())) {
			return Side.COUNTERPART;
		}
		if (conversation.getOrganizationId() != null && actor.getOrganization() != null
				&& conversation.getOrganizationId().equals(actor.getOrganization().getId())) {
			return Side.COUNTERPART;
		}
		throw new InvalidRequestException("You are not part of this conversation.");
	}

	private boolean coordinates(AppUser actor, Program program) {
		return actor.getRole() == UserRole.ADMIN
				|| (actor.getRole() == UserRole.COORDINATOR
						&& ProgramCoordination.coordinates(program, actor.getId(), programCoordinatorRepository));
	}

	private static void markRead(CoordinatorConversation conversation, Side side, Instant at) {
		if (side == Side.COORDINATOR) {
			conversation.setCoordinatorReadAt(at);
		} else {
			conversation.setCounterpartReadAt(at);
		}
	}

	private static boolean unread(CoordinatorConversation conversation, Side side) {
		Instant readAt = side == Side.COORDINATOR ? conversation.getCoordinatorReadAt() : conversation.getCounterpartReadAt();
		return conversation.getLastMessageAt() != null
				&& (readAt == null || conversation.getLastMessageAt().isAfter(readAt));
	}

	private List<CoordinatorConversationResponse> toResponses(List<CoordinatorConversation> conversations, Side side) {
		if (conversations.isEmpty()) {
			return List.of();
		}
		Map<UUID, Program> programs = programRepository
				.findAllById(conversations.stream().map(CoordinatorConversation::getProgramId).collect(Collectors.toSet()))
				.stream()
				.collect(Collectors.toMap(Program::getId, Function.identity()));
		Set<UUID> userIds = new HashSet<>();
		programs.values().forEach(program -> userIds.add(program.getCreatedBy()));
		conversations.stream().map(CoordinatorConversation::getMentorId).filter(id -> id != null).forEach(userIds::add);
		Map<UUID, AppUser> users = appUserRepository.findAllById(userIds).stream()
				.collect(Collectors.toMap(AppUser::getId, Function.identity()));
		Map<UUID, Organization> organizations = organizationRepository
				.findAllById(conversations.stream().map(CoordinatorConversation::getOrganizationId)
						.filter(id -> id != null).collect(Collectors.toSet()))
				.stream()
				.collect(Collectors.toMap(Organization::getId, Function.identity()));
		return conversations.stream()
				.sorted(Comparator.comparing(
						(CoordinatorConversation conversation) -> conversation.getLastMessageAt() == null
								? conversation.getCreatedAt()
								: conversation.getLastMessageAt())
						.reversed())
				.map(conversation -> {
					Program program = programs.get(conversation.getProgramId());
					AppUser coordinator = program == null ? null : users.get(program.getCreatedBy());
					boolean team = conversation.getOrganizationId() != null;
					UUID counterpartId = team ? conversation.getOrganizationId() : conversation.getMentorId();
					String counterpartName = team
							? nameOf(organizations.get(counterpartId))
							: nameOf(users.get(counterpartId));
					return new CoordinatorConversationResponse(
							conversation.getId(),
							conversation.getProgramId(),
							program == null ? null : program.getName(),
							team ? CoordinatorConversationCounterpart.TEAM : CoordinatorConversationCounterpart.MENTOR,
							counterpartId,
							counterpartName,
							program == null ? null : program.getCreatedBy(),
							coordinator == null ? null : coordinator.getFullName(),
							conversation.getLastMessagePreview(),
							conversation.getLastMessageAt(),
							unread(conversation, side));
				})
				.toList();
	}

	private static String nameOf(Organization organization) {
		return organization == null ? "Takım" : organization.getName();
	}

	private static String nameOf(AppUser user) {
		return user == null ? "Mentor" : user.getFullName();
	}

	private static CoordinatorMessageResponse toMessage(CoordinatorMessage message, AppUser sender, UUID actorId) {
		return new CoordinatorMessageResponse(
				message.getId(),
				message.getSenderId(),
				sender == null ? null : sender.getFullName(),
				actorId.equals(message.getSenderId()),
				message.getBody(),
				message.getCreatedAt());
	}
}
