package com.example.item;

import org.springframework.data.domain.Sort;

/**
 * 보정을 마친 문항 검색 조건. {@link ItemSearchService} 가 요청 원문에서 만든다.
 *
 * @param keywordPattern LIKE 패턴({@code %키워드%}). 키워드가 비면 null
 * @param unitCode       단원 코드(입력값 그대로, 대소문자 변환 없음). 비면 null
 * @param exactLevel     같아야 할 난이도. null 이면 기본 난이도 조건(level &lt; 5)을 쓴다
 * @param tagName        태그 이름. 비면 null
 * @param sort           정렬
 * @param page           1 ~ 999 로 보정한 페이지 번호
 */
record ItemSearchCondition(
    String keywordPattern,
    String unitCode,
    Integer exactLevel,
    String tagName,
    Sort sort,
    int page) {
}
