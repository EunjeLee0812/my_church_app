// 개인정보처리방침 — Play 스토어 등록에 필요한 공개 페이지
export function privacyPage(contactEmail) {
  const updated = '2026년 9월 20일';
  return `<!doctype html>
<html lang="ko"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>모두의 교회 개인정보처리방침</title>
<style>
  body { font-family: -apple-system, "Apple SD Gothic Neo", "Malgun Gothic", sans-serif;
         max-width: 720px; margin: 0 auto; padding: 32px 20px 80px;
         line-height: 1.8; color: #1b1b1b; background: #f8f5ef; }
  h1 { font-size: 26px; margin-bottom: 4px; }
  h2 { font-size: 19px; margin-top: 36px; border-top: 1px solid #e6ddd1; padding-top: 20px; }
  .date { color: #857b71; font-size: 14px; }
  table { border-collapse: collapse; width: 100%; margin: 12px 0; background: #fff; }
  th, td { border: 1px solid #e6ddd1; padding: 10px 12px; text-align: left; font-size: 15px; }
  th { background: #e4ece4; }
  ul { padding-left: 20px; }
  code { background: #fff; padding: 1px 5px; border: 1px solid #e6ddd1; }
</style></head><body>

<h1>개인정보처리방침</h1>
<p class="date">모두의 교회 앱 · 최종 개정일 ${updated}</p>

<p>모두의 교회 앱(이하 "앱")은 교회 구성원이 묵상을 나누고 교회 소식을 확인하기 위한
비영리 목적의 앱입니다. 이용자의 개인정보를 소중히 다루며, 아래와 같이 처리합니다.</p>

<h2>1. 수집하는 항목</h2>
<table>
  <tr><th>구분</th><th>항목</th><th>수집 시점</th></tr>
  <tr><td>필수</td><td>이름, 비밀번호(숫자 6자리·암호화 저장)</td><td>가입할 때</td></tr>
  <tr><td>필수</td><td>소속 교회, 직분, 소속 셀</td><td>교회 관리자가 지정할 때</td></tr>
  <tr><td>필수</td><td>이용자가 작성한 묵상 글·기도제목·댓글·좋아요</td><td>작성할 때</td></tr>
  <tr><td>선택</td><td>이메일 주소</td><td>교회 관리 권한자가 등록하는 경우에만</td></tr>
  <tr><td>자동</td><td>로그인 기록(최근 접속 시각)</td><td>이용 중</td></tr>
</table>

<p><strong>수집하지 않는 항목:</strong> 주민등록번호, 전화번호, 생년월일, 위치정보,
연락처·사진첩 등 기기 내 정보, 광고 식별자. 앱은 광고를 표시하지 않으며
분석 도구나 추적 도구를 사용하지 않습니다.</p>

<h2>2. 이용 목적</h2>
<ul>
  <li>교회 구성원 확인 및 로그인</li>
  <li>묵상 나눔·기도제목·공지·주보 등 앱 기능 제공</li>
  <li>교회 관리자의 구성원·셀 관리</li>
</ul>
<p>수집한 정보는 위 목적 외에 사용하지 않으며, 광고·마케팅에 이용하지 않습니다.</p>

<h2>3. 보관 장소</h2>
<p>정보는 Cloudflare, Inc. 가 제공하는 데이터베이스(D1)와 파일 저장소(R2)에 저장됩니다.
전송 구간은 HTTPS 로 암호화되며, 비밀번호는 복호화할 수 없는 형태(PBKDF2 해시)로
저장되어 운영자도 원래 값을 알 수 없습니다.</p>

<h2>4. 제3자 제공</h2>
<p>이용자의 개인정보를 제3자에게 제공하거나 판매하지 않습니다.
법령에 따라 수사기관이 적법한 절차로 요구하는 경우에만 예외로 합니다.</p>

<h2>5. 공개 범위</h2>
<p>작성한 묵상은 기본적으로 <strong>본인이 속한 셀</strong>의 구성원에게만 보입니다.
'나만 보기'로 올린 글은 다른 사람에게 보이지 않습니다.
담임목사 등 나눔 범위가 확장된 이용자는 여러 셀에 나눌 수 있습니다.
교회 관리 권한자는 구성원의 이름·직분·소속 셀을 볼 수 있습니다.</p>

<h2>6. 보유 기간과 파기</h2>
<ul>
  <li>이용자가 탈퇴를 요청하면 계정과 작성 글을 지체 없이 삭제합니다.</li>
  <li>교회가 앱 사용을 중단하면 해당 교회의 모든 정보를 삭제합니다.</li>
  <li>이용자는 앱에서 자신이 쓴 글을 직접 삭제할 수 있습니다.</li>
</ul>

<h2>7. 이용자의 권리</h2>
<p>이용자는 언제든지 자신의 정보를 열람·수정할 수 있고(설정 → 개인정보 변경),
삭제·처리정지를 요청할 수 있습니다. 소속 교회의 담임목사 또는 아래 연락처로
요청하시면 처리해 드립니다.</p>
<p><b>계정 삭제</b>는 <a href="/delete-account">계정 삭제 요청 페이지</a>에서
로그인 없이 바로 신청하실 수 있습니다.</p>

<h2>8. 만 14세 미만 아동</h2>
<p>앱은 만 14세 미만 아동을 대상으로 하지 않습니다.
만 14세 미만 아동이 이용할 경우 법정대리인의 동의가 필요합니다.</p>

<h2>9. 문의</h2>
<p>개인정보 관련 문의: <code>${contactEmail}</code></p>

<h2>10. 변경 고지</h2>
<p>이 방침이 바뀌면 앱 공지사항을 통해 알려 드립니다.</p>

</body></html>`;
}
