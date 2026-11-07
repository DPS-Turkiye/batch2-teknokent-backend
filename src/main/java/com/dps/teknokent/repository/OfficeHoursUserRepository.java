package com.dps.teknokent.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.OfficeHoursUser;

public interface OfficeHoursUserRepository extends JpaRepository<OfficeHoursUser, UUID> {

	Optional<OfficeHoursUser> findByIssuerAndSubject(String issuer, String subject);
}
