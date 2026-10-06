# 이관 검증 리포트 2 — `GET /api/items/search`

- 대상: `upstream/main...HEAD` (브랜치 `day1`, 커밋 5개 · 26개 파일 · +4220줄)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관
- 기준 문서: `templates/approval-checklist.md` v0
- 검증 일시: 2026-10-06 (HEAD `17bf8f1`)
- 이전 리포트: `docs/verify/migration-review-1.md` (조건부 승인). 이번에는 리포트 1 의 미실행 항목(레거시 대상 `characterization`)을 실행했다.

## 판정: 승인 (조건부)

치명 이슈는 없다. 테스트는 레거시 · 새 API 양쪽 모두 통과했다. 조건은 하나다.

- `upstream/main` 기준 diff 에 이관과 무관한 파일(`CLAUDE.md` · Skill · `docs/`)이 섞여 있다. 요청 문장만으로는 설명되지 않아 리뷰어가 포함 여부를 판단해야 한다(W1).

## 기준별 결과

| 기준 | 결과 | 근거 |
|---|---|---|
| 1. 테스트 통과 | 충족 | 아래 실행 결과 — 실패 0, 오류 0 |
| 2. 치명 이슈 0건 | 충족 | 시크릿 리터럴 없음, 쿼리 전부 `:name` 바인딩, 삭제 · 덮어쓰기 코드 없음 |
| 3. 요청 범위 이탈 없음 | 충족 (경고 1건) | `modern/api` 변경은 신규 파일 13개뿐. 문서 · Skill 은 W1 |
| 4. 컨벤션 준수 | 충족 | 아래 대조 |

### 1. 테스트 실행 결과

| 명령 | 결과 |
|---|---|
| `cd modern/api && ./gradlew test --rerun` | 62건 실행, 통과 62, 실패 0, 오류 0, 건너뜀 0 (BUILD SUCCESSFUL) |
| `cd characterization && npm test` (레거시 :8081) | 3개 파일 통과, 31 통과 · 실패 0 · 건너뜀 0 |
| `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` (새 API) | 2개 파일 통과 · 1개 건너뜀, 30 통과 · 1 건너뜀 · 실패 0 |

- 새 API 대상의 건너뜀 1건은 `characterization/tests/example-units.test.js` 의 레거시 전용 예제 테스트다. 이번 diff 가 만든 skip 이 아니다(리포트 1 과 동일, 줄번호는 이번에 다시 확인하지 않음 — 확인 필요).
- 레거시와 새 API 가 같은 스냅샷을 통과하므로 `rows · count · message` 기준 동작 보존이 확인됐다. 리포트 1 의 W1(레거시 미실행)은 해소됐다.
- 스냅샷과 테스트 코드는 베이스라인 `910a2a3` 이후 수정되지 않았다(`git diff 910a2a3..HEAD --stat -- characterization` 결과 없음).
- 이관 코드와 테스트에 `@Disabled` · `.skip` · `.only` 없음(grep 0건).
- 새 API(:8080) 프로세스는 2026-10-06 12:26 에 시작했고, 이관 커밋(`698a0b0`, 09-29)보다 뒤다. HEAD 의 `modern/api` 코드를 서빙한다고 볼 수 있다. 이후 커밋 `17bf8f1` 은 문서만 추가했다.
- 테스트 충분성: 새 클래스 4개에 `@Test` 가 Controller 7 · Repository 11 · Service 15 · PhpCompat 5개, 합 38개다. 새 엔드포인트와 새 public Service 메서드의 테스트 요건을 만족한다. 리포트 1 은 Service 를 17개로 적었으나 `@Test` 는 15개다(리포트 1 의 집계 오류로 보임).

### 4. 컨벤션 대조 (CLAUDE.md §2 · §3)

| 항목 | 결과 |
|---|---|
| Controller → Service → Repository, Controller 에 Repository · SQL · try-catch 없음 | 준수 (`ItemSearchController.java:17-27`) |
| DTO 는 record, 엔티티를 반환하지 않음 | 준수 (`ItemSearchResponse.java:9`, `ItemSearchRowResponse.java:9`) |
| `@Query` 파라미터 바인딩 | 준수 (`ItemSearchRepository.java:22-29`, `:45-50`) |
| `ItemStatus.ACTIVE` 사용, `'A'` 직접 사용 없음 | 준수 (`ItemSearchService.java:58`) |
| 조회 Service 에 `@Transactional(readOnly = true)` | 준수 (`ItemSearchService.java:23`) |
| `Clock` · 현재 시각 | 해당 없음 |
| 빈 catch · `System.out` · `printStackTrace` 없음 | 준수 (grep 0건) |
| 로그에 학생 식별자 · 이메일 · 토큰 없음 | 준수 (`ItemSearchService.java:67`, 건수만) |
| 새 매직 넘버는 상수 | 준수 (`ItemSearchService.java:28-41`). `PhpCompat.java:14-16` 의 문자 집합도 이름 붙은 상수 |
| 메서드 100줄 이하, 와일드카드 import 없음 | 준수 |
| 테스트: `@ActiveProfiles("test")`, `@DisplayName` | 준수 (4개 클래스 모두 `@ActiveProfiles` 1개, `@Test` 수와 `@DisplayName` 수 일치) |
| 테스트 종류: Service 는 Mockito, Controller 는 `@WebMvcTest`, Repository 는 `@DataJpaTest` | 준수로 판단. 어노테이션을 줄 단위로 대조하지는 않음 (확인 필요) |
| 의존성 · 스키마 · `application.yml` 변경 없음 | 준수 (`build.gradle` · `db/` · 설정 · `package.json` 이 diff 에 없음) |
| 기존 파일 수정 없음 | 준수 (`modern/api` 는 전부 신규) |
| 이관 중 레거시 버그를 고치지 않음 | 준수. 의심 동작은 코드 주석에 보존됨(`ItemSearchService.java:36`) |

