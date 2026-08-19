package com.dps.teknokent.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
