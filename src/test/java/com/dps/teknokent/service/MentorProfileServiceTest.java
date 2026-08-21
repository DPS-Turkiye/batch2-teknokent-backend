package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MentorProfileRepository;

@ExtendWith(MockitoExtension.class)
class MentorProfileServiceTest {

    @Mock
    private MentorProfileRepository mentorProfileRepository;

    @InjectMocks
    private MentorProfileService mentorProfileService;

    @Test
    void findAll_returnsMappedProfiles() {
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000102");

        MentorProfile first = mentorProfile(firstId, "Ada Çalışkan", "ada@tekno.com", "AC", "Senior Product Mentor");
        MentorProfile second = mentorProfile(secondId, "Bora Demir", "bora@tekno.com", "BD", "Growth Mentor");

        when(mentorProfileRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(first, second));

        var result = mentorProfileService.findAll();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(profile -> profile.id())
                .containsExactly(firstId, secondId);
        assertThat(result.getFirst().fullName()).isEqualTo("Ada Çalışkan");
        assertThat(result.get(1).professionalTitle()).isEqualTo("Growth Mentor");
    }

    @Test
    void findAllExcept_excludesTheGivenMentor() {
        UUID keptId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID excludedId = UUID.fromString("00000000-0000-0000-0000-000000000202");

        MentorProfile kept = mentorProfile(keptId, "Cem Yılmaz", "cem@tekno.com", "CY", "Technical Mentor");
        MentorProfile excluded = mentorProfile(excludedId, "Defne Aras", "defne@tekno.com", "DA", "UX Mentor");

        when(mentorProfileRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(kept, excluded));

        var result = mentorProfileService.findAllExcept(excludedId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(keptId);
        assertThat(result.getFirst().fullName()).isEqualTo("Cem Yılmaz");
    }

    @Test
    void findById_returnsMappedProfileWhenFound() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000301");
        MentorProfile profile = mentorProfile(id, "Efe Güneş", "efe@tekno.com", "EG", "Platform Mentor");
        profile.setCompany("Teknokent");
        profile.setAvailabilityLabel("Hafta içi");
        profile.setResponseTimeLabel("24 saat");
        profile.setMeetingMode("Uzaktan");
        profile.setCommunicationPreference("E-posta");
        profile.setMentoringStyle("Yapılandırılmış");
        profile.setFitLabel("İyi Uyum");
        profile.setExperienceLevel("10+ yıl");
        profile.setSectorExperience("Fintech");
        profile.setStartupStages("Seed, Series A");
        profile.setSpecialties(List.of("Product", "AI"));
        profile.setRecommendationReasons(List.of("İyi mentor", "Düzenli geri bildirim"));

        when(mentorProfileRepository.findOneById(id)).thenReturn(Optional.of(profile));

        var result = mentorProfileService.findById(id);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.fullName()).isEqualTo("Efe Güneş");
        assertThat(result.email()).isEqualTo("efe@tekno.com");
        assertThat(result.company()).isEqualTo("Teknokent");
        assertThat(result.specialties()).containsExactly("Product", "AI");
        assertThat(result.recommendationReasons()).containsExactly("İyi mentor", "Düzenli geri bildirim");
    }

    @Test
    void findById_throwsWhenProfileDoesNotExist() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000999");

        when(mentorProfileRepository.findOneById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mentorProfileService.findById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Mentor not found: " + id);
    }

    private static MentorProfile mentorProfile(UUID id, String fullName, String email, String initials, String title) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(email);
        user.setFullName(fullName);

        MentorProfile profile = new MentorProfile();
        profile.setId(id);
        profile.setUser(user);
        profile.setInitials(initials);
        profile.setProfessionalTitle(title);
        profile.setCompany("Teknokent");
        profile.setAvailabilityLabel("Hafta içi");
        profile.setResponseTimeLabel("24 saat");
        profile.setMeetingMode("Uzaktan");
        profile.setCommunicationPreference("E-posta");
        profile.setMentoringStyle("Yapılandırılmış");
        profile.setFitLabel("İyi Uyum");
        profile.setExperienceLevel("5+ yıl");
        profile.setSectorExperience("Siber güvenlik");
        profile.setStartupStages("Seed");
        profile.setSpecialties(List.of("Mentorluk"));
        profile.setRecommendationReasons(List.of("Uygun profil"));
        profile.setConfirmedEvidence(List.of());
        return profile;
    }
}
