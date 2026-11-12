package com.dps.teknokent.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.PeerMentorDraft;

public interface PeerMentorDraftRepository extends JpaRepository<PeerMentorDraft, UUID> {
}
