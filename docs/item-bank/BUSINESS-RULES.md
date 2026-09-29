# legacy/item-bank-php 비즈니스 규칙 후보

> 분석 범위: `legacy/item-bank-php` 의 `search.php` · `register.php` · `units.php` 전체와, 모듈이 읽는 뷰 `v_item_public`(`db/mariadb/init/01-schema.sql:50-70`).
> 선행 문서: [ARCHITECTURE.md](ARCHITECTURE.md), [ERD.md](ERD.md). 이 문서의 `search.php` · `register.php` · `units.php` 는 모두 `legacy/item-bank-php/` 아래 파일이고, `01-schema.sql` · `02-seed.sql` 은 `db/mariadb/init/` 아래 파일이다.
> 코드에서 확인한 것만 규칙으로 올렸다. 근거가 주석뿐인 내용은 §6 에 따로 모았다.

**수정(update) 기능은 이 모듈에 없다.** 모듈 전체에서 `UPDATE` 문자열은 `register.php:87` 의 `FOR UPDATE`(잠금) 한 곳뿐이고, `item` 을 고치는 화면 · SQL 이 없다. 그래서 "수정 시 검증" 규칙은 뽑지 못했다. 등록 시 검증만 §4 에 적는다.

확신도 기준
- **확실**: 조건문 · SQL 로 동작을 확인했다.
- **추정**: 이름 · 주석으로 짐작했다.

---

## 1. 문항 검색 — 조건 조합 (`search.php` `buildSearchQuery`)

### BR-01
- 규칙: 검색 조건(키워드 · 단원 · 난이도 · 태그)이 여러 개 주어지면 모두 AND 로 결합한다.
- 근거: `search.php:44`, `search.php:98`, `search.php:118`, `search.php:215`, `search.php:218`, `search.php:244`
- 근거 코드:
  ```php
  $where    = " WHERE 1=1";
  $where .= " AND (title LIKE ? OR stem LIKE ?)";
  $where .= " AND unit_code = ?";
  ```
- 확신도: 확실
- 비고: 난이도 조건은 값이 비어 있어도 항상 붙는다(BR-07). 그래서 조건을 하나도 주지 않아도 WHERE 에 `level < 5` 가 들어간다.

### BR-02
- 규칙: 같은 GET 파라미터가 배열로 오면(`?q[]=a&q[]=b`) 첫 값만 쓴다.
- 근거: `search.php:55`, `search.php:59`, `search.php:63`, `search.php:67`, `search.php:71`, `search.php:75`, `search.php:79`
- 근거 코드:
  ```php
  $q = is_array($params['q']) ? (string)reset($params['q']) : (string)$params['q'];
  ```
- 확신도: 확실
- 비고: 7개 파라미터 모두 같은 패턴이다.

### BR-03
- 규칙: 키워드는 앞뒤 공백을 제거하고, 100자를 넘으면 100자까지만 쓰고 경고를 띄운다.
- 근거: `search.php:85-88`
- 근거 코드:
  ```php
  $q = trim($q);
  if (mb_strlen($q, 'UTF-8') > 100) {
      $q = mb_substr($q, 0, 100, 'UTF-8');
  ```
- 확신도: 확실
- 비고: 매직 넘버 `100`. 폼 입력칸도 `maxlength="100"` 이다(`search.php:494`). 글자 수는 바이트가 아니라 UTF-8 문자 기준이다.

### BR-04
- 규칙: 키워드가 비어 있지 않으면 제목 또는 지문에 키워드가 포함된(`LIKE '%키워드%'`) 문항만 찾는다.
- 근거: `search.php:90`, `search.php:97-98`
- 근거 코드:
  ```php
  $like = '%' . $q . '%';
  $where .= " AND (title LIKE ? OR stem LIKE ?)";
  ```
- 확신도: 확실
- 비고: 키워드 안의 `%` · `_` 를 이스케이프하지 않아 와일드카드로 동작한다. 코드는 경고만 띄운다(`search.php:94-95`).

### BR-05
- 규칙: 키워드가 한 글자이면 경고만 띄우고 검색은 그대로 한다.
- 근거: `search.php:91-92`
- 근거 코드:
  ```php
  if (mb_strlen($q, 'UTF-8') === 1) {
      $warnings[] = '키워드가 한 글자라 결과가 많을 수 있습니다.';
  ```
