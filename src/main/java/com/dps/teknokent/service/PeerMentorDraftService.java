package com.dps.teknokent.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.PeerMentorProfileDraft;
import com.dps.teknokent.dto.responses.PeerMentorProfileDraftResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.PeerMentorDraft;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.PeerMentorDraftRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Taslak kaydet" for the peer-mentor wizard: one unfinished copy per student, readable and writable only by them,
 * so the wizard survives a refresh or another device. Publishing the profile removes it (PeerMentoringService).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeerMentorDraftService {

	private final PeerMentoringService peerMentoringService;
	private final PeerMentorDraftRepository drafts;
	private final JsonMapper jsonMapper;

	/** 404 when there is none, including one saved in a shape this version can no longer read. */
	@Transactional(readOnly = true)
	public PeerMentorProfileDraftResponse find(UUID actorId) {
		AppUser mentor = peerMentoringService.requirePeerMentor(actorId);
		return drafts.findById(mentor.getId())
				.flatMap(this::read)
				.orElseThrow(() -> new ResourceNotFoundException("No peer-mentor draft for " + mentor.getId()));
	}

	@Transactional
	public PeerMentorProfileDraftResponse save(UUID actorId, PeerMentorProfileDraft draft) {
		AppUser mentor = peerMentoringService.requirePeerMentor(actorId);
		PeerMentorDraft stored = drafts.findById(mentor.getId()).orElseGet(() -> new PeerMentorDraft(mentor.getId()));
		stored.setPayload(jsonMapper.writeValueAsString(draft));
		PeerMentorDraft saved = drafts.saveAndFlush(stored);
		return new PeerMentorProfileDraftResponse(draft, saved.getUpdatedAt());
	}

	@Transactional
	public void delete(UUID actorId) {
		drafts.deleteById(peerMentoringService.requirePeerMentor(actorId).getId());
	}

	private Optional<PeerMentorProfileDraftResponse> read(PeerMentorDraft stored) {
		try {
			return Optional.of(new PeerMentorProfileDraftResponse(
					jsonMapper.readValue(stored.getPayload(), PeerMentorProfileDraft.class), stored.getUpdatedAt()));
		} catch (JacksonException exception) {
			// Only the id: the payload is the student's own text.
			log.warn("Unreadable peer-mentor draft for user {}: {}", stored.getUserId(), exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}
}
