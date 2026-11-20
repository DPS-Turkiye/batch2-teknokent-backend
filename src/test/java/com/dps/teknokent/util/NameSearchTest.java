package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class NameSearchTest {

	@Test
	void foldsCaseAndTurkishLettersToPlainLowercase() {
		assertThat(NameSearch.fold("  Çağrı   ÖZTÜRK ")).isEqualTo("cagri ozturk");
		assertThat(NameSearch.fold("İpek IŞIK")).isEqualTo("ipek isik");
		assertThat(NameSearch.fold("Şule Gülşen Kâmil")).isEqualTo("sule gulsen kamil");
		assertThat(NameSearch.fold(null)).isEmpty();
	}

	/** The same cases as the frontend's foldSearchText test, so the two folds cannot drift apart. */
	@Test
	void foldsLikeTheFrontend() {
		assertThat(NameSearch.fold("Çağrı\u00A0Yılmaz")).isEqualTo("cagri yilmaz");
		assertThat(NameSearch.fold("\u3000İLKER\u2003ışık\uFEFF")).isEqualTo("ilker isik");
		assertThat(NameSearch.fold("Gökçe\tÜNAL")).isEqualTo("gokce unal");
	}

	@Test
	void aQueryMatchesWhenEveryWordAppearsInTheNameWithOrWithoutTurkishLetters() {
		assertThat(NameSearch.matches("Çağrı Yılmaz", NameSearch.words("cagri"))).isTrue();
		assertThat(NameSearch.matches("Cagri Yilmaz", NameSearch.words("ÇAĞRI"))).isTrue();
		assertThat(NameSearch.matches("Çağrı Yılmaz", NameSearch.words("yıl çağ"))).isTrue();
		assertThat(NameSearch.matches("İrem Işık", NameSearch.words("irem isik"))).isTrue();
		assertThat(NameSearch.matches("Çağrı Yılmaz", NameSearch.words("çağrı demir"))).isFalse();
	}

	@Test
	void aBlankQueryOrAMissingNameMatchesNothing() {
		assertThat(NameSearch.words("   ")).isEmpty();
		assertThat(NameSearch.matches("Çağrı Yılmaz", List.of())).isFalse();
		assertThat(NameSearch.matches(null, NameSearch.words("cagri"))).isFalse();
	}
}
