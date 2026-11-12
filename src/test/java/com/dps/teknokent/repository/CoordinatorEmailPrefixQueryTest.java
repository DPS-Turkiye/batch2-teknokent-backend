package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;

import jakarta.persistence.EntityManager;

@DataJpaTest
class CoordinatorEmailPrefixQueryTest {

	@Autowired
	private EntityManager entityManager;
	@Autowired
	private AppUserRepository repository;

	@Test
	void matchesTheStartOfTheEmailWhateverItsCase() {
		user("Ayse.Aydin@Teknokent.org", UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		user("elif@ayse.org", UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		flush();

		assertThat(emails("ays", 10)).containsExactly("Ayse.Aydin@Teknokent.org");
	}

	@Test
	void returnsOnlyApprovedCoordinators() {
		user("ayse.coordinator@x.org", UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		user("ayse.mentor@x.org", UserRole.MENTOR, UserAccountStatus.APPROVED);
		user("ayse.mentee@x.org", UserRole.MENTEE, UserAccountStatus.APPROVED);
		user("ayse.admin@x.org", UserRole.ADMIN, UserAccountStatus.APPROVED);
		user("ayse.deactivated@x.org", UserRole.COORDINATOR, UserAccountStatus.REJECTED);
		flush();

		assertThat(emails("ayse", 10)).containsExactly("ayse.coordinator@x.org");
	}

	@Test
	void treatsLikeWildcardsAsPlainCharacters() {
		user("a_bc@x.org", UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		user("axbc@x.org", UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		user("abc@x.org", UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		flush();

		assertThat(emails("a\\_b", 10)).containsExactly("a_bc@x.org");
		assertThat(emails("\\%", 10)).isEmpty();
	}

	@Test
	void stopsAtThePageSizeInEmailOrder() {
		user("coord-c@x.org", UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		user("coord-a@x.org", UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		user("coord-b@x.org", UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		flush();

		assertThat(emails("coord", 2)).containsExactly("coord-a@x.org", "coord-b@x.org");
	}

	private List<String> emails(String prefix, int size) {
		return repository.findByPermissionAndStatusAndEmailPrefix(UserRole.COORDINATOR, UserAccountStatus.APPROVED,
						prefix, PageRequest.of(0, size))
				.stream().map(AppUser::getEmail).toList();
	}

	private void user(String email, UserRole role, UserAccountStatus status) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(email);
		user.setRole(role);
		user.setStatus(status);
		entityManager.persist(user);
	}

	private void flush() {
		entityManager.flush();
		entityManager.clear();
	}
}
