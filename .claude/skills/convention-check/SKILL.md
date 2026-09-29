---
name: convention-check
description: 변경된 코드가 CLAUDE.md 의 코딩 컨벤션 · 금지 사항을 지켰는지 8개 항목으로 점검하고, 위반을 파일:줄번호와 함께 표로 보고한다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 곳 찾아줘" 같은 요청이나 커밋 · PR 직전에 쓴다. 코드는 고치지 않는다.
argument-hint: "[점검할 경로 …] (생략하면 git 변경 파일)"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

- 코드를 직접 고치지 않는다. 점검 결과만 보고한다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기

- `$ARGUMENTS` 가 있으면 그 경로(파일 또는 디렉터리)만 점검한다. 디렉터리는 Glob 으로 파일을 모은다.
- 없으면 `git status --porcelain` 으로 변경 파일을 모은다. `??`(아직 add 하지 않은 새 파일)도 포함하고, 삭제된 파일(`D`)은 뺀다.
  - 바뀐 줄은 `git diff --unified=0` 과 `git diff --cached --unified=0` 으로 확인한다. 새 파일은 전체를 본다.
  - 기존 파일은 바뀐 줄에서 생긴 위반만 보고한다. 바뀌지 않은 줄의 위반은 보고하지 않는다.
- 대상이 없으면 "점검할 변경 파일이 없습니다"라고 답하고 끝낸다.

## 2. 점검 항목

패턴 검색은 Grep 으로 하고, 걸린 줄은 Read 로 앞뒤 문맥을 열어 확인한 뒤 판정한다. 대상 파일이 없는 항목은 "해당 없음"이다.

| # | 규칙 | 대상 | 위반 판정 기준 |
|---|---|---|---|
| 1 | Controller 는 Repository 를 주입받지 않는다 | `modern/api/**/*Controller.java` | `Repository` 타입의 필드나 생성자 인자가 있다 |
| 2 | Controller 메서드에 `try-catch` 를 넣지 않는다 | `modern/api/**/*Controller.java` | `try {` 가 있다 |
| 3 | 빈 `catch` 를 두지 않고, catch 에서는 다시 던진다 | `modern/api/**/*.java` | `catch` 블록 안에 `throw` 가 없다 |
| 4 | 로그는 SLF4J 로만 남긴다 | `modern/api/**/*.java` | `System.out` · `System.err` · `printStackTrace(` 가 있다 |
| 5 | Service 에서 현재 시각은 주입받은 `Clock` 으로 구한다 | `modern/api/**/*Service.java` | 인자 없는 `now()` 호출이 있다 |
| 6 | 쿼리 문자열에 변수를 이어 붙이지 않는다 | `@Query` · `createQuery` · `createNativeQuery` | 쿼리 문자열에 `+` 연결이 있다 |
| 7 | web 에서 쓰지 않기로 한 API 를 쓰지 않는다 | `modern/web/src/**/*.ts(x)` | `src/api/client.ts` 밖의 `fetch(` 호출(`*.test.ts(x)` · `src/test/` 는 제외), `: any` · `as any`, `dangerouslySetInnerHTML` 중 하나라도 있다 |
| 8 | 승인이 필요한 파일을 바꿀 때는 먼저 묻는다 | 변경 파일 목록 | `build.gradle`, `package.json`, `package-lock.json`, `db/*/init/*`, `application.yml` 중 하나라도 바뀌었다 → "사전 승인 확인 필요" |

- 주석이나 문자열 리터럴 안에서 걸린 패턴은 위반이 아니다.
- 규칙 문구가 CLAUDE.md 와 다르면 CLAUDE.md 를 따르고, 다르다는 사실을 요약 끝에 적는다.

## 3. 출력 형식 (순서 고정)

### 판정 요약

점검 대상: 파일 N개 (인자 경로 또는 git 변경 파일)

| # | 규칙 | 판정 |
|---|---|---|
| 1 | Controller → Repository 주입 금지 | 통과 / 위반 N건 / 해당 없음 |
| … | … | … |

### 위반 목록

| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/src/main/java/com/example/item/ItemController.java:27` | #1 Controller → Repository 주입 금지 | `ItemService` 에 조회 메서드를 추가하고 Controller 는 Service 를 호출 |

- 위반이 없으면 표 대신 "위반 없음" 한 줄을 쓴다.
- 줄번호를 댈 수 없는 의심 사항은 표에 넣지 않는다. 표 아래 "확인 필요" 목록에 파일과 이유만 적는다.
