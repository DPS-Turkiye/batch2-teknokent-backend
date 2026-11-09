package com.dps.teknokent.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorshipGoalRequest;
import com.dps.teknokent.dto.requests.CreateMentorshipRequest;
import com.dps.teknokent.dto.requests.UpdateMentorshipGoalRequest;
import com.dps.teknokent.dto.requests.UpdateMentorshipRequest;
import com.dps.teknokent.dto.requests.UpsertMentorshipWeekCheckRequest;
import com.dps.teknokent.dto.responses.MentorshipGoalResponse;
import com.dps.teknokent.dto.responses.MentorshipResponse;
import com.dps.teknokent.dto.responses.MentorshipWeekCheckResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipGoal;
import com.dps.teknokent.entity.MentorshipWeekCheck;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipGoalRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipWeekCheckRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.TextUtils;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MentorshipService {

	private final MentorshipRepository mentorshipRepository;
	private final MentorshipGoalRepository mentorshipGoalRepository;
	private final MentorshipWeekCheckRepository mentorshipWeekCheckRepository;
	private final AppUserRepository appUserRepository;
	private final DomainLookupService domainLookupService;
	private final NotificationService notificationService;
	private final MenteeProgramService menteeProgramService;
	private final ProgramRepository programRepository;

	@Transactional(readOnly = true)
	public List<MentorshipResponse> findAll() {
		return mentorshipRepository.findAllByOrderByCreatedAtDesc().stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<MentorshipResponse> findActive(UUID mentorId, UUID organizationId) {
		if (mentorId == null && organizationId == null) {
			throw new InvalidRequestException("mentorId or organizationId is required");
		}
		if (mentorId != null && organizationId != null) {
			throw new InvalidRequestException("Provide only one of mentorId or organizationId");
		}
		LocalDate today = LocalDate.now();
		List<Mentorship> rows = mentorId != null
				? mentorshipRepository.findActiveForMentorOn(mentorId, MentorshipStatus.ACTIVE, today)
				: mentorshipRepository.findActiveForOrganizationOn(
						organizationId, MentorshipStatus.ACTIVE, today);
		return rows.stream().map(this::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public MentorshipResponse findById(UUID id) {
		return toResponse(requireMentorship(id));
	}

	@Transactional
	public MentorshipResponse create(CreateMentorshipRequest request) {
		if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
			throw new InvalidRequestException("endDate must be on or after startDate");
		}
		int daysPerWeek = request.daysPerWeek() == null ? 1 : request.daysPerWeek();
		if (daysPerWeek < 1 || daysPerWeek > 7) {
			throw new InvalidRequestException("daysPerWeek must be between 1 and 7");
		}

		AppUser mentor = domainLookupService.requireUser(request.mentorId());
		if (!mentor.has(UserRole.MENTOR)) {
			throw new InvalidRequestException("Selected user does not have the MENTOR role");
		}

		Organization organization = domainLookupService.requireOrganization(request.organizationId());
		Mentorship mentorship = Mentorship.fromRequest(
				new CreateMentorshipRequest(
						request.mentorId(),
						request.organizationId(),
						request.status() == null ? MentorshipStatus.ACTIVE : request.status(),
						request.startDate(),
						request.endDate(),
						daysPerWeek,
						request.goals()),
				mentor,
				organization);
		menteeProgramService.programOf(organization).flatMap(programRepository::findById)
				.ifPresent(mentorship::setProgram);
		Mentorship saved = mentorshipRepository.save(mentorship);
		notifyOrgMentees(saved);
		return toResponse(saved);
	}

	@Transactional
	public MentorshipResponse update(UUID mentorshipId, UUID actorId, UpdateMentorshipRequest request) {
		Mentorship mentorship = requireMentorship(mentorshipId);
		requireMentorOwner(mentorship, actorId);
		if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
			throw new InvalidRequestException("endDate must be on or after startDate");
		}
		int daysPerWeek = request.daysPerWeek() == null ? mentorship.getDaysPerWeek() : request.daysPerWeek();
		if (daysPerWeek < 1 || daysPerWeek > 7) {
			throw new InvalidRequestException("daysPerWeek must be between 1 and 7");
		}
		mentorship.setStartDate(request.startDate());
		mentorship.setEndDate(request.endDate());
		mentorship.setDaysPerWeek(daysPerWeek);
		return toResponse(mentorshipRepository.save(mentorship));
	}

	@Transactional
	public void delete(UUID mentorshipId, UUID actorId) {
		Mentorship mentorship = requireMentorship(mentorshipId);
		requireMentorOwner(mentorship, actorId);
		mentorshipRepository.delete(mentorship);
	}

	@Transactional
	public MentorshipGoalResponse addGoal(UUID mentorshipId, UUID actorId, CreateMentorshipGoalRequest request) {
		Mentorship mentorship = requireMentorship(mentorshipId);
		requireMentorOwner(mentorship, actorId);
		String title = TextUtils.trim(request.title());
		if (title.isEmpty()) {
			throw new InvalidRequestException("title is required");
		}
		int nextOrder = (int) mentorshipGoalRepository.countByMentorshipId(mentorshipId);
		MentorshipGoal goal = MentorshipGoal.create(mentorship, title, nextOrder);
		return MentorshipGoalResponse.fromEntity(mentorshipGoalRepository.save(goal));
	}

	@Transactional
	public MentorshipGoalResponse updateGoal(
			UUID mentorshipId, UUID goalId, UUID actorId, UpdateMentorshipGoalRequest request) {
		Mentorship mentorship = requireMentorship(mentorshipId);
		requireMentorOwner(mentorship, actorId);
		MentorshipGoal goal = mentorshipGoalRepository.findById(goalId)
				.filter(item -> item.getMentorship().getId().equals(mentorshipId))
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship goal not found: " + goalId));
		goal.setDone(Boolean.TRUE.equals(request.done()));
		return MentorshipGoalResponse.fromEntity(mentorshipGoalRepository.save(goal));
	}

	@Transactional(readOnly = true)
	public List<MentorshipWeekCheckResponse> listWeekChecks(UUID mentorId, LocalDate weekStart) {
		if (mentorId == null) {
			throw new InvalidRequestException("mentorId is required");
		}
		LocalDate normalizedWeekStart = mondayOf(weekStart);
		LocalDate weekEnd = normalizedWeekStart.plusDays(6);
		List<Mentorship> overlapping = mentorshipRepository.findOverlappingWeekForMentor(
				mentorId, MentorshipStatus.ACTIVE, normalizedWeekStart, weekEnd);
		if (overlapping.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = overlapping.stream().map(Mentorship::getId).toList();
		Map<UUID, MentorshipWeekCheck> checks = mentorshipWeekCheckRepository
				.findByMentorshipIdInAndWeekStart(ids, normalizedWeekStart)
				.stream()
				.collect(Collectors.toMap(check -> check.getMentorship().getId(), Function.identity()));
		return overlapping.stream()
				.map(mentorship -> {
					MentorshipWeekCheck check = checks.get(mentorship.getId());
					return new MentorshipWeekCheckResponse(
							mentorship.getId(),
							Organization.visibleName(mentorship.getOrganization()),
							mentorship.getDaysPerWeek(),
							mentorship.getStartDate(),
							mentorship.getEndDate(),
							normalizedWeekStart,
							check != null && check.isCompleted());
				})
				.toList();
	}

	@Transactional
	public MentorshipWeekCheckResponse upsertWeekCheck(
			UUID mentorshipId, UUID actorId, UpsertMentorshipWeekCheckRequest request) {
		Mentorship mentorship = requireMentorship(mentorshipId);
		requireMentorOwner(mentorship, actorId);
		LocalDate weekStart = mondayOf(request.weekStart());
		LocalDate weekEnd = weekStart.plusDays(6);
		if (mentorship.getStartDate().isAfter(weekEnd)
				|| (mentorship.getEndDate() != null && mentorship.getEndDate().isBefore(weekStart))) {
			throw new InvalidRequestException("Mentorship is not active in the selected week");
		}
		MentorshipWeekCheck check = mentorshipWeekCheckRepository
				.findByMentorshipIdAndWeekStart(mentorshipId, weekStart)
				.orElseGet(() -> MentorshipWeekCheck.create(mentorship, weekStart, false));
		check.setCompleted(Boolean.TRUE.equals(request.completed()));
		MentorshipWeekCheck saved = mentorshipWeekCheckRepository.save(check);
		return new MentorshipWeekCheckResponse(
				mentorship.getId(),
				Organization.visibleName(mentorship.getOrganization()),
				mentorship.getDaysPerWeek(),
				mentorship.getStartDate(),
				mentorship.getEndDate(),
				weekStart,
				saved.isCompleted());
	}

	private void notifyOrgMentees(Mentorship mentorship) {
		appUserRepository.findByOrganizationIdOrderByFullNameAsc(mentorship.getOrganization().getId()).stream()
				.filter(user -> user.has(UserRole.MENTEE))
				.forEach(mentee -> notificationService.create(
						mentee,
						Texts.get(mentee, "notification.regularMentorshipStarted.title"),
						Texts.get(mentee, "notification.regularMentorshipStarted.body",
								mentorship.getMentor().getFullName(),
								mentorship.getOrganization().getName(),
								mentorship.getEndDate() == null
										? Texts.get(mentee, "notification.regularMentorshipStarted.noEnd")
										: mentorship.getEndDate().toString()),
						"/mentorships?tab=regular"));
	}

	private MentorshipResponse toResponse(Mentorship mentorship) {
		List<MentorshipGoalResponse> goals = mentorshipGoalRepository
				.findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(mentorship.getId())
				.stream()
				.map(MentorshipGoalResponse::fromEntity)
				.toList();
		return MentorshipResponse.fromEntity(mentorship, goals);
	}

	private Mentorship requireMentorship(UUID id) {
		return mentorshipRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship not found: " + id));
	}

	private void requireMentorOwner(Mentorship mentorship, UUID actorId) {
		if (actorId == null || !mentorship.getMentor().getId().equals(actorId)) {
			throw new InvalidRequestException("Only the assigned mentor can modify this mentorship");
		}
	}

	static LocalDate mondayOf(LocalDate date) {
		LocalDate value = date == null ? LocalDate.now() : date;
		return value.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}
}
