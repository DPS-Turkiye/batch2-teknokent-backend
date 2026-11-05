package com.dps.teknokent.domain;

public enum ProfileAnalysisRecordScope {
    STARTUP("mentee", false),
    PERSONAL("individual_mentee", true),
    TEAM_PERSONAL("individual_mentee", false);

    private final String profileType;
    private final boolean personal;

    ProfileAnalysisRecordScope(String profileType, boolean personal) {
        this.profileType = profileType;
        this.personal = personal;
    }

    public String profileType() { return profileType; }
    public boolean personal() { return personal; }
}
