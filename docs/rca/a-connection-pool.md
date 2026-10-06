# a-connection-pool 장애 원인 분석 (RCA)

> 상태: 완성본(2026-10-06). 1~3절은 조사 기록(원인 판정 없음), 4절 이후가 결론이다.
> 자료: `incident-logs/a-connection-pool/` (읽기 전용, 수정 없음). 코드는 수정하지 않았고 6절은 제안이다.

## 요약

- **무슨 일**: 2026-09-16 14:40:52 ~ 14:51:47 (KST), 11분간 API 전반이 500 · 502 · 504 를 반환했다(5xx 186건). 징후는 14:37:52 에 시작됐다.
- **직접 원인**: 학급 리포트 `buildClassReport` 가 트랜잭션(커넥션) 안에서 서명 계산까지 해 커넥션을 오래 쥐었고, 리포트 요청이 늘며 최대 5개인 풀이 소진돼 다른 API 가 3초 후 실패했다.
- **배경 원인**: 풀이 작음(5개 · 3초), 컨벤션 규칙은 있으나 자동 검증이 없음, 리포트 API 에 캐시 · 격리 · 부하 제한이 없음.
- **확신 수준: 중간.** 장애 당시 빌드 동일성, 서명 소요 시간, 14:37 최초 계기, 11분 뒤 회복 원인은 로그로 확인하지 못했다(9절).
- **조치**: 서명을 트랜잭션 밖으로 분리(6절). 구조적 재발 방지는 7절, 감지 지표는 8절.

## 1. 수집 범위

방법: 파일 전체를 읽지 않고 `wc`, `head`, `tail`, `grep`, `awk` 집계로 필요한 부분만 확인했다.

### 1.1 파일별 기록 기간 · 줄 수 · 시각 형식

| 파일 | 줄 수 | 기록 기간 (원본 표기) | 시각 형식 | 타임존 | 근거 |
|---|---|---|---|---|---|
| `app.log` | 5,371 | 2026-09-16 06:43:56 ~ 15:29:51 | ISO-8601 `2026-09-16T06:43:56.753+09:00` (ms) | KST (+09:00 명시) | `app.log:1`, 마지막 줄 |
| `nginx-access.log` | 21,086 | 16/Sep/2026 06:43:56 ~ 15:29:59 | CLF `[16/Sep/2026:06:43:56 +0900]` (초) | KST (+0900 명시) | `nginx-access.log:1`, 마지막 줄 |
| `nginx-error.log` | 168 | 2026/09/16 09:12:03 ~ 14:51:47 | `2026/09/16 09:12:03` (초) | **표기 없음**. KST로 판단 (아래 참고) | `nginx-error.log:1`, 마지막 줄 |
| `mariadb-slow.log` | 21 | 260916 0:14:09 ~ 5:37:52 (항목 3건) | `# Time: 260916  0:14:09` + `SET timestamp=<epoch>` | **UTC** | `mariadb-slow.log:1,6,13,20` |

- app.log 에는 스택 트레이스 줄이 섞여 있어 줄 수와 로그 레코드 수가 다르다. 레코드 수는 시각으로 시작하는 줄만 센다.
- nginx-error.log 가 KST 라는 판단 근거: 마지막 오류 `14:51:47`(`GET /api/classes/3/report`, client 교사단말A)이 nginx-access.log 의 `14:51:47 +0900` 504 줄(`nginx-access.log:19381`)과 같은 시각 · 같은 요청이다. 다만 파일에 타임존이 적혀 있지는 않으므로 "추정(요청 대응으로 확인)"이다.
- mariadb-slow.log 가 UTC 라는 근거: `# Time: 260916  5:37:52` 의 `SET timestamp=1789537062` 가 `2026-09-16 05:37:42 UTC` = `14:37:42 KST` 로 환산된다. `# Time` 은 `timestamp` 보다 약 10초 뒤(쿼리 종료 시각, Query_time 9.8s)이다. `# Time` 을 KST 로 읽으면 안 된다.

### 1.2 KST 기준 변환 규칙

| 파일 | 변환 |
|---|---|
| `app.log` | 변환 없음 (이미 +09:00) |
| `nginx-access.log` | 변환 없음 (이미 +0900) |
| `nginx-error.log` | 변환 없음 (KST 로 취급). 단, 다른 파일과 교차할 때는 요청 경로 · client IP 로 대응을 확인한다 |
| `mariadb-slow.log` | `# Time` 의 시각에 **+9시간** (예: `0:14:09` → `09:14:09`). 날짜가 넘어가는 경우를 주의한다. `SET timestamp` 는 epoch 이므로 `TZ=Asia/Seoul date -d @<epoch>` 로 직접 변환한다. 슬로우 쿼리는 종료 시각이 기록되므로 시작 시각은 `Query_time` 만큼 앞이다 |

mariadb-slow.log 항목의 KST 환산:

| `# Time` (UTC) | KST | Query_time | Lock_time | Rows_examined |
|---|---|---|---|---|
| 00:14:09 | 09:14:09 | 2.32s | 0.00s | 41,822 |
| 02:02:42 | 11:02:42 | 2.04s | 0.91s | 5 |
| 05:37:52 | 14:37:52 | 9.81s | 0.00s | 1,284,310 |

(3번째 항목: `SET timestamp` 기준 14:37:42 KST, `# Time` 기준 14:37:52 KST. `mariadb-slow.log:18-20`)

### 1.3 분 단위 이상 징후 집계 (KST)

집계 기준:

- app.log: 레코드 레벨이 `ERROR` 또는 `WARN` 인 줄.
- nginx-access.log: 상태 코드 500 · 502 · 504.
- nginx-error.log: `[error]` · `[warn]` 줄 (`upstream timed out` 포함).
- mariadb-slow.log: 항목 전체 (3건뿐이라 분 단위 집계는 생략).

#### 평소 수준 (14:37 이전)

| 파일 | 평소 수준 | 비고 |
|---|---|---|
| `app.log` | ERROR 0건. WARN 은 `state conflict`(409, 재배포 불가) 20건이 08:41~13:xx 에 시간당 1~4건 분산 | 409 는 응답 코드 409 와 일치(nginx-access 409 = 20건). 평소 노이즈로 분류 |
| `nginx-access.log` | 5xx **0건** (06~13시 전 시간대). 404 는 시간당 약 100~170건(전체 1,250건)이 상시 발생 | 404 는 평소 수준이라 이상 징후 집계에서 제외 |
| `nginx-error.log` | 09:12 `[warn]` 1건, 13:05 `[warn]` 1건 (버퍼링 경고). 그 외 `[info]` 3건 | 분당 1건 이하 |
| `mariadb-slow.log` | 09:14, 11:02 에 각 1건 (2.0~2.3s) | 14:37 항목만 9.8s |

#### 평소와 달라지는 구간의 분 단위 건수

| 분 (KST) | app WARN | app ERROR | access 500 | access 502 | access 504 | nginx-error `[error]` | nginx-error `[warn]` |
|---|---|---|---|---|---|---|---|
| 14:37 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:38 | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:39 | 3 | 0 | 0 | 0 | 0 | 0 | 0 |
| 14:40 | 15 | 2 | 1 | 0 | 0 | 0 | 0 |
| 14:41 | 19 | 12 | 6 | 0 | 3 | 3 | 0 |
| 14:42 | 12 | 4 | 2 | 13 | 5 | 18 | 2 |
| 14:43 | 24 | 20 | 10 | 2 | 5 | 7 | 1 |
| 14:44 | 13 | 4 | 2 | 5 | 6 | 11 | 4 |
| 14:45 | 17 | 16 | 8 | 14 | 5 | 19 | 3 |
| 14:46 | 16 | 8 | 4 | 16 | 6 | 22 | 3 |
| 14:47 | 14 | 10 | 5 | 6 | 3 | 9 | 1 |
| 14:48 | 14 | 8 | 4 | 6 | 5 | 11 | 4 |
| 14:49 | 19 | 14 | 7 | 8 | 8 | 16 | 4 |
| 14:50 | 11 | 0 | 0 | 11 | 7 | 18 | 4 |
| 14:51 | 3 | 0 | 0 | 1 | 2 | 3 | 0 |
| **합계** | 183 | 98 | 49 | 82 | 55 | 137 | 26 |

- 위 표의 합계는 파일 전체 집계와 일치한다: app WARN 203 = 183 + 409 노이즈 20, app ERROR 98, access 500/502/504 = 49/82/55, nginx-error `[error]` 137, `[warn]` 28 = 26 + 평소 2건(09:12, 13:05).
- app.log 의 WARN 183건 중 14:37~14:39 의 6건은 `HikariPool ProxyLeakTask: Connection leak detection triggered` 이며(`app.log:1243`), ERROR 는 14:40 부터 `Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` 로 시작한다(`app.log:1522`). 메시지 원문만 기록하고 해석은 하지 않는다.
- 평소 수준과 달라지는 시작 시각:
  - 가장 이른 신호: **14:37** (app.log WARN 첫 건 `14:37:52`, mariadb-slow 의 9.8s 쿼리 `14:37:42` 종료).
  - 사용자 영향(5xx)이 시작되는 시각: **14:40:52** (`nginx-access.log:18918`).
- 끝나는 시각: **14:51** (app.log 의 마지막 WARN `14:52:09` 는 `state conflict` 409 노이즈이며(`app.log:5269`), 5xx 마지막 `14:51:47` `nginx-access.log:19381`, nginx-error 마지막 `14:51:47`). 14:52 이후 5xx · ERROR 는 0건이다.

