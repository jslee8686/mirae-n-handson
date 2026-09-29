# legacy/assignment-thymeleaf 비즈니스 규칙 후보 — "새 배포" 화면

> 분석 범위: `GET /distributions/new`, `POST /distributions` 와 그 호출 코드(`DistributionService.distribute`, `AssignmentDao`, `ClassDao`, `DistributionDao` 일부, `AppClock`).
> 재배포(`DistributionService.redistribute`) · 배포 목록 · 과제 목록의 규칙은 넣지 않았다.
> 확신도: 확실 = 조건문 · SQL 로 확인, 추정 = 이름 · 주석 · 화면 문구로 짐작.

## 1. 배포 가능 여부

### BR-01
- 규칙: 과제 마감 시각(`due_at`)이 현재 시각보다 이전이면 새 배포를 거부하되, 과제 상태가 `X` 이면 마감이 지나도 배포를 허용한다.
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/web/DistributionController.java:59-65`
- 근거 코드:
  ```
          // 마감 지난 과제는 새 배포 불가. 연장(X) 상태만 예외
          if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
              if (!"X".equals(a.getStatus())) {
                  ra.addFlashAttribute("error", "마감(" + AppClock.fmt(a.getDueAt()) + ")이 지난 과제는 배포할 수 없습니다. [" + a.getTitle() + "]");
                  return "redirect:/distributions/new";
              }
          }
  ```
- 확신도: 확실
- 비고:
  - 이 검사는 **컨트롤러에만** 있고 `DistributionService.distribute` 에는 없다 (`DistributionService.java:46-68`). Service 를 다른 경로에서 부르면 마감 검사가 빠진다.
  - `isBefore` 이므로 `due_at == now` 인 순간은 배포가 허용된다.
  - 화면에도 같은 조건으로 option 을 disabled 한다 (`distribution_form.html:16`) — 중복 구현. disabled 는 브라우저 제약일 뿐이라 서버 검사가 실제 규칙이다.
  - 마감 판단 기준 시각은 `AppClock.now()` 이다 (BR-08).

### BR-02
- 규칙: 삭제 상태(`D`) 과제는 배포를 거부한다(`IllegalStateException` → 폼에 오류 표시).
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/service/DistributionService.java:56-58`, `legacy/assignment-thymeleaf/src/main/java/com/example/assign/web/DistributionController.java:69-71`
- 근거 코드:
  ```
          if ("D".equals(a.getStatus())) {
              throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
          }
  ```
- 확신도: 확실
- 비고: `D` 검사는 컨트롤러의 마감 검사(BR-01) **뒤에** 실행된다. 마감 지난 `D` 과제는 "삭제" 가 아니라 "마감" 오류 메시지를 받는다 (`DistributionController.java:60-64` 가 먼저 return).

### BR-03
- 규칙: 같은 과제를 같은 학급에 이미 배포한 행이 1건 이상 있으면 새 배포를 거부한다.
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/service/DistributionService.java:59-61`, `legacy/assignment-thymeleaf/src/main/java/com/example/assign/dao/DistributionDao.java:47-52`
- 근거 코드:
  ```
          if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
              throw new IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.");
          }
  ```
  ```
                  "SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?",
  ```
- 확신도: 확실
- 비고:
  - DB 에 UNIQUE 제약이 없고 (`db/mariadb/init/01-schema.sql:98-102`) 애플리케이션만 막는다. 잠금 없이 COUNT → INSERT 라 동시 요청 시 중복 가능성 → 확인 필요.
  - 시드에 이미 중복 쌍 `(2, 1)` 이 있다 (`db/mariadb/init/02-seed.sql:133-134`).
  - COUNT 는 과제 상태를 보지 않는다. 연장(`X`) 과제도 이미 배포된 학급에는 새 배포가 안 된다.

### BR-04
- 규칙: 존재하지 않는 과제 id 나 학급 id 로 요청하면 배포를 거부하고 폼으로 돌려보낸다.
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/web/DistributionController.java:53-57`, `legacy/assignment-thymeleaf/src/main/java/com/example/assign/service/DistributionService.java:48-55`
- 근거 코드:
  ```
          AssignmentRow a = assignmentDao.findById(assignmentId);
          if (a == null) {
              ra.addFlashAttribute("error", "존재하지 않는 과제입니다.");
              return "redirect:/distributions/new";
          }
  ```
  ```
          ClassRow c = classDao.findById(classId);
          if (c == null) {
              throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");
          }
  ```
