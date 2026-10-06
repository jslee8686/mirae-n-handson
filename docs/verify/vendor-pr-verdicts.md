# 외주사 PR 판정 기록

승인 체크리스트(`templates/approval-checklist.md` v0) 기준으로 판정한 결과다. 한 PR 에 한 행을 추가한다.

| PR | 판정 | 미충족 기준 | 근거(파일:줄번호) | 외주사에 돌려보낼 수정 요청 |
|----|------|-------------|--------------------|------------------------------|
| pr-1 `단원별 문항 수 통계 API 추가` (`d18d048`) | 반려 | 1. 테스트 통과(테스트 충분성), 4. 컨벤션 준수(테스트 규칙) | `modern/api/src/main/java/com/example/item/UnitService.java:36` 새 public 메서드 `countActiveItemsByUnit()` 에 테스트 없음 · `modern/api/src/main/java/com/example/item/UnitController.java:26` 새 엔드포인트 `GET /api/units/item-counts` 에 테스트 없음 · 변경 3개 파일 모두 main 코드이고 `src/test` 변경 0건(`git diff --stat HEAD~1..HEAD`) | 1) `UnitServiceTest` 에 `countActiveItemsByUnit` 테스트 추가(`@ActiveProfiles("test")`, `@DisplayName` 한국어 설명): 단원 순서 유지, 문항 0개 단원이 0 으로 포함, `ItemStatus.ACTIVE` 로만 count 호출. 2) `UnitController` 용 `@WebMvcTest`(`UnitControllerTest`)로 `GET /api/units/item-counts` 200 · JSON 필드 검증. 3) (경고, 반려 사유 아님) 단원마다 count 쿼리를 한 번씩 보내는 N+1 구조(`UnitService.java:39`) — 그룹별 count 쿼리 하나로 줄일 수 있는지 검토. |

## pr-1 판정 상세

- 요청 범위: 단원별 문항 수 통계 API 추가 (`GET /api/units/item-counts`, `UnitService.countActiveItemsByUnit()`, `UnitItemCountResponse`).
- 테스트 실행: `cd modern/api && ./gradlew test` — 62건 실행, 통과 62, 실패 0, 오류 0, 건너뜀 0. 단, 통과한 테스트는 모두 기존 테스트이고 새 동작을 검증하는 테스트는 없다. 외주사 설명의 "기존 테스트 전부 통과"는 사실이지만 새 기능의 근거가 되지 못한다.

| 기준 | 결과 | 비고 |
|------|------|------|
| 테스트 통과 | 미충족 | 실행 결과는 통과이나 새 동작 테스트 0건 |
| 치명 이슈 0건 | 충족 | 비밀값 · SQL 문자열 결합 · 데이터 삭제 코드 없음. 쿼리는 Spring Data 파생 메서드(`ItemRepository.java:25`) |
| 요청 범위 이탈 없음 | 충족 | 변경 3개 파일(`UnitController`, `UnitItemCountResponse`, `UnitService`) 모두 요청 문장으로 설명됨. 의존성 · 스키마 · 설정 변경 없음 |
| 컨벤션 준수 | 미충족 | 새 public Service 메서드 · 새 엔드포인트마다 테스트 1개 이상 규칙(CLAUDE.md) 위반. 그 외는 준수: 클래스 `@Transactional(readOnly = true)`(`UnitService.java:10`), `ItemStatus.ACTIVE` 사용(`UnitService.java:39`), DTO record, Controller 는 Service 만 호출 |

### 이슈 목록

| 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|--------|-------------|------|-----------|
| 경고 | `UnitService.java:36` | 새 public Service 메서드에 테스트가 없다 | `UnitServiceTest` 에 케이스 추가 |
| 경고 | `UnitController.java:26` | 새 엔드포인트에 `@WebMvcTest` 가 없다(`src/test/java/com/example/item` 에 `UnitControllerTest` 없음) | 컨트롤러 슬라이스 테스트 추가 |
| 제안 | `UnitService.java:39` | 단원 수만큼 count 쿼리 실행(N+1) | 단원별 그룹 count 쿼리 1회로 조회 |

경고 2건은 CLAUDE.md 에서 "반드시" 요구하는 규칙이라 승인 기준 1 · 4 를 미충족으로 판정했다.
