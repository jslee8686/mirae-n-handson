// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// 규칙 ID(BR-xx)는 docs/item-bank/BUSINESS-RULES.md 를 따른다.
// 시드 근거: 단원 db/mariadb/init/02-seed.sql:10-14, 태그 :20-25, 문항 :38-71
//            (status='A' AND level<5 는 정확히 20건, 02-seed.sql:34)
//
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 모듈의 레거시 기본 주소).
// - 레거시와 새 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기를 두지 않는다.
// - 기대값을 손으로 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const SEARCH_PATH = '/search.php';

/** 키워드 최대 글자 수(BR-03, search.php:86) 바로 안쪽 · 바깥. 바이트가 아닌 글자 기준인지 보려고 멀티바이트를 쓴다. */
const KEYWORD_AT_LIMIT = '가'.repeat(100);
const KEYWORD_OVER_LIMIT = '가'.repeat(101);
/** 태그 최대 글자 수 50(BR-11, search.php:240)을 넘는 값. */
const TAG_OVER_LIMIT = '가'.repeat(60);

function search(params) {
  return fetchNormalized(MODULE, SEARCH_PATH, params);
}

describe('item-bank · 문항 검색(search.php)', () => {
  describe('정상', () => {
    it('단원 단독 필터 — M5-1 (BR-06 · BR-07 · BR-13 · BR-23: 난이도 5 · 검수중 제외, 기본 정렬)', async () => {
      expect(await search({ unit: 'M5-1' })).toMatchSnapshot();
    });

    it('키워드 + 난이도 + 제목 오름차순 — 분수 · 3 (BR-01 · BR-04 · BR-08 · BR-16 · BR-17)', async () => {
      expect(await search({ q: '분수', level: '3', sort: 'title', dir: 'asc' })).toMatchSnapshot();
    });

    it('태그 필터 + 단원 정렬 — 문장제 (BR-10 · BR-18 · BR-07 · BR-23)', async () => {
      expect(await search({ tag: '문장제', sort: 'unit' })).toMatchSnapshot();
    });
  });

  describe('경계값', () => {
    it('난이도 5 명시 — 난이도 5 가 나오는 유일한 경로 (BR-07 · BR-08)', async () => {
      expect(await search({ level: '5' })).toMatchSnapshot();
    });

    it('키워드 정확히 100자 — 잘라내기 경계 안쪽 (BR-03)', async () => {
      expect(await search({ q: KEYWORD_AT_LIMIT })).toMatchSnapshot();
    });

    it('키워드 101자 — 잘라내기 경계 바깥 (BR-03)', async () => {
      expect(await search({ q: KEYWORD_OVER_LIMIT })).toMatchSnapshot();
    });

    it('키워드 한 글자 — 경고만 하고 검색은 계속 (BR-05 · BR-04)', async () => {
      expect(await search({ q: '곱' })).toMatchSnapshot();
    });

    it('기본 목록 20건의 2페이지 — 행 0 이어도 빈 표 (BR-20 · BR-21 · BR-22)', async () => {
      expect(await search({ page: '2' })).toMatchSnapshot();
    });

    it('페이지 0 — 경고 없이 1페이지로 보정 (BR-21)', async () => {
      expect(await search({ page: '0' })).toMatchSnapshot();
    });

    it('페이지 1000 — 상한 999 로 보정 (BR-21 · BR-20)', async () => {
      expect(await search({ page: '1000' })).toMatchSnapshot();
    });

    it('태그 60자 — 50자로 자른 뒤 등록되지 않은 태그 (BR-11 · BR-12)', async () => {
      expect(await search({ tag: TAG_OVER_LIMIT })).toMatchSnapshot();
    });

    it('난이도 3abc — 정규식 밖 값을 정수로 바꿔 조회 (BR-09)', async () => {
      expect(await search({ level: '3abc' })).toMatchSnapshot();
    });
  });

  describe('빈 값 · 누락', () => {
    it('파라미터 없음 — 기본 목록 (BR-01 · BR-07 · BR-13 · BR-20 · BR-23)', async () => {
      expect(await search({})).toMatchSnapshot();
    });

    it('모든 파라미터 빈 값 · 공백 키워드 (BR-03 · BR-07 · BR-13 · BR-15 · BR-21)', async () => {
      const params = { q: '  ', unit: '', level: '', tag: '', sort: '', dir: '', page: '' };
      expect(await search(params)).toMatchSnapshot();
    });
  });

  describe('이상한 값', () => {
    it('음수 페이지 -1 — 경고 후 1페이지 (BR-21)', async () => {
      expect(await search({ page: '-1' })).toMatchSnapshot();
    });

    it('없는 단원 코드 X9-9 — 조회는 그대로, 0건 안내 (BR-06 · BR-22)', async () => {
      expect(await search({ unit: 'X9-9' })).toMatchSnapshot();
    });

    it('소문자 단원 코드 + 알 수 없는 정렬 · 방향 — m5-1 · DROP · up (BR-06 · BR-14 · BR-15)', async () => {
      expect(await search({ unit: 'm5-1', sort: 'DROP', dir: 'up' })).toMatchSnapshot();
    });
  });
});