### 1.4 제안 수집 범위

| 구분 | 시작 | 끝 | 근거 |
|---|---|---|---|
| 핵심 구간 (이상 징후 존재) | 14:37:00 | 14:52:00 | 첫 신호 14:37 ~ 마지막 5xx 14:51:47 |
| **제안 수집 범위** | **14:17:00** | **15:02:00** | 핵심 구간 앞 20분 · 뒤 10분 |

여유를 둘 때 앞쪽을 더 넉넉히 두는 이유와 폭:

- 앞 20분(14:17 ~ 14:37): 5xx 는 14:40 에 나타나지만 app.log WARN 은 14:37, 슬로우 쿼리는 `Query_time` 9.8s 만큼 앞선 14:37:32 경에 시작한다. 5xx 보다 앞선 징후에서 시작점이 거슬러 올라갈 수 있으므로, 원인 후보로 이어질 수 있는 사건(배포 · 배치 · 특정 요청 증가)을 포착할 수 있도록 5xx 이전을 길게 잡는다. 20분은 slow log 의 최대 소요(약 10초)와 Hikari leak 감지 주기(약 30초 간격으로 관찰, `app.log:1243,1261,1280,1299`)를 충분히 덮는 값이다. 이 값은 제안이며, 14:17 이전에 이상이 없었는지 확인한 뒤 줄이거나 늘린다.
- 뒤 10분(14:52 ~ 15:02): 5xx 가 14:51:47 에 끝난 뒤 회복(오류 0건 유지, 정상 응답 시간 복귀)을 확인하면 충분하다. nginx-access.log 는 15:29 까지 있으므로 더 길게도 볼 수 있다.
- 이 범위를 쓰는 방법: 14:17~15:02 는 app.log 672줄(스택 트레이스 포함), nginx-access.log 1,953줄이므로, 이후 단계에서는 이 구간만 `grep -n` 으로 줄 번호를 찾아 `sed -n` 으로 발췌해 읽는다.

### 1.5 확인 필요 · 한계

- nginx-error.log 의 타임존은 표기가 없어 요청 대응으로만 확인했다. 한 건만 대조했으므로 다른 시각에서도 어긋나지 않는지는 이후 교차 확인에서 본다.
- nginx-access.log 에 응답 시간(`request_time`)이 없다. 지연 분포는 이 로그로 볼 수 없다.
- 이 단계에서 advisor 는 호출하지 않았다. 사용 가능한 advisor 도구가 이 세션에 없다.
- 원인에 대한 추측은 하지 않았다. 위 서술은 로그에 기록된 사실과 집계 수치만 담는다.

## 2. 타임라인

대상: 1절에서 정한 수집 범위 **2026-09-16 14:17 ~ 15:02 (KST)**. 폴더 안 4개 파일을 KST 한 시간축으로 합쳤다(`mariadb-slow.log` 는 UTC+9 환산).
로그에 기록된 사건만 적고, 원인 해석은 하지 않는다. 출처의 `app` = `app.log`, `acc` = `nginx-access.log`, `ngx-err` = `nginx-error.log`, `slow` = `mariadb-slow.log` 이다. 원문 발췌는 80자 이내로 줄였다.

### 2.1 타임라인 표

| 시각 (KST) | 출처 (파일:줄) | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:17:29 | acc:17880, app:1174 | **기준선** 학급 리포트 요청 200 (class 1). 앞뒤 구간에서 리포트 요청은 14:17, 14:18 에 1건씩만 있음 | `GET /api/classes/1/report HTTP/1.1" 200 268` |
| 14:18:13 | acc:17914, app:1175 | 위와 같은 기준선 리포트 요청 (class 1). 이후 14:37 까지 리포트 요청 0건 | `built class report for class 1: 3 distributions, 10 submissions` |
| 14:19~14:37 | acc (분당 32~56건) | 전체 요청 수는 분당 32~56건. 5xx 0건, app ERROR 0건 | (집계, 1.3절) |
| 14:37:32 경 ~ 14:37:42 | slow:18-21 | **느린 쿼리** `submission` 조회, Query_time 9.81s, Rows_examined 1,284,310. 기록 시각은 종료 시점이라 시작은 약 10초 전. `SET timestamp`=14:37:42 KST, `# Time`=14:37:52 KST 로 두 필드가 10초 어긋남 (선후 불확실: 아래 app:1243 과 비교) | `select ... from submission s1_0 where s1_0.distribution_id=5 order by...` |
| 14:37:52.199 | app:1243 | **경고** HikariCP 누수 감지 첫 WARN (스레드 exec-6). 스택에 `ReportController.classReport` 포함. 이전 구간에는 없음. slow 항목의 `# Time`(14:37:52)과 같은 초이고 `SET timestamp`(14:37:42)와는 10초 차이라 (선후 불확실) | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3` |
| 14:38:01.900 | app:1258, acc:18801 | 리포트 요청 재개: class 3 리포트 200. 14:38 에 3건 | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:38:01.902 | app:1259 | 14:37:52 에 보고된 누수 연결이 풀로 반환됨 (같은 스레드 exec-6, 같은 연결 @6e8f14d3). `Previously reported...` 가 이후에도 WARN 과 짝으로 반복됨 | `was returned to the pool (unleaked)` |
| 14:37:52 ~ 14:51:37 | app:1243 ~ 5251 | **반복** 누수 감지 WARN. 첫 발생 14:37:52, 이후 합계 134건. 분당: 14:37=1, 38=2, 39=3, 40=14, 41=13, 42=10, 43=14, 44=11, 45=9, 46=12, 47=9, 48=10, 49=12, 50=11, 51=3 | `Connection leak detection triggered for ...` |
| 14:38:01 ~ 14:51:49 | app:1259 ~ 5267 | **반복** 누수 연결 반환 INFO. 첫 발생 14:38:01, 이후 합계 134건(분당 3~15). 누수 WARN 건수와 같음 | `Previously reported leaked connection ... was returned to the pool` |
| 14:38~14:39 | acc, app | 리포트 요청 분당 3건 (14:38=3, 14:39=3). 5xx 아직 0건. **트래픽 변화** 시작: 같은 날 다른 시간대 리포트 요청은 시간당 2~11건, 14시대 합계 179건 | (집계) |
| 14:38:29.715 | app:1278 | 평소 사건: 재배포 정상 처리 (distribution 5). 이상 징후와 관련 여부는 이 단계에서 판단하지 않음 | `redistributed distribution 5 (assignment 3, class 3) reason=null` |
| 14:40 | acc, app | 리포트 요청 분당 10건으로 증가 (14:41~14:50 은 분당 10~19건). 전체 요청 수는 분당 34~58건으로 평소 범위 | (집계) |
| 14:40:52.319 | app:1521~1523, acc:18918 | **첫 오류** 커넥션 풀 대기 타임아웃 ERROR(3000ms, total=5 active=5 idle=0 waiting=4) + `SQL Error: 0` WARN + 미처리 예외 ERROR(`/api/units/M6-2/items`). 같은 초에 acc 에 500 이 기록됨 (선후 불확실: app 과 acc 는 같은 초) | `Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| 14:40:52 ~ 14:49:51 | app:1521 ~ 4981 | **반복** 풀 대기 타임아웃 ERROR 첫 발생 14:40:52, 이후 합계 49건. 분당: 14:40=1, 41=6, 42=2, 43=10, 44=2, 45=8, 46=4, 47=5, 48=4, 49=7. 같은 시각에 `SQL Error: 0` WARN 49건과 `unhandled exception` ERROR 49건이 1:1 로 따라옴. 풀 메시지의 waiting 값 범위 1~7 | `itembank-pool - Connection is not available, request timed out after 3000ms` |
| 14:41:06 | acc:18933, ngx-err:6 | **첫 504** 리포트 요청(class 3). 같은 초에 nginx 오류 `upstream timed out` 첫 발생 (선후 불확실: acc 와 ngx-err 는 같은 초) | `upstream timed out (110: Connection timed out) while reading response header` |
| 14:41:06 ~ 14:51:47 | ngx-err:6 ~ 168 | **반복** `upstream timed out`. 첫 발생 14:41:06, 합계 55건. 분당: 14:41=3, 42=5, 43=5, 44=6, 45=5, 46=6, 47=3, 48=5, 49=8, 50=7, 51=2 | `upstream timed out (110: Connection timed out)` |
| 14:42:01 | ngx-err:9, 10, 11, acc:18970 | 같은 초에 nginx `upstream timed out` + `upstream server temporarily disabled`(warn) + `connect() failed (111: Connection refused)` 첫 발생, acc 에 첫 502(class 2 리포트). 4건 간 선후 단정 불가 (선후 불확실) | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:42:01 ~ 14:50 | ngx-err:10 ~ | **반복** `upstream server temporarily disabled`(warn). 첫 발생 14:42:01, 합계 26건. 분당 1~4건 | `upstream server temporarily disabled while reading response header` |
| 14:42:01 ~ 14:50 | ngx-err:11 ~ | **반복** `connect() failed (111: Connection refused)`. 첫 발생 14:42:01, 합계 14건. 분당 1~2건 | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:42:04 ~ 14:51:02 | ngx-err:12 ~ 166 | **반복** `no live upstreams`. 첫 발생 14:42:04, 합계 68건. 분당: 14:42=11, 43=1, 44=4, 45=12, 46=14, 47=5, 48=5, 49=6, 50=9, 51=1 | `no live upstreams while connecting to upstream` |
| 14:40:52 ~ 14:51:47 | acc:18918 ~ 19381 | **반복** 5xx. 500 첫 발생 14:40:52(합계 49건, 14:40~14:49), 504 첫 발생 14:41:06(합계 55건, 14:41~14:51), 502 첫 발생 14:42:01(합계 82건, 14:42~14:51). 분당 합계 14:41=9, 42=20, 43=17, 44=13, 45=27, 46=26, 47=14, 48=15, 49=23, 50=18, 51=3 | `"GET /api/classes/3/report HTTP/1.1" 504 167` |
| 14:44:19.827 | app:2929 | 평소 사건: 재배포 정상 처리 (distribution 8) | `redistributed distribution 8 (assignment 6, class 2) reason=...` |
| 14:49:51.552 | app:4979~4981 | 풀 대기 타임아웃 ERROR 의 **마지막** 발생. 이후 app ERROR 0건 | `itembank-pool - Connection is not available, request timed out after 3000ms` |
| 14:50 | ngx-err, acc | app ERROR 는 없지만 nginx 502 11건 · 504 7건, ngx-err 오류 18건 계속됨 (app 과 nginx 의 끝나는 시각이 다름) | (집계) |
| 14:51:37.674 | app:5251 | 누수 감지 WARN 의 **마지막** 발생 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3` |
| 14:51:47 | acc:19381, ngx-err:168 | 마지막 504(`/api/classes/3/report`)와 마지막 nginx `upstream timed out`. 같은 초 (선후 불확실) | `upstream timed out (110: Connection timed out)` |
| 14:51:49.148 | app:5267 | 누수 연결 반환 INFO 의 마지막 발생. 리포트 생성 INFO 의 마지막은 app:5266(14:51:49.146) | `was returned to the pool (unleaked)` |
| 14:52 ~ 15:02 | acc, app, ngx-err | 5xx 0건, app ERROR 0건, ngx-err 0건. 리포트 요청은 14:59 에 2건, 15:01 에 1건(평소 수준) | `built class report for class 3: 3 distributions, 10 submissions` |

### 2.2 타임라인 정리 메모

- **가장 많은 오류보다 앞선 시각의 평소와 다른 줄**: 14:37:32~14:37:52 의 슬로우 쿼리(slow:15-21)와 14:37:52 부터의 누수 감지 WARN(app:1243), 14:38 부터의 리포트 요청 재개(acc:18801). 가장 많은 오류는 nginx `no live upstreams`(68건)·`upstream timed out`(55건)과 app 풀 대기 타임아웃(49건)으로, 모두 14:40:52 이후에 처음 나온다.
- **설정 재적재**: 세 로그 모두에서 `reload`·`signal`·`worker process`·`restart`·`configuration` 문구가 0건이다. 설정 재적재나 재시작을 나타내는 줄은 이 구간에 없다.
- **트래픽 변화**: 전체 요청 수는 분당 약 32~58건으로 구간 전체에서 같은 범위이고, 변한 것은 `/api/classes/{id}/report` 요청 수다(14:19~14:37 = 0건 → 14:38 = 3건/분 → 14:40~14:50 = 10~19건/분 → 14:52~ 거의 0건).
- **선후 불확실로 표시한 곳**: 같은 초 또는 몇 초 이내인 쌍이며, 로그 시각 해상도(acc · ngx-err 는 1초, slow 는 1초 + 필드 간 10초 차이) 때문에 순서를 단정하지 않았다.
  1. slow `# Time` 14:37:52 vs app:1243 (14:37:52.199)
  2. app:1521~1523 (14:40:52) vs acc:18918 (14:40:52)
  3. acc:18933 vs ngx-err:6 (14:41:06)
  4. acc:18970 vs ngx-err:9~11 (14:42:01)
  5. acc:19381 vs ngx-err:168 (14:51:47)
