# CLAUDE.md

Claude Code 심화 과정(4회차) 실습 저장소다. 코드 · 데이터 · 로그는 모두 더미 에듀테크 도메인(문항 은행 · 과제 배포 · 성적 집계)이다.

- 답변과 문서는 한국어로 쓴다.
- `legacy/` 는 분석 · 이관 대상이며, 허락 없이 수정하지 않는다.

## 1. 빌드 · 테스트 명령

```bash
# modern/api — Spring Boot 3.3 · Java 21 · Gradle wrapper
cd modern/api && ./gradlew test           # 단위 · 슬라이스 테스트. DB 없이 통과해야 한다 (테스트 프로필은 H2)
cd modern/api && ./gradlew test --tests com.example.item.ItemServiceTest   # 단일 테스트 클래스
cd modern/api && ./gradlew build          # 테스트 포함 전체 빌드
cd modern/api && ./gradlew bootRun        # :8080. 먼저 docker compose --profile modern up -d 로 DB 를 띄운다
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/   # 기동 확인 (200)

# modern/web — React 18 · TS · Vite
cd modern/web && npm ci
cd modern/web && npm run lint && npm run typecheck && npm test
cd modern/web && npx vitest run src/components/ItemTable.test.tsx          # 단일 테스트 파일
cd modern/web && npm run dev              # :5173
```

- 테스트 설정은 `modern/api/src/test/resources/application-test.yml`(H2, MariaDB 모드)이다. 테스트가 실제 MariaDB 에 붙게 만들지 않는다.
- 작업을 끝났다고 보고하기 전에 아래를 모두 만족한다.
  - 바꾼 쪽(api · web)의 테스트 명령을 실행하고, 통과 · 실패 수를 답변에 적었다. 실행하지 않았으면 "실행하지 않음"이라고 적었다.
  - 바꾼 동작마다 테스트를 추가하거나 수정했다.
  - 변경 파일이 요청 범위 안에 있다. 범위 밖 파일을 고쳤다면 이유를 적었다.
  - 스키마 · 의존성 · 설정 변경이 없거나, 있었다면 먼저 승인받았다.

### 완료 기준 — 이관 · 리팩토링

- `characterization` 의 `npm test` 가 전부 통과하기 전에는 이관 · 리팩토링 작업을 완료했다고 보고하지 않는다.
- 테스트가 실패하면 실패한 케이스와 차이를 그대로 보고한다. 요약해서 "거의 됐다"고 말하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 멈추고 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않는다. "의심 동작" 목록으로 따로 보고한다.

## 2. 코딩 컨벤션

### 공통
- 새로 쓰는 메서드 · 함수는 100줄 이하로 쓴다.
- 새로 넣는 매직 넘버는 이름 붙인 상수(`UPPER_SNAKE_CASE`)로 선언한다. 기존 코드의 매직 넘버는 의미를 추측하지 말고 "의미 확인 필요"로 보고한다.
- 코드 동작을 설명하거나 레거시 규칙을 옮길 때는 근거로 `파일:줄번호` 를 붙인다(예: `legacy/item-bank-php/search.php:214`). 근거를 댈 수 없으면 "확인 필요"라고 적는다.
- 주석과 코드가 다르면 코드를 기준으로 설명하고, 다르다는 사실을 답변에 적는다.

### modern/api — 계층
- 패키지는 도메인 단위(`item`, `assignment`)로 나누고, 공통 코드는 `common`, 설정은 `config` 에 둔다.
- 호출 방향은 Controller → Service → Repository 다. Controller 는 Repository 를 주입받지 않고 SQL 문자열을 실행하지 않는다.
- Service 가 다른 도메인의 데이터를 쓸 때는 그 도메인의 Service 를 주입받는다. 다른 도메인의 Repository 를 주입받지 않는다.
- 요청 · 응답 DTO 는 record 로 만든다(응답은 `*Response`, 요청은 `*Request`). 엔티티를 Controller 반환 타입으로 쓰지 않는다.
- 새 조회 API 는 Controller · Service · Repository 와 그 테스트를 같은 도메인 패키지에 만든다.
- `@Query` 와 JPQL · 네이티브 쿼리에는 파라미터 바인딩(`:name`)을 쓴다. 쿼리 문자열에 변수를 이어 붙이지 않는다.
- Service 계층에서 현재 시각은 주입받은 `Clock` 빈(`ClockConfig`)으로 구한다. `now()` 를 인자 없이 호출하지 않는다.
- 공개 문항 여부는 `ItemStatus.ACTIVE` 로 판단한다. 문자열 `'A'` 를 코드에 직접 쓰지 않는다.
- 조회 전용 Service 메서드에는 `@Transactional(readOnly = true)` 를 붙인다.

