package com.dps.teknokent.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.domain.ProfileAnalysisRecordScope;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.ProfileAnalysisRecordService;

@WebMvcTest(ProfileAnalysisRecordController.class)
@Import(SecurityConfig.class)
class ProfileAnalysisRecordControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ProfileAnalysisRecordService records;
    private static final String URL = "/api/profile-analysis-records";

    @Test
    void authenticationAndAValidScopeAndProxyActorAreRequired() throws Exception {
        mvc.perform(get(URL).param("scope", "STARTUP")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).param("scope", "STARTUP").with(user("service"))).andExpect(status().isBadRequest());
        mvc.perform(get(URL).param("scope", "other").param("actorId", UUID.randomUUID().toString()).with(user("service"))).andExpect(status().isBadRequest());
        verifyNoInteractions(records);
    }

    @Test
    void proxyActorIsForwardedWithoutAcceptingOrganizationOrSubjectIds() throws Exception {
        var actor = UUID.randomUUID();
        mvc.perform(get(URL).param("scope", "PERSONAL").param("actorId", actor.toString())
                .param("organizationId", UUID.randomUUID().toString()).param("appUserId", UUID.randomUUID().toString()).with(user("service"))).andExpect(status().isOk());
        verify(records).latest(actor, ProfileAnalysisRecordScope.PERSONAL);
    }

    @Test
    void verifiedPrincipalWinsOverASpoofedProxyActor() throws Exception {
        var actor = UUID.randomUUID();
        var principal = new AppUserPrincipal(actor, "record@task.test", UserRole.MENTEE, UserAccountStatus.APPROVED, "record-user");
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(new SimpleGrantedAuthority("ROLE_MENTEE")));
        mvc.perform(get(URL).param("scope", "TEAM_PERSONAL").param("actorId", UUID.randomUUID().toString()).with(authentication(auth))).andExpect(status().isOk());
        verify(records).latest(actor, ProfileAnalysisRecordScope.TEAM_PERSONAL);
    }

    @Test
    void manualPayloadIsDecodedThroughTheRealMvcConverterAndNullProfilesAreRejected() throws Exception {
        var actor = UUID.randomUUID();
        mvc.perform(post(URL).param("actorId", actor.toString()).with(user("service"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"profile\":{\"startup_name\":\"Manual Startup\"}}"))
                .andExpect(status().isCreated());
        verify(records).createStartup(actor, Map.of("startup_name", "Manual Startup"));
        var id = UUID.randomUUID();
        mvc.perform(patch(URL + "/" + id).param("actorId", actor.toString()).with(user("service"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"profile\":{\"startup_name\":\"Reviewed Startup\"}}"))
                .andExpect(status().isOk());
        verify(records).confirmStartup(actor, id, Map.of("startup_name", "Reviewed Startup"));
        mvc.perform(post(URL).param("actorId", actor.toString()).with(user("service"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"profile\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"profile\":{}}"))
                .andExpect(status().isUnauthorized());
    }
}
