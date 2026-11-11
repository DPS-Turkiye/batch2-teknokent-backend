package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.ReplaceProgramPeerCategoriesRequest;
import com.dps.teknokent.dto.responses.ProgramPeerCategoriesResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.ProgramPeerCategoryService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coordinator/programs/{programId}/peer-categories")
@RequiredArgsConstructor
@Tag(name = "Peer-program categories")
public class ProgramPeerCategoryController {

	private final ProgramPeerCategoryService programPeerCategoryService;

	@GetMapping
	public ProgramPeerCategoriesResponse get(@PathVariable UUID programId,
			@RequestParam(required = false) UUID actorId) {
		return programPeerCategoryService.get(CurrentActor.resolve(actorId), programId);
	}

	@PutMapping
	public ProgramPeerCategoriesResponse replace(@PathVariable UUID programId,
			@Valid @RequestBody ReplaceProgramPeerCategoriesRequest request) {
		return programPeerCategoryService.replace(CurrentActor.resolve(request.actorId()), programId,
				request.categories());
	}
}
