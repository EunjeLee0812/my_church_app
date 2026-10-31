// 아동 안전 기준 (CSAE) — Google Play 가 요구하는 공개 페이지
// 요구사항: 오류 없이 열릴 것, 아동 안전을 다룰 것, 앱/개발자 이름이 들어갈 것
export function childSafetyPage(contactEmail) {
  const updated = '2026년 9월 21일';
  return `<!doctype html>
<html lang="ko"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>아동 안전 기준 · 모두의 교회</title>
<style>
  body { font-family: -apple-system, "Apple SD Gothic Neo", "Malgun Gothic", sans-serif;
         max-width: 720px; margin: 0 auto; padding: 32px 20px 80px;
         line-height: 1.8; color: #1b1b1b; background: #f8f5ef; }
  h1 { font-size: 26px; margin-bottom: 4px; }
  h2 { font-size: 19px; margin-top: 34px; border-top: 1px solid #e6ddd1; padding-top: 18px; }
  .date { color: #857b71; font-size: 14px; }
  table { border-collapse: collapse; width: 100%; margin: 12px 0; background: #fff; }
  th, td { border: 1px solid #e6ddd1; padding: 10px 12px; text-align: left; font-size: 15px; }
  th { background: #e4ece4; }
  ul { padding-left: 20px; }
  code { background: #fff; padding: 1px 5px; border: 1px solid #e6ddd1; }
  .box { background: #fff; border: 1px solid #e6ddd1; border-radius: 10px; padding: 16px; }
</style></head><body>

<h1>아동 성적 학대 및 착취(CSAE) 방지 기준</h1>
<p class="date">앱 이름: <b>모두의 교회</b> · 최종 개정일 ${updated}</p>

<p>모두의 교회(이하 "본 앱")는 교회 구성원이 성경 묵상을 기록하고 나누는 비영리 앱입니다.
본 앱은 아동에 대한 모든 형태의 성적 학대와 착취(CSAE)를 <b>절대 용납하지 않으며</b>,
아래 기준에 따라 이를 예방하고 대응합니다.</p>

<h2>1. 금지되는 행위</h2>
<p>본 앱에서 다음 행위는 전면 금지되며, 발견 즉시 계정이 영구 정지되고
관계 법령에 따라 수사기관에 신고됩니다.</p>
<ul>
  <li>아동 성착취물(CSAM)의 제작·소지·게시·공유·링크</li>
  <li>아동을 성적으로 묘사하거나 성적 대상화하는 모든 글·사진·파일</li>
  <li>아동에 대한 성적 목적의 접근(그루밍), 유인, 협박</li>
  <li>아동 인신매매나 성매매를 알선·조장하는 내용</li>
  <li>그 밖에 아동의 성적 안전을 해치는 일체의 행위</li>
</ul>

<h2>2. 구조적 예방 장치</h2>
<p>본 앱은 다음과 같은 구조로 아동에게 위험이 발생할 여지를 근본적으로 줄이고 있습니다.</p>
<table>
  <tr><th>낯선 사람과의 접촉</th><td>불가능합니다. 소속 교회의 담임목사가 승인한
      구성원끼리만 글을 볼 수 있으며, 모르는 사람을 만나거나 추천받는 기능이 없습니다.</td></tr>
  <tr><th>일대일 비공개 대화</th><td>없습니다. 모든 글과 댓글은 같은 셀(소그룹)
      구성원 전체에게 공개되며, 사적인 쪽지 기능이 존재하지 않습니다.</td></tr>
  <tr><th>사진·영상 업로드</th><td>일반 구성원은 불가능합니다. 교회 주보 파일만
      담임목사 또는 관리 권한자가 올릴 수 있습니다.</td></tr>
  <tr><th>익명 이용</th><td>불가능합니다. 실명으로 목사의 승인을 받아야 가입됩니다.</td></tr>
  <tr><th>대상 연령</th><td>본 앱은 만 14세 미만 아동을 대상으로 하지 않습니다.</td></tr>
</table>

<h2>3. 신고 방법</h2>
<p><b>앱 안에서:</b> <code>설정 → 문의·신고</code> 에서 내용을 적어 보내시면
앱 운영자에게 직접 전달됩니다. 앱을 벗어나지 않고 신고할 수 있습니다.</p>
<p><b>앱 밖에서:</b> <code>${contactEmail}</code> 로 보내 주셔도 됩니다.</p>
<p><b>소속 교회에:</b> 담임목사 또는 관리 권한자는 앱에서 해당 글을 즉시 삭제하고
해당 구성원의 사용을 중지시킬 수 있습니다.</p>

<div class="box">
  <b>긴급한 경우 수사기관에 바로 신고해 주세요.</b><br>
  경찰 112 · 사이버범죄 신고 <a href="https://ecrm.police.go.kr">ecrm.police.go.kr</a><br>
  해외 신고: <a href="https://report.cybertip.org">NCMEC CyberTipline</a>
</div>

<h2>4. 신고 접수 후 처리 절차</h2>
<ul>
  <li><b>즉시</b> — 신고된 게시물을 비공개 처리하고 검토합니다.</li>
  <li><b>24시간 이내</b> — 위반이 확인되면 게시물을 영구 삭제하고 해당 계정을 정지합니다.</li>
  <li><b>지체 없이</b> — CSAM 으로 의심되는 자료는 즉시 수사기관에 신고하며,
      수사에 필요한 기록을 관련 법령이 정하는 범위에서 보존·제공합니다.</li>
  <li>신고자의 신원은 보호되며, 신고를 이유로 불이익을 주지 않습니다.</li>
</ul>

<h2>5. 준수하는 법령</h2>
<ul>
  <li>아동·청소년의 성보호에 관한 법률</li>
  <li>정보통신망 이용촉진 및 정보보호 등에 관한 법률</li>
  <li>개인정보 보호법</li>
  <li>Google Play 개발자 프로그램 정책 — 아동 안전 기준</li>
</ul>

<h2>6. 담당자</h2>
<p>본 앱의 아동 안전 정책 담당자는 개발·운영자 <b>이은제</b>이며,
연락처는 <code>${contactEmail}</code> 입니다.
Google 또는 수사기관의 문의에 성실히 응답합니다.</p>

<h2>7. 기준의 변경</h2>
<p>이 기준이 변경되면 이 페이지에 즉시 반영하고 개정일을 갱신합니다.</p>

<p style="margin-top:30px"><a href="/privacy">개인정보처리방침</a> ·
<a href="/delete-account">계정 삭제 요청</a></p>

</body></html>`;
}
