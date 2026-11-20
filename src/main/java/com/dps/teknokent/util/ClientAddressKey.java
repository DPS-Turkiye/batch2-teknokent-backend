package com.dps.teknokent.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The visitor's address as the frontend's server saw it, turned into a rate-limit key. The backend only sees that
 * server, so the server names the visitor in {@link #HEADER}.
 */
public final class ClientAddressKey {

	/** Set by the frontend's server to the visitor's address. */
	public static final String HEADER = "X-Menton-Client-Address";
	public static final String UNKNOWN = "unknown";

	/** Only an IP literal is taken, so a header cannot make a host name lookup or mint endless bucket keys. */
	private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
	/** An IPv6 visitor usually holds a whole /64, so the budget is per /64, not per address in it. */
	private static final int IPV6_PREFIX_BYTES = 8;

	private ClientAddressKey() {
	}

	/** The key for {@code clientAddress}, or {@link #UNKNOWN} when it is missing or not an IP literal. */
	public static String of(@Nullable String clientAddress) {
		String value = clientAddress == null ? "" : clientAddress.trim();
		if (!IP_LITERAL.matcher(value).matches()) {
			return UNKNOWN;
		}
		try {
			InetAddress address = InetAddress.ofLiteral(value);
			if (address instanceof Inet6Address) {
				return "v6:" + HexFormat.of().formatHex(Arrays.copyOf(address.getAddress(), IPV6_PREFIX_BYTES));
			}
			return address.getHostAddress();
		} catch (IllegalArgumentException invalid) {
			return UNKNOWN;
		}
	}
}
