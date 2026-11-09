package com.dps.teknokent.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one Moodle site (LTI 1.3 platform) office hours trust, and this tool's own launch URL. The values come
 * from the tool registration in Moodle (Site administration → Plugins → External tool → Manage tools).
 *
 * @param platformIssuer   Moodle's wwwroot as it appears in the token's {@code iss}
 * @param clientId         the client ID Moodle generated for the tool
 * @param deploymentIds    comma-separated deployment IDs Moodle may send (one per site-wide registration)
 * @param platformAuthUrl  Moodle's OIDC authorization endpoint ({@code /mod/lti/auth.php})
 * @param platformJwksUrl  where this backend fetches Moodle's public keys ({@code /mod/lti/certs.php})
 * @param toolLaunchUrl    the frontend URL Moodle posts the token to; must match the registration
 * @param toolPrivateKey   PEM (PKCS#8) RSA key for the tool's own key set; a throwaway key is made when empty
 */
@ConfigurationProperties(prefix = "app.lti")
public record LtiProperties(
		String platformIssuer,
		String clientId,
		String deploymentIds,
		String platformAuthUrl,
		String platformJwksUrl,
		String toolLaunchUrl,
		String toolPrivateKey) {

	public boolean configured() {
		return notBlank(platformIssuer) && notBlank(clientId) && notBlank(deploymentIds)
				&& notBlank(platformAuthUrl) && notBlank(platformJwksUrl) && notBlank(toolLaunchUrl);
	}

	public List<String> deploymentIdList() {
		if (!notBlank(deploymentIds)) {
			return List.of();
		}
		return Arrays.stream(deploymentIds.split(",")).map(String::trim).filter(id -> !id.isEmpty()).toList();
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}