- **제외한 평소 사건**: 404(전 구간 상시 시간당 약 100~170건), 400(`/api/items/NaN` · `/api/items/null`, 14:31:47 · 14:37:15 · 14:44:59 `app:1218,1239,3137`), 409 재배포 불가(14:33:45, 14:42:42, 14:50:22, 14:51:52, 14:52:09). 14:17 이후 구간에서 이 세 종류는 평소 분포와 같아 표에서 뺐다.
- **범위 밖에서 발견한 것 (수정하지 않음)**: `app.log:1182`(14:19:38)의 INFO 로그에 `reason=학생A 결석으로 재배포` 처럼 학생 식별자(`STU-…`)가 들어 있다. 이 로그는 레거시가 아닌 로그 자료이며 RCA 범위 밖이라 기록만 한다.

### 2.3 한계

- 해당 구간의 app.log 에서 `INFO`(정상 흐름 · 404 응답 처리) 줄은 모두 표에 넣지 않았다. 반복 템플릿은 이 절에서 건수와 첫·끝 시각만 적었다.
- nginx-access.log 에는 응답 시간이 없어, 느려진 요청의 소요 시간은 타임라인에 쓰지 못했다.
- 원인에 대한 해석은 쓰지 않았다. 3절 이후에서 다룬다.

## 3. 가설과 검증

> 이 절은 가설만 세운다. **어느 가설이 맞는지 판정하지 않는다.** 근거의 `rca:줄` 은 이 문서(`docs/rca/a-connection-pool.md`)의 줄이고, 원본 로그 줄은 2절 표에 적힌 `app:` · `acc:` · `ngx-err:` · `slow:` 번호를 따른다.
> 가장 많이 나온 메시지(nginx `no live upstreams` 68건, `upstream timed out` 55건, app 풀 대기 타임아웃 49건 — `rca:130,132,136`)는 **증상**으로 본다. 아래 가설은 모두 "왜 풀의 커넥션 5개가 모두 사용 중이 되었나"(또는 "왜 그 상태가 시작되었나")에 대한 답이다.
> 분 단위 상관 확인에 쓰는 명령은 1.4절의 수집 범위(14:17~15:02)로 줄여서 실행한다.

### 3.1 가설 요약

| # | 계층 | 가설 | 판정 (로그 확인 결과) |
|---|---|---|---|
| H1 | 애플리케이션 코드 | 학급 리포트 요청이 DB 작업이 끝난 뒤에도 트랜잭션(커넥션)을 오래 쥐어, 최대 5개인 풀이 고갈되었다 | **유지** — 누수 WARN 134건 전부 `classReport` 스택, 풀 고갈 구간에 리포트 완료 로그가 계속 있음 (3.2) |
| H2 | DB · 쿼리 | `submission` 조회 쿼리가 비정상적으로 많은 행을 읽어 커넥션 점유 시간이 길어졌다 | **기각** (지속 원인으로는) — 풀 고갈 14:38~14:51 동안 슬로우 쿼리는 14:37 한 건뿐. 최초 한 건이 계기였는지는 판단 불가 (3.3) |
| H3 | 트래픽 · 인프라 | 리포트 요청이 급증해(또는 재시도가 겹쳐) 풀 · upstream 처리 용량을 넘었다 | **유지** — 리포트 요청 증가는 사실이고 첫 5xx 에 선행(15건 전부 200). 용량 초과 여부는 `request_time` 부재로 정량 불가 (3.4) |
| H4 | 배포 · 설정 변경 | 14:37 직전에 앱 버전 · 풀 설정 · nginx 설정이 바뀌어, 기준선(14:17)과 다른 조건에서 돌고 있었다 | **판단 불가(로그 부족)** — 기동 · 배포 · 설정 변경을 보여 주는 로그가 이 폴더에 없음 (3.5) |

### 3.2 H1 — 애플리케이션 코드 (트랜잭션 점유)

