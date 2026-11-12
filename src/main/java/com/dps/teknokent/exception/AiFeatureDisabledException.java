package com.dps.teknokent.exception;

import com.dps.teknokent.domain.AiFeature;

/** A request for an AI feature this deployment has switched off. Answered 403 with code {@link #CODE}. */
public class AiFeatureDisabledException extends RuntimeException {

	public static final String CODE = "AI_FEATURE_DISABLED";

	private final AiFeature feature;

	public AiFeatureDisabledException(AiFeature feature) {
		super("AI feature " + feature + " is disabled in this deployment.");
		this.feature = feature;
	}

	public AiFeature feature() {
		return feature;
	}
}
