package com.dps.teknokent.service;

/** Reads what SES has to say about mails already sent; one implementation per {@code EmailBounceSource}. */
interface EmailBounceReader {

	void read();

	/** SES answers about one such question a second, far fewer than the mails it takes. */
	default boolean asksPerAddress() {
		return false;
	}

	/** Whether SES is known not to deliver to the address, so that a mail would vanish without any report. */
	default boolean refuses(String address) {
		return false;
	}
}