## 이슈 목록

치명 0건.

### 경고

| ID | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|
| W1 | `CLAUDE.md`, `.claude/skills/convention-check/SKILL.md`, `.claude/skills/document-module/SKILL.md`, `docs/assignment/*`, `docs/item-bank/*`, `docs/verify/migration-review-1.md` | 기준이 `upstream/main`(`1bb4f2f`)이라 선행 커밋 `cf2f524` · `910a2a3` 와 리뷰 반영 커밋 `17bf8f1` 의 문서 · Skill · 테스트 베이스라인이 diff 에 포함된다. "엔드포인트 1개 이관" 이라는 요청 문장으로는 설명되지 않는다. 줄 단위 근거는 해당 없음(파일 전체 신규). | 이관 자체만 검증하려면 `910a2a3..HEAD` 로 범위를 좁혀 재검증한다. 선행 커밋을 포함한 PR 이라면 PR 설명에 이유를 적는다. |
| W2 | `ItemSearchService.java:20` | 레거시의 입력값 경고 문구를 응답에 넣지 않는다. 정규화 비교(`rows · count · message`)는 통과했지만, 레거시 HTML 의 경고 문구는 비교 대상이 아니다. 소비자가 문구를 쓰는지 확인 필요. | 의도한 생략이면 `docs/item-bank/BUSINESS-RULES.md` 에 명시한다. 아니면 포함할지 사람이 정한다. |
| W3 | (실행 환경) | `characterization` 은 `rows · count · message` 정규화 결과만 비교한다. 정규화 밖의 응답 차이(예: HTTP 헤더, 필드 순서)는 검증 범위 밖이다. | 소비자가 의존하는 항목이 있으면 확인 필요로 남기고, 있으면 그 항목을 베이스라인에 추가할지 사람이 정한다. 스냅샷은 이 검증에서 수정하지 않는다. |

### 제안

| ID | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|
| S1 | `ItemSearchRepository.java:33-34` | `"select ..." + SEARCH_CONDITION` 은 컴파일 상수끼리의 이어 붙이기라 주입 위험이 없고 값은 모두 바인딩이다. 체크리스트 문구("이어 붙여 만드는 곳")와 겉모습이 같아 오해를 부른다. | 현행 유지 가능. 의도를 한 줄 주석으로 남기면 리뷰가 쉬워진다. |
| S2 | `docs/verify/migration-review-1.md` | Service 테스트 수를 17개로 적었으나 `@Test` 는 15개다. | 다음 정리 때 수치를 바로잡는다(이 검증에서는 수정하지 않음). |

### 확인 필요 (근거 줄번호 미확정 · 사람 판단)

- 요청에서 `name` 과 `name[]` 이 섞이거나 `name[0]` 이 올 때 레거시 동작 — 코드 주석이 이미 "확인 필요"로 남겼다(`ItemSearchRequest.java:37`).
- `PhpCompat.intCast` 오버플로 · 무한대 처리 — 코드 주석이 "확인 필요"로 남겼다(`PhpCompat.java:63`).
- 테스트 클래스별 슬라이스 어노테이션(`@WebMvcTest` · `@DataJpaTest` · `@ExtendWith(MockitoExtension.class)`) 일치 여부 — 줄 단위로 대조하지 않았다.

### 의심 동작 (레거시 버그 가능성 — 이관 중에는 고치지 않음)

- 난이도를 비우면 `level < 5` 로 난이도 5 문항이 기본 검색에서 빠진다(`legacy/item-bank-php/search.php:215`, 새 코드 `ItemSearchService.java:36-37`). 의도인지 의미 확인 필요.
- 키워드 LIKE 가 ESCAPE 없이 `%` · `_` 를 와일드카드로 해석한다(`ItemSearchRepository.java:19`, BR-04).

## 요약

- 코드는 수정하지 않았다. 이 파일만 새로 만들었다.
- 승인 전 할 일: W1 의 범위 판단(선행 커밋 포함 여부). 필요하면 `910a2a3..HEAD` 로 재검증한다.
