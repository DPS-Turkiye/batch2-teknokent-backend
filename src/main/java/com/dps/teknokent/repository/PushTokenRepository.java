package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.entity.PushToken;

public interface PushTokenRepository extends JpaRepository<PushToken, UUID> {
	List<PushToken> findByUserId(UUID userId);
	Optional<PushToken> findByToken(String token);

	@Transactional
	@Modifying
	@Query("delete from PushToken p where p.token = :token and p.user.id = :userId")
	int deleteByTokenAndUserId(@Param("token") String token, @Param("userId") UUID userId);

	@Transactional
	@Modifying
	@Query("delete from PushToken p where p.token in :tokens")
	int deleteByTokenIn(@Param("tokens") Collection<String> tokens);
}
