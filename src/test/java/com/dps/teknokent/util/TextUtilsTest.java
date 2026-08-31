package com.dps.teknokent.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextUtilsTest {

	@Test
	void normalizesOptionalAndComparisonText() {
		assertThat(TextUtils.normalizeOptional("  hello  ")).isEqualTo("hello");
		assertThat(TextUtils.normalizeOptional("   ")).isNull();
		assertThat(TextUtils.normalizeOptional(null)).isNull();
		assertThat(TextUtils.normalizeForComparison(" Next.js ")).isEqualTo("nextjs");
		assertThat(TextUtils.normalizeForComparison("İş Modeli")).isEqualTo("işmodeli");
	}

	@Test
	void convertsNullToEmptyString() {
		assertThat(TextUtils.nullToEmpty(null)).isEmpty();
		assertThat(TextUtils.nullToEmpty("value")).isEqualTo("value");
	}

    @Test
    void initials_takesTheFirstLetterOfTheFirstTwoWords() {
        assertThat(TextUtils.initials("Dr. Kemal Arslan")).isEqualTo("DK");
        assertThat(TextUtils.initials("leyla aksoy")).isEqualTo("LA");
        assertThat(TextUtils.initials("Cher")).isEqualTo("C");
        assertThat(TextUtils.initials("  Ece   Demir  ")).isEqualTo("ED");
    }

    @Test
    void initials_fallsBackWhenThereIsNoUsableName() {
        assertThat(TextUtils.initials(null)).isEqualTo("?");
        assertThat(TextUtils.initials("   ")).isEqualTo("?");
    }
}
