package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.InstitutionPersonSummaryResponse;
import com.dps.teknokent.dto.responses.InstitutionRecordResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.InstitutionDirectoryService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** The caller's own institutional record and, for academicians, their advisees. Read-only: Menton never edits these. */
@RestController
@RequestMapping("/api/institution")
@RequiredArgsConstructor
@Tag(name = "Institution")
public class InstitutionController {

	private final InstitutionDirectoryService institutionDirectoryService;

	/** A 404 when no institution vouches for the caller (e-mail sign-ups, startups). */
	@GetMapping("/me")
	public InstitutionRecordResponse me(@RequestParam(required = false) UUID actorId) {
		return institutionDirectoryService.recordOf(CurrentActor.resolve(actorId));
	}

	/** Empty unless the caller is an academician with advisees on record. */
	@GetMapping("/me/advisees")
	public List<InstitutionPersonSummaryResponse> advisees(@RequestParam(required = false) UUID actorId) {
		return institutionDirectoryService.adviseesOf(CurrentActor.resolve(actorId));
	}
}
