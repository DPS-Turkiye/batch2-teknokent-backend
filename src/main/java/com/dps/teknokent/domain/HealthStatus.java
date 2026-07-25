package com.dps.teknokent.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum HealthStatus {

	UP,
	DOWN;

	@JsonValue
	public String jsonValue() {
		return name();
	}

	public boolean isUp() {
		return this == UP;
	}

}
