package com.dps.teknokent.service;

import java.util.UUID;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.dps.teknokent.domain.ProfileAnalysisRecordScope;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.ProfileAnalysisResponse;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProfileAnalysisRecordRepository;
import com.dps.teknokent.util.JsonUtils;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProfileAnalysisRecordService {
    private final DomainLookupService lookup;
    private final ProfileAnalysisRecordRepository records;
    private final ProfileAnalysisService analysis;
    private final JsonUtils json;

    @Transactional(readOnly = true)
    public ProfileAnalysisResponse latest(UUID actorId, ProfileAnalysisRecordScope scope) {
        AppUser actor = requireMember(actorId);
        // The proxy names the caller; neither organization nor another person's id is an input.
        var record = scope.personal()
                ? records.findFirstByOrganizationIdAndAppUserIdAndProfileTypeOrderByCreatedAtDescIdDesc(
                        actor.getOrganization().getId(), actorId, scope.profileType())
                : records.findFirstByOrganizationIdAndAppUserIdIsNullAndProfileTypeOrderByCreatedAtDescIdDesc(
                        actor.getOrganization().getId(), scope.profileType());
        return record
                .map(ProfileAnalysisResponse::fromEntity)
                .orElseThrow(() -> new ResourceNotFoundException("No saved analysis was found"));
    }

    @Transactional
    public ProfileAnalysisResponse createStartup(UUID actorId, Map<String, Object> profile) {
        AppUser actor = requireMember(actorId);
        return analysis.createManual(actor.getOrganization().getId(), null,
                new CreateManualProfileRequest(ProfileAnalysisRecordScope.STARTUP.profileType(), json.valueToTree(profile)));
    }

    @Transactional
    public ProfileAnalysisResponse confirmStartup(UUID actorId, UUID id, Map<String, Object> profile) {
        AppUser actor = requireMember(actorId);
        var record = records.findById(id).orElseThrow(() -> new ResourceNotFoundException("No saved analysis was found"));
        if (!record.getOrganizationId().equals(actor.getOrganization().getId())
                || record.getAppUserId() != null
                || !record.getProfileType().equals(ProfileAnalysisRecordScope.STARTUP.profileType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only this startup's matching profile can be confirmed");
        }
        return analysis.confirm(id, json.writeValue(Map.of("profile", profile)));
    }

    private AppUser requireMember(UUID actorId) {
        AppUser actor = lookup.requireUser(actorId);
        if (!actor.has(UserRole.MENTEE) || actor.getStatus() != UserAccountStatus.APPROVED
                || actor.getOrganization() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An approved startup member is required");
        }
        return actor;
    }
}