- 확신도: 확실
- 비고: 최소 길이 제한은 없다.

### BR-06
- 규칙: 단원 코드가 주어지면 앞뒤 공백을 제거한 값과 `unit_code` 가 같은(`=`) 문항만 찾고, 형식이 틀리거나 소문자가 섞이거나 등록되지 않은 코드여도 조회는 그대로 하고 경고만 띄운다.
- 근거: `search.php:109-118`, `search.php:141-142`
- 근거 코드:
  ```php
  if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {
  if ($unit !== strtoupper($unit)) {
  $where .= " AND unit_code = ?";
  ```
- 확신도: 확실 (조건 · 경고 동작). 소문자 입력의 실제 결과는 확인 필요
- 비고:
  - 형식 정규식의 `{1,2}` 는 매직 넘버(§5).
  - 경고 문구는 "입력값 그대로 조회합니다"(`search.php:116`)라서 소문자면 0건일 것처럼 읽힌다. 하지만 테이블 콜레이션이 `utf8mb4_unicode_ci`(대소문자 무시, `01-schema.sql:33`)라서 `m5-1` 도 `M5-1` 과 일치할 수 있다. 실제 결과는 확인 필요.
  - 단원 이름 조회(`search.php:125`)는 요약 · 경고용이고 필터에는 영향이 없다.

### BR-07
- 규칙: 난이도 값이 비어 있으면 난이도 5 문항을 빼고(`level < 5`) 찾는다.
- 근거: `search.php:211-216`
- 근거 코드:
  ```php
  // 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
  if ($level == '') {
      $where .= " AND level < 5";
  ```
- 확신도: 확실
- 비고:
  - **주석과 코드가 다르다.** 주석(`search.php:213`)은 "1~5 모두 포함"이라고 쓰지만 코드는 난이도 5 를 뺀다. 파일 머리 주석도 `level : 난이도 (1~5, 빈값 허용)`(`search.php:8`)이라 전체 검색처럼 읽힌다.
  - 시드 주석은 코드 동작을 전제한다: "`status='A' AND level<5` 는 정확히 20건 (검색 화면 페이지 크기와 같다)"(`02-seed.sql:34`).
  - 조건 요약에는 이 조건이 나오지 않는다. 조건을 안 주면 "조건 없이 검색했습니다"(`search.php:355-356`)라고 표시하지만 실제로는 난이도 5 가 빠져 있다.
  - 이 조건은 기본 목록뿐 아니라 키워드 · 단원 · 태그만 준 검색에도 모두 붙는다. 난이도 5 문항은 `level=5` 를 직접 줘야만 나온다.
  - 매직 넘버 `5`. 의도인지 버그인지 확인 필요. characterization 비교 대상이다.
  - 비교가 `==`(느슨한 비교)이지만 `trim` 뒤의 문자열과 `''` 비교라 동작 차이는 확인하지 못했다.

### BR-08
- 규칙: 난이도가 `1`~`5` 중 한 자리 숫자이면 그 난이도와 같은 문항만 찾는다.
- 근거: `search.php:217-221`
- 근거 코드:
  ```php
  else if (preg_match('/^[1-5]$/', $level)) {
      $where .= " AND level = ?";
  ```
- 확신도: 확실
- 비고: 같은 정규식이 등록 검증에도 있다(`register.php:70`, BR-31). 등록은 거부하고 검색은 아래 BR-09 처럼 계속 조회한다.

### BR-09
- 규칙: 난이도가 비어 있지 않고 `1`~`5` 한 자리 숫자도 아니면 경고를 띄우고, 값을 정수로 바꾼(`(int)`) 결과와 같은 난이도를 찾는다.
- 근거: `search.php:223-229`
- 근거 코드:
  ```php
  $warnings[] = '난이도는 1~5 사이여야 합니다.';
  $where .= " AND level = ?";
  $values[] = (int)$level;
  ```
- 확신도: 확실
- 비고:
  - 주석(`search.php:224`)은 "대개 0건"이라고 쓰지만, PHP `(int)` 변환 규칙상 `01` → 1, `3abc` → 3, `2.9` → 2 로 바뀌어 **경고와 함께 결과가 나온다**. `abc` → 0 이면 0건이다. 실제 응답은 확인 필요.
  - 이 경우 `level < 5` 조건(BR-07)은 붙지 않는다.

