package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

/**
 * 저장소 슬라이스 — H2(MariaDB 모드)에 실제 JPQL 을 날린다.
 * H2 는 MariaDB 콜레이션(utf8mb4_unicode_ci, 대소문자 무시)과 달라 대소문자 일치 여부는 여기서 보지 않는다.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemSearchRepositoryTest {

    private static final int DEFAULT_BOUND = ItemSearchService.DEFAULT_LEVEL_UPPER_BOUND;
    private static final Pageable FIRST_PAGE =
        PageRequest.of(0, ItemSearchService.PAGE_SIZE, ItemSearchService.DEFAULT_SORT);

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemSearchRepository itemSearchRepository;

    private Integer wordProblemId;

    @BeforeEach
    void seed() {
        Unit fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimal = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));
        Tag hard = entityManager.persist(ItemFixtures.tag(4, "오답률높음"));

        entityManager.persist(ItemFixtures.item(null, fraction, "분수 덧셈", 2, ItemStatus.ACTIVE, calc));
        // 태그를 id 역순으로 붙여도 결과는 태그 id 순이어야 한다
        wordProblemId = entityManager.persist(
            ItemFixtures.item(null, fraction, "분수 문장제", 4, ItemStatus.ACTIVE, word, calc)).getId();
        entityManager.persist(ItemFixtures.item(null, decimal, "난이도 5 소수", 5, ItemStatus.ACTIVE, hard));
        entityManager.persist(ItemFixtures.item(null, fraction, "검수 중 분수", 3, ItemStatus.REVIEWING, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "삭제된 분수", 1, ItemStatus.DELETED));
        entityManager.persist(new Item(decimal, "소수 곱셈", "분수를 소수로 바꾸어 곱하시오.", 3,
            ItemStatus.ACTIVE, ItemFixtures.SEED_TIME));
        entityManager.flush();
        entityManager.clear();
    }

    private Page<Item> search(String keyword, String unitCode, Integer exactLevel, String tagName, Pageable pageable) {
        return itemSearchRepository.search(
            ItemStatus.ACTIVE, keyword, unitCode, exactLevel, DEFAULT_BOUND, tagName, pageable);
    }

    private static List<String> titles(Page<Item> page) {
        return page.getContent().stream().map(Item::getTitle).toList();
    }

    @Test
    @DisplayName("search: 조건이 없으면 공개 문항 중 난이도 5 를 빼고, 난이도 내림차순 · id 오름차순")
    void searchDefaultExcludesLevel5AndInactive() {
        Page<Item> page = search(null, null, null, null, FIRST_PAGE);

        assertThat(titles(page)).containsExactly("분수 문장제", "소수 곱셈", "분수 덧셈");
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("search: 난이도 5 를 직접 주면 난이도 5 만 나온다")
    void searchExactLevel5ReturnsLevel5Only() {
        assertThat(titles(search(null, null, 5, null, FIRST_PAGE))).containsExactly("난이도 5 소수");
    }

    @Test
    @DisplayName("search: 범위 밖 난이도는 오류 없이 0건")
    void searchOutOfRangeLevelReturnsEmpty() {
        Page<Item> page = search(null, null, 9, null, FIRST_PAGE);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("search: 키워드는 제목 또는 지문에 포함되면 찾는다")
    void searchKeywordMatchesTitleOrStem() {
        assertThat(titles(search("%분수%", null, null, null, FIRST_PAGE)))
            .containsExactly("분수 문장제", "소수 곱셈", "분수 덧셈");
    }

    @Test
    @DisplayName("search: 키워드의 _ 는 이스케이프하지 않아 와일드카드로 동작한다")
    void searchKeywordUnderscoreIsWildcard() {
        assertThat(titles(search("%분_ 덧%", null, null, null, FIRST_PAGE))).containsExactly("분수 덧셈");
    }

    @Test
    @DisplayName("search: 단원 코드 일치 + 기본 난이도 조건이 AND 로 걸린다")
    void searchByUnitCodeCombinesWithDefaultLevel() {
        assertThat(titles(search(null, "M5-3", null, null, FIRST_PAGE))).containsExactly("소수 곱셈");
        assertThat(search(null, "X9-9", null, null, FIRST_PAGE).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("search: 태그 이름이 정확히 같은 태그가 붙은 문항만, 없는 태그는 0건")
    void searchByTagNameMatchesExactly() {
        assertThat(titles(search(null, null, null, "계산", FIRST_PAGE))).containsExactly("분수 문장제", "분수 덧셈");
        assertThat(search(null, null, null, "계", FIRST_PAGE).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("search: 단원 정렬은 단원 코드, 난이도 내림차순, id 오름차순")
    void searchSortsByUnitThenLevelDesc() {
        Sort byUnit = ItemSearchService.resolveSort("unit", "");

        assertThat(titles(search(null, null, null, null, PageRequest.of(0, ItemSearchService.PAGE_SIZE, byUnit))))
            .containsExactly("분수 문장제", "분수 덧셈", "소수 곱셈");
    }

    @Test
    @DisplayName("search: 제목 · 작성 시각 정렬도 실행된다")
    void searchSortsByTitleAndCreated() {
        Sort byTitle = ItemSearchService.resolveSort("title", "desc");
        Sort byCreated = ItemSearchService.resolveSort("created", "");

        assertThat(titles(search(null, null, null, null, PageRequest.of(0, ItemSearchService.PAGE_SIZE, byTitle))))
            .containsExactly("소수 곱셈", "분수 문장제", "분수 덧셈");
        assertThat(search(null, null, null, null, PageRequest.of(0, ItemSearchService.PAGE_SIZE, byCreated))
            .getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("search: 마지막 페이지를 넘으면 행은 비고 전체 건수는 그대로")
    void searchBeyondLastPageKeepsTotal() {
        Page<Item> second = search(null, null, null, null, PageRequest.of(1, 2, ItemSearchService.DEFAULT_SORT));
        Page<Item> far = search(null, null, null, null, PageRequest.of(998, 2, ItemSearchService.DEFAULT_SORT));

        assertThat(titles(second)).containsExactly("분수 덧셈");
        assertThat(second.getTotalElements()).isEqualTo(3);
        assertThat(far.getContent()).isEmpty();
        assertThat(far.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("findTagNamesByItemIds: 문항 id 순, 같은 문항 안에서는 태그 id 순")
    void findTagNamesByItemIdsOrdersByTagId() {
        List<ItemTagName> tags = itemSearchRepository.findTagNamesByItemIds(List.of(wordProblemId));

        assertThat(tags).containsExactly(
            new ItemTagName(wordProblemId, "계산"), new ItemTagName(wordProblemId, "문장제"));
    }
}
