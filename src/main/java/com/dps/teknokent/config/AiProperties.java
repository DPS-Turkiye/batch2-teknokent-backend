package com.dps.teknokent.config;

import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The AI provider settings under {@code ai.*} (AI_PROVIDER, OPENAI_*, OPENROUTER_*), read once for every class that
 * talks to the provider or labels what it returned. {@code ai.features} is read by {@code AiFeatures}.
 *
 * <p>{@code ai.provider} binds to {@link AiProvider}, so a value that names no supported provider (an old
 * {@code ollama}, a typo) stops the application at start-up instead of quietly sending data to another provider.
 * Unset or blank means OpenAI, as before.
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(AiProvider provider, OpenAi openai, OpenRouter openrouter) {

	public AiProperties {
		provider = provider == null ? AiProvider.OPENAI : provider;
		openai = openai == null ? OpenAi.unset() : openai;
		openrouter = openrouter == null ? OpenRouter.unset() : openrouter;
	}

	public enum AiProvider {
		OPENAI,
		OPENROUTER;

		/** The lowercase name stored with each mentor embedding and shown in the matching model label. */
		public String wireName() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * Spring binds an unset property as null; the constructor puts the defaults in its place, so only {@code apiKey}
	 * can be null afterwards.
	 */
	public record OpenAi(@Nullable String apiKey, String baseUrl, String profileModel, String embeddingModel) {
		private static final String BASE_URL = "https://api.openai.com/v1";
		private static final String PROFILE_MODEL = "gpt-4o-mini";
		private static final String EMBEDDING_MODEL = "text-embedding-3-small";

		public OpenAi {
			baseUrl = orDefault(baseUrl, BASE_URL);
			profileModel = orDefault(profileModel, PROFILE_MODEL);
			embeddingModel = orDefault(embeddingModel, EMBEDDING_MODEL);
		}

		static OpenAi unset() {
			return new OpenAi(null, BASE_URL, PROFILE_MODEL, EMBEDDING_MODEL);
		}
	}

	/** Like {@link OpenAi}: only {@code apiKey} can be null once constructed. */
	public record OpenRouter(@Nullable String apiKey, String baseUrl, String profileModel, String reportModel,
			String embeddingModel, String appName) {
		private static final String BASE_URL = "https://openrouter.ai/api/v1";
		private static final String PROFILE_MODEL = "openai/gpt-4o-mini";
		private static final String EMBEDDING_MODEL = "openai/text-embedding-3-small";
		private static final String APP_NAME = "TeamTNT Spring Backend";

		public OpenRouter {
			baseUrl = orDefault(baseUrl, BASE_URL);
			profileModel = orDefault(profileModel, PROFILE_MODEL);
			// A blank report model falls back to the profile model, as OPENROUTER_REPORT_MODEL documents.
			reportModel = orDefault(reportModel, profileModel);
			embeddingModel = orDefault(embeddingModel, EMBEDDING_MODEL);
			appName = orDefault(appName, APP_NAME);
		}

		static OpenRouter unset() {
			return new OpenRouter(null, BASE_URL, PROFILE_MODEL, PROFILE_MODEL, EMBEDDING_MODEL, APP_NAME);
		}
	}

	public @Nullable String apiKey() {
		return provider == AiProvider.OPENROUTER ? openrouter.apiKey() : openai.apiKey();
	}

	public String baseUrl() {
		String url = provider == AiProvider.OPENROUTER ? openrouter.baseUrl() : openai.baseUrl();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	public String profileModel() {
		return provider == AiProvider.OPENROUTER ? openrouter.profileModel() : openai.profileModel();
	}

	public String reportModel() {
		return provider == AiProvider.OPENROUTER ? openrouter.reportModel() : openai.profileModel();
	}

	public String embeddingModel() {
		return provider == AiProvider.OPENROUTER ? openrouter.embeddingModel() : openai.embeddingModel();
	}

	private static String orDefault(@Nullable String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}
}
