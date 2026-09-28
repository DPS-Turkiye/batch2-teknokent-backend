package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.entity.AssistantSession;

public interface AssistantSessionRepository extends JpaRepository<AssistantSession, UUID> {
  @Modifying
  @Query(value = "INSERT INTO assistant_session_lock(lock_key) VALUES (concat(:platform, ':', :nativeMeetingId)) ON CONFLICT DO NOTHING", nativeQuery = true)
  void ensureProviderMeetingLock(@Param("platform") String platform, @Param("nativeMeetingId") String nativeMeetingId);

  @Query(value = "SELECT lock_key FROM assistant_session_lock WHERE lock_key = concat(:platform, ':', :nativeMeetingId) FOR UPDATE", nativeQuery = true)
  String lockProviderMeetingRow(@Param("platform") String platform, @Param("nativeMeetingId") String nativeMeetingId);

  default void lockProviderMeeting(String platform, String nativeMeetingId) {
    ensureProviderMeetingLock(platform, nativeMeetingId);
    lockProviderMeetingRow(platform, nativeMeetingId);
  }
  Optional<AssistantSession> findByIdAndOwnerId(UUID id, UUID ownerId);
  Optional<AssistantSession> findByIdAndMeetingId(UUID id, UUID meetingId);
  Optional<AssistantSession> findByPlatformAndNativeMeetingId(String platform, String nativeMeetingId);
  Optional<AssistantSession> findByMeetingId(UUID meetingId);
  long countByOwnerIdAndCreatedAtAfter(UUID ownerId, Instant after);
}
