package com.dps.teknokent.domain;

/**
 * Where a coordinator's mentorship row comes from. Accepting a request books a meeting but makes no mentorship row,
 * so a team working with a mentor through an accepted request is listed from the request itself.
 */
public enum MentorshipSource {
	MENTORSHIP,
	ACCEPTED_REQUEST
}
