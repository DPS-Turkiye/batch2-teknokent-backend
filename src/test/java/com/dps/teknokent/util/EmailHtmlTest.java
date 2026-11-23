package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailHtmlTest {

	@Test
	void keepsFormattingAndDropsEverythingThatCanRunOrLoad() {
		String clean = EmailHtml.sanitize("<p onclick=\"x()\">Merhaba <strong>Ayşe</strong></p><script>x()</script>"
				+ "<img src=\"https://track.test/p.gif\"><iframe src=\"https://x.test\"></iframe>"
				+ "<a href=\"javascript:x()\">a</a><a href=\"https://menton.com.tr\">b</a><style>p{}</style>");

		assertThat(clean).contains("<strong>Ay").contains("href=\"https://menton.com.tr\"")
				.doesNotContain("onclick", "script", "img", "iframe", "javascript", "style");
	}

	@Test
	void theTextTwinKeepsLinesListsAndLinkAddresses() {
		String clean = EmailHtml.sanitize("<p>Merhaba &amp; hoş geldin</p><ul><li>Bir</li><li>İki</li></ul>"
				+ "<p><a href=\"https://menton.com.tr/invite\">Kayıt ol</a></p>");

		assertThat(EmailHtml.toText(clean)).isEqualTo(
				"Merhaba & hoş geldin\n- Bir\n- İki\n\nKayıt ol (https://menton.com.tr/invite)");
	}

	@Test
	void aLinkThatShowsItsOwnAddressIsWrittenOnce() {
		assertThat(EmailHtml.toText(EmailHtml.sanitize("<a href=\"https://x.test\">https://x.test</a>")))
				.isEqualTo("https://x.test");
	}

	@Test
	void escapesANameForHtml() {
		assertThat(EmailHtml.escape("A & <b>")).isEqualTo("A &amp; &lt;b&gt;");
	}
}
