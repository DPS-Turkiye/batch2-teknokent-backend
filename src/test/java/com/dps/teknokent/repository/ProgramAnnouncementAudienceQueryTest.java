package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.AnnouncementAudience;
import com.dps.teknokent.entity.ProgramAnnouncement;

@DataJpaTest
class ProgramAnnouncementAudienceQueryTest {

	private static final UUID PROGRAM = UUID.randomUUID();
	private static final UUID OTHER_PROGRAM = UUID.randomUUID();

	@Autowired
	private ProgramAnnouncementRepository repository;

	@BeforeEach
	void postOneOfEach() {
		save(PROGRAM, "Herkese", AnnouncementAudience.EVERYONE);
		save(PROGRAM, "Mentorlara", AnnouncementAudience.MENTORS);
		save(PROGRAM, "Takımlara", AnnouncementAudience.PARTICIPANTS);
		save(OTHER_PROGRAM, "Başka program", AnnouncementAudience.EVERYONE);
	}

	@Test
	void aMentorReadsWhatIsForEveryoneAndForMentors() {
		assertThat(bodies(repository.findByProgramIdInAndAudienceIn(Set.of(PROGRAM),
				AnnouncementAudience.FOR_MENTORS)))
				.containsExactlyInAnyOrder("Herkese", "Mentorlara");
	}

	@Test
	void aTeamMemberReadsWhatIsForEveryoneAndForParticipants() {
		assertThat(bodies(repository.findByProgramIdInAndAudienceIn(Set.of(PROGRAM),
				AnnouncementAudience.FOR_PARTICIPANTS)))
				.containsExactlyInAnyOrder("Herkese", "Takımlara");
	}

	@Test
	void anAnnouncementWithoutAnAudienceIsForEveryone() {
		ProgramAnnouncement announcement = new ProgramAnnouncement();
		announcement.setId(UUID.randomUUID());
		announcement.setProgramId(PROGRAM);
		announcement.setAuthorId(UUID.randomUUID());
		announcement.setBody("Varsayılan");

		assertThat(repository.saveAndFlush(announcement).getAudience()).isEqualTo(AnnouncementAudience.EVERYONE);
	}

	private void save(UUID programId, String body, AnnouncementAudience audience) {
		ProgramAnnouncement announcement = new ProgramAnnouncement();
		announcement.setId(UUID.randomUUID());
		announcement.setProgramId(programId);
		announcement.setAuthorId(UUID.randomUUID());
		announcement.setBody(body);
		announcement.setAudience(audience);
		repository.saveAndFlush(announcement);
	}

	private static List<String> bodies(List<ProgramAnnouncement> announcements) {
		return announcements.stream().map(ProgramAnnouncement::getBody).toList();
	}
}
