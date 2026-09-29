# legacy/assignment-thymeleaf 데이터 흐름 · ERD — "새 배포" 화면

> 분석 범위: `GET /distributions/new`, `POST /distributions` 가 실행하는 SQL 만. 배포 목록 · 재배포 쿼리는 제외.
> 스키마 근거는 `db/mariadb/init/01-schema.sql`, 시드는 `db/mariadb/init/02-seed.sql` 이다.

## 1. 테이블 목록

| 테이블 | 정의 | 이 화면에서 | 쓰는 컬럼 |
|---|---|---|---|
| `assignment` | `01-schema.sql:82-90` | 읽기 | `id`, `title`, `unit_id`, `due_at`, `status` |
| `unit` | `01-schema.sql:11-18` | 읽기(조인) | `id`, `code`, `name` |
| `class` | `01-schema.sql:75-80` | 읽기 | `id`, `name`, `teacher_id` |
| `distribution` | `01-schema.sql:92-103` | 읽기 · **쓰기(INSERT)** | `id`, `assignment_id`, `class_id`, `distributed_at`, `redistributed` |
| `submission` | `01-schema.sql:105-115` | 사용 안 함 | — |

- 뷰는 쓰지 않는다.
- `distribution` 에 `(assignment_id, class_id)` UNIQUE 제약이 없다 (`01-schema.sql:98-102` 에 PK · 일반 KEY · FK 만 있음). 중복은 애플리케이션이 막는다 (`DistributionDao.java:46-52`).
- `assignment.status` 스키마 주석은 `O=진행 C=마감` 두 값뿐이다 (`01-schema.sql:87`). 코드는 `X`, `D` 도 다룬다 (`AssignmentDao.java:36`, `DistributionService.java:56`) → 주석과 코드가 다름.

## 2. 관계

```mermaid
erDiagram
    unit ||--o{ assignment : "unit_id (fk_assignment_unit)"
    assignment ||--o{ distribution : "assignment_id (fk_distribution_assignment)"
    class ||--o{ distribution : "class_id (fk_distribution_class)"

    unit {
        INT id PK
        VARCHAR code UK
        VARCHAR name
    }
    assignment {
        INT id PK
        VARCHAR title
        INT unit_id FK
        DATETIME due_at "NOT NULL"
        CHAR status "DEFAULT 'O'"
    }
    class {
        INT id PK
        VARCHAR name
        VARCHAR teacher_id
    }
    distribution {
        INT id PK "앱이 MAX+1 로 채번"
        INT assignment_id FK
        INT class_id FK
        DATETIME distributed_at
        TINYINT redistributed "DEFAULT 0"
    }
```

FK 근거: `01-schema.sql:89`, `01-schema.sql:101`, `01-schema.sql:102`.

## 3. SQL 목록

| # | 위치 | SQL 요지 | 종류 |
|---|---|---|---|
| Q1 | `AssignmentDao.java:21-24` + `:34-39` | `assignment a LEFT JOIN unit u` + 배포 학급 수 서브쿼리, `WHERE a.status IN ('O','X','C') ORDER BY a.due_at, a.id` | SELECT |
| Q2 | `ClassDao.java:21` | `SELECT id, name, teacher_id FROM class ORDER BY id` | SELECT |
| Q3 | `AssignmentDao.java:41-48` | Q1 과 같은 SELECT + `WHERE a.id = ?`, 0건이면 `null` | SELECT |
| Q4 | `ClassDao.java:24-30` | `SELECT ... FROM class WHERE id = ?`, 0건이면 `null` | SELECT |
| Q5 | `DistributionDao.java:47-52` | `SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?` | SELECT |
| Q6 | `DistributionDao.java:54-57` | `SELECT COALESCE(MAX(id), 0) FROM distribution` → +1 | SELECT |
| Q7 | `DistributionDao.java:59-63` | `INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)` | INSERT |

- 모든 SQL 은 `?` 파라미터 바인딩을 쓴다. 이어 붙이는 값은 상수 문자열뿐이다 (`AssignmentDao.java:35-37`, `:42`).
- Q1 · Q3 의 `class_cnt` 서브쿼리(`AssignmentDao.java:23`)는 이 화면 템플릿에서 쓰지 않는다 (`distribution_form.html:14-18` 에 `classCount` 참조 없음).

## 4. 요청 → SQL → 응답 흐름

