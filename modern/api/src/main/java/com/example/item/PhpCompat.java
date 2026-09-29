package com.example.item;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 레거시 PHP 7.4 문자열 함수의 동작을 옮긴 도우미. 이관한 문항 검색이 레거시({@code search.php})와 같은 입력 해석을 하도록 쓴다.
 * Java 표준 함수({@code strip} · {@code length} · {@code toLowerCase} · {@code parseInt})는 경계에서 결과가 달라 따로 둔다.
 */
final class PhpCompat {

    /** PHP {@code trim()} 이 기본으로 지우는 문자: 공백, \t, \n, \r, \0, \x0B. 전각 공백 등은 지우지 않는다. */
    private static final String TRIM_CHARS = " \t\n\r\0\u000B";
    /** PHP 숫자 문자열 해석이 앞에서 건너뛰는 공백: 공백, \t, \n, \r, \v, \f. */
    private static final String LEADING_WHITESPACE = " \t\n\r\u000B\f";
    /** {@code (int)} 변환이 읽는 숫자 앞부분 — 부호, 정수부, 소수부, 지수부. 뒤에 붙은 글자는 버린다. */
    private static final Pattern NUMERIC_PREFIX =
        Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    private PhpCompat() {
    }

    /** PHP {@code trim($value)}. */
    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && TRIM_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** PHP {@code mb_strlen($value, 'UTF-8')} — UTF-16 단위가 아니라 코드포인트 수. */
    static int mbLength(String value) {
        return value.codePointCount(0, value.length());
    }

    /** PHP {@code mb_substr($value, 0, $length, 'UTF-8')} — 앞에서 코드포인트 {@code length} 개. */
    static String mbSubstr(String value, int length) {
        if (mbLength(value) <= length) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, length));
    }

    /** PHP {@code strtolower()}(PHP 7.4, C 로케일) — ASCII 대문자만 바꾼다. */
    static String asciiLower(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return out.toString();
    }

    /**
     * PHP 7.4 {@code (int)$value}. 앞 공백을 건너뛰고 숫자로 읽을 수 있는 앞부분만 쓴다.
     * 예: {@code "3abc"} → 3, {@code "2.9"} → 2, {@code "1e1"} → 10, {@code "abc"} → 0, {@code "0x1A"} → 0.
     * 범위를 넘으면 long 최댓값 · 최솟값으로 맞추고, 무한대는 0 이다(zend_dval_to_lval_cap). 오버플로 동작은 확인 필요.
     */
    static long intCast(String value) {
        int start = 0;
        while (start < value.length() && LEADING_WHITESPACE.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        Matcher matcher = NUMERIC_PREFIX.matcher(value).region(start, value.length());
        if (!matcher.lookingAt()) {
            return 0L;
        }
        String number = matcher.group();
        boolean integral = number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0;
        if (integral) {
            BigInteger parsed = new BigInteger(number);
            if (parsed.bitLength() < Long.SIZE) {
                return parsed.longValue();
            }
            return parsed.signum() > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return doubleToLongCapped(Double.parseDouble(number));
    }

    private static long doubleToLongCapped(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0L;
        }
        if (value >= (double) Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        if (value <= (double) Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        return (long) value;
    }
}