### BR-10
- 규칙: 태그가 주어지면 앞뒤 공백을 제거한 값과 이름이 정확히 같은 태그가 붙은 문항만 찾는다.
- 근거: `search.php:235-236`, `search.php:244-245`
- 근거 코드:
  ```php
  $where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
          . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
  ```
- 확신도: 확실
- 비고:
  - 태그는 한 개만 받는다(BR-02). 여러 태그 조합(AND · OR) 검색은 없다.
  - `%` · `_` 가 있으면 "부분 일치를 지원하지 않습니다" 경고만 띄운다(`search.php:237-238`). `=` 비교라 문자 그대로 비교한다.
  - 필터는 뷰의 `tag_names` 문자열이 아니라 `item_tag` · `tag` 를 다시 조인한다(ERD.md §2).

### BR-11
- 규칙: 태그 이름이 50자를 넘으면 50자까지만 쓰고 경고를 띄운다.
- 근거: `search.php:240-242`
- 근거 코드:
  ```php
  if (mb_strlen($tag, 'UTF-8') > 50) {
      $tag = mb_substr($tag, 0, 50, 'UTF-8');
  ```
- 확신도: 확실
- 비고: 매직 넘버 `50`. `tag.name` 이 `VARCHAR(50)` 이다(`01-schema.sql:37`, ERD.md §1).

### BR-12
- 규칙: 등록되지 않은 태그 이름이어도 조회는 그대로 하고(결과 0건) 경고를 띄운다.
- 근거: `search.php:250-260`
- 근거 코드:
  ```php
  if ($opt['value'] !== '' && $opt['value'] === $tag) {
  ...
      $warnings[] = '등록되지 않은 태그입니다: ' . $tag;
  ```
- 확신도: 확실
- 비고: 등록 여부는 PHP 의 `===`(대소문자 구분)로 판정하지만, SQL 필터(BR-10)는 `utf8mb4_unicode_ci` 콜레이션이라 대소문자를 무시할 수 있다. 대소문자만 다른 태그는 "등록되지 않은 태그" 경고와 함께 결과가 나올 수 있다. 확인 필요.

---

## 2. 문항 검색 — 정렬 · 페이지

### BR-13
- 규칙: 정렬 기준(`sort`)이 비어 있으면 난이도 내림차순, 같은 난이도면 id 오름차순으로 정렬한다.
- 근거: `search.php:317-320`
- 근거 코드:
  ```php
  case '':
      // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순
      $orderBy = " ORDER BY level DESC, id ASC";
  ```
- 확신도: 확실
- 비고: 주석과 코드가 일치한다. 기본 목록에서는 난이도 5 가 빠지므로(BR-07) 첫 행은 난이도 4 문항이다.

### BR-14
- 규칙: 정렬 기준이 `id` · `title` · `unit` · `level` · `created` 가 아니면 경고를 띄우고 기본 정렬(BR-13)을 쓴다.
- 근거: `search.php:322-326`
- 근거 코드:
  ```php
  default:
      $warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';
      $orderBy = " ORDER BY level DESC, id ASC";
  ```
- 확신도: 확실
- 비고: 기본 정렬 SQL 이 `search.php:319` 와 `search.php:325` 두 곳에 같은 문자열로 따로 있다.

### BR-15
- 규칙: `sort` · `dir` 는 앞뒤 공백을 제거하고 소문자로 바꿔 비교하며, `dir` 가 `asc` · `desc` 가 아니면 방향을 지정하지 않은 것으로 본다(값이 있었으면 경고).
- 근거: `search.php:266-273`
- 근거 코드:
  ```php
  $sort = strtolower(trim($sort));
  $dir  = strtolower(trim($dir));
  if ($dir !== 'asc' && $dir !== 'desc') {
  ```
- 확신도: 확실
- 비고: 없음

### BR-16
- 규칙: 방향을 지정하지 않으면 `level` · `created` 는 내림차순, `id` · `title` · `unit` 은 오름차순으로 정렬한다.
- 근거: `search.php:278-282`, `search.php:286-290`, `search.php:294-298`, `search.php:302-306`, `search.php:310-314`, `search.php:328-331`
- 근거 코드:
  ```php
  if ($dir === 'asc') {
      $orderBy = " ORDER BY level ASC, id ASC";
  } else {
  ```