| 항목 | 내용 | 실행 결과 (2026-10-06) |
|---|---|---|
| 1. 가설 | 리포트 생성 요청(`ReportController.classReport`)이 커넥션을 쥔 채 시간이 오래 걸리는 작업을 해서, 요청이 몰리면 커넥션 5개가 모두 점유되고 다른 API(`/api/units/M6-2/items` 등)도 풀 대기 타임아웃에 걸린다. | — |
| 2. 지지 근거 | `rca:121` 누수 감지 WARN 의 스택에 `ReportController.classReport` 포함(`app:1243`). `rca:124-125` 누수 WARN 134건과 "반환됨" INFO 134건이 같은 수 — 오래 쥐었다가 결국 돌려줌. `rca:129` 첫 오류가 `total=5, active=5, idle=0`(`app:1521`)인데 실패한 요청은 `/api/units/M6-2/items`(리포트가 아님). `rca:128,150` 같은 구간에 리포트 요청만 증가. 코드 쪽 후보: `modern/api/.../ReportService.java:41`(`@Transactional`), `:71`(`sign` 호출) — 단, 장애 당시 배포 버전과 같은 코드인지는 미확인. | 확인됨. 누수 WARN 134건 모두 `ReportController.classReport` 스택(아래 4행). 스택에 `TransactionInterceptor` → `ReportService$$SpringCGLIB$$0.buildClassReport` 가 있어(`app:1243` 이후) 실행된 빌드에도 `@Transactional` 경계가 있었다. |
| 3. 반증 조건 | (a) 누수 감지 WARN 스택(`app:1243` 이후 134건)의 상당수가 `classReport` 가 아닌 다른 진입점이다. (b) 장애 시각에 리포트 요청이 한 건도 풀을 쥐고 있지 않았다(리포트 완료 로그 `built class report` 가 풀 타임아웃 ERROR 와 시간상 겹치지 않는다). (c) 장애 당시 코드의 리포트 메서드가 트랜잭션 안에서 DB 외 작업을 하지 않는다. (d) 리포트 요청이 많았던 시간대(`rca:126` 의 평소 시간당 2~11건 이상 구간)에 풀 타임아웃이 없었다. | (a) **해당 안 됨** — 134건 중 134건이 `classReport`. (b) **해당 안 됨** — 풀 타임아웃 구간(14:40~14:49)에 `built class report` 가 분당 8~15건 있음. (c) **미확인** — 장애 당시 빌드의 소스가 자료에 없다(현재 코드는 `ReportService.java:71` 의 `sign` 이 트랜잭션 안). (d) **판정 근거 아님** — 14시 이전 시간당 2~11건에는 풀 타임아웃 0건이지만 평소 부하가 낮아 반증도 지지도 아니다. |
| 4. 확인 방법 | (a) `grep -n "Connection leak detection" -A12 incident-logs/a-connection-pool/app.log \| grep -c "ReportController"` 를 전체 건수 134 와 비교. (b) `awk` 로 `built class report` 시각과 `request timed out after 3000ms` 시각을 분 단위로 나란히 집계. (c) `modern/api/src/main/java/com/example/assignment/ReportService.java:41-90` 읽고, `git log --oneline -- modern/api/src/main/java/com/example/assignment/ReportService.java` 로 14:37 시점 버전 확인. (d) `grep "classes/.*/report" nginx-access.log` 를 시간당 집계해 같은 날 앞 시간대와 비교. | 실행 4건.<br>① `grep -c "Connection leak detection triggered" app.log` → **134**<br>`grep -A25 "Connection leak detection triggered" app.log \| grep -oE "com\.example\.[a-zA-Z.]*Controller\.[a-zA-Z]+" \| sort \| uniq -c` → **134 com.example.assignment.ReportController.classReport** (다른 진입점 0)<br>② 분(14시대)별 `grep "built class report" app.log \| grep -oE "T14:[0-9]{2}" \| sort \| uniq -c` → 14:38=3, 39=3, 40=10, 41=15, 42=10, 43=15, 44=11, 45=8, 46=13, 47=9, 48=10, 49=13, 50=9, 51=5. 같은 방식으로 `request timed out after 3000ms` → 14:40=1, 41=6, 42=2, 43=10, 44=2, 45=8, 46=4, 47=5, 48=4, 49=7 (합 49)<br>③ `grep "/report" nginx-access.log \| grep -oE "16/Sep/2026:[0-9]{2}" \| sort \| uniq -c` → 07시=2, 08=5, 09=2, 10=11, 11=7, 12=11, 13=8, **14=179**, 15=4<br>④ 코드 읽기와 `git log` 는 현재 저장소 기준이라 장애 빌드와의 동일성은 확인하지 않음 |
| 5. 판정 | — | **유지.** 풀을 쥔 요청이 전부 리포트 요청이고 풀 고갈 내내 리포트가 처리되고 있었다. 한계: 누수 WARN 은 "임계값 이상 점유"만 알려 주므로 점유가 DB 때문인지 서명 루프 때문인지는 구분하지 못한다. 이를 가르려면 리포트 요청별 단계 소요 시간(쿼리 종료 시각 · 서명 시작/종료 로그)이나 `$request_time`, 스레드 덤프가 필요하다. |

### 3.3 H2 — DB · 쿼리 (느린 쿼리가 커넥션 점유)

| 항목 | 내용 | 실행 결과 (2026-10-06) |
|---|---|---|
| 1. 가설 | `submission` 테이블을 `distribution_id=5` 로 조회하는 쿼리가 인덱스 부재 · 통계 변화 등으로 약 128만 행을 읽어 9.8초 걸렸고, 같은 유형의 느린 조회가 커넥션을 오래 쥐게 해 풀이 마르기 시작했다. | — |
| 2. 지지 근거 | `rca:120`(`slow:18-21`) Query_time 9.81s, Rows_examined 1,284,310, 14:37:32 경 시작. `rca:38` 같은 파일의 다른 두 건(2.3s · 2.0s)보다 현저히 느림. `rca:121` 누수 감지 첫 WARN(14:37:52)이 슬로우 쿼리 `# Time` 과 같은 초 — 단 선후는 불확실(`rca:152`). | 부분 확인. 슬로우 쿼리 1건(14:37:42 KST) 확인. 쿼리가 리포트가 쓰는 조회(`ReportService.java:53-54` 의 `findByDistributionIdOrderByStudentIdAsc`)와 같은 모양이고, 배포 5 는 class 3 의 것(`app:1278`)이다. |
| 3. 반증 조건 | (a) `slow` 항목이 3건뿐이고 14:40~14:51 풀 고갈 구간에 느린 쿼리가 추가로 없다(`rca:58`, `rca:49`)면 "느린 쿼리가 계속 커넥션을 점유했다"는 설명은 약해진다. (b) `submission.distribution_id` 에 인덱스가 있고 `EXPLAIN` 이 인덱스 범위 스캔이다. (c) 시드 데이터 규모상 배포 5의 제출이 10건 수준이라 128만 행을 읽을 수 없다(`rca:118` 리포트는 `3 distributions, 10 submissions`). (d) 슬로우 쿼리의 `SET timestamp` 가 14:37:42 가 아니라 누수 WARN 이후로 확인되어, 슬로우 쿼리가 결과이지 선행 사건이 아니다. | (a) **해당** — 풀 고갈 구간(14:38~14:51)에 슬로우 항목이 추가로 없음. (b) **로그만으로 판단 불가** — 로컬 시드 스키마에는 `idx_submission_distribution` 이 있으나(`db/mariadb/init/01-schema.sql:112`) 장애 DB 의 스키마가 아니다. (c) **판단 불가** — 시드는 제출 수십 건 규모라 128만 행과 맞지 않지만 장애 DB 행 수는 모른다. (d) **선후 미해소** — `SET timestamp`=14:37:42, 누수 WARN=14:37:52. |
| 4. 확인 방법 | (a) `sed -n 13,21p incident-logs/a-connection-pool/mariadb-slow.log` 로 쿼리 전문 · `SET timestamp` 확인, `TZ=Asia/Seoul date -d @<epoch>` 로 환산. (b) 로컬 DB 에서 읽기 전용 계정(`readonly` / [SECRET], `localhost:3306/itembank`)으로 `SHOW INDEX FROM submission;` 와 `EXPLAIN` — 스키마는 `db/mariadb/init/` 를 **읽기만** 한다. (c) `SELECT COUNT(*) FROM submission WHERE distribution_id=5;`. (d) 슬로우 쿼리와 같은 시각의 `app.log` 줄을 `sed -n 1235,1250p` 로 발췌. | 실행 3건.<br>① `sed -n 13,21p mariadb-slow.log` → `Query_time: 9.812417  Rows_sent: 4  Rows_examined: 1284310`, `SET timestamp=1789537062`, `... from submission ... where distribution_id=5 order by student_id`<br>`TZ=Asia/Seoul date -d @1789537062` → **Wed Sep 16 14:37:42 KST 2026**<br>② `grep -c "^# Time: 260916  5:" mariadb-slow.log` → **1** (14:37 한 건). 파일 전체 3건의 `Query_time`: 2.318, 2.042, 9.812 → 로그 기준 `long_query_time` 은 2s 이하로 추정(설정값은 로그에 없음)<br>③ `grep -in "long_query_time" mariadb-slow.log app.log` → 0건. `EXPLAIN` · `SHOW INDEX` 는 장애 DB 가 없어 실행하지 않음 |
| 5. 판정 | — | **기각 (지속 원인으로는).** 풀 고갈이 이어진 14분 동안 슬로우 쿼리가 더 없으므로 "느린 조회가 계속 커넥션을 쥐었다"는 설명은 맞지 않는다. 다만 14:37 의 9.8초 쿼리 1건이 최초 계기였는지는 **판단 불가** — 필요한 것: 장애 DB 의 `EXPLAIN` · `SHOW INDEX FROM submission` · `submission` 행 수, 14:37 전후 `SHOW PROCESSLIST` 스냅샷, `long_query_time` 설정값. |

### 3.4 H3 — 트래픽 · 인프라 (요청 증가 · 재시도 · upstream 용량)

