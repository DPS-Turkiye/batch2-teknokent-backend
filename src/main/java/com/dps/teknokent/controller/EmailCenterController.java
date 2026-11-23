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

import com.dps.teknokent.dto.requests.ActorRequest;
import com.dps.teknokent.dto.requests.EmailCampaignRequest;
import com.dps.teknokent.dto.requests.SendEmailCampaignRequest;
import com.dps.teknokent.dto.requests.UnsubscribeEmailRequest;
import com.dps.teknokent.dto.responses.EmailCampaignResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.EmailCampaignService;
import com.dps.teknokent.service.EmailSuppressionService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/email-center")
@RequiredArgsConstructor
@Tag(name = "Email center")
public class EmailCenterController {

	private final EmailCampaignService campaignService;
	private final EmailSuppressionService suppressionService;

	@GetMapping("/options")
	public EmailCampaignResponse.Options options(@RequestParam(required = false) UUID actorId) {
		return campaignService.options(CurrentActor.resolve(actorId));
	}

	@GetMapping("/templates")
	public List<EmailCampaignResponse.Template> templates(
			@RequestParam UUID programId,
			@RequestParam(required = false) UUID actorId) {
		return campaignService.templates(CurrentActor.resolve(actorId), programId);
	}

	@GetMapping("/campaigns")
	public List<EmailCampaignResponse> list(@RequestParam(required = false) UUID actorId) {
		return campaignService.list(CurrentActor.resolve(actorId));
	}

	@GetMapping("/campaigns/{id}")
	public EmailCampaignResponse.Detail get(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		return campaignService.get(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/campaigns")
	@ResponseStatus(HttpStatus.CREATED)
	public EmailCampaignResponse.Detail create(@Valid @RequestBody EmailCampaignRequest request) {
		return campaignService.create(CurrentActor.resolve(request.actorId()), request);
	}

	@PutMapping("/campaigns/{id}")
	public EmailCampaignResponse.Detail update(
			@PathVariable UUID id,
			@Valid @RequestBody EmailCampaignRequest request) {
		return campaignService.update(CurrentActor.resolve(request.actorId()), id, request);
	}

	@DeleteMapping("/campaigns/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		campaignService.delete(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/campaigns/{id}/test")
	public EmailCampaignResponse.Detail sendTest(@PathVariable UUID id, @Valid @RequestBody ActorRequest request) {
		return campaignService.sendTest(CurrentActor.resolve(request.actorId()), id);
	}

	@PostMapping("/campaigns/{id}/review")
	public EmailCampaignResponse.Detail review(@PathVariable UUID id, @Valid @RequestBody ActorRequest request) {
		return campaignService.review(CurrentActor.resolve(request.actorId()), id);
	}

	@PostMapping("/campaigns/{id}/send")
	public EmailCampaignResponse.Detail send(
			@PathVariable UUID id,
			@Valid @RequestBody SendEmailCampaignRequest request) {
		return campaignService.send(CurrentActor.resolve(request.actorId()), id, request);
	}

	@PostMapping("/campaigns/{id}/stop")
	public EmailCampaignResponse.Detail stop(@PathVariable UUID id, @Valid @RequestBody ActorRequest request) {
		return campaignService.stop(CurrentActor.resolve(request.actorId()), id);
	}

	/** From the link in a mail, without an account: the token is the proof. Answers the same for any token. */
	@PostMapping("/unsubscribe")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unsubscribe(@Valid @RequestBody UnsubscribeEmailRequest request) {
		suppressionService.unsubscribe(request.token());
	}
}
