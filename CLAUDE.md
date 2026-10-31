# CLAUDE.md — 모두의 교회

교회 묵상 나눔 · 기도제목 · 공지 · 주보를 한 곳에서 쓰는 앱.
**안드로이드 네이티브 앱 + 같은 기능의 웹앱(PWA)** 이 **하나의 Cloudflare Workers 서버**를 함께 씁니다.
여러 교회가 각자 공간으로 쓰고(멀티 테넌트), 목사님·관리자가 성도·구역(셀)·직분·권한을 관리합니다.
사용자 다수가 **시니어**라서 단순하고 큰 글씨를 쓰는 디자인이 최우선입니다.

- 앱 이름: 모두의 교회 / 패키지: `kr.woorichurch.app` (바꿀 수 없음)
- 서버·웹앱 주소: `https://woori-church.lej0812.workers.dev`
- 개인정보처리방침: `/privacy` · 계정 삭제: `/delete-account` · 아동 안전: `/child-safety`
- 현재 상태: Play 스토어 비공개 테스트 단계 (12명 × 14일 조건 충족 후 프로덕션)

---

## 폴더 구조

```
우리교회어플/
├── android/            Kotlin + Jetpack Compose 앱 (Android Studio 로 이 폴더를 엶)
│   └── app/src/main/java/kr/woorichurch/app/
│       ├── MainActivity.kt, WooriApp.kt
│       ├── data/       Api.kt(OkHttp+캐시/prefetch) · Repo.kt · Models.kt · Prefs.kt(DataStore)
│       ├── push/       Push.kt — FCM
│       ├── util/       Util.kt
│       └── ui/
│           ├── AppRoot.kt      NavHost · Route 정의 · AppState
│           ├── theme/          Theme.kt — 색 토큰, fontScale 타이포
│           ├── common/         Common.kt · Press.kt(눌림 애니메이션 래퍼) · Extra.kt
│           ├── home/           Home.kt · MainTabs.kt(떠 있는 알약형 하단 탭) · Roulette.kt(추가 기능)
│           ├── devotion/       Write · Plaza(나눔광장) · Attendance(출석표 잔디) · PersonAndSearch
│           ├── prayer/ board/(공지·주보) admin/(관리·성도) auth/ settings/(+Export)
├── server/
│   ├── src/index.js    API 전체 (라우터 + 권한 + SQL). 약 1,600줄 단일 파일
│   ├── src/push.js     Web Push(VAPID) + FCM 발송
│   ├── src/privacy.js · delete-account.js · child-safety.js · share.js(/s/<token> 공유 페이지)
│   ├── public/         웹앱 — index.html · app.js(약 2,400줄) · sw.js · manifest · postcode.html(카카오 우편번호)
│   ├── schema.sql      D1 스키마 (create if not exists → 여러 번 실행해도 안전)
│   ├── migrate-*.sql   추가 마이그레이션 (역시 재실행 안전하게 작성)
│   ├── gen-vapid.mjs   웹 푸시 열쇠 생성
│   └── wrangler.toml   D1 `DB`, R2 `FILES`, 정적 자산 `./public`
├── store/              Play 스토어 아이콘 · 피처 그래픽 · 스크린샷
├── legacy-web/, _old/  예전 Supabase 단일 HTML 버전 (더 쓰지 않음, 참고용)
├── README.md           설치·배포·권한·이단 차단 설명 (사람용 상세 문서)
├── PLAY-배포.md         Play 스토어 배포 절차
└── 알림-설정.md          VAPID / Firebase 알림 설정 절차
```

## 기술 스택

| 영역 | 사용 |
|---|---|
| 안드로이드 | Kotlin, Jetpack Compose (Material 3), Navigation Compose, OkHttp, kotlinx.serialization, DataStore, FCM |
| SDK | minSdk 26 · targetSdk 36 · `versionCode`는 `android/app/build.gradle.kts` |
| 서버 | Cloudflare Workers (JS 모듈, 프레임워크 없음) |
| DB · 파일 | D1 (SQLite) · R2 (주보·묵상 사진) |
| 웹앱 | 빌드 도구 없는 바닐라 JS + Service Worker (PWA, 아이폰은 사파리 '홈 화면에 추가') |
| 인증 | 이름 + PIN → `sessions` 토큰(Bearer). PIN 은 PBKDF2-SHA256 10만 회(Workers 한도) |

