package com.example.item;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 문항 검색(레거시 search.php 이관) 전용 저장소.
 * 레거시는 뷰 {@code v_item_public}(item JOIN unit WHERE status='A', 01-schema.sql:52-70)을 읽는다.
 * 여기서는 같은 조건을 엔티티로 쓴다. 각 조건은 파라미터가 null 이면 걸리지 않는다.
 */
public interface ItemSearchRepository extends Repository<Item, Integer> {

    /**
     * 검색 조건(모두 AND, BR-01). 키워드는 ESCAPE 없이 LIKE 로 비교한다(BR-04, {@code %} · {@code _} 가 와일드카드).
     * 난이도: {@code exactLevel} 이 null 이면 {@code level < defaultLevelUpperBound}(BR-07), 아니면 {@code level = exactLevel}.
     */
    String SEARCH_CONDITION = """
        where i.status = :status
          and (:keyword is null or i.title like :keyword or i.stem like :keyword)
          and (:unitCode is null or u.code = :unitCode)
          and ((:exactLevel is null and i.level < :defaultLevelUpperBound) or i.level = :exactLevel)
          and (:tagName is null or exists (
                select 1 from Item x join x.tags t where x.id = i.id and t.name = :tagName))
        """;

    /** 현재 페이지 행과 전체 건수. 정렬 · 페이지는 {@code pageable} 로 받는다. */
    @Query(
        value = "select i from Item i join fetch i.unit u " + SEARCH_CONDITION,
        countQuery = "select count(i) from Item i join i.unit u " + SEARCH_CONDITION)
    Page<Item> search(
        @Param("status") String status,
        @Param("keyword") String keyword,
        @Param("unitCode") String unitCode,
        @Param("exactLevel") Integer exactLevel,
        @Param("defaultLevelUpperBound") int defaultLevelUpperBound,
        @Param("tagName") String tagName,
        Pageable pageable);

    /** 문항들의 태그 이름. 문항 id 순, 같은 문항 안에서는 태그 id 순(BR-26, 01-schema.sql:64). */
    @Query("""
        select new com.example.item.ItemTagName(i.id, t.name)
        from Item i join i.tags t
        where i.id in :itemIds
        order by i.id asc, t.id asc
        """)
    List<ItemTagName> findTagNamesByItemIds(@Param("itemIds") Collection<Integer> itemIds);
}
