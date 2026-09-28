package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.AddProgramCoordinatorRequest;
import com.dps.teknokent.dto.requests.AddProgramTeamsRequest;
import com.dps.teknokent.dto.requests.CreateCoordinatorProgramRequest;
import com.dps.teknokent.dto.requests.CreateProgramMatchRequest;
import com.dps.teknokent.dto.requests.CreateProgramEventRequest;
import com.dps.teknokent.dto.requests.DecideProgramMentorRequest;
import com.dps.teknokent.dto.requests.InviteProgramMentorRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest;
import com.dps.teknokent.dto.requests.SetProgramArchivedRequest;
import com.dps.teknokent.dto.requests.TeamInviteInput;
import com.dps.teknokent.dto.requests.UpdateProgramDetailsRequest;
import com.dps.teknokent.dto.responses.CoordinatorProgramResponse;
import com.dps.teknokent.dto.responses.MentorPoolResponse;
import com.dps.teknokent.dto.responses.ProgramMatchingResponse;
import com.dps.teknokent.dto.responses.ProgramEventResponse;
import com.dps.teknokent.dto.responses.ProgramTeamDetailResponse;
import com.dps.teknokent.dto.responses.ProgramTeamResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.CoordinatorProgramService;
import com.dps.teknokent.service.MentorPoolService;
import com.dps.teknokent.service.ProgramCoordinatorService;
import com.dps.teknokent.service.ProgramLaunchService;
import com.dps.teknokent.service.ProgramMatchingService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coordinator/programs")
@RequiredArgsConstructor
@Tag(name = "Coordinator programs")
public class CoordinatorProgramController {

	private final CoordinatorProgramService coordinatorProgramService;
	private final ProgramCoordinatorService programCoordinatorService;
	private final ProgramLaunchService programLaunchService;
	private final MentorPoolService mentorPoolService;
	private final ProgramMatchingService programMatchingService;

	@GetMapping
	public List<CoordinatorProgramResponse> list(@RequestParam(required = false) UUID actorId) {
		return coordinatorProgramService.list(CurrentActor.resolve(actorId));
	}

	@GetMapping("/{id}")
	public CoordinatorProgramResponse.Detail detail(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return coordinatorProgramService.detail(CurrentActor.resolve(actorId), id);
	}

	@PutMapping("/{id}")
	public CoordinatorProgramResponse.Detail updateDetails(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateProgramDetailsRequest request) {
		return coordinatorProgramService.updateDetails(CurrentActor.resolve(request.actorId()), id, request);
	}

	@PutMapping("/{id}/phases")
	public CoordinatorProgramResponse.Detail replacePhases(
			@PathVariable UUID id,
			@Valid @RequestBody ReplaceProgramPhasesRequest request) {
		return coordinatorProgramService.replacePhases(CurrentActor.resolve(request.actorId()), id, request);
	}

