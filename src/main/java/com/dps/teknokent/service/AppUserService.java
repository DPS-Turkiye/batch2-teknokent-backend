package com.dps.teknokent.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AppUserService {

	private final AppUserRepository appUserRepository;

	@Transactional(readOnly = true)
	public List<UserResponse> findAll(UserRole role) {
		return appUserRepository.findAll().stream()
				.filter(user -> role == null || user.getRole() == role)
				.sorted(Comparator.comparing(user -> user.getFullName().toLowerCase()))
				.map(this::toResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public UserResponse findById(UUID id) {
		return appUserRepository.findById(id).map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
	}

	private UserResponse toResponse(com.dps.teknokent.entity.AppUser user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
				user.getOrganization() == null ? null : user.getOrganization().getId(),
				user.getOrganization() == null ? null : user.getOrganization().getName(),
				user.getJobTitle());
	}
}
