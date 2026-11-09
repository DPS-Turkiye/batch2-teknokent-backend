package com.dps.teknokent.security.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.InstitutionAdvising;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.InstitutionAdvisingRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;

import tools.jackson.databind.json.JsonMapper;

class DevInstitutionLoginProviderTest {

	private final InstitutionPersonRepository people = mock(InstitutionPersonRepository.class);
	private final InstitutionAdvisingRepository advising = mock(InstitutionAdvisingRepository.class);
	private final DevInstitutionLoginProvider stub =
			new DevInstitutionLoginProvider(JsonMapper.builder().build(), people, advising, profiles("dev"));

	private static MockEnvironment profiles(String... active) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(active);
		return environment;
	}

	// Spring has to be able to build it: the unit tests above call the constructors directly.
	@Test
	void springCreatesTheStubOnlyWhenItIsSwitchedOnInTheDevProfile() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
				.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
				.withBean(InstitutionPersonRepository.class, () -> people)
				.withBean(InstitutionAdvisingRepository.class, () -> advising)
				.withUserConfiguration(DevInstitutionLoginProvider.class);

		runner.withPropertyValues("app.institution-login.dev-stub=true", "spring.profiles.active=dev")
				.run(context -> assertThat(context).hasSingleBean(DevInstitutionLoginProvider.class));
		runner.run(context -> assertThat(context).doesNotHaveBean(DevInstitutionLoginProvider.class));
		runner.withPropertyValues("app.institution-login.dev-stub=true")
				.run(context -> assertThat(context).hasFailed());
	}

	// Any server that is not explicitly the dev profile, including one with no profile at all.
	@Test
	void refusesToStartOutsideTheDevProfile() {
		for (MockEnvironment environment : new MockEnvironment[] { profiles(), profiles("prod"), profiles("test") }) {
			assertThatThrownBy(() -> new DevInstitutionLoginProvider(JsonMapper.builder().build(), people, advising,
					environment)).isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	void theFixtureHasEveryPersonaTheOtherLanesNeed() {
		Map<String, DevInstitutionLoginProvider.DevPersona> byId = stub.personas().stream()
				.collect(Collectors.toMap(DevInstitutionLoginProvider.DevPersona::id, persona -> persona));

		assertThat(byId.get("dev-ogrenci-1").kinds()).containsExactly(IdentityKind.STUDENT);
		assertThat(byId.get("dev-iskur-1").iskurParticipant()).isTrue();
		assertThat(stub.extraGrants("dev-iskur-2")).containsExactly(UserRole.MENTOR);
		assertThat(byId.get("dev-akademisyen-1").kinds()).containsExactly(IdentityKind.ACADEMIC);
		assertThat(byId.get("dev-arasgor-1").kinds())
				.containsExactlyInAnyOrder(IdentityKind.STUDENT, IdentityKind.ACADEMIC);
		assertThat(byId.get("dev-personel-1").kinds()).containsExactly(IdentityKind.STAFF);
		assertThat(byId.values()).filteredOn(persona -> persona.kinds().contains(IdentityKind.ACADEMIC))
				.hasSizeGreaterThanOrEqualTo(5);
	}

	// Reserved .invalid addresses can never match a real account, so the stub can never link to one by e-mail.
	@Test
	void everyPersonaHasAnAddressThatCannotExist() {
		assertThat(stub.personas()).allSatisfy(persona -> assertThat(persona.email()).endsWith(".invalid"));
	}

	@Test
	void everyAdvisorIsAnAcademicPersona() {
		Set<String> academics = stub.personas().stream()
				.filter(persona -> persona.kinds().contains(IdentityKind.ACADEMIC))
				.map(DevInstitutionLoginProvider.DevPersona::id)
				.collect(Collectors.toSet());

		assertThat(stub.personas()).flatMap(DevInstitutionLoginProvider.DevPersona::advisorIds)
				.isNotEmpty()
				.allSatisfy(advisorId -> assertThat(academics).contains(advisorId));
	}

	@Test
	void thePickedPersonaIsWhoSignsIn() {
		InstitutionIdentity identity = stub.completeLogin(Map.of(DevInstitutionLoginProvider.PERSONA_PARAM,
				"dev-ogrenci-1"));

		assertThat(identity.persistentId()).isEqualTo("dev-ogrenci-1");
		assertThat(identity.emailVerified()).isTrue();
		assertThat(identity.departmentName()).isEqualTo("Bilgisayar Mühendisliği");
		assertThat(stub.startLogin("a b")).isEqualTo("/login/odtu/dev?state=a+b");
	}

	@Test
	void anUnknownPersonaIsRefused() {
		assertThatThrownBy(() -> stub.completeLogin(Map.of(DevInstitutionLoginProvider.PERSONA_PARAM, "nobody")))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> stub.completeLogin(Map.of())).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void seedingFillsTheDirectoryOnce() {
		when(people.findByInstitutionAndPersistentId(anyString(), anyString())).thenReturn(Optional.empty());

		stub.seedDirectory();

		verify(people, times(stub.personas().size())).save(any(InstitutionPerson.class));
		long links = stub.personas().stream().mapToLong(persona -> persona.advisorIds().size()).sum();
		verify(advising, times((int) links)).save(any(InstitutionAdvising.class));
	}
}