### modern/api — 예외 · 로깅
- 빈 `catch` 블록을 두지 않는다. `catch` 에서는 예외를 다시 던지거나 도메인 예외로 감싸 던진다.
- 예외를 HTTP 응답으로 바꾸는 코드는 `common/GlobalExceptionHandler`(`@RestControllerAdvice`)에만 둔다. Controller 메서드에 `try-catch` 를 넣지 않는다.
  - `NotFoundException` → 404, 검증 실패 · `IllegalArgumentException` → 400, `IllegalStateException` → 409, 그 밖의 예외 → 500
- 로그는 SLF4J(`LoggerFactory.getLogger`)로만 남긴다. `System.out` · `System.err` · `printStackTrace()` 를 쓰지 않는다.
- 로그 인자에 학생 식별자(`STU-…`) · 이메일 · 토큰 값을 넣지 않는다.
- 로그 레벨: `GlobalExceptionHandler` 에서 400 · 404 는 `INFO`, 409 는 `WARN`, 500 은 `ERROR` 로 남긴다. Service 의 정상 흐름은 `INFO` 이하로 남긴다.

### modern/api — 이름 · 형식
- 클래스는 `PascalCase`, 메서드 · 필드는 `camelCase`, 상수는 `UPPER_SNAKE_CASE` 로 짓는다. 약어도 첫 글자만 대문자로 쓴다(`ItemDto`).
- 들여쓰기는 4칸, 한 줄은 120자 이내로 쓴다. 와일드카드 import(`.*`)를 쓰지 않는다.

### modern/api — 테스트
- 새 public Service 메서드와 새 엔드포인트마다 테스트를 1개 이상 둔다.
- 모든 테스트 클래스에 `@ActiveProfiles("test")` 를 붙인다.
- Service 는 `@ExtendWith(MockitoExtension.class)` 단위 테스트, Controller 는 `@WebMvcTest`, Repository 쿼리는 `@DataJpaTest` 로 쓴다.
- 문항 픽스처는 `ItemFixtures` 를 쓴다. 시각에 의존하는 테스트는 `Clock.fixed(...)` 로 시각을 고정한다.
- 테스트 메서드 이름은 `<대상 메서드><기대 결과>` 형태의 camelCase(예: `getItemMissingReturns404`, `redistributeRejectsClosedAssignment`)로 짓고, `@DisplayName` 에 한국어 설명을 붙인다.

### modern/web
- 모든 HTTP 요청은 `src/api/client.ts` 의 `getJson` 을 거친다. 이 파일 밖에서 `fetch` 를 직접 호출하지 않는다.
- 조회 상태는 `src/hooks/useApiQuery.ts` 로 관리하고, 도메인 훅(`useUnits`, `useItem` …)은 이 훅을 감싼다. 컴포넌트에서 `useEffect` 로 직접 fetch 하지 않는다.
- 컴포넌트는 `src/components/<이름>.tsx` 파일로 두고, 테스트는 같은 폴더의 `<이름>.test.tsx` 에 둔다.
- 테스트에서 `fetch` 는 `src/test/mockFetch.ts` 로 가짜로 바꾼다. msw 를 추가하지 않는다.
- `any` 를 쓰지 않는다. 타입만 가져올 때는 `import type` 을 쓴다(ESLint 가 오류로 잡는다).
- `dangerouslySetInnerHTML` 을 쓰지 않는다.
- API 주소는 `VITE_API_BASE` 로 정한다. `localhost:8080` 은 `src/api/client.ts` 의 기본값과 `vite.config.ts` 프록시에만 쓴다.

## 3. 금지 사항

- DB 스키마(테이블 · 컬럼 · 인덱스)와 시드(`db/mariadb/init/`, `db/mssql/init/`)를 바꾸기 전에 먼저 묻는다.
- 의존성(`build.gradle` 의 `dependencies`, `package.json`)을 바꾸기 전에 먼저 묻고, 이유와 대안을 함께 적는다.
- 의존성은 `npm ci` 로 설치한다. `npm install` 로 `package-lock.json` 을 바꾸지 않는다.
- `application.yml` 의 DB 접속 정보와 커넥션 풀 설정을 바꾸지 않는다.
- 비밀값(운영 계정 · 토큰 · 키)을 코드나 설정 파일에 리터럴로 넣지 않는다. 실습용 더미 값(`application.yml` 의 `app-pass`)은 예외다.
- 운영 DB 호스트(`prod-db` 등)에 접속하는 명령 · 설정을 만들지 않는다. 로컬 compose 의 DB 만 쓰고, 조회는 읽기 전용 계정으로 한다.
- `@Transactional` 메서드 안에서 외부 HTTP 호출(`RestTemplate` · `WebClient` · `HttpClient`)을 하지 않는다. Repository 호출이 끝난 뒤의 계산(해시 · 서명 · 직렬화)은 트랜잭션 밖 메서드에서 한다. 커넥션 풀이 max 5 다(`ReportService.buildClassReport` 의 서명 루프는 `incident-logs/a-connection-pool` 실습 소재다).
- characterization 스냅샷이나 기존 테스트의 기대값을 바꾸는 변경은 리팩토링 커밋과 다른 커밋으로 나눈다.
- 요청받지 않은 파일을 고치지 않는다. 포맷팅 · import 정리는 요청 범위 안의 파일에서만 한다. 범위 밖에서 발견한 문제는 목록으로 보고한다.
- characterization 비교를 깨뜨리는 변경은 먼저 사람에게 알린다. 이관 뒤 비교가 실패하면 이관 코드를 고치고, 테스트나 스냅샷을 고쳐서 통과시키지 않는다.
- `vendor-prs/*.patch` 와 `incident-logs/` 는 실습 소재다. 요청 없이 고치거나 `main` 에 적용하지 않는다.
- `templates/CLAUDE.*.md` 는 예시 템플릿이다. 규칙이 필요하면 템플릿이 아니라 실제 코드를 기준으로 삼는다.
- 일부러 넣지 않은 파일(`.claude/` 의 skills · agents · settings, `hooks/*.mjs`, `.mcp.json`, `docs/`, `characterization/tests/<모듈>.test.js` 와 스냅샷, `/api/items/search` 이관 엔드포인트)은 요청을 받았을 때만 만든다.