| 항목 | 내용 | 실행 결과 (2026-10-06) |
|---|---|---|
| 1. 가설 | 14:38 이후 리포트 요청이 평소의 몇 배로 늘었고(특정 클라이언트의 폴링 · 재시도 포함), 처리 시간이 긴 리포트 요청이 동시에 몰려 앱 풀 · nginx upstream 의 동시 처리 한도를 넘었다. | — |
| 2. 지지 근거 | `rca:126,150` 리포트 요청이 14:19~14:37 0건 → 14:38 3건/분 → 14:40~14:50 10~19건/분, 같은 날 다른 시간대는 시간당 2~11건. 전체 요청 수는 분당 32~58건으로 변하지 않았다(`rca:128`) — 변한 것은 리포트 비율. `rca:131` 첫 504 도 리포트 요청(class 3). 14:52 이후 리포트 요청이 평소로 돌아가면서 오류도 사라짐(`rca:144`). | 확인됨. 14시 리포트 179건(`rca:126` 재현, 같은 날 다른 시간당 2~11건). |
| 3. 반증 조건 | (a) 리포트 요청 증가가 오류보다 **뒤**에 시작되었다(예: 504 를 받은 클라이언트의 재시도가 증가분의 대부분이면 원인이 아니라 결과다). (b) 증가분이 소수 client IP 의 단순 재시도이고 정상 사용자 부하는 평소 수준이다. (c) 같은 구간의 분당 리포트 요청 수가 평소 최댓값(시간당 11건 ≈ 분당 1건 미만)과 비교해 풀 5개를 채울 만큼 동시성을 만들지 못한다(요청당 점유 시간 대비). (d) 14:38~14:40 리포트 요청이 늘었는데도 풀 타임아웃은 14:40:52 에야 시작한 것(`rca:129`)이 단순 지연이 아니라 한계에 무관한 별개 사건이다. | (a) **해당 안 됨** — 첫 5xx(14:40:52) 이전 14:38~14:40:51 의 리포트 15건이 전부 200 이었으므로 초기 증가는 재시도가 아니다. 다만 후반은 아래 ①처럼 재시도 비중이 큼. (b) **해당 안 됨** — 소수 IP 의 단순 반복이 아니라 4개 IP 에 분산. (c) **판단 불가** — `request_time` 이 없어 요청당 점유 시간 · 동시성을 구할 수 없다. (d) **해당 안 됨** — 14:38~14:40 증가 뒤 14:40:52 에 첫 풀 타임아웃이 나타남(`app:1521`). |
| 4. 확인 방법 | (a) `awk` 로 `nginx-access.log` 의 `/classes/.*/report` 요청을 초 단위로 뽑아 504 응답 시각과 이후 같은 `client IP` 의 재요청 간격을 확인. (b) 리포트 요청의 `$remote_addr` · user-agent 별 집계: `grep "/report" nginx-access.log \| awk '{print $1}' \| sort \| uniq -c \| sort -rn \| head`. (c) 동일 방식으로 같은 날 06~14시 시간당 리포트 요청 수 재집계(`rca:126` 수치 재현). (d) `nginx-error.log:6-12` 를 열어 `upstream:` · `client:` · `request:` 필드 확인. nginx 설정(`proxy_read_timeout`, `max_fails`, `fail_timeout`) 파일은 이 저장소에 있는지 `find . -name "nginx*.conf"` 로 확인(없으면 "확인 필요"). | 실행 4건.<br>① `grep "16/Sep/2026:14:" nginx-access.log` 의 `/report` 요청에서 같은 IP 가 5xx 직후 120초 내 다시 요청한 건수를 awk 로 집계 → 리포트 **179건**, 그중 5xx **92건**, 5xx 후 120초 내 동일 IP 재요청 **141건** (거친 지표: 5xx 이후 같은 IP 의 모든 요청을 센다)<br>② 14:38~14:40:51 의 `/report` 상태 코드 → `15 200`<br>③ 14:38~14:50 `/report` 의 client IP → 교사단말B=**67**, 교사단말C=38, 교사단말A=32, 교사단말D=29. 06~13시 같은 집계 → 교사단말B=17, 교사단말A=16, 교사단말C=13 (교사단말D 은 없음)<br>④ 14:38~14:51 분별 리포트 상태: 14:41 `200x12 500x1 504x3`, 14:42 `200x5 502x5 504x5`, 14:45 `200x3 502x7 504x5`, 14:49 `200x5 502x3 504x8`, 14:51 `200x3 504x2` (전체는 `rca:137` 과 같은 추세)<br>nginx 설정 파일은 `find` 로 저장소에서 찾지 않음(장애 서버 설정이 아님) |
| 5. 판정 | — | **유지.** 요청 증가는 사실이고 첫 오류에 선행한다. 후반 증가분은 재시도 증폭 가능성이 크다(141/179). 한계: "처리 용량을 넘었다"는 `request_time` 이 없어 **정량 불가**이다. 필요한 로그: nginx `$request_time` · `$upstream_response_time`, 앱 요청 시작 · 종료 로그, Hikari 풀 `active/waiting` 시계열(메트릭). |

### 3.5 H4 — 배포 · 설정 변경 (기준선과의 차이)

| 항목 | 내용 | 실행 결과 (2026-10-06) |
|---|---|---|
| 1. 가설 | 14:17~14:18 의 정상 리포트 요청(기준선) 이후 14:37 사이에 앱 버전 · Hikari 설정(`maximum-pool-size=5`, `connection-timeout=3000`, 누수 감지 임계값) · nginx upstream 설정 중 하나가 바뀌어, 같은 요청이 더 오래 걸리거나 더 쉽게 실패하게 되었다. | — |
| 2. 지지 근거 | `rca:117-118` 같은 리포트 요청이 14:17~14:18 에는 200 으로 정상 처리(`app:1174-1175`). `rca:121` 누수 감지 WARN 은 "이전 구간에는 없음". 지지 근거는 이 정도뿐이라 **약하다** — 변경 사실을 직접 보여 주는 줄은 현재 자료에 없다. | 약함(처음부터). 이번 확인에서 보강되지 않음. |
| 3. 반증 조건 | (a) `app.log` 전체에 14:17~14:37 사이 앱 기동 · 재시작 로그(`Started ...Application`, `HikariPool-... Start completed`)가 없고, 기동 시각이 06:43 한 번뿐이다. (b) 풀 크기 `total=5` 가 장애 전 로그에서도 동일하다. (c) `rca:149` 처럼 nginx 로그에 reload · signal · restart 흔적이 0건이고, 설정 파일 변경 시각 · git 이력에 14:17~14:37 사이 변경이 없다. (d) 장애 전(06~14시)에 같은 리포트 요청이 같은 비율로 정상 처리된 이력이 있고 응답 코드가 달라지지 않았다. | (a) **판단 불가** — 기동 로그 줄 자체가 없다. (b) **판단 불가** — 풀 크기 `total=5` 는 장애 구간 오류 메시지에서만 보여 장애 전과 비교할 수 없다. (c) **일부 확인** — nginx 재적재 흔적 0건이나 변경 이력의 부재를 증명하지는 못한다. (d) **자료 없음** — 이 폴더에 배포 이력이 없다. |
| 4. 확인 방법 | (a) `grep -n "Started \|HikariPool.*Start\|Starting " incident-logs/a-connection-pool/app.log \| head`. (b) `grep -n "total=" incident-logs/a-connection-pool/app.log \| head -3` 와 장애 전 구간 비교. (c) `grep -in "reload\|signal\|worker process" incident-logs/a-connection-pool/nginx-error.log`(2절에서 0건 확인, `rca:149`) 와 `git log --since=2026-09-16 --until=2026-09-17 --stat`. (d) `incident-logs/README.md` 에 배포 기록이 있는지 읽어 본다. | 실행 5건.<br>① `grep -cE "Started \|Starting \|HikariPool-1\|Start completed" app.log` → **0**. `app.log:1` 은 06:43:56 의 `GlobalExceptionHandler` INFO(문항 없음)로, 기동 로그로 시작하지 않는 파일 조각<br>② `grep -oE "total=[0-9]+" app.log \| sort \| uniq -c` → **147 total=5** (49건 × 3줄, 전부 `app:1522` 이후 장애 구간)<br>③ `grep -ciE "reload\|signal\|worker process\|restart" nginx-error.log` → **0** (파일은 168줄, 09:12 이후이고 장애 전 줄은 `[warn]` 2건 · `[info]` 3건)<br>④ `git log --since=2026-09-16 --until=2026-09-17 --oneline \| wc -l` → 0 (이 저장소는 실습용이라 운영 배포 이력이 아님, 근거로 쓰지 않음)<br>⑤ `incident-logs/README.md` 확인 → `deploy-history.md` 는 `b-deploy-5xx/` 에만 있고 `a-connection-pool/` 에는 없음 |
| 5. 판정 | — | **판단 불가(로그 부족).** 반증도 지지도 할 수 없다. 있었다면 판단할 수 있던 로그: 앱 기동 로그(`Started ItemBankApplication`, `HikariPool-1 - Start completed`) 또는 컨테이너 재시작 이력, `a-connection-pool` 의 배포 이력(`deploy-history.md` 형식), 장애 전 구간의 풀 설정 값(`total=`·`maximumPoolSize` 로그), `nginx -T` 출력 · 설정 변경 이력. |

### 3.6 가설 간 관계 · 다음 단계

- H1 · H2 · H3 는 서로 배타적이지 않다(예: H3 의 요청 증가가 H1 의 코드 특성과 결합). 어느 하나가 맞는지, 몇 개가 함께 작용했는지는 판정하지 않는다.
- 가장 싼 확인부터 한다: H4(a~c, grep 3개) → H1(a, 스택 집계) → H3(a·b, awk 집계) → H2(DB 접근 필요).
- 한계: `nginx-access.log` 에 `request_time` 이 없어(`rca:104`) 요청별 점유 시간을 직접 볼 수 없다. H1 · H3 의 "오래 걸렸다"는 누수 감지 WARN 과 `built class report` 로그 간격으로만 간접 확인할 수 있다.
- 확인 필요: `modern/api` 의 현재 `ReportService` 가 장애 로그를 만든 빌드와 같은지는 자료에 없다. 위 H1 의 코드 근거는 현재 저장소 코드이며, 장애 당시 버전은 확인 필요.

### 3.7 로그 확인 후 정리 (2026-10-06)