## 자주 쓰는 명령

```bash
# 서버 (server/ 에서)
npm run dev                     # 로컬
npm run deploy                  # 배포 (웹앱 public/ 도 같이 올라감)
npm run db:init                 # schema.sql 을 원격 D1 에 적용
npx wrangler d1 execute woori-church --remote --file=./migrate-xxx.sql
npx wrangler d1 execute woori-church --remote --command "select ..."

# 안드로이드 (android/ 에서)
./gradlew assembleDebug
./gradlew bundleRelease         # Play 업로드용 AAB (keystore.properties 있으면 자동 서명)
```

> 터미널 명령 줄 끝에 `# 한글 설명`을 붙이면 wrangler 인자로 넘어가 오류가 납니다. 안내문에 쓸 때 주의하세요.

---

## 도메인 · 권한 모델

- **직분(호칭)과 권한(role)은 별개**입니다.
  - 직분 `TITLES`: 목사 / 사모 / 장로 / 안수집사 / 권사 / 집사 / 성도
  - 권한 `ROLES`: `owner`(관리자, 교회 등록자 · 담임목사와 같은 전체 권한, 여러 명 가능) · `admin`(부관리자) · `leader`(셀장) · `member`(성도)
  - `is_platform_admin`: 앱 운영자 (교회 등록 신청 심사, 계정 삭제 요청 처리)
- **나눔 범위 `share_scope`**: `own_cell`(기본, 본인 구역만) / `all_cells`(구역 선택 + 전체 나누기). 목사·사모는 기본이 `all_cells`
- 한 사람이 **여러 구역에 속할 수 있음** → `member_cells` 표. `members.cell_id`는 대표 구역
- 교회 가입: 교회 검색 → 가입 신청 → 관리자 승인. 교회 등록: 플랫폼 운영자 승인
- **이단 차단 3단**: `blocked_keywords` 자동 차단 → 교단 명부 대조 → 사람이 승인. 거절 사유는 신청자에게 알리지 않음 (명예훼손 소지)
- 묵상 `entries`: 한 사람당 하루 1편 (`unique(member_id, date)`), 사진은 최대 4장(`entry_images`), 나눔 대상 구역은 `entry_targets`
- 그 밖의 표: likes, comments, prayers(+targets, amens), notices(+reads), bulletins, audit_log(직분·셀 변경 기록), suggestions(건의함), push_tokens, notifications, deletion_requests, entry_shares

## API 규칙 (server/src/index.js)

- 모든 엔드포인트는 `/api/...`, 메서드는 `GET`(조회) / `POST`(변경)만 씁니다. 삭제도 `POST /api/xxx/delete`
- 라우팅은 `if (P('/entries/save') && req.method === 'POST')` 식의 if 체인입니다
- 응답: 성공 `json(data)`, 실패 `fail('사용자에게 보여줄 한국어 문장')`. **에러 문구는 화면에 그대로 뜨니 시니어도 이해할 수 있게** 쓰세요
- 권한 검사는 항상 서버에서 (`canManage`, `isOwner`, `canShareAnywhere`). 클라이언트는 버튼만 숨깁니다
- 구역 이름이 길면 서버가 `shortCellLabel`로 줄여서 보냅니다 ('1구역 외 2', '전체 구역')
- 스키마 변경은 `schema.sql` 에 반영하고, 운영 DB 용으로 **재실행해도 안전한** `migrate-YYYY-MM.sql` 을 따로 만듭니다

---

## 꼭 지켜야 할 원칙

1. **안드로이드 앱과 웹앱은 항상 기능이 같아야 합니다.** 한쪽을 고치면 다른 쪽도 같이 고칩니다 (`ui/**.kt` ↔ `server/public/app.js`)
2. **시니어 우선 디자인**
   - 이모티콘 금지. 글씨와 도형, 아이콘만 씁니다
   - 버튼은 크게, 누르는 횟수는 적게 (예: 줌은 버튼 한 번에 바로 입장)
   - 시니어 모드 on/off. 끄면 새 디자인 + 글씨 크기 '가장 작게'(fontScale 0.85)가 기본, 켜면 1.0