## 4. 아키텍처 안내

레거시 → 현행 이관이 중심 흐름이다. `legacy/` 는 이관 대상, `modern/` 은 팀 컨벤션의 기준 코드, `characterization/` 은 레거시와 새 API 의 동작이 같은지 확인하는 스냅샷 테스트다.

```
legacy/item-bank-php          문항 은행 (PHP 7.4)        :8081  --profile php
legacy/assignment-thymeleaf   과제 배포 (Spring MVC)     :8082  --profile thymeleaf
legacy/grade-mssql            성적 집계 (MS-SQL 프로시저) :8083  --profile mssql
modern/api/src/main/java/com/example/
├── item/         문항 · 단원 · 태그 (ItemController, UnitController, ItemService, Item, Unit)
├── assignment/   과제 배포 · 재배포 · 학급 리포트 (Distribution*, Report*)
├── common/       GlobalExceptionHandler, ErrorResponse, NotFoundException, ClockConfig
└── config/       WebConfig (CORS)
modern/api/src/main/resources/application.yml       기본 프로필 = 로컬 MariaDB, 커넥션 풀 max 5 · timeout 3s
modern/web                    React 18 · TS · Vite       :5173
characterization/             동작 보존 스냅샷 테스트 (vitest, README 참조)
mcp-skeleton/                 MCP 서버 (SDK v2 · zod/v4, stdout 은 프로토콜 채널)
```

- **엔드포인트**: `GET /api/units`, `GET /api/units/{code}/items`, `GET /api/items/{id}`, `GET /api/distributions/{id}`, `POST /api/distributions/{id}/redistribute`, `GET /api/classes/{id}/report`. 새 엔드포인트는 `/api/<도메인 복수형>` 아래에 둔다.
- **DB**: `php`, `thymeleaf`, `modern` 프로필은 MariaDB 하나(`itembank`)를 함께 쓴다. 성적 집계만 별도 MS-SQL(`grades`)을 쓰고, 핵심 로직은 저장 프로시저에 있다. 영구 볼륨이 없으므로 `down` 후 `up` 하면 시드 상태로 돌아간다. `docker compose` 에는 항상 `--profile` 을 붙인다.
- **읽기 전용 계정**: MariaDB `readonly` / `readonly-pass` (`localhost:3306/itembank`), MS-SQL `readonly` / `Readonly-pass1` (`localhost:1433/grades`).
- **고정 시각**: 시드는 고정 ID · 고정 시각을 쓴다. `assignment-thymeleaf` 의 `AppClock` 은 현재 시각을 `2026-09-15 00:00:00` 으로 고정한다.
- **CORS**: `WebConfig` 에서 `http://localhost:5173` 의 GET 만 허용한다.
- **modern/web**: `getJson` 은 오류 시 `ApiError` 를 던진다. `useApiQuery` 는 `idle / loading / success / error` 판별 유니온 상태를 갖고, key 가 바뀌면 `AbortSignal` 로 이전 요청을 취소한다. `VITE_API_BASE` 기본값은 `http://localhost:8080` 이고, 빈 문자열이면 Vite 프록시를 탄다.
- **characterization**: 대상 주소는 `lib/target.mjs` 의 `DEFAULT_BASE_URLS` 에만 적는다. 레거시 경로가 404 면 `PATH_ALIASES` 의 새 경로로 다시 요청한다.
- **Hook**: 실습 Hook 은 `bash "$CLAUDE_PROJECT_DIR"/scripts/hook-node.sh hooks/<파일>.mjs` 로 실행한다. node 를 찾지 못하면 exit 2 로 막는다.

도메인 용어: 문항 `item` · 단원 `unit`(예: `M5-1`) · 난이도 `level`(1~5) · 태그 `tag` / 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` / 학생 식별자 `STU-<숫자>`.
