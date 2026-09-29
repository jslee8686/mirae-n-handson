# item-bank-php 비즈니스 규칙 추출 (교차 검증용)

- 대상: `legacy/item-bank-php` 의 `search.php`, `register.php`, `units.php` (`index.php` · `inc/db.php` 는 규칙 없음 확인).
- 방법: 위 파일의 코드만 읽었다. `docs/` · `vendor/` 는 읽지 않았다. 주석과 코드가 다르면 코드를 기준으로 썼다.
- 한계
  - 수정 화면(`edit.php` 등)은 찾지 못했다(`edit.php` 는 없음 확인). 디렉터리 목록을 뽑지 못해(셸 실행 실패) 파일명을 추정해 읽었으므로, 등록 · 수정 중 **수정 검증 규칙은 확인 필요**다.
  - 검색이 조회하는 뷰 `v_item_public` 의 정의는 이 폴더 밖(DB 초기화 스크립트)에 있어 읽지 않았다. 뷰가 제외하는 조건은 **확인 필요**다.

## 1. 검색 조건 조합

### CX-01 키워드는 제목 또는 지문에 부분 일치한다
- 규칙: `q` 는 앞뒤 공백을 제거하고 100자로 자른 뒤, 제목 · 지문 중 하나에 포함되면 일치한다(`%` · `_` 는 와일드카드로 그대로 동작).
- 근거: `legacy/item-bank-php/search.php:85-89`, `:97-98`
```php
$q = trim($q);
$like = '%' . $q . '%';
$where .= " AND (title LIKE ? OR stem LIKE ?)";
```

### CX-02 단원은 코드 정확 일치이며, 형식 · 대소문자는 경고만 한다
- 규칙: `unit` 은 `unit_code = ?` 로 비교한다. 형식이 틀리거나 소문자여도 정규화하지 않고 입력값 그대로 조회한다(경고만 표시).
- 근거: `search.php:111-120`
```php
if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) { $warnings[] = ...
if ($unit !== strtoupper($unit)) { $warnings[] = '... (입력값 그대로 조회합니다)';
$where .= " AND unit_code = ?";
```

### CX-03 난이도를 비우면 난이도 5 문항이 제외된다 (주석과 코드 불일치)
- 규칙: `level` 이 비어 있으면 `level < 5` 조건이 붙어 난이도 5 는 결과에서 빠진다. 난이도 5 는 `level=5` 로 명시해야만 나온다.
- 주석은 "전체 난이도 검색 (1~5 모두 포함)"(`:213`)이라고 하나 코드는 1~4 만 포함한다. 코드를 기준으로 썼다.
- 근거: `search.php:213-216`
```php
// 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
if ($level == '') {
    $where .= " AND level < 5";
```

### CX-04 난이도 1~5 는 정확 일치, 그 밖의 값은 정수로 바꿔 비교한다
- 규칙: `level` 이 `1`~`5` 한 자리면 `level = ?`. 그 밖의 값은 경고 후 `(int)` 변환 값으로 `level = ?` 비교한다(숫자가 아니면 0).
- 근거: `search.php:217-229`
```php
else if (preg_match('/^[1-5]$/', $level)) { $where .= " AND level = ?"; ...
else { $warnings[] = '난이도는 1~5 사이여야 합니다.'; ... $values[] = (int)$level;
```

### CX-05 태그는 이름 정확 일치, 한 번에 하나만 쓴다
- 규칙: `tag` 는 50자로 자르고, 그 이름의 태그가 붙은 문항만 남긴다(`EXISTS`). 미등록 태그도 조회하며(0건) 경고만 표시한다. 배열로 들어오면 첫 값만 쓴다.
- 근거: `search.php:240-245`, `:65-68`
```php
$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
        . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
```

### CX-06 조건은 모두 AND 로 결합한다
- 규칙: 키워드 · 단원 · 난이도 · 태그는 `WHERE 1=1` 에 `AND` 로 덧붙는다. OR 결합은 CX-01 의 제목/지문 안에서만 있다. 파라미터가 배열이면 첫 값만 쓴다.
- 근거: `search.php:44`, `:98`, `:118`, `:226`, `:244`
```php
$where   = " WHERE 1=1";
$where .= " AND unit_code = ?";
```

## 2. 목록의 기본 정렬 · 제외 조건 · 페이지

### CX-07 검색은 공개 문항 뷰만 조회한다
- 규칙: 검색 대상은 `item` 이 아니라 `v_item_public` 이다. 뷰의 제외 조건은 이 폴더에서 확인되지 않는다(확인 필요).
- 근거: `search.php:521-522`, 목록 건수도 같은 뷰 `:526`
```php
$select = "SELECT id, title, unit_code, level, tag_names, created_at";
$from   = " FROM v_item_public";
```

### CX-08 기본 정렬은 난이도 내림차순, 같으면 ID 오름차순이다
- 규칙: `sort` 가 비었거나 알 수 없는 값이면 `level DESC, id ASC`. 알 수 없는 값은 경고 후 기본 정렬로 돌아간다.
- 근거: `search.php:317-325`
```php
case '':
    $orderBy = " ORDER BY level DESC, id ASC";
default: $warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';
```

