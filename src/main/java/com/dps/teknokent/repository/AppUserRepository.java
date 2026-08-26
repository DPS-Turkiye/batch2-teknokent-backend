package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
	List<AppUser> findByOrganizationIdOrderByFullNameAsc(UUID organizationId);

	boolean existsByEmailIgnoreCase(String email);
}
