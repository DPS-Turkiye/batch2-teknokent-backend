package com.dps.teknokent.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.security.FirebaseIdentity;
import com.dps.teknokent.service.AuthEmailService;

@WebMvcTest(AuthEmailController.class)
@Import(SecurityConfig.class)
class AuthEmailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthEmailService authEmailService;

    @Test
    void mailsTheVerificationLinkToTheSignedInFirebaseAddress() throws Exception {
        mockMvc.perform(post("/api/auth/register/verification-email")
                        .with(authentication(firebaseLogin("mentor@bilkent.edu.tr"))))
                .andExpect(status().isNoContent());

        verify(authEmailService).sendVerification("mentor@bilkent.edu.tr");
    }

    @Test
    void sendsNoVerificationMailWithoutAFirebaseSignIn() throws Exception {
        mockMvc.perform(post("/api/auth/register/verification-email").with(user("proxy")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(authEmailService);
    }

    @Test
    void takesAPasswordResetOnlyThroughTheProxyLogin() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"mentor@metu.edu.tr\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/password-reset")
                        .with(user("proxy"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"mentor@metu.edu.tr\"}"))
                .andExpect(status().isNoContent());

        verify(authEmailService).sendPasswordReset("mentor@metu.edu.tr");
    }

    @Test
    void refusesAResetForSomethingThatIsNotAnAddress() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset")
                        .with(user("proxy"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"not-an-address\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authEmailService);
    }

    private static UsernamePasswordAuthenticationToken firebaseLogin(String email) {
        return new UsernamePasswordAuthenticationToken(
                new FirebaseIdentity("uid-1", email, true), null, List.of(new SimpleGrantedAuthority("ROLE_REGISTERING")));
    }
}