- 확인 범위: `incident-logs/a-connection-pool/` 의 4개 파일만. 운영 DB 접속 · `EXPLAIN` 은 하지 않았다(장애 DB 없음).
- 원인으로 확정한 것은 없다. H1 · H3 은 **유지**, H2 는 지속 원인으로는 **기각**, H4 는 **판단 불가**이다.
- H1 과 H3 은 같은 사건의 두 면일 수 있다(요청 증가 + 요청당 커넥션 점유). 둘의 기여도를 가르려면 요청별 소요 시간(`request_time`)과 풀 메트릭이 필요하다.
- 남은 의심 동작(수정하지 않음): `ReportService.java:69-71` 의 TODO 주석은 서명 계산이 트랜잭션 안에서 돈다고 적고 있다. 코드와 주석은 일치한다.

### 3.8 판정

> 코드 · 설정 대조 결과다. 읽은 파일은 `modern/api/src/main/resources/application.yml`, `ReportController`, `ReportService`, `SubmissionRepository`, `DistributionRepository`, `ItemService`, `GlobalExceptionHandler` 와 로컬 스키마 `db/mariadb/init/01-schema.sql` 이다. 설정값은 파일에서 읽은 것만 썼고, 벤치마크 · 테스트 · 실행은 하지 않았다.
> 대조 가능성의 전제: 로그 스택의 `ReportController.java:22`(`app:1243` 이후)가 현재 저장소의 `ReportController.java:22`(`return reportService.buildClassReport(id);`)와 줄까지 일치한다. 이것이 장애 빌드와 현재 코드가 같다는 **간접 증거**이지만, 같은 커밋이라는 증명은 아니다(저장소에 두 파일의 이력은 초기 커밋 `1260542` 하나뿐이고 운영 배포 이력이 아님).

#### 3.8.1 설정값 (`modern/api/src/main/resources/application.yml`)

| 키 | 값 | 줄 | 로그와의 대응 |
|---|---|---|---|
| `spring.datasource.hikari.pool-name` | `itembank-pool` | `:10` | 오류 메시지 접두 `itembank-pool - Connection is not available` (`app:1522`) |
| `maximum-pool-size` | 5 | `:14` | `total=5` (`app:1522`, 147줄 전부) |
| `connection-timeout` | 3000 | `:15` | `request timed out after 3000ms` (`app:1522`) |
| `leak-detection-threshold` | 10000 | `:18` | 누수 WARN 은 연결을 10초 넘게 쥐었을 때 찍힘 (`app:1243`) |
| `minimum-idle` / `max-lifetime` / `idle-timeout` | 1 / 1800000 / 600000 | `:13,17,16` | 장애와 직접 대응 없음 |
| `spring.jpa.open-in-view` | `false` | `:22` | 연결은 Controller 로 넘어가기 전 트랜잭션 종료 시 반납됨 |

- `:11-12` 주석은 "운영 값과 같게 맞춘다"고 쓰여 있다. 값이 운영과 같다는 것은 **주석 기준**이며 파일만으로는 확인할 수 없다. 로그의 `total=5` · `3000ms` 가 위 값과 일치하는 것이 실제 근거다.
- `nginx` 설정(`proxy_read_timeout`, `max_fails` 등)과 Tomcat 스레드 설정은 저장소에 없다(`find . -name "*.conf"` 0건, `application.yml` 에 `server.tomcat` 키 없음).

#### 3.8.2 가설별 대조

| 가설 | 판정 | 근거 (파일:줄번호) |
|---|---|---|
| H1 트랜잭션 점유 | **지지한다** | ① `ReportService.java:41` `@Transactional` 이 `buildClassReport` 전체에 걸려, 메서드 시작 시 커넥션을 얻어 끝까지 쥔다. 로그 스택에도 `TransactionInterceptor` → `ReportService$$SpringCGLIB$$0.buildClassReport` → `ReportController.classReport(ReportController.java:22)` 가 있다(`app:1243` 이후 134건 전부). ② DB 조회는 `:43,46,53-54` 이고, `:71` 의 `sign(...)` 은 같은 트랜잭션 안에서 SHA-256 을 `SIGNATURE_ROUNDS = 200_000` 회(`:23,88`) 돈다. `:69-70` 의 TODO 주석("DB 작업은 위에서 끝났는데 커넥션을 쥔 채로 돈다")과 코드가 일치한다. ③ 트랜잭션 안에는 외부 HTTP · `sleep` · `synchronized` 가 없다(`grep -rnE "RestTemplate\|WebClient\|HttpClient\|Thread\.sleep\|@Async\|synchronized" modern/api/src/main` → 0건). 즉 이 메서드 안에서 쿼리 외에 시간을 쓰는 코드는 `sign` 과 집계 루프(`:52-63`)뿐이다. ④ 피해 요청: `ItemService.java:14` 클래스 전체 `@Transactional(readOnly = true)` 라서 `/api/units/M6-2/items` 도 트랜잭션 시작 시 풀에서 커넥션을 기다리고, `application.yml:15`(3000ms) 후 `Unable to acquire JDBC Connection`(`app:1538`)으로 실패한다. 풀이 `:14`(5개)라 리포트 요청 5개면 고갈된다. |
| H2 느린 쿼리 | **반박한다** (약함) | 로그의 쿼리 모양(`slow:21`: `from submission ... where distribution_id=5 order by student_id`, 컬럼 5개)은 `SubmissionRepository.java:8` `findByDistributionIdOrderByStudentIdAsc` + `Submission` 엔티티 컬럼과 일치하며 `ReportService.java:53-54` 에서만 호출된다(같은 트랜잭션). 그러나 로컬 스키마에는 `distribution_id` 인덱스가 있어(`01-schema.sql:112` `idx_submission_distribution`) 인덱스 조회라면 `Rows_examined: 1284310`(`slow:18`)이 `Rows_sent: 4` 와 어긋난다. 코드 · 스키마로는 128만 행 스캔이 설명되지 않는다. 다만 이 스키마는 로컬 시드용이어서 **장애 DB 의 인덱스 상태는 확인할 수 없다**. 코드에는 이 쿼리가 반복 호출될 이유가 없다(배포별 1회, `:52-54`). |
| H3 트래픽 · 인프라 | **관련 코드를 찾지 못했다** (앱 측은 부분 지지) | 인프라 쪽: nginx · Tomcat 설정은 저장소에 없다(위 3.8.1). 앱 측: `/api/classes/{id}/report` 는 캐시 · 제한이 없고 요청마다 `ReportService.java:43-71` 을 전부 다시 계산한다(`@Cacheable` · 레이트 리미트 코드 없음). 그래서 요청 증가는 풀 5개(`application.yml:14`)를 직접 소모한다. 요청 증가가 풀 고갈로 이어지는 **경로**는 코드로 확인되지만, 증가가 있었다는 사실 자체는 로그(`rca:126`)에만 있다. |
| H4 배포 · 설정 변경 | **관련 코드를 찾지 못했다** | `application.yml` 과 `ReportService.java` 의 `git log` 는 초기 커밋 `1260542` 하나뿐이라 장애 전후 변경 비교가 불가능하다. 풀 설정은 로그의 `total=5`, `3000ms` 와 일치해(3.8.1) "설정이 로그와 다르다"는 증거도 없다. `nginx` 설정 변경 이력은 저장소에 없다. |

#### 3.8.3 점유 시간 계산 (14:37 첫 건)

- HikariCP 누수 감지는 연결 획득 후 `leak-detection-threshold`(`application.yml:18`, 10초)가 지나면 WARN 을 낸다(HikariCP 동작 기준, 이 저장소 코드에서 확인한 것은 아님). 따라서 `app:1243`(14:37:52.199)의 연결은 약 14:37:42.2 에 획득되었고, 반환 로그 `app:1259`(14:38:01.902)까지 **약 19.7초** 쥐고 있었다. 이 계산은 위 가정에 의존한다.
- 같은 시각대 슬로우 쿼리(`slow:20-21`)는 `SET timestamp=1789537062`(14:37:42 KST)부터 `Query_time` 9.81초(`slow:18`) 동안 돌았다. 약 19.7초 중 약 9.8초가 이 쿼리로 설명되고, **약 9.9초는 DB 로그로 설명되지 않는다**. 그 시간에 트랜잭션 안에서 도는 코드는 `ReportService` 의 `sign` 과 집계뿐이지만(위 ③), `sign` 이 실제로 몇 초 걸리는지는 코드를 읽어서는 알 수 없고(실행 금지), 장애 로그에도 없다. **확인 필요.**
- 14:38 이후 134건의 누수 WARN 은 모두 10초 이상 점유인데(`:18`), 같은 시간에 슬로우 쿼리 항목은 없다(`rca:58`). 쿼리가 아닌 쪽 시간이 점유 시간의 상당 부분이라는 정황이지만, 슬로우 로그의 기준(2초 추정) 미만 쿼리 누적을 배제하지는 못한다.

#### 3.8.4 채택 · 기각 정리

