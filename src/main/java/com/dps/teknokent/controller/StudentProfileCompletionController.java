package com.dps.teknokent.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.SaveStudentProfileCompletionRequest;
import com.dps.teknokent.dto.responses.StudentProfileCompletionResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.StudentProfileCompletionService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** The ODTÜ student's interests and goal: the profile completion steps and Profilim › İlgi alanları. */
@RestController
@RequestMapping("/api/student/profile-completion")
@RequiredArgsConstructor
@Tag(name = "Student workspace")
public class StudentProfileCompletionController {

	private final StudentProfileCompletionService studentProfileCompletionService;

	@GetMapping
	public StudentProfileCompletionResponse get(@RequestParam(required = false) UUID actorId) {
		return studentProfileCompletionService.get(CurrentActor.resolve(actorId));
	}

	@PutMapping
	public StudentProfileCompletionResponse save(@Valid @RequestBody SaveStudentProfileCompletionRequest request) {
		return studentProfileCompletionService.save(CurrentActor.resolve(request.actorId()), request);
	}
}
