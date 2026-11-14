package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.AppUserIdentity;

public interface AppUserIdentityRepository extends JpaRepository<AppUserIdentity, UUID> {

	Optional<AppUserIdentity> findByProviderAndPersistentId(String provider, String persistentId);

	boolean existsByUserIdAndProvider(UUID userId, String provider);

	boolean existsByUserIdAndProviderNot(UUID userId, String provider);

	List<AppUserIdentity> findByUserIdIn(Collection<UUID> userIds);
}