	@GetMapping("/{id}/events")
	public List<ProgramEventResponse> listEvents(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return coordinatorProgramService.listEvents(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/{id}/events")
	@ResponseStatus(HttpStatus.CREATED)
	public ProgramEventResponse createEvent(
			@PathVariable UUID id,
			@Valid @RequestBody CreateProgramEventRequest request) {
		return coordinatorProgramService.createEvent(CurrentActor.resolve(request.actorId()), id, request);
	}

	@DeleteMapping("/{id}/events/{eventId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteEvent(
			@PathVariable UUID id,
			@PathVariable UUID eventId,
			@RequestParam(required = false) UUID actorId) {
		coordinatorProgramService.deleteEvent(CurrentActor.resolve(actorId), id, eventId);
	}

	@PutMapping("/{id}/archive")
	public CoordinatorProgramResponse.Detail setArchived(
			@PathVariable UUID id,
			@Valid @RequestBody SetProgramArchivedRequest request) {
		return coordinatorProgramService.setArchived(CurrentActor.resolve(request.actorId()), id, request.archived());
	}

	@GetMapping("/{id}/coordinators")
	public List<CoordinatorProgramResponse.CoordinatorRef> coordinators(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return programCoordinatorService.list(CurrentActor.resolve(actorId), id);
	}

	@GetMapping("/{id}/coordinators/candidates")
	public List<CoordinatorProgramResponse.CoordinatorRef> coordinatorCandidates(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return programCoordinatorService.candidates(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/{id}/coordinators")
	public List<CoordinatorProgramResponse.CoordinatorRef> addCoordinator(
			@PathVariable UUID id,
			@Valid @RequestBody AddProgramCoordinatorRequest request) {
		return programCoordinatorService.add(CurrentActor.resolve(request.actorId()), id, request.userId());
	}

	@DeleteMapping("/{id}/coordinators/{userId}")
	public List<CoordinatorProgramResponse.CoordinatorRef> removeCoordinator(
			@PathVariable UUID id,
			@PathVariable UUID userId,
			@RequestParam(required = false) UUID actorId) {
		return programCoordinatorService.remove(CurrentActor.resolve(actorId), id, userId);
	}

	/** The "Yeni program" wizard: basic info, rules and teams with their leads, in one request. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CoordinatorProgramResponse.Detail create(@Valid @RequestBody CreateCoordinatorProgramRequest request) {
		return programLaunchService.create(CurrentActor.resolve(request.actorId()), request);
	}

	@GetMapping("/{id}/teams")
	public List<ProgramTeamResponse> teams(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return programLaunchService.teams(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/{id}/teams")
	public List<ProgramTeamResponse> addTeams(
			@PathVariable UUID id,
			@Valid @RequestBody AddProgramTeamsRequest request) {
		return programLaunchService.addTeams(CurrentActor.resolve(request.actorId()), id, request.teams());
	}

	@PutMapping("/{id}/teams/{inviteId}")
	public List<ProgramTeamResponse> updateTeam(
			@PathVariable UUID id,
			@PathVariable UUID inviteId,
			@Valid @RequestBody TeamInviteInput input,
			@RequestParam(required = false) UUID actorId) {
		return programLaunchService.updateTeam(CurrentActor.resolve(actorId), id, inviteId, input);
	}

	@DeleteMapping("/{id}/teams/{inviteId}")
	public List<ProgramTeamResponse> removeTeam(
			@PathVariable UUID id,
			@PathVariable UUID inviteId,
			@RequestParam(required = false) UUID actorId) {
		return programLaunchService.removeTeam(CurrentActor.resolve(actorId), id, inviteId);
	}

	@PostMapping("/{id}/teams/{inviteId}/send-code")
	public List<ProgramTeamResponse> sendTeamCode(
			@PathVariable UUID id,
			@PathVariable UUID inviteId,
			@RequestParam(required = false) UUID actorId) {
		return programLaunchService.sendCode(CurrentActor.resolve(actorId), id, inviteId);
	}

	@GetMapping("/{id}/team-details/{organizationId}")
	public ProgramTeamDetailResponse teamDetail(
			@PathVariable UUID id,
			@PathVariable UUID organizationId,
			@RequestParam(required = false) UUID actorId) {
		return programLaunchService.teamDetail(CurrentActor.resolve(actorId), id, organizationId);
	}

	@GetMapping("/{id}/mentors")
	public List<MentorPoolResponse.Row> mentorPool(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return mentorPoolService.pool(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/{id}/mentors")
	public List<MentorPoolResponse.Row> inviteMentor(
			@PathVariable UUID id,
			@Valid @RequestBody InviteProgramMentorRequest request) {
		return mentorPoolService.invite(CurrentActor.resolve(request.actorId()), id, request.email());
	}

	@DeleteMapping("/{id}/mentors/{mentorId}")
	public List<MentorPoolResponse.Row> withdrawMentorInvite(
			@PathVariable UUID id,
			@PathVariable UUID mentorId,
			@RequestParam(required = false) UUID actorId) {
		return mentorPoolService.withdraw(CurrentActor.resolve(actorId), id, mentorId);
	}

	@GetMapping("/{id}/mentors/{mentorId}/review")
	public MentorPoolResponse.Review reviewMentor(
			@PathVariable UUID id,
			@PathVariable UUID mentorId,
			@RequestParam(required = false) UUID actorId) {
		return mentorPoolService.review(CurrentActor.resolve(actorId), id, mentorId);
	}

	@PostMapping("/{id}/mentors/{mentorId}/decision")
	public List<MentorPoolResponse.Row> decideMentor(
			@PathVariable UUID id,
			@PathVariable UUID mentorId,
			@Valid @RequestBody DecideProgramMentorRequest request) {
		return mentorPoolService.decide(CurrentActor.resolve(request.actorId()), id, mentorId, request.accepted());
	}

	@PostMapping("/{id}/mentor-invites/{inviteId}/renew")
	public List<MentorPoolResponse.Row> renewMentorInvite(
			@PathVariable UUID id,
			@PathVariable UUID inviteId,
			@RequestParam(required = false) UUID actorId) {
		return mentorPoolService.renewInvite(CurrentActor.resolve(actorId), id, inviteId);
	}

	@DeleteMapping("/{id}/mentor-invites/{inviteId}")
	public List<MentorPoolResponse.Row> cancelMentorInvite(
			@PathVariable UUID id,
			@PathVariable UUID inviteId,
			@RequestParam(required = false) UUID actorId) {
		return mentorPoolService.cancelInvite(CurrentActor.resolve(actorId), id, inviteId);
	}

	@GetMapping("/{id}/matching")
	public ProgramMatchingResponse.Overview matching(
			@PathVariable UUID id,
			@RequestParam(required = false) UUID actorId) {
		return programMatchingService.overview(CurrentActor.resolve(actorId), id);
	}

	@GetMapping("/{id}/matching/{organizationId}/suggestions")
	public ProgramMatchingResponse.Suggestions matchSuggestions(
			@PathVariable UUID id,
			@PathVariable UUID organizationId,
			@RequestParam(required = false) UUID actorId) {
		return programMatchingService.suggestions(CurrentActor.resolve(actorId), id, organizationId);
	}

	@PostMapping("/{id}/matching")
	public ProgramMatchingResponse.Overview match(
			@PathVariable UUID id,
			@Valid @RequestBody CreateProgramMatchRequest request) {
		return programMatchingService.match(
				CurrentActor.resolve(request.actorId()), id, request.organizationId(), request.mentorId());
	}
}
