package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeStatus;
import com.dps.teknokent.entity.UserBadge;

public interface UserBadgeRepository extends JpaRepository<UserBadge, UUID> {

	List<UserBadge> findByUserIdOrderByAwardedAtDesc(UUID userId);

	List<UserBadge> findByUserIdAndStatusOrderByAwardedAtDesc(UUID userId, BadgeStatus status);

	Optional<UserBadge> findFirstByUserIdAndBadgeCodeAndProgramIdAndStatus(UUID userId, BadgeCode badgeCode,
			UUID programId, BadgeStatus status);

	List<UserBadge> findByProgramIdAndBadgeCodeOrderByAwardedAtDesc(UUID programId, BadgeCode badgeCode);

	List<UserBadge> findByBadgeCodeAndUserIdInOrderByAwardedAtDesc(BadgeCode badgeCode, Collection<UUID> userIds);

	List<UserBadge> findByBadgeCodeOrderByAwardedAtDesc(BadgeCode badgeCode);

	/** Any award of the badge, active or not: a rule never awards one again after a revoke. */
	boolean existsByUserIdAndBadgeCode(UUID userId, BadgeCode badgeCode);
}
