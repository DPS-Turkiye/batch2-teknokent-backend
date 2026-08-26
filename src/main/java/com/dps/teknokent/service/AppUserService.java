package com.dps.teknokent.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.CreateOrganizationMemberRequest;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AppUserService {

	private final AppUserRepository appUserRepository;
	private final DomainLookupService domainLookupService;

	@Transactional(readOnly = true)
	public List<UserResponse> findAll(UserRole role) {
		return appUserRepository.findAll().stream()
				.filter(user -> role == null || user.getRole() == role)
				.sorted(Comparator.comparing(user -> user.getFullName().toLowerCase()))
				.map(UserResponse::fromEntity)
				.toList();
	}

	@Transactional(readOnly = true)
	public UserResponse findById(UUID id) {
		return appUserRepository.findById(id).map(UserResponse::fromEntity)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
	}

	@Transactional(readOnly = true)
	public List<UserResponse> findByOrganization(UUID organizationId) {
		domainLookupService.requireOrganization(organizationId);
		return appUserRepository.findByOrganizationIdOrderByFullNameAsc(organizationId).stream()
				.map(UserResponse::fromEntity)
				.toList();
	}

	@Transactional
	public UserResponse addOrganizationMember(UUID organizationId, CreateOrganizationMemberRequest request) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		String email = TextUtils.trim(request.email());
		if (appUserRepository.existsByEmailIgnoreCase(email)) {
			throw new InvalidRequestException("A user with this email already exists.");
		}

		AppUser member = new AppUser();
		member.setFullName(TextUtils.trim(request.fullName()));
		member.setEmail(email);
		member.setRole(UserRole.MENTEE);
		member.setOrganization(organization);
		member.setJobTitle(TextUtils.normalizeOptional(request.jobTitle()));
		return UserResponse.fromEntity(appUserRepository.save(member));
	}
}
