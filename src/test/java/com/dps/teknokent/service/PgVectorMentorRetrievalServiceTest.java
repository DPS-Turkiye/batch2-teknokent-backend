package com.dps.teknokent.service;

import org.mockito.ArgumentCaptor;

import com.dps.teknokent.dto.responses.StudentProfileResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import com.dps.teknokent.dto.responses.MentorEvidenceResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;

@ExtendWith(MockitoExtension.class)
class PgVectorMentorRetrievalServiceTest {

        @Mock
        private JdbcTemplate jdbcTemplate;

        @Mock
        private AiProviderClient aiProviderClient;

        private PgVectorMentorRetrievalService service;

        @BeforeEach
        void setUp() {
                service = new PgVectorMentorRetrievalService(jdbcTemplate, aiProviderClient);
                ReflectionTestUtils.setField(service, "enabled", true);
        }

        @Test
        void shortlist_throwsWhenPgVectorMatchingIsDisabled() {
                ReflectionTestUtils.setField(service, "enabled", false);

                assertThatThrownBy(() -> service.shortlist("startup need", List.of(), "openai",
                                "text-embedding-3-small", 5))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("pgvector matching is disabled");
        }

        @Test
        void shortlist_returnsOriginalMentorsWhenInputListIsEmpty() throws Exception {
                List<MentorProfileResponse> result = service.shortlist("startup need", List.of(), "openai",
                                "text-embedding-3-small", 5);

                assertThat(result).isEmpty();
        }

        @Test
        void shortlist_embedsMissingProfilesAndReturnsClosestMentorIds() throws Exception {
                UUID firstMentorId = UUID.fromString("00000000-0000-0000-0000-000000000101");
                UUID secondMentorId = UUID.fromString("00000000-0000-0000-0000-000000000102");
                String provider = "openai";
                String model = "text-embedding-3-small";

                MentorProfileResponse first = mentorProfile(firstMentorId, "Ada", "Product Mentor");
                MentorProfileResponse second = mentorProfile(secondMentorId, "Bora", "Growth Mentor");

                when(aiProviderClient.providerName()).thenReturn(provider);
                when(aiProviderClient.embeddingModel()).thenReturn(model);
                when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(firstMentorId), eq(provider),
                                eq(model)))
                                .thenReturn(List.of("different-fingerprint"));
                when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(secondMentorId), eq(provider),
                                eq(model)))
                                .thenReturn(List.of("same-fingerprint"));
                when(aiProviderClient.embeddings(anyList()))
                                .thenReturn(List.of(List.of(0.1, 0.2), List.of(0.3, 0.4), List.of(0.9, 0.8)));
                when(jdbcTemplate.query(
                                anyString(),
                                any(PreparedStatementSetter.class),
                                ArgumentMatchers.<RowMapper<UUID>>any()))
                                .thenReturn(List.of(secondMentorId, firstMentorId));

                var result = service.shortlist("need product growth support", List.of(first, second), provider, model,
                                5);

                assertThat(result).extracting(MentorProfileResponse::id)
                                .containsExactly(secondMentorId, firstMentorId);
                verify(jdbcTemplate).update(
                                anyString(),
                                eq(firstMentorId),
                                eq(provider),
                                eq(model),
                                any(),
                                anyString());
        }

        @Test
        void shortlist_embedsAStudentMentorsStudiesButLeavesOtherMentorsTextAsItWas() throws Exception {
                UUID studentId = UUID.fromString("00000000-0000-0000-0000-000000000201");
                UUID otherId = UUID.fromString("00000000-0000-0000-0000-000000000202");
                MentorProfileResponse other = mentorProfile(otherId, "Bora", "Growth Mentor");
                MentorProfileResponse student = withStudent(mentorProfile(studentId, "Ece", "Öğrenci mentor"),
                                new StudentProfileResponse("Bilgisayar Mühendisliği", "4. sınıf",
                                                List.of("CENG 140"), List.of(), "TU Delft"));
                when(aiProviderClient.providerName()).thenReturn("openai");
                when(aiProviderClient.embeddingModel()).thenReturn("text-embedding-3-small");
                when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(UUID.class), anyString(),
                                anyString())).thenReturn(List.of());
                when(aiProviderClient.embeddings(anyList()))
                                .thenReturn(List.of(List.of(0.1, 0.2), List.of(0.3, 0.4)))
                                .thenReturn(List.of(List.of(0.5, 0.6)));
                when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class),
                                ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(studentId));

                service.shortlist("erasmus", List.of(student, other), "openai", "text-embedding-3-small", 5);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
                verify(aiProviderClient, org.mockito.Mockito.times(2)).embeddings(texts.capture());
                List<String> documents = texts.getAllValues().getFirst();
                assertThat(documents.get(0)).contains("Bilgisayar Mühendisliği", "CENG 140", "TU Delft");
                assertThat(documents.get(1)).doesNotContain("Bilgisayar Mühendisliği", "TU Delft");
                assertThat(documents.get(1).split("\n")).hasSize(6);
        }

        private static MentorProfileResponse withStudent(MentorProfileResponse m, StudentProfileResponse student) {
                return new MentorProfileResponse(m.id(), m.fullName(), m.email(), m.initials(), m.professionalTitle(),
                                m.company(), m.availabilityLabel(), m.responseTimeLabel(), m.meetingMode(),
                                m.communicationPreference(), m.mentoringStyle(), m.fitLabel(), m.experienceLevel(),
                                m.sectorExperience(), m.startupStages(), m.specialties(), m.recommendationReasons(),
                                m.recommendationEvidence(), m.menteeCapacity(), m.about(), m.defaultMeetingDurationMinutes(),
                                m.meetingBufferMinutes(), m.meetingFrequency(), m.prepFileRequirement(), m.languages(),
                                m.activeMenteeCount(), m.completedMentorshipCount(), m.programNames(), m.programs(),
                                m.experiences(), m.offerings(), student);
        }

        private static MentorProfileResponse mentorProfile(UUID id, String fullName, String title) {
                return new MentorProfileResponse(
                                id,
                                fullName,
                                fullName.toLowerCase() + "@example.com",
                                fullName.substring(0, 1).toUpperCase(),
                                title,
                                "Teknokent",
                                "Weekdays",
                                "Within 24h",
                                "ONLINE",
                                "Email",
                                "Structured",
                                "Strong fit",
                                "Senior",
                                "Fintech",
                                "Seed",
                                List.of("AI", "Product"),
                                List.of("Great mentor"),
                                List.of(new MentorEvidenceResponse("technical", "Builds products",
                                                "Strong product signal", 1, "v1")));
        }
}
