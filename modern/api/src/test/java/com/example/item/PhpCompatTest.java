package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class PhpCompatTest {

    @Test
    @DisplayName("trim: PHP 기본 문자(공백 · 탭 · 줄바꿈 · \\0 · \\x0B)만 지우고 전각 공백은 남긴다")
    void trimRemovesOnlyPhpDefaultChars() {
        assertThat(PhpCompat.trim(" \t\n\r\0\u000B분수 \n")).isEqualTo("분수");
        assertThat(PhpCompat.trim("　분수　")).isEqualTo("　분수　");
        assertThat(PhpCompat.trim("   ")).isEmpty();
    }

    @Test
    @DisplayName("mbLength · mbSubstr: UTF-16 단위가 아니라 코드포인트 기준")
    void mbSubstrCountsCodePoints() {
        String emoji = "😀";
        assertThat(PhpCompat.mbLength(emoji + "가")).isEqualTo(2);
        assertThat(PhpCompat.mbSubstr(emoji + emoji + "가", 2)).isEqualTo(emoji + emoji);
        assertThat(PhpCompat.mbSubstr("가".repeat(101), 100)).isEqualTo("가".repeat(100));
        assertThat(PhpCompat.mbSubstr("가나", 100)).isEqualTo("가나");
    }

    @Test
    @DisplayName("asciiLower: ASCII 대문자만 소문자로 바꾼다")
    void asciiLowerChangesAsciiOnly() {
        assertThat(PhpCompat.asciiLower("DROP Title")).isEqualTo("drop title");
        assertThat(PhpCompat.asciiLower("İD")).isEqualTo("İd");
    }

    @Test
    @DisplayName("intCast: 숫자로 읽을 수 있는 앞부분만 정수로 바꾼다(PHP 7.4 (int))")
    void intCastReadsNumericPrefix() {
        assertThat(PhpCompat.intCast("3abc")).isEqualTo(3L);
        assertThat(PhpCompat.intCast("2.9")).isEqualTo(2L);
        assertThat(PhpCompat.intCast("01")).isEqualTo(1L);
        assertThat(PhpCompat.intCast("1e1")).isEqualTo(10L);
        assertThat(PhpCompat.intCast("+5")).isEqualTo(5L);
        assertThat(PhpCompat.intCast("-1")).isEqualTo(-1L);
        assertThat(PhpCompat.intCast(" \f3")).isEqualTo(3L);
        assertThat(PhpCompat.intCast(".5")).isEqualTo(0L);
    }

    @Test
    @DisplayName("intCast: 숫자가 아니면 0, 범위를 넘으면 long 끝값")
    void intCastReturnsZeroOrCapped() {
        assertThat(PhpCompat.intCast("abc")).isZero();
        assertThat(PhpCompat.intCast("")).isZero();
        assertThat(PhpCompat.intCast("0x1A")).isZero();
        assertThat(PhpCompat.intCast("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
        assertThat(PhpCompat.intCast("-99999999999999999999")).isEqualTo(Long.MIN_VALUE);
        assertThat(PhpCompat.intCast("1e400")).isZero();
    }
}
