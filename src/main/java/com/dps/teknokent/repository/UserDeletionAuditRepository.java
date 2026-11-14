package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.UserDeletionAudit;

public interface UserDeletionAuditRepository extends JpaRepository<UserDeletionAudit, UUID> {

	List<UserDeletionAudit> findByDeletedUserId(UUID deletedUserId);
}
