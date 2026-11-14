package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import com.dps.teknokent.config.AiProperties.AiProvider;

class AiPropertiesTest {

	@Configuration
	@EnableConfigurationProperties(AiProperties.class)
	static class Settings {
	}

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(Settings.class);

	// Before, AiProviderClient sent "ollama" embeddings to OpenAI while the matcher labelled them ollama:bge-m3.
	@Test
	void aProviderTheBackendCannotTalkToStopsTheApplicationAtStartUp() {
		context.withPropertyValues("ai.provider=ollama")
				.run(started -> assertThat(started).hasFailed()
						.getFailure().rootCause().hasMessageContaining("ollama"));
	}

	@Test
	void theProviderIsReadWhateverItsCaseAndDefaultsToOpenAi() {
		context.withPropertyValues("ai.provider=openrouter")
				.run(started -> assertThat(started.getBean(AiProperties.class).provider())
						.isEqualTo(AiProvider.OPENROUTER));
		context.withPropertyValues("ai.provider=OpenRouter")
				.run(started -> assertThat(started.getBean(AiProperties.class).provider())
						.isEqualTo(AiProvider.OPENROUTER));
		context.withPropertyValues("ai.provider=")
				.run(started -> assertThat(started.getBean(AiProperties.class).provider())
						.isEqualTo(AiProvider.OPENAI));
		context.run(started -> assertThat(started.getBean(AiProperties.class).provider())
				.isEqualTo(AiProvider.OPENAI));
	}

	// The deployments set these environment variables; application.yml must still route each to its setting.
	@Test
	void theEnvironmentVariablesInEnvExampleStillReachEverySetting() throws IOException {
		AiProperties properties = bindFromApplicationYml(Map.of(
				"AI_PROVIDER", "openrouter",
				"OPENROUTER_API_KEY", "or-key",
				"OPENROUTER_BASE_URL", "https://router.example/v1/",
				"OPENROUTER_MODEL", "openai/gpt-4o-mini",
				"OPENROUTER_REPORT_MODEL", "google/gemini-3-flash-preview",
				"OPENROUTER_EMBEDDING_MODEL", "openai/text-embedding-3-large",
				"OPENROUTER_APP_NAME", "Menton",
				"OPENAI_API_KEY", "oa-key",
				"OPENAI_EMBEDDING_MODEL", "text-embedding-3-large"));

		assertThat(properties.provider()).isEqualTo(AiProvider.OPENROUTER);
		assertThat(properties.apiKey()).isEqualTo("or-key");
		assertThat(properties.baseUrl()).isEqualTo("https://router.example/v1");
		assertThat(properties.profileModel()).isEqualTo("openai/gpt-4o-mini");
		assertThat(properties.reportModel()).isEqualTo("google/gemini-3-flash-preview");
		assertThat(properties.embeddingModel()).isEqualTo("openai/text-embedding-3-large");
		assertThat(properties.openrouter().appName()).isEqualTo("Menton");
		assertThat(properties.openai().apiKey()).isEqualTo("oa-key");
		assertThat(properties.openai().embeddingModel()).isEqualTo("text-embedding-3-large");
	}

	@Test
	void withNoEnvironmentVariablesTheDefaultsAreTheOnesTheBackendAlwaysUsed() throws IOException {
		AiProperties properties = bindFromApplicationYml(Map.of());

		assertThat(properties.provider()).isEqualTo(AiProvider.OPENAI);
		assertThat(properties.apiKey()).isEmpty();
		assertThat(properties.baseUrl()).isEqualTo("https://api.openai.com/v1");
		assertThat(properties.profileModel()).isEqualTo("gpt-4o-mini");
		assertThat(properties.reportModel()).isEqualTo("gpt-4o-mini");
		assertThat(properties.embeddingModel()).isEqualTo("text-embedding-3-small");
		assertThat(properties.openrouter().reportModel()).isEqualTo("openai/gpt-4o-mini");
		assertThat(properties.openrouter().appName()).isEqualTo("TeamTNT Spring Backend");
	}

	@Test
	void anUnsupportedProviderInTheEnvironmentFailsToBind() {
		assertThatThrownBy(() -> bindFromApplicationYml(Map.of("AI_PROVIDER", "ollama")))
				.isInstanceOf(BindException.class);
	}

	@Test
	void eachSettingFollowsTheChosenProvider() {
		AiProperties.OpenAi openAi = new AiProperties.OpenAi("oa", null, "gpt-x", "embed-oa");
		AiProperties.OpenRouter openRouter = new AiProperties.OpenRouter("or", null, "or/profile", null, "or/embed",
				null);

		AiProperties onOpenAi = new AiProperties(AiProvider.OPENAI, openAi, openRouter);
		assertThat(onOpenAi.apiKey()).isEqualTo("oa");
		assertThat(onOpenAi.embeddingModel()).isEqualTo("embed-oa");
		assertThat(onOpenAi.reportModel()).isEqualTo("gpt-x");
		assertThat(onOpenAi.provider().wireName()).isEqualTo("openai");

		AiProperties onOpenRouter = new AiProperties(AiProvider.OPENROUTER, openAi, openRouter);
		assertThat(onOpenRouter.apiKey()).isEqualTo("or");
		assertThat(onOpenRouter.embeddingModel()).isEqualTo("or/embed");
		assertThat(onOpenRouter.reportModel()).as("no report model: the profile model").isEqualTo("or/profile");
		assertThat(onOpenRouter.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
		assertThat(onOpenRouter.provider().wireName()).isEqualTo("openrouter");
	}

	/** Binds {@code ai.*} from the real src/main/resources/application.yml with {@code env} as the environment. */
	private static AiProperties bindFromApplicationYml(Map<String, Object> env) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().addFirst(new MapPropertySource("env", env));
		for (PropertySource<?> source : new YamlPropertySourceLoader().load("application.yml",
				new FileSystemResource("src/main/resources/application.yml"))) {
			environment.getPropertySources().addLast(source);
		}
		return Binder.get(environment).bindOrCreate("ai", AiProperties.class);
	}
}
