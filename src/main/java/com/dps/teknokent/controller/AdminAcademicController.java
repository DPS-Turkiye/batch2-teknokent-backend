package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.AdminAcademicResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.AdminAcademicService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/academics")
@RequiredArgsConstructor
@Tag(name = "Admin academics")
public class AdminAcademicController {

	private final AdminAcademicService adminAcademicService;

	@GetMapping
	public List<AdminAcademicResponse> list(@RequestParam(required = false) UUID actorId) {
		return adminAcademicService.list(CurrentActor.resolve(actorId));
	}
}
