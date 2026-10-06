# 이관 검증 리포트 1 — `GET /api/items/search`

- 대상: `upstream/main...HEAD` (브랜치 `day1`, 커밋 3개: `910a2a3` · `cf2f524` · `698a0b0`)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관
- 기준 문서: `templates/approval-checklist.md` v0
- 검증 일시: 2026-10-06

## 판정: 승인 (조건부)

치명 이슈는 없다. 다만 아래 두 가지는 승인 전에 리뷰어가 확인해야 한다.

1. 레거시(:8081)가 꺼져 있어 레거시 대상 `characterization` 은 실행하지 않았다(새 API 대상만 실행).
2. `upstream/main` 기준 diff 에 이관 커밋(`698a0b0`) 외 선행 커밋의 파일이 섞여 있다. 선행 커밋이 이번 요청에 속하는지 리뷰어가 판단한다.

## 기준별 결과

| 기준 | 결과 | 근거 |
|---|---|---|
| 1. 테스트 통과 | 충족 (레거시 대상 미실행) | 아래 실행 결과 |
| 2. 치명 이슈 0건 | 충족 | 시크릿 리터럴 없음, 쿼리 전부 `:name` 바인딩, 삭제 · 덮어쓰기 코드 없음 |
| 3. 요청 범위 이탈 없음 | 충족 (경고 2건) | 이관 코드 · 테스트는 모두 범위 안. 문서 변경은 경고 W2 · W3 |
| 4. 컨벤션 준수 | 충족 | 아래 대조 |

### 1. 테스트 실행 결과

| 명령 | 결과 |
|---|---|
| `cd modern/api && ./gradlew test --rerun` | 62건 실행, 실패 0, 오류 0, 건너뜀 0 (BUILD SUCCESSFUL) |
| `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` | 30 통과 · 1 건너뜀 · 실패 0 |
| `cd characterization && npm test` (레거시 대상) | **실행하지 않음** — :8081 응답 없음. 레거시 기동 후 실행 필요 |

- 건너뛴 1건은 `characterization/tests/example-units.test.js:21` 의 `it.skipIf(legacyOnly)` 로, 새 API 대상에서는 원래 건너뛰는 예제 테스트다. 이번 diff 가 만든 skip 이 아니다.
- `item-bank.test.js` 의 이관 대상 케이스는 새 API(:8080)에서 모두 통과했다.
- 스냅샷(`__snapshots__/item-bank.test.js.snap`)과 테스트 코드는 베이스라인 커밋 `910a2a3` 이후 수정되지 않았다(`git log -- characterization` 로 확인).
- 이관 코드에 `@Disabled` · `skip` · `.only` 는 없다.
- 새 API 프로세스가 `HEAD` 빌드인지는 확인하지 못했다. 다만 `/api/items/search` 가 응답해 이관 코드를 서빙하고 있음은 확인했다.
- 테스트 충분성: 새 클래스 4개(Controller 7 · Repository 11 · Service 17 · PhpCompat 5 메서드)가 보정 규칙 · 정렬 · 페이지 · 0건 · 태그를 다룬다. 새 엔드포인트와 새 public Service 메서드의 테스트 요건을 만족한다.

### 4. 컨벤션 대조 (CLAUDE.md §2 · §3)

| 항목 | 결과 |
|---|---|
| Controller → Service → Repository, Controller 에 Repository · SQL · try-catch 없음 | 준수 (`ItemSearchController.java:17-27`) |
| DTO 는 record, 엔티티를 반환하지 않음 | 준수 (`ItemSearchResponse.java:9`, `ItemSearchRowResponse.java:9`) |
| `@Query` 파라미터 바인딩 | 준수 (`ItemSearchRepository.java:22-29`, `:45-50`) |
| `ItemStatus.ACTIVE` 사용, `'A'` 직접 사용 없음 | 준수 (`ItemSearchService.java:58`) |
| 조회 Service 에 `@Transactional(readOnly = true)` | 준수 (`ItemSearchService.java:23`) |
| 현재 시각 사용 없음(`Clock` 해당 없음) | 해당 없음 |
| 빈 catch · `System.out` · `printStackTrace` 없음 | 준수 (grep 0건) |
| 로그에 학생 식별자 · 이메일 · 토큰 없음 | 준수 (`ItemSearchService.java:67`, debug, 건수만) |
| 새 매직 넘버는 상수 | 준수 (`ItemSearchService.java:28-41`) |
| 메서드 100줄 이하, 줄 120자 이하, 와일드카드 import 없음 | 준수 |
| 테스트: `@ActiveProfiles("test")` | 준수 (4개 클래스 모두) |
| 테스트: Service 는 Mockito, Controller 는 `@WebMvcTest`, Repository 는 `@DataJpaTest`, `ItemFixtures` 사용 | 준수 |
| 의존성 · 스키마 · `application.yml` 변경 없음 | 준수 (`build.gradle` · `db/` · 설정 파일이 diff 에 없음) |
| 기존 파일 수정 없음 | 준수 (`modern/api` 변경은 전부 신규 파일) |

