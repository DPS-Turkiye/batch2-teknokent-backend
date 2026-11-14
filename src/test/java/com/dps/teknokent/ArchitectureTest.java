package com.dps.teknokent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import jakarta.persistence.Entity;

/**
 * Which layer may use which, checked against the compiled main classes. These are the rules AGENTS.md states in
 * words; a change that breaks one fails here with the offending class and line.
 */
@AnalyzeClasses(packages = "com.dps.teknokent", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	// The application class alone, not its subpackages.
	private static final String ROOT = "com.dps.teknokent";
	private static final String CONTROLLERS = "com.dps.teknokent.controller..";
	private static final String SERVICES = "com.dps.teknokent.service..";
	private static final String REPOSITORIES = "com.dps.teknokent.repository..";
	private static final String ENTITIES = "com.dps.teknokent.entity..";
	private static final String DOMAIN = "com.dps.teknokent.domain..";

	@ArchTest
	static void controllersReachDataOnlyThroughServices(JavaClasses classes) {
		noClasses().that().resideInAPackage(CONTROLLERS)
				.should().dependOnClassesThat().resideInAnyPackage(REPOSITORIES, ENTITIES)
				.because("a controller calls a service and returns its DTO; it never loads or returns an entity")
				.check(classes);
	}

	@ArchTest
	static void nothingBelowTheWebLayerKnowsControllers(JavaClasses classes) {
		noClasses().that().resideOutsideOfPackage(CONTROLLERS)
				.should().dependOnClassesThat().resideInAPackage(CONTROLLERS)
				.because("controllers are the outermost layer; logic that two of them need belongs in a service")
				.check(classes);
	}

	@ArchTest
	static void repositoriesDoNotCallServices(JavaClasses classes) {
		noClasses().that().resideInAPackage(REPOSITORIES)
				.should().dependOnClassesThat().resideInAPackage(SERVICES)
				.because("a repository only reads and writes; rules live in the service that calls it")
				.check(classes);
	}

	@ArchTest
	static void domainTypesStandOnTheirOwn(JavaClasses classes) {
		noClasses().that().resideInAPackage(DOMAIN)
				.should().dependOnClassesThat()
				.resideInAnyPackage("org.springframework..", SERVICES, REPOSITORIES, ENTITIES)
				.because("enums and value types are usable anywhere; JPA's converter interface is the only framework"
						+ " type they touch")
				.check(classes);
	}

	@ArchTest
	static void dependenciesComeThroughTheConstructor(JavaClasses classes) {
		noFields().should().beAnnotatedWith(Autowired.class)
				.because("constructor injection keeps a class buildable in a plain unit test")
				.check(classes);
	}

	@ArchTest
	static void controllersLiveInTheControllerPackage(JavaClasses classes) {
		classes().that().areAnnotatedWith(RestController.class)
				.should().resideInAPackage(CONTROLLERS)
				.andShould().haveSimpleNameEndingWith("Controller")
				.check(classes);
	}

	@ArchTest
	static void servicesLiveInTheServicePackage(JavaClasses classes) {
		classes().that().areAnnotatedWith(Service.class)
				.should().resideInAPackage(SERVICES)
				.check(classes);
	}

	@ArchTest
	static void repositoriesLiveInTheRepositoryPackage(JavaClasses classes) {
		classes().that().areAssignableTo(Repository.class)
				.should().resideInAPackage(REPOSITORIES)
				.andShould().haveSimpleNameEndingWith("Repository")
				.check(classes);
	}

	@ArchTest
	static void entitiesLiveInTheEntityPackage(JavaClasses classes) {
		classes().that().areAnnotatedWith(Entity.class)
				.should().resideInAPackage(ENTITIES)
				.check(classes);
	}

	@ArchTest
	static void jsonGoesThroughJacksonThree(JavaClasses classes) {
		noClasses().should().dependOnClassesThat()
				.resideInAnyPackage("com.fasterxml.jackson.databind..", "com.fasterxml.jackson.core..")
				.because("Spring MVC reads and writes bodies with Jackson 3 (tools.jackson), which cannot handle a"
						+ " Jackson 2 JsonNode: such a field arrives as a 500 or leaves as the node's bean getters."
						+ " The annotations in com.fasterxml.jackson.annotation are shared by both and stay allowed")
				.check(classes);
	}

	@ArchTest
	static void everyPackageButEntitiesAndRequestBodiesIsNullMarked(JavaClasses classes) {
		classes().that().resideOutsideOfPackages(ROOT, ENTITIES, "..dto.requests..")
				.should(new ArchCondition<JavaClass>("be in a @NullMarked package") {
					@Override
					public void check(JavaClass javaClass, ConditionEvents events) {
						if (!javaClass.getPackage().isAnnotatedWith(NullMarked.class)) {
							events.add(SimpleConditionEvent.violated(javaClass, javaClass.getPackageName()
									+ " has no package-info.java with @NullMarked"));
						}
					}
				})
				.because("NullAway checks only null-marked packages, so a package without it is silently unchecked;"
						+ " entities (JPA fills their fields) and request bodies (null until validated) stay out")
				.check(classes);
	}
}
