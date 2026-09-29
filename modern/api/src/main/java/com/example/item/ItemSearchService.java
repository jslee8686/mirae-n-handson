package com.example.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문항 검색 — 레거시 {@code legacy/item-bank-php/search.php} 의 이관.
 * 입력 보정 · 조건 · 정렬 · 페이지 규칙은 레거시 동작을 그대로 옮겼다(규칙 ID 는 docs/item-bank/BUSINESS-RULES.md).
 * 레거시의 입력값 경고 문구는 응답에 넣지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class ItemSearchService {

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    /** 키워드 최대 글자 수(BR-03, search.php:86). */
    static final int KEYWORD_MAX_LENGTH = 100;
    /** 태그 이름 최대 글자 수(BR-11, search.php:240). */
    static final int TAG_MAX_LENGTH = 50;
    /** 한 페이지 건수(BR-20, search.php:349 · 523). */
    static final int PAGE_SIZE = 20;
    /** 페이지 번호 상한(BR-21, search.php:345). */
    static final int MAX_PAGE = 999;
    /** 난이도를 비웠을 때의 조건 {@code level < 5} 의 경계(BR-07, search.php:215). 의도인지는 확인 필요. */
    static final int DEFAULT_LEVEL_UPPER_BOUND = 5;
    /** 전체 0건일 때 안내 문구(BR-22, search.php:658). */
    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";
    /** 기본 정렬 — 난이도 내림차순, id 오름차순(BR-13, search.php:319 · 325). */
    static final Sort DEFAULT_SORT = Sort.by(Order.desc("level"), Order.asc("id"));

    /** 난이도 1~5 한 자리(search.php:217). */
    private static final Pattern EXACT_LEVEL = Pattern.compile("[1-5]");
    /** 숫자로만 된 페이지 번호(search.php:337). PCRE 의 {@code $} 처럼 끝의 줄바꿈 하나를 허용한다. */
    private static final Pattern PAGE_DIGITS = Pattern.compile("[0-9]+\n?");

    private final ItemSearchRepository itemSearchRepository;

    public ItemSearchService(ItemSearchRepository itemSearchRepository) {
        this.itemSearchRepository = itemSearchRepository;
    }

    /** 검색. 없는 단원 · 범위 밖 난이도도 오류 없이 0건으로 응답한다. */
    public ItemSearchResponse search(ItemSearchRequest request) {
        ItemSearchCondition condition = toCondition(request);
        Page<Item> page = itemSearchRepository.search(
            ItemStatus.ACTIVE,
            condition.keywordPattern(),
            condition.unitCode(),
            condition.exactLevel(),
            DEFAULT_LEVEL_UPPER_BOUND,
            condition.tagName(),
            PageRequest.of(condition.page() - 1, PAGE_SIZE, condition.sort()));
        long total = page.getTotalElements();
        List<ItemSearchRowResponse> rows = toRows(page.getContent());
        log.debug("item search matched {} items, page {} has {} rows", total, condition.page(), rows.size());
        return new ItemSearchResponse(rows, total, total == 0 ? NO_RESULT_MESSAGE : null);
    }

    /** 요청 원문 → 보정한 조건. */
    static ItemSearchCondition toCondition(ItemSearchRequest request) {
        return new ItemSearchCondition(
            resolveKeywordPattern(request.q()),
            blankToNull(PhpCompat.trim(request.unit())),
            resolveExactLevel(request.level()),
            resolveTagName(request.tag()),
            resolveSort(request.sort(), request.dir()),
            resolvePage(request.page()));
    }

    /** 키워드: trim, 100자까지, 제목 · 지문 부분 일치 패턴(BR-03 · BR-04, search.php:85-97). */
    static String resolveKeywordPattern(String raw) {
        String keyword = PhpCompat.mbSubstr(PhpCompat.trim(raw), KEYWORD_MAX_LENGTH);
        return keyword.isEmpty() ? null : "%" + keyword + "%";
    }

    /**
     * 난이도: 비면 null(기본 조건 level &lt; 5), 1~5 한 자리면 그 값, 그 밖에는 PHP {@code (int)} 변환 값(BR-07 ~ BR-09).
     * int 범위를 넘는 값은 int 끝값으로 맞춘다. 난이도는 1~5 라 어느 쪽이든 0건이다.
     */
    static Integer resolveExactLevel(String raw) {
        String level = PhpCompat.trim(raw);
        if (level.isEmpty()) {
            return null;
        }
        if (EXACT_LEVEL.matcher(level).matches()) {
            return Integer.parseInt(level);
        }
        long cast = PhpCompat.intCast(level);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, cast));
    }

    /** 태그: trim, 50자까지(BR-10 · BR-11, search.php:235-242). */
    static String resolveTagName(String raw) {
        String tag = PhpCompat.trim(raw);
        if (tag.isEmpty()) {
            return null;
        }
        return PhpCompat.mbSubstr(tag, TAG_MAX_LENGTH);
    }

    /** 정렬(BR-13 ~ BR-18, search.php:266-327). 분기 구조는 레거시를 그대로 따른다. */
    static Sort resolveSort(String sortRaw, String dirRaw) {
        String sort = PhpCompat.asciiLower(PhpCompat.trim(sortRaw));
        String dir = PhpCompat.asciiLower(PhpCompat.trim(dirRaw));
        if (!dir.equals("asc") && !dir.equals("desc")) {
            dir = "";
        }
        boolean desc = dir.equals("desc");
        boolean asc = dir.equals("asc");
        return switch (sort) {
            case "id" -> desc ? Sort.by(Order.desc("id")) : Sort.by(Order.asc("id"));
            case "title" -> Sort.by(desc ? Order.desc("title") : Order.asc("title"), Order.asc("id"));
            case "unit" -> Sort.by(
                desc ? Order.desc("unit.code") : Order.asc("unit.code"), Order.desc("level"), Order.asc("id"));
            case "level" -> Sort.by(asc ? Order.asc("level") : Order.desc("level"), Order.asc("id"));
            case "created" -> Sort.by(asc ? Order.asc("createdAt") : Order.desc("createdAt"), Order.asc("id"));
            default -> DEFAULT_SORT;
        };
    }

    /** 페이지: 숫자만이면 그 값, 아니면 1. 1 미만은 1, 999 초과는 999(BR-21, search.php:336-348). trim 하지 않는다. */
    static int resolvePage(String raw) {
        long page = 1;
        if (PAGE_DIGITS.matcher(raw).matches()) {
            page = PhpCompat.intCast(raw);
        }
        if (page < 1) {
            page = 1;
        }
        if (page > MAX_PAGE) {
            page = MAX_PAGE;
        }
        return (int) page;
    }

    private List<ItemSearchRowResponse> toRows(List<Item> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = items.stream().map(Item::getId).toList();
        Map<Integer, List<String>> tagsByItem = new LinkedHashMap<>();
        for (ItemTagName tagName : itemSearchRepository.findTagNamesByItemIds(ids)) {
            tagsByItem.computeIfAbsent(tagName.itemId(), id -> new ArrayList<>()).add(tagName.tagName());
        }
        return items.stream()
            .map(item -> new ItemSearchRowResponse(
                item.getId(),
                item.getTitle(),
                item.getUnit().getCode(),
                item.getLevel(),
                List.copyOf(tagsByItem.getOrDefault(item.getId(), List.of()))))
            .toList();
    }

    private static String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