3. **색은 `ui/theme/Theme.kt` 토큰만** 씁니다. 딥 그린(`Green #1F4D3A`) 포인트 + 크림·베이지(`Bg #F8F5EF`, `Surface`, `SurfaceWarm`, `Line`). 웹앱 CSS 변수도 같은 값입니다
4. **버튼·Surface는 `kr.woorichurch.app.ui.common`의 래퍼**(`Press.kt`)를 import 합니다. material3 기본 `Button`을 쓰면 눌림 애니메이션(누르면 지수적으로 커지고 떼면 천천히 복귀)이 빠집니다. 스프링으로 튕기는 효과는 쓰지 않습니다
5. **용어**: 이 교회는 셀을 **'구역'** 이라고 부릅니다. 서버는 구역/셀/목장 중 교회가 쓰는 말을 따라갑니다
6. **하단 탭**(새 디자인): 추가 기능 · 알림(공지·주보) · 홈 · 나눔광장 · 설정. 인스타그램처럼 떠 있는 알약형이고, 스크롤을 내리면 작아지고 올리면 커집니다
7. **화면 이동**은 뒤로가기 스택 + '홈으로' 버튼. 묵상을 올린 직후 뒤로가기는 홈으로 갑니다
8. **캐시/prefetch**: 읽기 전용 목록은 1분 캐시, 다음에 누를 화면은 미리 받아 둡니다. POST 뒤에는 관련 캐시를 비웁니다 (`Api.kt`, `app.js`의 `CACHEABLE`/`invalidateAfter`). 새 조회 API를 만들면 양쪽 캐시 목록에도 추가하세요
9. **묵상 완료 %**: 본인만 봅니다. 분기는 그 분기 3달 전체 일수, 연간은 365일이 분모입니다 (오늘까지 지난 날 수가 아님)
10. **출석표(잔디)**: 가로 방향, 세로 7칸(일요일 시작), 이번 달이 맨 오른쪽, 안 한 날은 빈 동그라미, 점은 눌리지 않음(이름·월 라벨을 누름)

## 비밀 파일 — 절대 커밋 금지

`android/upload-keystore.jks`, `android/keystore.properties`, `android/app/google-services.json`,
`android/local.properties`, Firebase 서비스 계정 JSON, `.dev.vars`. 모두 `.gitignore`에 들어 있습니다.
서버 비밀값(VAPID_*, FCM_SERVICE_ACCOUNT)은 `wrangler secret put`으로만 넣습니다.
keystore를 잃어버리면 앱 업데이트를 영영 올릴 수 없습니다.

## 릴리스 체크리스트

1. 스키마가 바뀌었으면 → `migrate-*.sql`을 원격에 실행
2. `server/`에서 `npm run deploy` (웹앱도 함께 반영됨. 웹앱 파일을 바꿨으면 `server/public/sw.js` 의 `CACHE = 'woori-vN'` 숫자도 올리기)
3. `android/app/build.gradle.kts`에서 `versionCode` +1 → `./gradlew bundleRelease` → Play Console 업로드

---

## Claude 작업 메모

- 이 폴더는 사용자 맥(`~/Desktop/LEJ/교회/우리교회어플`)에 있습니다. **사용자가 직접 파일을 고치기도 하니**, 수정 전에 항상 최신 파일을 다시 읽고 그 위에 얹으세요
- 연결된 폴더에서 Claude 셸은 기본적으로 파일을 삭제할 수 없습니다. **git 명령을 돌리면 `.git/index.lock`이 남아** 사용자의 git이 막힙니다. git 작업은 삭제 권한을 먼저 받거나 사용자에게 맡기세요
- 루트의 `.*.tgz`는 파일 전달용 임시 압축본입니다 (gitignore 됨). 새로 만들었으면 작업 후 정리하세요
- 답변·주석·UI 문구는 모두 한국어로 씁니다
