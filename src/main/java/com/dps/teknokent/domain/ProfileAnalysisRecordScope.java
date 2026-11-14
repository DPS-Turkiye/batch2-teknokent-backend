package com.dps.teknokent.domain;

public enum ProfileAnalysisRecordScope {
	STARTUP(ProfileType.MENTEE, false),
	PERSONAL(ProfileType.INDIVIDUAL_MENTEE, true),
	TEAM_PERSONAL(ProfileType.INDIVIDUAL_MENTEE, false);

	private final ProfileType profileType;
	private final boolean personal;

	ProfileAnalysisRecordScope(ProfileType profileType, boolean personal) {
		this.profileType = profileType;
		this.personal = personal;
	}

	public ProfileType profileType() {
		return profileType;
	}

	public boolean personal() {
		return personal;
	}
}