테스트 메서드 이름은 `<대상 메서드><기대 결과>` 형태다(예: `searchNoMatchReturns200WithMessage`). `@DisplayName` 이 모든 메서드에 붙었는지는 개수 집계만 했다(확인 필요).

## 이슈 목록

치명 0건.

### 경고

| ID | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|
| W1 | (실행 환경) | 레거시 `:8081` 이 꺼져 있어 `characterization` 을 레거시 대상으로 돌리지 못했다. 스냅샷이 현재 레거시와 일치하는지 이번에 확인하지 못했다. | `docker compose --profile php up -d` 후 `cd characterization && npm test` 를 실행해 결과를 덧붙인다. |
| W2 | `docs/item-bank/BUSINESS-RULES.md` (+8줄, 커밋 `698a0b0`) | 이관 커밋이 문서를 수정했다. 요청 문장("엔드포인트 1개 이관")에 문서 수정이 없고, CLAUDE.md §3 은 요청받지 않은 파일 수정을 금한다. 커밋 메시지의 "회고 반영"이 근거일 수 있다(확인 필요). | 요청에 포함된 변경이면 그대로 두고, 아니면 별도 커밋으로 분리한다. |
| W3 | `CLAUDE.md`, `.claude/skills/*`, `docs/assignment/*`, `docs/item-bank/*` (커밋 `cf2f524`) | `upstream/main...HEAD` 에 이관과 무관한 문서 · Skill 변경 1739줄이 포함된다. 이 요청 문장으로는 설명되지 않는다. | 리뷰어가 선행 커밋 포함 여부를 정한다. 이관만 검증하려면 `910a2a3..HEAD` 로 범위를 좁혀 다시 검증한다. |
| W4 | `ItemSearchService.java:20` | 레거시의 입력값 경고 문구를 응답에 넣지 않는다. 레거시와 응답이 다르다. 정규화 · 스냅샷은 통과했으나(`rows · count · message` 만 비교), 소비자가 경고 문구를 쓰는지는 확인 필요. | 의도한 생략이면 `docs/item-bank/BUSINESS-RULES.md` 에 명시하고, 아니면 응답에 포함할지 사람이 결정한다. |

### 제안

| ID | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|
| S1 | `ItemSearchRepository.java:33-34` | `"select ..." + SEARCH_CONDITION` 은 컴파일 상수끼리의 이어 붙이기라 SQL 주입 위험은 없고 값은 모두 바인딩이다. 다만 체크리스트 문구("이어 붙여 만드는 곳")와 겉모습이 같아 오해를 부른다. | 현행 유지 가능. 의도를 한 줄 주석으로 남기면 리뷰가 쉬워진다. |

### 확인 필요 (근거 줄번호 미확정 · 사람 판단)

- 요청에서 `name` 과 `name[]` 이 섞이거나 `name[0]` 이 올 때 레거시 동작 — 코드 주석이 이미 "확인 필요"로 남겼다(`ItemSearchRequest.java:37`).
- `PhpCompat.intCast` 오버플로 · 무한대 처리 — 코드 주석이 "확인 필요"로 남겼다(`PhpCompat.java:63`).
- 새 API 프로세스(:8080)가 `HEAD` 빌드인지 — 프로세스 시작 시각 · 빌드 해시를 확인하지 못했다.

### 의심 동작 (레거시 버그 가능성 — 이관 중에는 고치지 않음)

- 난이도를 비우면 `level < 5` 로 난이도 5 문항이 기본 검색에서 빠진다(`legacy/item-bank-php/search.php:215`, 새 코드 `ItemSearchService.java:36-37`). 의도인지 의미 확인 필요.
- 키워드 LIKE 가 ESCAPE 없이 `%` · `_` 를 와일드카드로 해석한다(`ItemSearchRepository.java:19`, BR-04).

## 요약

- 코드는 수정하지 않았다. 이 파일만 새로 만들었다.
- 승인 전 할 일: (1) 레거시 기동 후 `characterization` 레거시 대상 실행, (2) W2 · W3 의 범위 판단.