### GET `/distributions/new`

```
요청 ─▶ Q1 (과제 선택 목록) ─▶ model.assignments   DistributionController.java:42
     ─▶ Q2 (학급 목록)       ─▶ model.classes       DistributionController.java:43
     ─▶ AppClock.now()      ─▶ model.now           DistributionController.java:44
응답: distribution_form.html
  - 과제 <option>: "id. 제목 (단원코드, 마감 MM-dd HH:mm)" + 마감 지남이면 " [연장]" 또는 " [마감]"   distribution_form.html:17-18
  - 마감 지남 && status != 'X' 이면 disabled                                                   distribution_form.html:16
  - 학급 <option>: "이름 (teacher_id)"                                                         distribution_form.html:25
DB 예외 시: DbErrorAdvice → db_error.html (detail 에 원인 메시지 노출)                         DbErrorAdvice.java:15-16, db_error.html:8
```

### POST `/distributions`

```
요청(assignmentId, classId)
  ─▶ Q3 ─ null ─▶ flash error "존재하지 않는 과제입니다." → redirect:/distributions/new     DistributionController.java:53-57
  ─▶ due_at < now && status != 'X' ─▶ flash error "마감(...)이 지난 과제는..." → 폼       DistributionController.java:60-64
  ─▶ [트랜잭션 시작]                                                                       DistributionService.java:46
       Q3 ─ null ─▶ IllegalArgumentException                                               DistributionService.java:48-51
       Q4 ─ null ─▶ IllegalArgumentException "존재하지 않는 학급입니다."                  DistributionService.java:52-55
       status == 'D' ─▶ IllegalStateException                                              DistributionService.java:56-58
       Q5 > 0 ─▶ IllegalStateException "이미 이 학급에 배포된 과제입니다..."               DistributionService.java:59-61
       Q6 → id
       Q7 (distributed_at = AppClock.now(), redistributed = 0) ─ n != 1 ─▶ IllegalStateException   DistributionService.java:62-66
     [커밋]
  ─▶ flash message "배포가 등록되었습니다. (배포 #id)" → redirect:/distributions           DistributionController.java:68, :76
  예외: IllegalArgument/IllegalState → flash error = 예외 메시지 → 폼                       DistributionController.java:69-71
        DataAccessException → flash error "DB 오류로 배포에 실패했습니다." → 폼             DistributionController.java:72-74
```

## 5. 시드 기준 화면 상태 (`AppClock` = 2026-09-15 00:00:00)

시드: 학급 `02-seed.sql:111-114`, 과제 `02-seed.sql:119-125`, 배포 `02-seed.sql:130-138`.

| 과제 id | status | due_at | 선택 목록 표시 | 서버 결과(마감 검사까지) |
|---|---|---|---|---|
| 1 | C | 2026-08-20 23:59 | `[마감]` disabled | 마감 오류 |
| 2 | C | 2026-08-28 23:59 | `[마감]` disabled | 마감 오류 |
| 3 | O | 2026-09-10 23:59 | `[마감]` disabled | 마감 오류 |
| 4 | O | 2026-09-25 23:59 | 선택 가능 | 통과 (학급 3 은 이미 배포 → 중복 오류) |
| 5 | O | 2026-10-02 23:59 | 선택 가능 | 통과 (학급 3 중복) |
| 6 | O | 2026-09-30 23:59 | 선택 가능 | 통과 (학급 2 중복) |

- 시드 배포 3 · 4 는 같은 `(assignment_id=2, class_id=1)` 이다 (`02-seed.sql:133-134`). DB 에는 중복이 이미 있다.
- 시드에 `X` · `D` · `R` 상태 과제는 없다 → 연장 · 삭제 분기는 시드로 재현할 수 없다.

## 6. 확인 필요

- `assignment.due_at` 은 `NOT NULL` 인데 (`01-schema.sql:86`) 코드는 `null` 분기를 둔다 (`AssignmentDao.java:59-60`, `DistributionController.java:60`, `distribution_form.html:16`). 다른 경로로 NULL 이 들어오는지 확인 필요.
- `nextId`(MAX+1) 와 중복 COUNT 는 잠금 없이 실행된다 (`DistributionDao.java:47-57`). 동시 요청 시 PK 충돌 · 중복 배포가 생기는지 확인 필요 (격리 수준 설정은 이 모듈에 없음).