- 확신도: 확실
- 비고: `id` · `title` · `unit` 은 `if ($dir === 'desc')` 로, `level` · `created` 는 `if ($dir === 'asc')` 로 분기해 기본 방향이 반대다. 화면 표시용 기본 방향도 같은 규칙으로 다시 계산한다(`search.php:330`).

### BR-17
- 규칙: `id` 이외의 정렬 기준에서는 마지막 기준으로 id 오름차순을 붙이고, 이 id 방향은 `dir` 와 무관하게 항상 오름차순이다.
- 근거: `search.php:287`, `search.php:289`, `search.php:295`, `search.php:297`, `search.php:303`, `search.php:305`, `search.php:311`, `search.php:313`, `search.php:319`
- 근거 코드:
  ```php
  $orderBy = " ORDER BY title DESC, id ASC";
  $orderBy = " ORDER BY created_at DESC, id ASC";
  ```
- 확신도: 확실
- 비고: 없음

### BR-18
- 규칙: 단원 정렬(`sort=unit`)은 단원 코드 방향만 `dir` 를 따르고, 2차 기준 난이도는 항상 내림차순이다.
- 근거: `search.php:295`, `search.php:297`
- 근거 코드:
  ```php
  $orderBy = " ORDER BY unit_code DESC, level DESC, id ASC";
  $orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
  ```
- 확신도: 확실
- 비고: 단원 코드는 문자열 정렬이라 `M10-1` 이 `M5-1` 보다 앞에 올 수 있다. 시드에 두 자리 학년 코드가 있는지는 확인하지 않았다.

### BR-19
- 규칙: 결과 표의 정렬 링크는 ID · 제목 · 단원 · 난이도 열에만 있고, 현재 정렬 열을 누르면 방향을 뒤집으며, 다른 열은 첫 클릭에 오름차순(난이도만 내림차순)이다.
- 근거: `search.php:392-413`, `search.php:418`
- 근거 코드:
  ```php
  } else if ($key === 'level') {
      // 난이도는 첫 클릭에 내림차순
      $nextDir = 'desc';
  ```
- 확신도: 확실
- 비고: `created` 정렬은 SQL 에는 있지만(`search.php:309-315`) 링크가 없어 URL 을 직접 입력해야 쓸 수 있다. 태그 열은 정렬 링크 없이 글자만 있다(`search.php:418`).

### BR-20
- 규칙: 검색 결과는 한 페이지에 20건씩 보여 주고, `(page - 1) × 20` 건을 건너뛴다.
- 근거: `search.php:349`, `search.php:523`, `search.php:553`
- 근거 코드:
  ```php
  $offset = ($page - 1) * 20;
  $limit  = " LIMIT 20 OFFSET " . (int)$offset;
  'page_size'    => 20,
  ```
- 확신도: 확실
- 비고: 매직 넘버 `20` 이 세 곳에 따로 적혀 있다. 주석 `한 페이지 20건`(`search.php:334`)과 일치한다. 총 건수는 같은 WHERE 로 `COUNT(*)` 를 따로 센다(`search.php:526`).

### BR-21
- 규칙: 페이지 번호가 숫자로만 되어 있지 않으면 경고를 띄우고 1페이지를 보여 주고, 1 미만이면 1 로, 999 를 넘으면 999 로 맞추고 경고를 띄운다.
- 근거: `search.php:336-348`
- 근거 코드:
  ```php
  if (preg_match('/^[0-9]+$/', $pageRaw)) {
  if ($page > 999) {
      $page = 999;
  ```
- 확신도: 확실
- 비고:
  - 매직 넘버 `999`.
  - `page=0` 은 숫자라 경고 없이 1 이 된다(`search.php:342-344`). `page=` (빈 값)도 경고 없이 1 이다(`search.php:339`). `page=-1` 은 경고 후 1 이다.
  - 총 페이지 수를 넘는 번호(예: 결과 20건에 `page=5`)는 막지 않는다. 이때 건수는 그대로 나오고 표는 행 없이 출력될 것으로 본다(`search.php:655`, `search.php:657`). 실제 화면은 확인 필요.

### BR-22
- 규칙: 검색 결과가 0건이면 결과 표 대신 "검색 결과가 없습니다" 문구를 보여 준다.
- 근거: `search.php:657-659`
- 근거 코드:
  ```php
  if ($total === 0) {
      echo '<p id="message">검색 결과가 없습니다</p>' . "\n";
      return;
  ```
