package com.dps.teknokent.controller;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import com.dps.teknokent.dto.requests.CreateStartupAnalysisRecordRequest;
import com.dps.teknokent.domain.ProfileAnalysisRecordScope;
import com.dps.teknokent.dto.responses.ProfileAnalysisResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.ProfileAnalysisRecordService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/profile-analysis-records")
@RequiredArgsConstructor
public class ProfileAnalysisRecordController {
    private final ProfileAnalysisRecordService records;

    @GetMapping
    public ProfileAnalysisResponse latest(@RequestParam ProfileAnalysisRecordScope scope,
            @RequestParam(required = false) UUID actorId) {
        return records.latest(CurrentActor.resolve(actorId), scope);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileAnalysisResponse create(@RequestParam(required = false) UUID actorId,
            @Valid @RequestBody CreateStartupAnalysisRecordRequest input) {
        return records.createStartup(CurrentActor.resolve(actorId), input.profile());
    }

    @PatchMapping("/{id}")
    public ProfileAnalysisResponse confirm(@PathVariable UUID id, @RequestParam(required = false) UUID actorId,
            @Valid @RequestBody CreateStartupAnalysisRecordRequest input) {
        return records.confirmStartup(CurrentActor.resolve(actorId), id, input.profile());
    }
}