### CX-09 지정 정렬의 방향 기본값과 동순위 처리
- 규칙: `sort` 는 id · title · unit · level · created 만 허용한다. `dir` 이 asc/desc 가 아니면 비운다. 방향이 없으면 level · created 는 내림차순, 나머지는 오름차순이다. 동순위는 `id ASC`(단 id 정렬 자체는 제외), `unit` 정렬은 `level DESC` 를 그 사이에 둔다.
- 근거: `search.php:268-273`, `:295-297`, `:330`
```php
$orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
$dir = ($sort === 'level' || $sort === 'created') ? 'desc' : 'asc';
```
- 참고: `level`(`:301-307`) · `created`(`:309-315`) 는 `dir` 이 `asc` 일 때만 오름차순이고, 그 외(빈값 포함)는 내림차순이다.

### CX-10 한 페이지 20건, 페이지 번호는 1~999 로 보정한다
- 규칙: `LIMIT 20 OFFSET (page-1)*20`. 숫자가 아니면 1 페이지, 0 이하는 1, 999 초과는 999 로 바꾼다.
- 근거: `search.php:336-349`, `:523`
```php
if ($page > 999) { $page = 999; ...
$offset = ($page - 1) * 20;
$limit  = " LIMIT 20 OFFSET " . (int)$offset;
```

### CX-11 단원 목록의 공개 문항 수는 상태 'A' 만 센다
- 규칙: 단원별 문항 수는 `status = 'A'` 인 문항만 센다. 삭제 · 검수중은 제외한다(주석과 코드가 일치).
- 근거: `legacy/item-bank-php/units.php:15-17`
```php
// 단원별 공개(status='A') 문항 수. 삭제 · 검수중 문항은 세지 않는다.
(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
```

## 3. 등록 검증 (`register.php`)

### CX-12 제목은 5자 이상 200자 이하이다
- 규칙: 앞뒤 공백만 제거한 문자 수(`mb_strlen`)로 판단한다. 내부 공백은 글자 수에 포함된다.
- 근거: `register.php:41`, `:49-54`
```php
if (mb_strlen($title, 'UTF-8') < 5) { ... 5자 이상
if (mb_strlen($title, 'UTF-8') > 200) { ... 200자를 넘을 수 없습니다.
```

### CX-13 지문은 필수이다 (길이 상한 검증 없음)
- 규칙: 앞뒤 공백 제거 후 빈 문자열이면 오류다. 상한 검사는 코드에 없다.
- 근거: `register.php:42`, `:56-58`
```php
if ($stem === '') { $errors[] = '지문을 입력해야 합니다.';
```

### CX-14 단원은 필수이며 목록에 있는 id 여야 한다
- 규칙: `unit_id` 가 `unit` 테이블 목록의 `id` 와 문자열로 일치해야 한다.
- 근거: `register.php:60-68`
```php
if ((string)$u['id'] === $unitId) { $unitOk = true; }
if (!$unitOk) { $errors[] = '단원을 선택해야 합니다.';
```

### CX-15 난이도는 1~5 한 자리 숫자여야 한다
- 근거: `register.php:70-72`
```php
if (!preg_match('/^[1-5]$/', $level)) {
    $errors[] = '난이도는 1~5 사이여야 합니다.';
```

### CX-16 태그는 선택 사항이고, 목록에 없는 태그 id 는 오류 없이 버린다
- 규칙: 태그가 없어도 등록된다. 목록에 없는 id 는 오류 없이 조용히 걸러진다.
- 근거: `register.php:73-81`, `:104`
```php
if ((string)$t['id'] === (string)$tid) { $validTagIds[] = (int)$tid; }
if (!empty($validTagIds)) {
```

### CX-17 등록 직후 상태는 검수중('R')이다
- 규칙: 새 문항은 `status='R'` 로 저장된다. 코드 주석은 검수 완료 전까지 검색에 나오지 않는다고 하나, 이를 실제로 거르는 것은 `v_item_public` 이며 이 폴더에서는 확인할 수 없다(확인 필요). 단원 목록 집계는 `'A'` 만 세므로(CX-11) 그쪽은 제외가 확인된다.
- 근거: `register.php:84`, `:93-94`
```php
// 등록 직후에는 검수중(R) 상태로 들어간다 → 검수 완료 전까지 검색 화면에 나오지 않는다.
VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())
```

### CX-18 문항 id 는 최대 id + 1 로 직접 채번한다
- 규칙: 트랜잭션 안에서 `MAX(id)+1` 을 `FOR UPDATE` 로 구해 새 id 로 쓴다. 문항 저장 실패 · 태그 저장 실패 시 롤백한다.
- 근거: `register.php:85-90`, `:119-122`
```php
$res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");
} catch (Exception $e) { $conn->rollback();
```

## 주석과 코드가 다른 곳 요약
| 위치 | 주석 | 코드 |
|---|---|---|
| `search.php:213-216` | 빈 난이도는 1~5 모두 포함 | `level < 5` 로 난이도 5 제외 (CX-03) |
