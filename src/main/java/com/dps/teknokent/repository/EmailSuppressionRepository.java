package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.EmailSuppression;

public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, UUID> {

	boolean existsByAddressHash(String addressHash);

	Optional<EmailSuppression> findByAddressHash(String addressHash);

	List<EmailSuppression> findByAddressHashIn(Collection<String> addressHashes);
}
