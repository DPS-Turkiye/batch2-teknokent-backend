package com.dps.teknokent.security.institution;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.StudyLevel;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.InstitutionAdvising;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.InstitutionAdvisingRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for ODTÜ's login until its protocol is known, so the student and academician screens can be built on
 * real sessions. A developer picks a persona on the frontend's /login/odtu/dev page; everything after that (account,
 * grants, custom token, session) is the real path. Never on unless {@code INSTITUTION_LOGIN_DEV_STUB=true}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.institution-login.dev-stub", havingValue = "true")
public class DevInstitutionLoginProvider implements InstitutionLoginProvider {

	public static final String ID = "odtu";
	static final String PERSONA_PARAM = "persona";
	static final String DEV_PROFILE = "dev";
	private static final String FIXTURE = "dev/odtu-personas.json";

	private final Map<String, DevPersona> personas;
	private final InstitutionPersonRepository people;
	private final InstitutionAdvisingRepository advising;

	@Autowired
	public DevInstitutionLoginProvider(JsonMapper jsonMapper, InstitutionPersonRepository people,
			InstitutionAdvisingRepository advising, Environment environment) {
		this(load(jsonMapper), people, advising);
		requireDevProfile(environment);
	}

	/** Allowlist, not denylist: a server whose profile is unknown or missing must not start with the stub on. */
	static void requireDevProfile(Environment environment) {
		if (!environment.acceptsProfiles(Profiles.of(DEV_PROFILE))) {
			throw new IllegalStateException("INSTITUTION_LOGIN_DEV_STUB=true needs SPRING_PROFILES_ACTIVE=" + DEV_PROFILE
					+ "; refusing to start with the ODTÜ dev login on any other server.");
		}
	}

	DevInstitutionLoginProvider(List<DevPersona> personas, InstitutionPersonRepository people,
			InstitutionAdvisingRepository advising) {
		this.personas = new LinkedHashMap<>();
		personas.forEach(persona -> this.personas.put(persona.id(), persona));
		this.people = people;
		this.advising = advising;
		log.warn("ODTÜ dev login stub is ON: anyone reaching this backend can sign in as a test persona. "
				+ "Never set INSTITUTION_LOGIN_DEV_STUB on a shared or production server.");
	}

	private static List<DevPersona> load(JsonMapper jsonMapper) {
		try (InputStream fixture = new ClassPathResource(FIXTURE).getInputStream()) {
			return jsonMapper.readValue(fixture, DevPersonaFixture.class).people();
		} catch (IOException exception) {
			throw new UncheckedIOException("Cannot read " + FIXTURE, exception);
		}
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String startLogin(String state) {
		return "/login/odtu/dev?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
	}

	@Override
	public InstitutionIdentity completeLogin(String state, Map<String, String> callback) {
		DevPersona persona = Optional.ofNullable(callback.get(PERSONA_PARAM))
				.map(personas::get)
				.orElseThrow(() -> new InvalidRequestException("Unknown dev persona."));
		return persona.identity();
	}

	@Override
	public Set<UserRole> extraGrants(String persistentId) {
		DevPersona persona = personas.get(persistentId);
		return persona == null ? Set.of() : Set.copyOf(persona.extraGrants());
	}

	public List<DevPersona> personas() {
		return List.copyOf(personas.values());
	}

	/**
	 * Puts every persona (and the academicians nobody signs in as) into the institution tables, so the academician
	 * list, "Akademik Danışmanım" and "Danışan Öğrencilerim" have data before anyone signs in.
	 */
	@Transactional
	@EventListener(ApplicationReadyEvent.class)
	public void seedDirectory() {
		Instant now = Instant.now();
		for (DevPersona persona : personas.values()) {
			InstitutionPerson person = people.findByInstitutionAndPersistentId(ID, persona.id())
					.orElseGet(() -> {
						InstitutionPerson created = new InstitutionPerson(ID, persona.id());
						created.setSource(InstitutionDataSource.DEV_STUB);
						return created;
					});
			InstitutionRecords.apply(persona.identity(), person, now);
			people.save(person);
			for (String advisorId : persona.advisorIds()) {
				if (!advising.existsByInstitutionAndStudentPersistentIdAndAdvisorPersistentId(ID, persona.id(),
						advisorId)) {
					advising.save(new InstitutionAdvising(ID, persona.id(), advisorId, now));
				}
			}
		}
		log.info("ODTÜ dev stub: {} personas in the institution directory", personas.size());
	}

	record DevPersonaFixture(List<DevPersona> people) {
	}

	/** One test person; {@code id} is also their persistent id. */
	public record DevPersona(
			String id,
			String label,
			String fullName,
			String email,
			Set<IdentityKind> kinds,
			String faculty,
			String departmentCode,
			String departmentName,
			Integer classYear,
			StudyLevel programLevel,
			String academicTitle,
			Boolean iskurParticipant,
			List<String> advisorIds,
			List<UserRole> extraGrants) {

		// Boxed: academicians leave it out, and Jackson 3 will not read a missing field into a primitive.
		public DevPersona {
			iskurParticipant = Boolean.TRUE.equals(iskurParticipant);
			kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
			advisorIds = advisorIds == null ? List.of() : List.copyOf(advisorIds);
			extraGrants = extraGrants == null ? List.of() : List.copyOf(extraGrants);
		}

		InstitutionIdentity identity() {
			return new InstitutionIdentity(id, email, true, fullName, kinds, faculty, departmentCode,
					departmentName, classYear, programLevel, academicTitle, iskurParticipant);
		}
	}
}
