package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.AdminAcademicResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.AdminAcademicService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "Office Hours academicians")
public class ProgramAcademicController {

	private final AdminAcademicService adminAcademicService;

	@GetMapping("/api/coordinator/programs/{programId}/academics")
	public List<AdminAcademicResponse> forProgram(@PathVariable UUID programId,
			@RequestParam(required = false) UUID actorId) {
		return adminAcademicService.forProgram(CurrentActor.resolve(actorId), programId);
	}
}
