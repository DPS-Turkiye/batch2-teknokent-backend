package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientAddressKeyTest {

	@Test
	void keysOnlyAnIpLiteralAndAnIpv6VisitorByItsSlash64() {
		assertThat(ClientAddressKey.of(" 203.0.113.7 ")).isEqualTo("203.0.113.7");
		assertThat(ClientAddressKey.of("2001:db8:1:2::1"))
				.isEqualTo(ClientAddressKey.of("2001:db8:1:2:ffff::9"))
				.isNotEqualTo(ClientAddressKey.of("2001:db8:1:3::1"));
		assertThat(ClientAddressKey.of("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
	}

	@Test
	void anythingElseIsUnknown() {
		assertThat(ClientAddressKey.of(null)).isEqualTo(ClientAddressKey.UNKNOWN);
		assertThat(ClientAddressKey.of("  ")).isEqualTo(ClientAddressKey.UNKNOWN);
		assertThat(ClientAddressKey.of("evil.example.com")).isEqualTo(ClientAddressKey.UNKNOWN);
		assertThat(ClientAddressKey.of("1.2.3.4, 5.6.7.8")).isEqualTo(ClientAddressKey.UNKNOWN);
		assertThat(ClientAddressKey.of("999.1.1.1")).isEqualTo(ClientAddressKey.UNKNOWN);
	}
}