- 확신도: 확실
- 비고: 판정 기준은 전체 건수(`total`)다. 현재 페이지 행이 0 이어도 전체 건수가 1 이상이면 빈 표를 출력한다.

---

## 3. 목록 · 검색의 제외 조건

### BR-23
- 규칙: 문항 검색은 상태가 `'A'` 인 문항만 대상으로 한다(`'R'` · `'D'` 등은 제외).
- 근거: `search.php:522`, `01-schema.sql:70`
- 근거 코드:
  ```php
  $from   = " FROM v_item_public";
  ```
  ```sql
  WHERE i.status = 'A';
  ```
- 확신도: 확실
- 비고:
  - `'A'` · `'R'` · `'D'` 의 의미(공개 · 검수중 · 삭제)는 스키마 주석(`01-schema.sql:26`)에만 있다. 코드는 값만 비교한다.
  - 뷰가 `unit` 과 INNER JOIN 하므로(`01-schema.sql:69`) 단원이 없는 문항도 빠진다. FK(`01-schema.sql:32`)가 있어 실제로는 생기지 않을 것으로 본다(추정).

### BR-24
- 규칙: 단원 목록의 "공개 문항 수"는 상태가 `'A'` 인 문항만 세고, 난이도는 거르지 않는다.
- 근거: `units.php:16-17`
- 근거 코드:
  ```php
  $sql = "SELECT u.id, u.code, u.name, u.grade,
                 (SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
  ```
- 확신도: 확실
- 비고:
  - **같은 규칙이 두 곳에 다르게 구현돼 있다.** 공개 판정 `status = 'A'` 가 뷰(`01-schema.sql:70`)와 `units.php:17` 에 따로 있다. `units.php` 는 뷰를 쓰지 않는다.
  - 단원 목록 건수는 난이도 5 를 포함하지만, 같은 단원을 검색 화면에서 난이도 없이 찾으면 난이도 5 가 빠진다(BR-07). 두 화면의 건수가 다를 수 있다.
  - 주석 `삭제 · 검수중 문항은 세지 않는다`(`units.php:15`)는 코드와 일치한다.
  - 문항이 0건인 단원도 목록에 나온다(상관 서브쿼리라 `unit` 행이 모두 남는다).

### BR-25
- 규칙: 단원 목록과 단원 선택 목록(검색 · 등록)은 학년 오름차순, 같은 학년이면 단원 코드 오름차순으로 정렬한다.
- 근거: `units.php:19`, `search.php:156`, `register.php:27`
- 근거 코드:
  ```php
           ORDER BY u.grade ASC, u.code ASC";
  $res = $conn->query("SELECT code, name, grade FROM unit ORDER BY grade ASC, code ASC");
  $res = $conn->query("SELECT id, code, name FROM unit ORDER BY grade ASC, code ASC");
  ```
- 확신도: 확실
- 비고: 검색 폼은 단원을 학년별 `<optgroup>` 으로 묶는다(`search.php:452-462`).

### BR-26
- 규칙: 태그 선택 목록(검색 · 등록)과 결과 표의 태그 표시는 태그 id 오름차순이다.
- 근거: `search.php:178`, `register.php:34`, `01-schema.sql:64`
- 근거 코드:
  ```php
  $res = $conn->query("SELECT name FROM tag ORDER BY id ASC");
  ```
  ```sql
  (SELECT GROUP_CONCAT(t.name ORDER BY t.id SEPARATOR ',')
  ```
- 확신도: 확실
- 비고: 태그가 없는 문항은 `tag_names` 가 NULL 이고 빈 칸으로 표시한다(`search.php:678`).

---

## 4. 문항 등록 — 검증 · 저장 (`register.php`)

### BR-27
- 규칙: 제목은 앞뒤 공백을 제거한 뒤 5자 미만이면 등록을 거부한다.
- 근거: `register.php:41`, `register.php:49-50`
- 근거 코드:
  ```php
  $title  = isset($_POST['title'])  ? trim((string)$_POST['title'])  : '';
  if (mb_strlen($title, 'UTF-8') < 5) {
      $errors[] = '제목은 5자 이상 입력해야 합니다.';
  ```
