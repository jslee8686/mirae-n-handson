package com.example.item;

import java.util.List;
import org.springframework.util.MultiValueMap;

/**
 * {@code GET /api/items/search} 요청. 레거시 {@code search.php} 와 같은 파라미터 이름을 쓰고, 값은 보정 전 원문 그대로 담는다.
 * 보정(trim · 자르기 · 정수 변환)은 {@link ItemSearchService} 가 한다.
 */
public record ItemSearchRequest(
    String q,
    String unit,
    String level,
    String tag,
    String sort,
    String dir,
    String page) {

    /** 페이지 파라미터가 없을 때의 원문 값(search.php:77). */
    static final String DEFAULT_PAGE = "1";

    /** 쿼리 파라미터 → 요청. 없는 파라미터는 빈 문자열(page 만 "1")로 둔다(search.php:53-80). */
    public static ItemSearchRequest from(MultiValueMap<String, String> params) {
        return new ItemSearchRequest(
            phpParam(params, "q", ""),
            phpParam(params, "unit", ""),
            phpParam(params, "level", ""),
            phpParam(params, "tag", ""),
            phpParam(params, "sort", ""),
            phpParam(params, "dir", ""),
            phpParam(params, "page", DEFAULT_PAGE));
    }

    /**
     * PHP {@code $_GET} 해석을 따른다(BR-02).
     * {@code name=a&name=b} 는 뒤 값이 앞 값을 덮어 마지막 값, {@code name[]=a&name[]=b} 는 배열이 되어 첫 값을 쓴다.
     * 두 형태가 섞이거나 {@code name[0]} 같은 인덱스 키가 오는 경우의 레거시 동작은 확인 필요 — 여기서는 {@code name} 을 먼저 본다.
     */
    private static String phpParam(MultiValueMap<String, String> params, String name, String fallback) {
        List<String> scalar = params.get(name);
        if (scalar != null && !scalar.isEmpty()) {
            return scalar.get(scalar.size() - 1);
        }
        List<String> array = params.get(name + "[]");
        if (array != null && !array.isEmpty()) {
            return array.get(0);
        }
        return fallback;
    }
}
