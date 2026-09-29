package com.example.item;

import java.util.List;

/**
 * {@code GET /api/items/search} 응답. 필드 이름은 동작 보존 테스트의 정규화 결과({@code rows · count · message})와 같다.
 * {@code count} 는 현재 페이지 행 수가 아니라 전체 건수, {@code message} 는 전체 0건일 때만 안내 문구이고 그 밖에는 null 이다.
 */
public record ItemSearchResponse(List<ItemSearchRowResponse> rows, long count, String message) {
}
