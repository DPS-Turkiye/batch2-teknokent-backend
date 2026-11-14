package com.dps.teknokent.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import com.dps.teknokent.domain.ProfileType;

/**
 * Reads a {@code profileType} request parameter by its lowercase wire value ("individual_mentee"), which Spring's
 * own enum conversion (by constant name) would refuse. An unknown value fails as the usual 400 with the message
 * from {@link ProfileType#fromValue}. (When this converter refuses, Spring still tries the constant name, so
 * "MENTEE" is read too; nothing sends it.)
 */
@Component
public class ProfileTypeParameterConverter implements Converter<String, ProfileType> {

	@Override
	public ProfileType convert(String source) {
		return ProfileType.fromValue(source.trim());
	}
}
