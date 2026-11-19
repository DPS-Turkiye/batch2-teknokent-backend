package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.MentorLabelColor;
import com.dps.teknokent.entity.ProgramMentorLabel;
import com.dps.teknokent.entity.ProgramMentorLabelAssignment;

import jakarta.persistence.EntityManager;

/** The bulk deletes and lookups run as real JPQL against H2. */
@DataJpaTest
class ProgramMentorLabelAssignmentRepositoryTest {

	private static final Instant NOW = Instant.parse("2026-11-19T08:00:00Z");
	private static final UUID PROGRAM_A = UUID.randomUUID();
	private static final UUID PROGRAM_B = UUID.randomUUID();
	private static final UUID MENTOR = UUID.randomUUID();
	private static final UUID OTHER_MENTOR = UUID.randomUUID();

	@Autowired
	private ProgramMentorLabelAssignmentRepository assignments;
	@Autowired
	private ProgramMentorLabelRepository labels;
	@Autowired
	private EntityManager entityManager;

	@Test
	void clearingOneMentorInOneProgramLeavesEveryoneElse() {
		ProgramMentorLabel fintech = label(PROGRAM_A, "Fintech");
		ProgramMentorLabel growth = label(PROGRAM_B, "Pazarlama");
		assign(fintech, MENTOR);
		assign(fintech, OTHER_MENTOR);
		assign(growth, MENTOR);
		entityManager.flush();

		assertThat(assignments.deleteByProgramIdAndMentorId(PROGRAM_A, MENTOR)).isEqualTo(1);
		entityManager.clear();

		assertThat(assignments.findByProgramId(PROGRAM_A)).extracting(ProgramMentorLabelAssignment::getMentorId)
				.containsExactly(OTHER_MENTOR);
		assertThat(assignments.findByMentorIdAndProgramIdIn(MENTOR, List.of(PROGRAM_A, PROGRAM_B)))
				.extracting(ProgramMentorLabelAssignment::getLabelId).containsExactly(growth.getId());
	}

	@Test
	void deletingALabelsAssignmentsTouchesNoOtherLabel() {
		ProgramMentorLabel fintech = label(PROGRAM_A, "Fintech");
		ProgramMentorLabel ai = label(PROGRAM_A, "AI");
		assign(fintech, MENTOR);
		assign(ai, MENTOR);
		entityManager.flush();

		assertThat(assignments.deleteByLabelId(fintech.getId())).isEqualTo(1);
		entityManager.clear();

		assertThat(assignments.findByProgramId(PROGRAM_A)).extracting(ProgramMentorLabelAssignment::getLabelId)
				.containsExactly(ai.getId());
		assertThat(labels.findByProgramIdInOrderByNameAsc(List.of(PROGRAM_A))).extracting(ProgramMentorLabel::getName)
				.containsExactly("AI", "Fintech");
		assertThat(labels.findByIdAndProgramId(fintech.getId(), PROGRAM_B)).isEmpty();
		assertThat(labels.countByProgramId(PROGRAM_A)).isEqualTo(2);
	}

	private ProgramMentorLabel label(UUID programId, String name) {
		ProgramMentorLabel label = new ProgramMentorLabel();
		label.setId(UUID.randomUUID());
		label.setProgramId(programId);
		label.setName(name);
		label.setColor(MentorLabelColor.PURPLE);
		label.setCreatedAt(NOW);
		entityManager.persist(label);
		return label;
	}

	private void assign(ProgramMentorLabel label, UUID mentorId) {
		ProgramMentorLabelAssignment assignment = new ProgramMentorLabelAssignment();
		assignment.setId(UUID.randomUUID());
		assignment.setLabelId(label.getId());
		assignment.setProgramId(label.getProgramId());
		assignment.setMentorId(mentorId);
		assignment.setCreatedAt(NOW);
		entityManager.persist(assignment);
	}
}
