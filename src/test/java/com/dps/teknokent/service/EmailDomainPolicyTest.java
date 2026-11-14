package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.config.RegistrationProperties;
import com.dps.teknokent.exception.InvalidRequestException;

class EmailDomainPolicyTest {

    private static EmailDomainPolicy policy(String... domains) {
        return new EmailDomainPolicy(new RegistrationProperties(List.of(domains)));
    }

    @Test
    void withNoDomainsConfigured_anyAddressMaySignUp() {
        EmailDomainPolicy open = new EmailDomainPolicy(new RegistrationProperties(null));

        assertThat(open.allows("ayse@gmail.com")).isTrue();
        assertThat(policy(" ", "").allows("ayse@startup.com")).isTrue();
    }

    @Test
    void acceptsTheDomainAndItsSubdomainsIgnoringCase() {
        EmailDomainPolicy metu = policy("metu.edu.tr");

        assertThat(metu.allows("e123456@metu.edu.tr")).isTrue();
        assertThat(metu.allows("Ali.Veli@METU.edu.tr ")).isTrue();
        assertThat(metu.allows("ali@ceng.metu.edu.tr")).isTrue();
    }

    @Test
    void rejectsLookalikeAndOtherDomains() {
        EmailDomainPolicy metu = policy("@metu.edu.tr");

        assertThat(metu.allows("ali@gmail.com")).isFalse();
        assertThat(metu.allows("ali@fakemetu.edu.tr")).isFalse();
        assertThat(metu.allows("ali@metu.edu.tr.evil.com")).isFalse();
        assertThat(metu.allows("ali.metu.edu.tr")).isFalse();
        assertThat(metu.allows(null)).isFalse();
    }

    @Test
    void requireAllowed_namesTheAllowedDomainsInTheError() {
        EmailDomainPolicy metu = policy("metu.edu.tr", "ODTU.edu.tr");

        assertThatCode(() -> metu.requireAllowed("ali@odtu.edu.tr")).doesNotThrowAnyException();
        assertThatThrownBy(() -> metu.requireAllowed("ali@gmail.com"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("@metu.edu.tr, @odtu.edu.tr");
    }
}