- 확신도: 확실
- 비고: 과제 존재 검사는 컨트롤러(`:53-57`)와 Service(`DistributionService.java:48-51`)에 중복돼 있고 메시지가 다르다(Service 쪽은 `(id=…)` 가 붙음). 화면에서는 컨트롤러 메시지만 보인다.

### BR-05
- 규칙: 과제 상태가 `C` 이거나 선택 목록에 없는 상태(예: `R`)여도, 마감 전이고 `D` 가 아니며 중복이 아니면 서버는 배포를 허용한다.
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/web/DistributionController.java:60-61`, `legacy/assignment-thymeleaf/src/main/java/com/example/assign/service/DistributionService.java:56`, `legacy/assignment-thymeleaf/src/main/java/com/example/assign/dao/AssignmentDao.java:36`
- 근거 코드:
  ```
          if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
              if (!"X".equals(a.getStatus())) {
  ```
  ```
          if ("D".equals(a.getStatus())) {
  ```
  ```
                  + " WHERE a.status IN ('O', 'X', 'C') "
  ```
- 확신도: 확실 (서버 경로에 `C` · `R` 을 막는 조건문이 없음을 확인)
- 비고:
  - 화면 선택 목록은 `O` · `X` · `C` 만 보여 주지만 (`AssignmentDao.java:36`), POST 는 상태 목록을 검사하지 않는다. 목록 필터와 서버 검사가 다르다.
  - `C` 의 의미가 스키마 주석은 "마감"(`01-schema.sql:87`), 과제 목록 화면은 "종료"(`assignments.html:26`)다. "마감(C) 과제도 due_at 전이면 배포 가능" 이 의도인지 확인 필요.

## 2. 배포 저장

### BR-06
- 규칙: 새 배포 id 는 `distribution` 의 현재 최대 id + 1 이다(비어 있으면 1).
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/dao/DistributionDao.java:54-57`, `legacy/assignment-thymeleaf/src/main/java/com/example/assign/service/DistributionService.java:62`
- 근거 코드:
  ```
      public long nextId() {
          Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM distribution", Long.class);
          return (max == null ? 0L : max.longValue()) + 1L;
      }
  ```
- 확신도: 확실
- 비고: `distribution.id` 는 AUTO_INCREMENT 가 아니다 (`01-schema.sql:93`). 동시 요청 시 PK 충돌 → `DataAccessException` → "DB 오류로 배포에 실패했습니다." 로 보일 것으로 보이나 재현하지 않음 → 확인 필요.

### BR-07
- 규칙: 새 배포는 `distributed_at = AppClock.now()`, `redistributed = 0` 으로 저장하고, 저장 건수가 1이 아니면 실패로 본다.
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/service/DistributionService.java:63-66`, `legacy/assignment-thymeleaf/src/main/java/com/example/assign/dao/DistributionDao.java:61`
- 근거 코드:
  ```
          int n = distributionDao.insert(id, assignmentId, classId, AppClock.now());
          if (n != 1) {
              throw new IllegalStateException("배포 저장에 실패했습니다.");
          }
  ```
  ```
                  "INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)",
  ```
- 확신도: 확실
- 비고: 컨트롤러 마감 검사의 `now`(`DistributionController.java:58`)와 저장 시각의 `now`(`DistributionService.java:63`)는 따로 구한다. 고정 시계에서는 같지만 `app.clock=system` 이면 다를 수 있다.

### BR-08
- 규칙: 현재 시각은 시스템 속성 `app.clock` 이 `system` 일 때만 실제 시각(나노초 0)이고, 그 밖에는 `2026-09-15 00:00:00` 고정값이다.
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/AppClock.java:9`, `legacy/assignment-thymeleaf/src/main/java/com/example/assign/AppClock.java:14-20`
- 근거 코드:
  ```
      private static final String FIXED = "2026-09-15 00:00:00";
  ```
  ```
      public static LocalDateTime now() {
          String sys = System.getProperty("app.clock");
          if (sys != null && sys.equals("system")) {
              return LocalDateTime.now().withNano(0);
          }
          return LocalDateTime.parse(FIXED, FMT);
      }
  ```
- 확신도: 확실
- 비고: 주석(`AppClock.java:8`)은 "2024-03 QA 기간 중 임시로 고정" 이라고 하나 값은 2026-09-15 다 → 주석과 코드가 다름. 이관 시 이 고정 시계를 옮길지(`Clock` 빈으로) 결정 필요.

## 3. 화면 표시 · 결과 처리

### BR-09
- 규칙: 과제 선택 목록은 상태 `O` · `X` · `C` 과제를 마감 시각 오름차순, 같으면 id 오름차순으로 보여 준다.
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/dao/AssignmentDao.java:34-38`
- 근거 코드:
  ```
      public List<AssignmentRow> findAllForSelect() {
          String sql = BASE_SELECT
                  + " WHERE a.status IN ('O', 'X', 'C') "
                  + " ORDER BY a.due_at ASC, a.id ASC";
          return jdbc.query(sql, MAPPER);
  ```
- 확신도: 확실
- 비고: 과제 목록 화면용 `findAllForList` 는 `status <> 'D'` 로 필터가 다르다 (`AssignmentDao.java:29`). 이 화면에는 `R` 등이 안 보이지만 과제 목록에는 보인다.

### BR-10
- 규칙: 마감이 지난 과제 option 은 상태가 `X` 면 ` [연장]`, 아니면 ` [마감]` 을 붙이고, `X` 가 아니면 선택할 수 없게(disabled) 한다.
- 근거: `legacy/assignment-thymeleaf/src/main/resources/templates/distribution_form.html:16-18`
- 근거 코드:
  ```
                      th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"
                      th:text="${a.id} + '. ' + ${a.title} + ' (' + ${a.unitCode} + ', 마감 ' + ${#temporals.format(a.dueAt, 'MM-dd HH:mm')} + ')'
                               + (${a.dueAt != null and a.dueAt.isBefore(now)} ? (${a.status == 'X'} ? ' [연장]' : ' [마감]') : '')"></option>
  ```
- 확신도: 확실
- 비고: 화면은 마감을 `MM-dd HH:mm`, 서버 오류 메시지는 `yyyy-MM-dd HH:mm`(`AppClock.java:12`, `:26`)로 표시한다.

### BR-11
- 규칙: 학급 선택 목록은 모든 학급을 id 오름차순으로 "이름 (teacher_id)" 형식으로 보여 준다(담당 교사로 거르지 않는다).
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/dao/ClassDao.java:21`, `legacy/assignment-thymeleaf/src/main/resources/templates/distribution_form.html:25`
- 근거 코드:
  ```
          return jdbc.query("SELECT id, name, teacher_id FROM `class` ORDER BY id ASC", MAPPER);
  ```
  ```
              <option th:each="c : ${classes}" th:value="${c.id}" th:text="${c.name} + ' (' + ${c.teacherId} + ')'"></option>
  ```
- 확신도: 확실
- 비고: 로그인 · 권한 처리는 이 화면 경로에 없다 → 이 모듈에 없음(범위 내 코드 기준).

### BR-12
- 규칙: 배포에 성공하면 "배포가 등록되었습니다. (배포 #id)" 를 flash 로 담아 배포 목록으로, 검증 실패(`IllegalArgumentException` · `IllegalStateException`)는 예외 메시지를, DB 오류는 고정 문구를 flash 로 담아 폼으로 돌려보낸다.
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/web/DistributionController.java:66-76`
- 근거 코드:
  ```
          try {
              long id = distributionService.distribute(assignmentId, classId);
              ra.addFlashAttribute("message", "배포가 등록되었습니다. (배포 #" + id + ")");
          } catch (IllegalArgumentException | IllegalStateException e) {
              ra.addFlashAttribute("error", e.getMessage());
              return "redirect:/distributions/new";
          } catch (DataAccessException e) {
              ra.addFlashAttribute("error", "DB 오류로 배포에 실패했습니다.");
              return "redirect:/distributions/new";
          }
          return "redirect:/distributions";
  ```
- 확신도: 확실
- 비고: POST 는 모든 결과가 302 리다이렉트다. 이관 API(`POST` JSON)에서는 상태 코드 매핑(400/404/409)을 새로 정해야 한다. 컨트롤러 `:53` 의 과제 조회에서 난 `DataAccessException` 은 try 밖이라 `DbErrorAdvice` 로 간다 (`DbErrorAdvice.java:11-16`).

### BR-13 (추정)
- 규칙: 과제 상태 `X` 는 "연장" 을 뜻한다.
- 근거: `legacy/assignment-thymeleaf/src/main/java/com/example/assign/web/DistributionController.java:59`, `legacy/assignment-thymeleaf/src/main/resources/templates/distribution_form.html:18`
- 근거 코드:
  ```
          // 마감 지난 과제는 새 배포 불가. 연장(X) 상태만 예외
  ```
  ```
                               + (${a.dueAt != null and a.dueAt.isBefore(now)} ? (${a.status == 'X'} ? ' [연장]' : ' [마감]') : '')"></option>
  ```
- 확신도: 추정 (주석 · 화면 문구로 짐작. 연장된 새 마감일을 저장하는 컬럼은 스키마에 없음 — `01-schema.sql:82-90`)
- 비고: 스키마 주석에는 `X` 가 없다 (`01-schema.sql:87`). "연장" 인데 마감일 제한이 전혀 없다(언제까지든 배포 가능). 의도 확인 필요.

## 4. 주석에만 있는 내용

| 위치 | 주석 | 비고 |
|---|---|---|
| `db/mariadb/init/01-schema.sql:87` | `-- O=진행 C=마감` | 상태 의미는 주석뿐. 코드에 의미를 정의한 곳 없음 |
| `AppClock.java:8` | `운영 반영 전 LocalDateTime.now() 로 되돌릴 것 (아직 안 되돌림)` | 할 일 메모. 규칙 아님 |

## 5. 주석과 코드가 다름

| 위치 | 주석 | 코드 | 기준 |
|---|---|---|---|
| `db/mariadb/init/01-schema.sql:87` | 상태 값은 `O`, `C` | `X` · `D` 도 사용 (`AssignmentDao.java:36`, `DistributionService.java:56`, `DistributionController.java:61`) | 코드 |
| `db/mariadb/init/01-schema.sql:87` vs `assignments.html:26` | `C=마감` | 과제 목록 화면 라벨은 `C` → "종료" | 코드(화면 라벨), 의미는 확인 필요 |
| `AppClock.java:8` | "2024-03 QA 기간 중 임시로 고정" | 고정값 `2026-09-15 00:00:00` (`AppClock.java:9`) | 코드 |
| `DistributionDao.java:46` | "같은 학급 + 같은 과제 는 한 건만 존재해야 한다" | 시드 데이터에 중복 쌍 존재 (`02-seed.sql:133-134`). 코드는 새 배포만 막음 | 코드(새 배포 시에만 막음) |

- `DistributionController.java:59`, `distribution_form.html:13` 의 주석은 코드와 일치한다.

## 6. 확인 필요

1. `C`(마감/종료) 상태 과제가 `due_at` 전이면 배포되는 것이 의도인가 (BR-05).
2. 선택 목록에 없는 `R`(검수중) 등의 과제를 POST 로 직접 보내면 배포되는 것이 의도인가 (BR-05). 시드에 `R` 이 없어 재현하지 않음.
3. `due_at == now` 경계에서 허용(`isBefore`)이 의도인가 (BR-01).
4. `assignmentId` · `classId` 가 빈 값 · 숫자 아님일 때 응답 — 모듈 코드에 처리 없음, Spring 기본 동작 확인 필요 (`DistributionController.java:50-51`).
5. 동시 배포 시 `nextId` PK 충돌 · 중복 배포 가능성 (BR-03, BR-06).
6. `due_at` NULL 분기(`DistributionController.java:60`)는 `NOT NULL` 스키마(`01-schema.sql:86`)에서 도달 가능한가.
7. 마감 지난 `D` 과제가 "삭제" 대신 "마감" 메시지를 받는 순서가 의도인가 (BR-02).