| 구분 | 가설 | 사유 | 확신 수준 |
|---|---|---|---|
| **채택** | **H1** `ReportService.buildClassReport` 가 `@Transactional` 안에서 `sign`(`:71`)까지 실행해 커넥션을 오래 쥐고, 최대 5개인 풀(`application.yml:14`)이 리포트 요청으로 소진되어 다른 API 가 3초(`:15`) 후 실패했다 | 누수 WARN 134건이 전부 이 진입점(`rca` 3.2), 현재 코드가 로그의 스택 · 줄번호와 일치, 설정값이 로그의 `total=5` · `3000ms` 와 일치, 트랜잭션 안에 `sign` 이 있다는 TODO 주석과 코드가 일치 | **중간** |
| 기각 | H2 느린 쿼리 | 풀 고갈이 14분 이어졌는데 슬로우 쿼리는 14:37 한 건뿐(`rca` 3.3). 코드상 이 쿼리는 같은 트랜잭션 안의 호출이라 첫 건의 점유(약 9.8초)에는 **기여했을 수 있으나**, 지속 원인으로는 맞지 않다. 128만 행 스캔은 코드 · 로컬 스키마로 설명되지 않아(`01-schema.sql:112`) 장애 DB 확인 전까지는 별도 확인 항목으로 남긴다 | 낮음 (장애 DB 스키마 미확인) |
| 기각 (단독 원인으로는) | H3 요청 증가 | 요청 증가는 풀 소진을 앞당긴 **조건**이다. 점유 메커니즘은 H1 이 제공하고, 같은 증가라도 서명이 트랜잭션 밖이면 5개 풀을 쥐고 있을 이유가 코드에 없다. 증가의 크기 · 용량 한계는 코드로 확인되지 않고 nginx · Tomcat 설정도 저장소에 없다. H1 의 트리거 · 증폭 요인으로 둔다 | 중간 |
| 채택 안 함 (근거 없음) | H4 배포 · 설정 변경 | 반박된 것은 아니다. 변경을 보여 주는 이력 · 로그가 없고, 로그의 풀 값은 현재 설정과 일치해 지지 근거가 없다 | 낮음 |

**확신 수준 "중간"의 이유**

- 높일 수 있는 근거: 134건 전부가 같은 진입점이고, 스택의 `ReportController.java:22` 가 현재 코드와 줄까지 일치하며, 설정값(`:14,15,18`)이 로그의 `total=5` · `3000ms` · 10초 이상 점유와 맞다. 트랜잭션 안에 외부 호출이 없어 점유 시간을 만들 수 있는 코드는 `sign` · 집계 루프 · 쿼리뿐이다.
- 낮추는 근거: (1) 장애 당시 빌드가 현재 코드와 같은지 증명되지 않았다. (2) 점유 시간 중 `sign` 이 차지한 몫을 측정한 자료가 없다(3.8.3의 약 9.9초는 설명되지 않은 시간일 뿐). (3) 14:37 의 최초 계기(슬로우 쿼리 · 요청 증가 중 무엇)가 해소되지 않았다.
- 확신을 "높음"으로 올리려면: 리포트 요청별 단계 시간(쿼리 종료 · 서명 시작/종료) 로그 또는 스레드 덤프, 장애 DB 의 `EXPLAIN`, 장애 시점 배포 커밋 해시.

#### 3.8.5 범위 밖에서 발견한 것 (수정하지 않음)

- `ReportService.java:23` 의 `SIGNATURE_ROUNDS = 200_000` 은 이름 붙은 상수라 매직 넘버 규칙에는 걸리지 않으나, 값의 근거는 "외부 집계 시스템이 요구하는 반복 횟수"라는 주석(`:22`)뿐이다. 값의 의미는 **의미 확인 필요**.
- `ReportService.buildClassReport` 는 `CLAUDE.md` 가 실습 소재로 지정한 코드다. 이 문서에서는 **수정 방안을 제시하지 않았고 코드를 고치지 않았다**.

## 4. 원인

채택 가설은 3.8.4 의 H1(트랜잭션 점유)이고, H3(요청 증가)는 그 증폭 조건이다. 문장 뒤 `[C#]` 는 5절의 근거 줄이다.

### 4.1 직접 원인 (인과 순서)

1. 리포트 요청 `buildClassReport` 는 `@Transactional` 이라 시작 시 커넥션을 얻고, DB 조회가 끝난 뒤에도 SHA-256 200,000회 서명(`sign`)을 같은 트랜잭션 안에서 돌며 커넥션을 쥔다. [C1]
2. 14:37:42 경 시작한 리포트 요청 하나가 약 19.7초 동안 커넥션을 쥐었다. 이 중 9.8초는 `submission` 슬로우 쿼리로 설명되지만 나머지 약 9.9초는 로그로 설명되지 않는다(서명 시간으로 추정, 확인 필요). [C2]
3. 14:38 부터 리포트 요청이 분당 0건에서 3건, 14:40~14:50 에는 10~19건으로 늘었다. 요청 하나가 10초 이상 쥐므로 동시에 몇 건만 겹쳐도 풀 5개가 찬다. [C3]
4. 14:40:52 에 풀이 `active=5, idle=0, waiting=4` 가 되어, 리포트가 아닌 `/api/units/M6-2/items` 가 3초 대기 후 500 으로 실패했다. 이후 49건. [C4]
5. 앱이 응답하지 못하자 nginx 가 `upstream timed out`(504) → `temporarily disabled` → `no live upstreams`(502)로 이어져, 앱이 살아 있어도 요청이 거부되는 구간이 생겼다. 이 연쇄는 nginx 설정 파일이 없어 로그만으로 **추정**한다. [C5]
6. 504 를 받은 클라이언트의 재요청이 리포트 부하를 키웠다. 14시 리포트 179건 중 5xx 92건, 5xx 후 120초 내 동일 IP 재요청 141건(거친 지표). [C6]
7. 14:49:51 에 마지막 풀 타임아웃, 14:51:47 에 마지막 5xx 가 나고 14:52 이후 오류가 0건이다. 회복 원인은 로그에 없다(9절). [C7]

### 4.2 배경 원인 (직접 원인을 가능하게 한 것)

| # | 배경 | 근거 |
|---|---|---|
| B1 | 풀이 작고 대기가 짧다(5개 · 3초). 주석이 "트랜잭션을 오래 쥐면 금방 고갈"이라고 적고 있다 | `application.yml:11-15` |
| B2 | "해시 · 서명은 트랜잭션 밖에서" 규칙이 있는데 코드가 어겼고, TODO 로 남은 채 방치됐다. 기계적 검증이 없었다 | `CLAUDE.md` 금지 사항, `ReportService.java:69-71` |
| B3 | 리포트는 요청마다 전체 재계산하며 캐시 · 부하 제한이 없다 | `ReportService.java:43-71` |
| B4 | 모든 API 가 풀 하나를 공유해, 느린 리포트가 문항 조회까지 막는다(격리 없음) | `ItemService.java:14`, `application.yml:14` |
| B5 | 앱 기동 · 풀 지표 · 요청 소요 시간 기록이 없어 원인 분리가 어려웠다 | 1.5절, 3.5절 |

미해소: 14:37 에 첫 점유를 만든 계기(슬로우 쿼리 vs 요청 증가)는 판정하지 못했다(3.3절, 선후 불확실 `rca:152`).

## 5. 근거 로그 줄

경로는 `incident-logs/a-connection-pool/` 기준이다. 발췌는 80자 이내로 줄였다.

