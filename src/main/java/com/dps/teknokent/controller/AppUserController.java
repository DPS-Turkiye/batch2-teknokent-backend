package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.service.AppUserService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users")
public class AppUserController {

	private final AppUserService appUserService;

	@GetMapping
	public List<UserResponse> findAll(@RequestParam(required = false) UserRole role) {
		return appUserService.findAll(role);
	}

	@GetMapping("/{id}")
	public UserResponse findById(@PathVariable UUID id) {
		return appUserService.findById(id);
	}
}