- 확신도: 확실
- 비고: 매직 넘버 `5`. 주석 `제목은 5자 이상 (공백 제외 아님, 앞뒤 공백만 제거)`(`register.php:48`)과 일치한다. 가운데 공백은 글자 수에 들어간다. 폼 라벨도 `제목 (5자 이상)`(`register.php:143`)이다.

### BR-28
- 규칙: 제목이 200자를 넘으면 등록을 거부한다.
- 근거: `register.php:52-53`
- 근거 코드:
  ```php
  if (mb_strlen($title, 'UTF-8') > 200) {
      $errors[] = '제목은 200자를 넘을 수 없습니다.';
  ```
- 확신도: 확실
- 비고: 매직 넘버 `200`. `item.title` 이 `VARCHAR(200)` 이다(`01-schema.sql:23`).

### BR-29
- 규칙: 지문이 앞뒤 공백을 제거한 뒤 빈 문자열이면 등록을 거부한다.
- 근거: `register.php:42`, `register.php:56-57`
- 근거 코드:
  ```php
  if ($stem === '') {
      $errors[] = '지문을 입력해야 합니다.';
  ```
- 확신도: 확실
- 비고: 지문 최대 길이 검증은 없다(`item.stem` 은 `TEXT`, `01-schema.sql:24`).

### BR-30
- 규칙: 단원 id 가 DB 의 단원 목록 id 중 하나와 문자열로 정확히 같지 않으면 등록을 거부한다.
- 근거: `register.php:60-67`
- 근거 코드:
  ```php
  if ((string)$u['id'] === $unitId) {
      $unitOk = true;
  ```
- 확신도: 확실
- 비고: 문자열 비교라 `01` 처럼 앞에 0 이 붙은 값은 거부된다. 검색은 단원 **코드**로, 등록은 단원 **id** 로 받는다.

### BR-31
- 규칙: 난이도가 `1`~`5` 중 한 자리 숫자가 아니면 등록을 거부한다.
- 근거: `register.php:70-71`
- 근거 코드:
  ```php
  if (!preg_match('/^[1-5]$/', $level)) {
      $errors[] = '난이도는 1~5 사이여야 합니다.';
  ```
- 확신도: 확실
- 비고: **같은 규칙이 여러 곳에 다르게 구현돼 있다.** 검색(`search.php:217`)은 같은 정규식을 쓰지만 벗어나면 거부하지 않고 정수로 바꿔 계속 조회한다(BR-09). DB 에는 CHECK 제약이 없다(ERD.md §1).

### BR-32
- 규칙: 태그는 DB 태그 목록에 있는 id 만 저장하고, 목록에 없는 id 는 오류 없이 버린다.
- 근거: `register.php:45`, `register.php:74-81`
- 근거 코드:
  ```php
  if ((string)$t['id'] === (string)$tid) {
      $validTagIds[] = (int)$tid;
  ```
- 확신도: 확실
- 비고:
  - 태그는 필수가 아니다(0개 허용, `register.php:104`). 개수 상한도 없다.
  - `tags` 가 배열이 아니면 태그 없음으로 본다(`register.php:45`).
  - 같은 id 가 두 번 오면 `$validTagIds` 에 두 번 들어가 `item_tag` PK 위반으로 등록 전체가 롤백될 수 있다(ERD.md §4). 재현은 확인 필요.

### BR-33
- 규칙: 검증 오류가 하나라도 있으면 저장하지 않고, 모든 오류 문구를 한꺼번에 보여 준다.
- 근거: `register.php:83`, `register.php:133-138`
- 근거 코드:
  ```php
  if (empty($errors)) {
  ```
- 확신도: 확실
- 비고: 검증은 첫 오류에서 멈추지 않고 제목 · 지문 · 단원 · 난이도를 모두 검사한다(`register.php:49-72`). 실패하면 입력값을 폼에 다시 채운다(`register.php:144`, `register.php:148`).

### BR-34
- 규칙: 새로 등록한 문항은 상태 `'R'` 로 저장하므로, 등록 직후에는 검색에 나오지 않는다.
- 근거: `register.php:93-94`, `01-schema.sql:70`
- 근거 코드:
  ```php
  "INSERT INTO item (id, unit_id, title, stem, level, status, created_at, updated_at)
   VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
  ```
