package com.dps.teknokent.dto.requests;

import java.util.List;

/** The startup fields shared by the signup wizard and the "Startup'ımı oluştur" form on /startup. */
public interface StartupProfileFields {

	String website();

	String mvpUrl();

	Integer foundedYear();

	String problem();

	String solution();

	String currentSolution();

	String targetUser();

	String sector();

	String maturity();

	String programLevel();

	List<String> supportAreas();
}