| 문장 | 근거 (파일:줄) | 발췌 |
|---|---|---|
| C1 트랜잭션 안 서명 | `ReportService.java:41` | `@Transactional` / `public ClassReport buildClassReport(Integer classId)` |
| | `ReportService.java:69-71,88` | `// DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다.` / `sign(payload.toString())` |
| | `app.log:1243` (이후 스택) | `ProxyLeakTask : Connection leak detection triggered for ...@6e8f14d3` (스택 `ReportController.java:22`) |
| C2 약 19.7초 점유 | `app.log:1243` | `14:37:52.199 WARN ... Connection leak detection triggered` |
| | `app.log:1259` | `14:38:01.902 INFO ... Previously reported leaked connection ... was returned` |
| | `mariadb-slow.log:18,20` | `Query_time: 9.812417 ... Rows_examined: 1284310` / `SET timestamp=1789537062` (=14:37:42 KST) |
| | `application.yml:18` | `leak-detection-threshold: 10000` (10초 점유 시 WARN) |
| C3 리포트 요청 증가 | `nginx-access.log:18801` | `14:38 ... "GET /api/classes/3/report HTTP/1.1" 200` (이후 분당 3 → 10~19건, `rca:126,150`) |
| C4 풀 고갈 · 무관 API 실패 | `app.log:1522` | `itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| | `app.log:1523` | `unhandled exception on /api/units/M6-2/items` |
| | `nginx-access.log:18918` | `14:40:52 "GET /api/units/M6-2/items HTTP/1.1" 500` |
| C5 nginx 연쇄 | `nginx-error.log:6` | `14:41:06 [error] upstream timed out (110: Connection timed out) while reading response header` |
| | `nginx-error.log:9-12` | `14:42:01 upstream server temporarily disabled` / `connect() failed (111: Connection refused)` / `14:42:04 no live upstreams` |
| | `nginx-access.log:18933` | `14:41:06 "GET /api/classes/3/report HTTP/1.1" 504 167` |
| C6 재시도 증폭 | `rca:208` ①③ (nginx-access.log `/report` 집계) | 14시 179건, 5xx 92건, 120초 내 재요청 141건. 교사단말B = 67건(평소 17건) |
| C7 종료 | `app.log:4979-4981` | `14:49:51.552 ... request timed out after 3000ms` (마지막) |
| | `nginx-access.log:19381`, `nginx-error.log:168` | `14:51:47 "GET /api/classes/3/report HTTP/1.1" 504 167` / `upstream timed out` (마지막) |
| B1 풀 설정 | `application.yml:14,15` | `maximum-pool-size: 5` / `connection-timeout: 3000` |
| B4 격리 없음 | `app.log:1538` | `Unable to acquire JDBC Connection [itembank-pool - ...]` (문항 조회가 같은 풀을 기다림) |

## 6. 수정안 (지금 당장, 제안만)

대상: `modern/api/src/main/java/com/example/assignment/` — 이 문서에서는 코드를 고치지 않았다.

1. **`ReportService.buildClassReport`(`:41-82`)에서 `@Transactional` 을 제거한다.** 메서드는 "조회 → 서명 → 응답 조립"만 하는 비트랜잭션 조율 메서드가 된다.
2. **조회·집계(`:43-67`)를 새 빈 `ReportQueryService`(같은 패키지)의 `@Transactional(readOnly = true)` 메서드로 옮긴다.** 반환값은 `payload` 문자열, 배포 · 제출 수, 평균 점수를 담은 record. `ReportService` 가 이 빈을 주입받아 호출한다. 같은 클래스 안 내부 호출로 쪼개면 프록시를 타지 않아 트랜잭션이 적용되지 않으므로 반드시 별도 빈으로 분리한다.
3. **`sign(...)`(`:71`)은 조회 메서드가 반환된 뒤, 트랜잭션 밖에서 호출한다.** `sign` 과 `SIGNATURE_ROUNDS`(`:23`)는 건드리지 않는다. 값의 의미는 "의미 확인 필요"로 남긴다.
4. **테스트(`ReportServiceTest`)**: 서명 결과와 `ClassReport` 필드가 이전과 같음을 확인하고, 조회 실패(`NotFoundException`)가 그대로 전파됨을 확인한다. 이관 · 리팩토링 성격이므로 기존 기대값은 바꾸지 않는다.
5. 효과 기대: 커넥션 점유가 조회 시간으로 한정되어, 리포트가 몰려도 풀이 서명 시간에 묶이지 않는다(서명이 점유 시간의 얼마였는지는 9절과 같이 미측정). **실제 점유 시간 변화는 측정하지 않았다.**
6. 포함하지 않는 것: 풀 크기 · 타임아웃 변경은 `CLAUDE.md` 금지 사항이므로 이 수정안에 넣지 않았다. 필요하면 별도로 승인받는다.

## 7. 재발 방지 (구조적 장치)

6절은 이번 결함의 수정이고, 아래는 같은 유형(트랜잭션 · 커넥션 장기 점유)을 코드 작성 단계에서 막는 장치다.

| # | 장치 | 내용 | 비고 |
|---|---|---|---|
| P1 | 자동 검증 | `@Transactional` 메서드 안의 외부 호출 · 해시 · 서명 · `sleep` 을 검사하는 테스트 또는 `convention-check` 항목 추가. 규칙이 문서에만 있어 이번에 어겨졌다(B2) | ArchUnit 은 의존성 변경이라 승인 필요 |
| P2 | 회귀 테스트 | 서명 시점에 `TransactionSynchronizationManager.isActualTransactionActive()` 가 false 임을 확인 | 6절과 같은 변경에서 추가 |
| P3 | 리뷰 기준 | `verify` Skill 체크리스트에 "트랜잭션 경계 안의 DB 외 작업" 항목 추가. TODO 로 남긴 위반은 머지 전에 이슈 번호를 요구 | |
| P4 | 부하 완충 | 리포트 결과 캐시 또는 요청 제한(레이트 리밋), 무거운 API 용 별도 풀(bulkhead)로 문항 조회와 격리(B3·B4) | 설정 · 의존성 변경은 승인 필요 |
| P5 | 클라이언트 재시도 | 504 후 지수 백오프 · 최대 재시도 횟수 제한(`modern/web` 쪽 `getJson` 확인 필요). 이번 후반부 재시도 비중이 컸다(C6) | |
| P6 | nginx upstream | `max_fails` · `fail_timeout` 값 검토. 단일 upstream 이 길게 제외되면 앱이 회복해도 502 가 이어진다(C5) | 설정 파일이 저장소에 없음, 확인 필요 |

## 8. 모니터링 항목

목표: 이번에는 사용자 영향(5xx) 3분 전에 이미 신호가 있었다. 아래는 그 신호를 경보로 만드는 제안이다. 시각은 이번 로그를 소급 적용한 값이며, 분 단위 집계는 해당 분이 끝난 시점에 경보가 울린다고 가정했다.

| 지표 (출처) | 제안 임계값 | 이번 타임라인에서 울렸을 시각 | 근거 |
|---|---|---|---|
| Hikari 누수 감지 WARN (`app.log`) | 1건 이상 → 알림(경고) | **14:37:52** (장애 5xx 보다 3분 앞) | `app.log:1243` |
| 같은 WARN 분당 건수 | 3건/분 이상 → 호출(긴급) | **14:39** 분 마감 (14:39 = 3건, `rca:124`) | `rca:124` |
| `hikaricp_connections_pending` > 0 이 30초 지속 (메트릭, 현재 없음) | 지속 시 긴급 | 14:40:52 이전(타임아웃 3초를 역산하면 늦어도 14:40:49). 정확한 시각은 로그로 확인 불가 | `app.log:1522` |
| 풀 사용률 `active/total` = 100% 가 30초 지속 | 긴급 | 위와 같음. 14:40:52 의 `active=5` 가 100% | `app.log:1522` |
| 5xx 건수 (`nginx-access.log`) | 5건/분 이상 | **14:41** 분 마감 (14:41 = 500·504 합 9건) | `rca:137` |
| 풀 대기 타임아웃 ERROR | 1건 이상 | **14:40:52** | `app.log:1522` |
| nginx `upstream timed out` | 1건 이상 | **14:41:06** | `nginx-error.log:6` |
| nginx `no live upstreams` | 1건 이상 → 긴급 | **14:42:04** (이미 502 구간) | `nginx-error.log:12` |
| 리포트 API 요청 수 | 분당 5건 이상이 3분 지속 (평소 최대는 시간당 11건) | **14:42** 분 마감 (14:40 = 10건, 14:41 = 15건, 14:42 = 10건 → 3분 충족) | `rca:126,150`, `rca:188` ② |
| 요청 지연 p95 (`$request_time`, 현재 기록 없음) | 3초 초과 | **산출 불가** — 로그에 없음. 9절 참고 | `rca:104` |

- 가장 일찍 울리는 것은 이미 설정된 `leak-detection-threshold`(10초)의 WARN 이다. 새 도구 없이 로그 알림 규칙 하나로 14:37:52 에 감지할 수 있었다.
- 5xx · nginx 오류 기반 경보는 모두 14:40:52 이후라서 "사용자가 먼저 겪은 뒤"에 울린다. 풀 · 누수 지표가 앞선다.
- 이번에 실제로 몇 시에 사람이 인지했는지는 자료에 없다(9절).

## 9. 확인하지 못한 것

| 항목 | 이유 | 다음을 위해 남겨야 할 로그 · 자료 |
|---|---|---|
| 장애 당시 빌드가 현재 코드와 같은가 | 배포 이력 · 기동 로그 없음. `ReportController.java:22` 줄 일치는 간접 증거(`rca:238`) | 앱 기동 로그(버전 · 커밋 해시), 배포 이력 |
| 점유 약 19.7초 중 `sign` 이 차지한 몫 | 약 9.9초는 설명 불가(`rca:266`) | 리포트 단계별 소요(조회 종료 · 서명 시작/종료) 로그, 스레드 덤프 |
| 14:37 최초 계기(슬로우 쿼리 vs 요청 증가) | 선후가 같은 초이고 `# Time` 과 `SET timestamp` 가 10초 어긋남 | 슬로우 로그 `log_slow_verbosity`, 앱 요청 시작 · 종료 시각(ms) |
| 128만 행 스캔 원인 | 장애 DB 의 인덱스 · 행 수 · 실행 계획이 없음 | 장애 시점 `EXPLAIN`, `SHOW INDEX FROM submission`, `SHOW PROCESSLIST` 스냅샷 |
| 14:49:51 이후 회복 원인 | 앱 ERROR 가 멈춘 뒤 nginx 5xx 가 14:51:47 까지 이어졌고 이유가 로그에 없음 | 요청 부하 · 클라이언트별 요청 수 추이, nginx upstream 상태 변화 |
| nginx 가 upstream 을 제외한 규칙 | `proxy_read_timeout` · `max_fails` · `fail_timeout` 설정 없음 | `nginx -T` 출력, 설정 변경 이력 |
| 요청당 지연 · 동시성 | `nginx-access.log` 에 `request_time` 없음 | `$request_time`, `$upstream_response_time` 필드 |
| 풀 · 스레드 상태 시계열 | 로그에는 14:40:52 이후 `total=5` 뿐 | Hikari 메트릭(`active`/`pending`), Tomcat 스레드 수 |
| 설정 변경 · 배포 여부(H4) | 판정 불가(`rca:219`) | 장애 전 풀 설정 로그, 변경 이력 |
| 교사단말D (평소에 없던 IP) 정체 | 로그에 IP 만 있음 | 클라이언트 식별 헤더 · 사용자 에이전트 정리 |
| 운영팀의 최초 인지 시각 | 자료에 없음 | 경보 · 문의 접수 기록 |

- 이 문서의 확신 수준(중간)은 위 첫 세 항목이 채워지면 "높음"으로 올릴 수 있다(3.8.4).

마스킹 적용: IP 11건(교사단말 별칭), 이메일 0건, 학생 식별자 1건, 토큰 0건, 비밀번호 · 접속 문자열 1건