- 확신도: 확실
- 비고:
  - 주석 `등록 직후에는 검수중(R) 상태로 들어간다 → 검수 완료 전까지 검색 화면에 나오지 않는다.`(`register.php:84`)와 일치한다.
  - 스키마 기본값은 `'A'`(`01-schema.sql:26`)지만 등록 화면은 `'R'` 을 명시한다.
  - `'R'` → `'A'` 로 바꾸는 코드는 이 모듈에 없다(ERD.md §4).

### BR-35
- 규칙: 새 문항 id 는 트랜잭션 안에서 `item` 의 최대 id + 1(문항이 없으면 1)로 정한다.
- 근거: `register.php:85`, `register.php:87`, `register.php:90`
- 근거 코드:
  ```php
  $res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");
  ```
- 확신도: 확실
- 비고: `item.id` 는 AUTO_INCREMENT 가 아니다(`01-schema.sql:21`). 삭제(`'D'`) 문항도 행이 남아 있으면 MAX 에 들어간다. 동시 등록 시 동작은 확인 필요(ARCHITECTURE.md §6).

### BR-36
- 규칙: 문항과 태그 연결은 한 트랜잭션으로 저장하고, 하나라도 실패하면 모두 되돌린 뒤 "저장 중 오류가 발생했습니다"를 보여 준다.
- 근거: `register.php:85`, `register.php:104-114`, `register.php:119-122`
- 근거 코드:
  ```php
  $conn->commit();
  ...
      $conn->rollback();
      $errors[] = '저장 중 오류가 발생했습니다.';
  ```
- 확신도: 확실
- 비고: `$stmt->execute()` 가 `false` 를 돌려주면 직접 예외를 던진다(`register.php:99-100`, `register.php:108-109`). mysqli 예외 모드 설정은 `inc/db.php` 에서 확인하지 않았다.

### BR-37
- 규칙: 등록 시각(`created_at`)과 수정 시각(`updated_at`)은 DB 서버의 현재 시각(`NOW()`)으로 같은 값을 넣는다.
- 근거: `register.php:94`
- 근거 코드:
  ```php
   VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
  ```
- 확신도: 확실
- 비고: 시드는 고정 시각(`2026-09-01 09:00:00`, `02-seed.sql:38`)이다. `sort=created`(BR-16) 결과에 영향이 있다.

---

## 5. 매직 넘버

"추정 의미"는 주변 코드 · 메시지 · 스키마로 짐작한 것이다. 확정하려면 담당자 확인이 필요하다(의미 확인 필요).

| 값 | 위치 | 추정 의미 |
|---|---|---|
| `100` | `search.php:86`, `search.php:87`, `search.php:494`(`maxlength`) | 검색 키워드 최대 글자 수 |
| `1` | `search.php:91` | "결과가 많을 수 있다" 경고를 띄우는 키워드 길이 |
| `{1,2}` · `{1,2}` | `search.php:111` | 단원 코드 형식 `영문 1자 + 숫자 1~2자 - 숫자 1~2자`. 앞은 학년, 뒤는 단원 순번으로 추정 (의미 확인 필요) |
| `5` | `search.php:215` | 난이도 없이 검색할 때 제외하는 난이도 경계(`level < 5`). 주석과 달라 의도 확인 필요 |
| `1`~`5` (`[1-5]`) | `search.php:217`, `register.php:70`, `search.php:193-198`, `register.php:163` | 난이도 허용 범위. 라벨은 1 매우 쉬움 ~ 5 최상 (`search.php:194-198`) |
| `50` | `search.php:240`, `search.php:241` | 태그 이름 최대 글자 수 (`tag.name VARCHAR(50)` 과 같음) |
| `20` | `search.php:349`, `search.php:523`, `search.php:553` | 검색 결과 페이지 크기 |
| `999` | `search.php:345`, `search.php:346` | 페이지 번호 상한 (최대 19,980번째 행까지 조회 가능) |
| `5` | `register.php:49` | 제목 최소 글자 수 |
| `200` | `register.php:52` | 제목 최대 글자 수 (`item.title VARCHAR(200)` 과 같음) |
| `0` · `+ 1` | `register.php:87` | 문항이 없을 때 첫 id 를 1 로 시작 |
| `'A'` | `units.php:17`, `01-schema.sql:70` | 공개 상태 코드 (주석상 공개) |
| `'R'` | `register.php:94` | 등록 직후 상태 코드 (주석상 검수중) |

---

