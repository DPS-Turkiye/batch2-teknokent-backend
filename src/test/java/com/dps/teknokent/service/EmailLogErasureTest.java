package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.EmailAudience;
import com.dps.teknokent.domain.EmailRecipientKind;
import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.EmailCampaign;
import com.dps.teknokent.entity.EmailCampaignRecipient;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class EmailLogErasureTest {

	@Autowired
	private UserDataEraser userDataEraser;
	@Autowired
	private OrganizationDataEraser organizationDataEraser;
	@Autowired
	private EntityManager entityManager;

	@Test
	void aDeletedPersonsNameAndAddressLeaveTheLogButTheRowStays() {
		AppUser sender = user("sender@log.test", "Gönderen Kişi");
		AppUser reader = user("reader@log.test", "Okuyan Kişi");
		UUID campaign = campaign(sender);
		UUID own = row(campaign, reader.getId(), null, "reader@log.test", "Okuyan Kişi");
		UUID listedEarlier = row(campaign(sender), null, null, "reader@log.test", "Okuyan Kişi");
		UUID other = row(campaign, null, null, "other@log.test", "Başkası");
		entityManager.flush();

		userDataEraser.erase(reader, sender, Set.of(), Instant.now());

		for (UUID id : new UUID[] { own, listedEarlier }) {
			EmailCampaignRecipient erased = entityManager.find(EmailCampaignRecipient.class, id);
			assertThat(erased.getAddress()).isNull();
			assertThat(erased.getFullName()).isEqualTo(AppUser.DELETED_FULL_NAME);
			assertThat(erased.getUnsubscribeTokenHash()).isNull();
			assertThat(erased.getStatus()).isEqualTo(EmailRecipientStatus.SENT);
		}
		assertThat(entityManager.find(EmailCampaignRecipient.class, other).getAddress()).isEqualTo("other@log.test");
	}

	@Test
	void aDeletedStartupsListedPeopleLeaveTheLogAndItsMembersStayWithoutItsName() {
		AppUser founder = user("founder@log.test", "Kurucu Kişi");
		Organization startup = new Organization();
		startup.setName("GreenLoop");
		startup.setFounder(founder);
		entityManager.persist(startup);
		founder.setOrganization(startup);
		UUID campaign = campaign(founder);
		UUID listed = row(campaign, null, startup.getId(), "listed@log.test", "Davetli");
		UUID member = row(campaign, founder.getId(), startup.getId(), "founder@log.test", "Kurucu Kişi");
		entityManager.flush();

		organizationDataEraser.erase(startup, founder, Instant.now());

		entityManager.clear();
		EmailCampaignRecipient erased = entityManager.find(EmailCampaignRecipient.class, listed);
		assertThat(erased.getAddress()).isNull();
		assertThat(erased.getFullName()).isEqualTo(AppUser.DELETED_FULL_NAME);
		EmailCampaignRecipient kept = entityManager.find(EmailCampaignRecipient.class, member);
		assertThat(kept.getAddress()).isEqualTo("founder@log.test");
		assertThat(kept.getTeamName()).isEqualTo(Organization.DELETED_NAME);
	}

	private AppUser user(String email, String name) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(name);
		user.setRole(UserRole.MENTEE);
		entityManager.persist(user);
		return user;
	}

	private UUID campaign(AppUser sender) {
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("Build");
		program.setCreatedBy(sender.getId());
		program.setCreatedAt(Instant.now());
		entityManager.persist(program);
		EmailCampaign campaign = new EmailCampaign();
		campaign.setId(UUID.randomUUID());
		campaign.setProgramId(program.getId());
		campaign.setCreatedBy(sender.getId());
		campaign.setAudience(EmailAudience.EVERYONE);
		campaign.setSubject("Konu");
		campaign.setBodyHtml("<p>Metin</p>");
		campaign.setCreatedAt(Instant.now());
		entityManager.persist(campaign);
		return campaign.getId();
	}

	private UUID row(UUID campaign, UUID userId, UUID organizationId, String address, String name) {
		EmailCampaignRecipient row = new EmailCampaignRecipient();
		row.setId(UUID.randomUUID());
		row.setCampaignId(campaign);
		row.setUserId(userId);
		row.setOrganizationId(organizationId);
		row.setKind(userId == null ? EmailRecipientKind.UNJOINED : EmailRecipientKind.PARTICIPANT);
		row.setAddress(address);
		row.setFullName(name);
		row.setTeamName("GreenLoop");
		row.setStatus(EmailRecipientStatus.SENT);
		row.setUnsubscribeTokenHash(UUID.randomUUID().toString());
		entityManager.persist(row);
		return row.getId();
	}
}
