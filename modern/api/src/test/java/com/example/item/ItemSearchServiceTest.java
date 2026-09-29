package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.test.context.ActiveProfiles;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    private static final ItemSearchRequest EMPTY_REQUEST = new ItemSearchRequest("", "", "", "", "", "", "1");

    @Mock
    private ItemSearchRepository itemSearchRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private final Unit unit = ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5);

    private static ItemSearchRequest request(String q, String unit, String level, String tag) {
        return new ItemSearchRequest(q, unit, level, tag, "", "", "1");
    }

    @Test
    @DisplayName("search: 행에는 id · 제목 · 단원 코드 · 난이도 · 태그(저장소 순서)를 담고 message 는 null")
    void searchMapsRowsWithTags() {
        Item hard = ItemFixtures.item(18, unit, "분수의 뺄셈 문장제", 4, ItemStatus.ACTIVE);
        Item easy = ItemFixtures.item(1, unit, "분모가 같은 분수의 덧셈", 1, ItemStatus.ACTIVE);
        PageRequest pageable = PageRequest.of(0, ItemSearchService.PAGE_SIZE, ItemSearchService.DEFAULT_SORT);
        when(itemSearchRepository.search(any(), any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(hard, easy), pageable, 2));
        when(itemSearchRepository.findTagNamesByItemIds(List.of(18, 1))).thenReturn(List.of(
            new ItemTagName(1, "계산"), new ItemTagName(1, "개념"),
            new ItemTagName(18, "문장제"), new ItemTagName(18, "오답률높음")));

        ItemSearchResponse response = itemSearchService.search(EMPTY_REQUEST);

        assertThat(response.count()).isEqualTo(2);
        assertThat(response.message()).isNull();
        assertThat(response.rows()).containsExactly(
            new ItemSearchRowResponse(18, "분수의 뺄셈 문장제", "M5-1", 4, List.of("문장제", "오답률높음")),
            new ItemSearchRowResponse(1, "분모가 같은 분수의 덧셈", "M5-1", 1, List.of("계산", "개념")));
    }

    @Test
    @DisplayName("search: 태그가 없는 문항은 빈 태그 목록")
    void searchUntaggedItemReturnsEmptyTags() {
        Item item = ItemFixtures.item(4, unit, "비의 뜻", 1, ItemStatus.ACTIVE);
        when(itemSearchRepository.search(any(), any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, ItemSearchService.PAGE_SIZE), 1));
        when(itemSearchRepository.findTagNamesByItemIds(List.of(4))).thenReturn(List.of());

        ItemSearchResponse response = itemSearchService.search(EMPTY_REQUEST);

        assertThat(response.rows().get(0).tags()).isEmpty();
    }

    @Test
    @DisplayName("search: 전체 0건이면 안내 문구, 오류 없이 빈 행(없는 단원 X9-9)")
    void searchNoMatchReturnsNoResultMessage() {
        when(itemSearchRepository.search(any(), any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, ItemSearchService.PAGE_SIZE), 0));

        ItemSearchResponse response = itemSearchService.search(request("", "X9-9", "", ""));

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemSearchRepository, never()).findTagNamesByItemIds(anyList());
    }

    @Test
    @DisplayName("search: 마지막 페이지를 넘으면 행은 비고 전체 건수는 그대로, message 는 null")
    void searchBeyondLastPageKeepsCount() {
        PageRequest second = PageRequest.of(1, ItemSearchService.PAGE_SIZE, ItemSearchService.DEFAULT_SORT);
        when(itemSearchRepository.search(any(), any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(), second, 20));

        ItemSearchResponse response = itemSearchService.search(new ItemSearchRequest("", "", "", "", "", "", "2"));

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isEqualTo(20);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("search: 조건이 없으면 공개 문항 · 기본 난이도 조건(5 미만) · 기본 정렬 · 1페이지로 묻는다")
    void searchWithoutConditionsQueriesDefaults() {
        when(itemSearchRepository.search(any(), any(), any(), any(), anyInt(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, ItemSearchService.PAGE_SIZE), 0));

        itemSearchService.search(EMPTY_REQUEST);

        verify(itemSearchRepository).search(
            ItemStatus.ACTIVE, null, null, null, ItemSearchService.DEFAULT_LEVEL_UPPER_BOUND, null,
            PageRequest.of(0, ItemSearchService.PAGE_SIZE, ItemSearchService.DEFAULT_SORT));
    }

    @Test
    @DisplayName("toCondition: 키워드 trim 후 %키워드%, 공백만이면 null")
    void toConditionWrapsTrimmedKeyword() {
        assertThat(ItemSearchService.toCondition(request("  분수 ", "", "", "")).keywordPattern())
            .isEqualTo("%분수%");
        assertThat(ItemSearchService.toCondition(request("  ", "", "", "")).keywordPattern()).isNull();
        assertThat(ItemSearchService.toCondition(request("50%_", "", "", "")).keywordPattern())
            .isEqualTo("%50%_%");
    }

    @Test
    @DisplayName("toCondition: 키워드는 100자까지, 101자는 100자로 자른다")
    void toConditionTruncatesKeywordAt100() {
        assertThat(ItemSearchService.toCondition(request("가".repeat(101), "", "", "")).keywordPattern())
            .isEqualTo("%" + "가".repeat(100) + "%");
        assertThat(ItemSearchService.toCondition(request("가".repeat(100), "", "", "")).keywordPattern())
            .isEqualTo("%" + "가".repeat(100) + "%");
    }

    @Test
    @DisplayName("toCondition: 단원 코드는 trim 만 하고 대소문자는 그대로")
    void toConditionKeepsUnitCase() {
        assertThat(ItemSearchService.toCondition(request("", " m5-1 ", "", "")).unitCode()).isEqualTo("m5-1");
        assertThat(ItemSearchService.toCondition(request("", "", "", "")).unitCode()).isNull();
    }

    @Test
    @DisplayName("toCondition: 태그는 trim 후 50자까지")
    void toConditionTruncatesTagAt50() {
        assertThat(ItemSearchService.toCondition(request("", "", "", "가".repeat(60))).tagName())
            .isEqualTo("가".repeat(50));
        assertThat(ItemSearchService.toCondition(request("", "", "", " 문장제 ")).tagName()).isEqualTo("문장제");
        assertThat(ItemSearchService.toCondition(request("", "", "", " ")).tagName()).isNull();
    }

    @Test
    @DisplayName("resolveExactLevel: 빈 값은 null, 1~5 는 그 값, 그 밖에는 PHP (int) 변환 값")
    void resolveExactLevelFollowsPhpCast() {
        assertThat(ItemSearchService.resolveExactLevel("")).isNull();
        assertThat(ItemSearchService.resolveExactLevel(" ")).isNull();
        assertThat(ItemSearchService.resolveExactLevel("5")).isEqualTo(5);
        assertThat(ItemSearchService.resolveExactLevel("3abc")).isEqualTo(3);
        assertThat(ItemSearchService.resolveExactLevel("9")).isEqualTo(9);
        assertThat(ItemSearchService.resolveExactLevel("abc")).isZero();
        assertThat(ItemSearchService.resolveExactLevel("99999999999")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("resolveSort: 비었거나 모르는 기준은 기본 정렬(level DESC, id ASC)")
    void resolveSortUnknownReturnsDefault() {
        assertThat(ItemSearchService.resolveSort("", "")).isEqualTo(ItemSearchService.DEFAULT_SORT);
        assertThat(ItemSearchService.resolveSort("DROP", "up")).isEqualTo(ItemSearchService.DEFAULT_SORT);
    }

    @Test
    @DisplayName("resolveSort: 방향이 없으면 id · title · unit 은 오름차순, level · created 는 내림차순")
    void resolveSortDefaultDirections() {
        assertThat(ItemSearchService.resolveSort("id", "")).isEqualTo(Sort.by(Order.asc("id")));
        assertThat(ItemSearchService.resolveSort("title", ""))
            .isEqualTo(Sort.by(Order.asc("title"), Order.asc("id")));
        assertThat(ItemSearchService.resolveSort("level", ""))
            .isEqualTo(Sort.by(Order.desc("level"), Order.asc("id")));
        assertThat(ItemSearchService.resolveSort("created", "sideways"))
            .isEqualTo(Sort.by(Order.desc("createdAt"), Order.asc("id")));
    }

    @Test
    @DisplayName("resolveSort: 대소문자 · 공백을 정리하고, 2차 id 는 항상 오름차순, unit 의 난이도는 항상 내림차순")
    void resolveSortExplicitDirections() {
        assertThat(ItemSearchService.resolveSort(" ID ", "DESC")).isEqualTo(Sort.by(Order.desc("id")));
        assertThat(ItemSearchService.resolveSort("title", "desc"))
            .isEqualTo(Sort.by(Order.desc("title"), Order.asc("id")));
        assertThat(ItemSearchService.resolveSort("unit", "desc"))
            .isEqualTo(Sort.by(Order.desc("unit.code"), Order.desc("level"), Order.asc("id")));
        assertThat(ItemSearchService.resolveSort("unit", "asc"))
            .isEqualTo(Sort.by(Order.asc("unit.code"), Order.desc("level"), Order.asc("id")));
        assertThat(ItemSearchService.resolveSort("level", "asc"))
            .isEqualTo(Sort.by(Order.asc("level"), Order.asc("id")));
        assertThat(ItemSearchService.resolveSort("created", "asc"))
            .isEqualTo(Sort.by(Order.asc("createdAt"), Order.asc("id")));
    }

    @Test
    @DisplayName("resolvePage: 숫자만이면 그 값, 1 미만은 1, 999 초과는 999, 나머지는 1")
    void resolvePageClampsLikeLegacy() {
        assertThat(ItemSearchService.resolvePage("2")).isEqualTo(2);
        assertThat(ItemSearchService.resolvePage("0")).isEqualTo(1);
        assertThat(ItemSearchService.resolvePage("-1")).isEqualTo(1);
        assertThat(ItemSearchService.resolvePage("1000")).isEqualTo(999);
        assertThat(ItemSearchService.resolvePage("99999999999999999999")).isEqualTo(999);
        assertThat(ItemSearchService.resolvePage("")).isEqualTo(1);
        assertThat(ItemSearchService.resolvePage("abc")).isEqualTo(1);
        assertThat(ItemSearchService.resolvePage(" 2")).isEqualTo(1);
        assertThat(ItemSearchService.resolvePage("2\n")).isEqualTo(2);
    }

    @Test
    @DisplayName("search: 범위 밖 난이도(9)도 오류 없이 저장소에 그대로 묻는다")
    void searchOutOfRangeLevelQueriesAsIs() {
        when(itemSearchRepository.search(any(), isNull(), isNull(), any(), anyInt(), isNull(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, ItemSearchService.PAGE_SIZE), 0));

        ItemSearchResponse response = itemSearchService.search(request("", "", "9", ""));

        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemSearchRepository).search(
            ItemStatus.ACTIVE, null, null, 9, ItemSearchService.DEFAULT_LEVEL_UPPER_BOUND, null,
            PageRequest.of(0, ItemSearchService.PAGE_SIZE, ItemSearchService.DEFAULT_SORT));
    }
}
