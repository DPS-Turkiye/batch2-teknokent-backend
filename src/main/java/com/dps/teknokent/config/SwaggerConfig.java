package com.dps.teknokent.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;

@Configuration
@OpenAPIDefinition(
		info = @Info(
				title = "Teknokent REST API",
				version = "v1",
				description = "Interactive API documentation for the Teknokent backend",
				contact = @Contact(name = "Teknokent Support", email = "support@teknokent.com")),
		servers = @Server(url = "http://localhost:8080", description = "Local development server"),
		security = @SecurityRequirement(name = "basicAuth"))
@SecurityScheme(
		name = "basicAuth",
		type = SecuritySchemeType.HTTP,
		scheme = "basic")
public class SwaggerConfig {
}
