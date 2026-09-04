package com.dps.teknokent.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.UpsertOnboardingDraftRequest;
import com.dps.teknokent.dto.responses.OnboardingDraftResponse;
import com.dps.teknokent.entity.OnboardingDraft;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.OnboardingDraftRepository;
import com.dps.teknokent.util.JsonUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OnboardingDraftService {

	static final Set<String> KINDS = Set.of("signup-wizard", "mentor-onboarding");
	static final int MAX_PAYLOAD_CHARS = 100_000;

	private final OnboardingDraftRepository onboardingDraftRepository;
	private final JsonUtils jsonUtils;

	@Transactional(readOnly = true)
	public Optional<OnboardingDraftResponse> find(String firebaseUid) {
		return onboardingDraftRepository.findById(requireUid(firebaseUid)).map(this::toResponse);
	}

	@Transactional
	public OnboardingDraftResponse upsert(String firebaseUid, UpsertOnboardingDraftRequest request) {
		String uid = requireUid(firebaseUid);
		String kind = requireKind(request.kind());
		Map<String, Object> payload = requirePayload(request.payload());
		String encoded = jsonUtils.writeValue(payload);
		if (encoded.length() > MAX_PAYLOAD_CHARS) {
			throw new InvalidRequestException("The onboarding draft is too large to store.");
		}

		OnboardingDraft draft = onboardingDraftRepository.findById(uid)
				.orElseGet(() -> OnboardingDraft.forUser(uid));
		draft.setKind(kind);
		draft.setStep(request.step().trim());
		draft.setPayload(encoded);
		return toResponse(onboardingDraftRepository.saveAndFlush(draft));
	}

	@Transactional
	public void delete(String firebaseUid) {
		onboardingDraftRepository.deleteById(requireUid(firebaseUid));
	}

	private OnboardingDraftResponse toResponse(OnboardingDraft draft) {
		return new OnboardingDraftResponse(
				draft.getKind(),
				draft.getStep(),
				jsonUtils.toMap(jsonUtils.readTree(draft.getPayload())),
				draft.getUpdatedAt());
	}

	private static String requireUid(String firebaseUid) {
		if (firebaseUid == null || firebaseUid.isBlank()) {
			throw new InvalidRequestException("A Firebase session is required.");
		}
		return firebaseUid;
	}

	private static String requireKind(String kind) {
		String trimmed = kind == null ? "" : kind.trim();
		if (!KINDS.contains(trimmed)) {
			throw new InvalidRequestException("kind must be signup-wizard or mentor-onboarding.");
		}
		return trimmed;
	}

	private static Map<String, Object> requirePayload(Map<String, Object> payload) {
		if (payload == null) {
			throw new InvalidRequestException("payload must be a JSON object.");
		}
		return payload;
	}
}