## 6. 주석만 있음 · 주석과 코드 불일치 · 확인 필요

### 주석만 있음 (규칙으로 올리지 않음)

- **상태 `'D'` = 삭제**: 스키마 주석(`01-schema.sql:26`)에만 있다. 모듈 코드에는 `'D'` 가 나오지 않는다. 검색에서 빠지는 것은 BR-23(`status = 'A'` 만 대상)의 결과일 뿐, `'D'` 를 따로 다루는 코드는 없다.
- **등록 화면도 `v_item_public` 을 기준으로 한다**: 스키마 주석(`01-schema.sql:50-51`)의 내용이다. `register.php` 는 뷰를 참조하지 않고 `unit` · `tag` · `item` 을 직접 읽고 쓴다(`register.php:27`, `register.php:34`, `register.php:87`). 코드와 다르다.
- **노출 여부는 삭제 플래그가 아니라 status 코드로 판정한다**: 스키마 주석(`01-schema.sql:50`). 코드상으로도 status 로 거르지만(BR-23), 삭제 플래그 컬럼 자체가 없으므로 주석이 말하는 대비는 확인할 수 없다.

### 주석과 코드가 다름 (코드 기준으로 규칙을 씀)

| 위치 | 주석 | 코드 | 관련 규칙 |
|---|---|---|---|
| `search.php:213` | 난이도가 비면 1~5 모두 포함 | `level < 5` 로 난이도 5 제외 (`search.php:215`) | BR-07 |
| `search.php:8` | `level : 난이도 (1~5, 빈값 허용)` | 빈 값이면 1~4 만 | BR-07 |
| `search.php:224` | 1~5 밖의 값은 대개 0건 | `(int)` 변환이라 `01` · `3abc` 등은 결과가 나옴 | BR-09 |
| `search.php:116` (경고 문구) | 입력값 그대로 조회 | 콜레이션이 대소문자 무시라 소문자도 일치할 수 있음 (확인 필요) | BR-06 |
| `search.php:33-36` (docblock) | 반환 키 목록 | `warning_html` · `form_html` · `page_prefix` 키가 더 있음 (`search.php:547`, `search.php:549`, `search.php:551`) | — (ARCHITECTURE.md §6) |
| `01-schema.sql:51` | 등록 화면도 뷰 기준 | 등록 화면은 뷰를 쓰지 않음 | BR-34 |

### 같은 규칙이 여러 곳에 다르게 구현됨

- **공개 판정**: 뷰(`01-schema.sql:70`)와 `units.php:17`. 단원 목록 건수와 검색 기본 건수가 난이도 5 만큼 다를 수 있다 (BR-07, BR-24).
- **난이도 범위 검증**: 등록(`register.php:70`)은 거부, 검색(`search.php:217`, `search.php:223-229`)은 경고 후 조회 (BR-09, BR-31).
- **기본 정렬 SQL**: `search.php:319` 와 `search.php:325` 에 같은 문자열이 두 번 (BR-14).
- **페이지 크기 20**: `search.php:349` · `search.php:523` · `search.php:553` 세 곳에 따로 (BR-20).
- **단원 지정 방식**: 검색은 단원 코드(`search.php:118`), 등록은 단원 id(`register.php:62`) (BR-06, BR-30).

### 확인 필요

- 난이도 빈 값일 때 난이도 5 제외(BR-07)가 의도인지 버그인지. 이관 시 characterization 비교 대상.
- 소문자 단원 코드 · 대소문자만 다른 태그가 콜레이션 때문에 실제로 일치하는지 (BR-06, BR-12).
- `level=01` · `level=3abc` 의 실제 응답 (BR-09).
- 총 페이지 수를 넘는 `page` 의 화면 (BR-21).
- 태그 id 중복 전송 시 롤백 재현 (BR-32).
- 수정 · 상태 변경(`R` → `A`, `A` → `D`) 경로가 어디에 있는지. 이 모듈에는 없다.

---

## 이관 회고

| 바뀐 지점 | 레거시 동작 | AI가 만든 동작 | 테스트가 잡았나 | 다음에 막을 방법 |
|---|---|---|---|---|
| 난이도 미지정 검색 | 난이도 5를 빼고 검색 | 전체 난이도를 검색 | 잡음 | 규칙 비고에 "주석과 다름"을 적어 두고 경계 케이스를 세움 |
