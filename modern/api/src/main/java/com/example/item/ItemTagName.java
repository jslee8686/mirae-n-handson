package com.example.item;

/** 문항 id 와 태그 이름 한 쌍. {@link ItemSearchRepository#findTagNamesByItemIds} 의 조회 결과. */
public record ItemTagName(Integer itemId, String tagName) {
}
