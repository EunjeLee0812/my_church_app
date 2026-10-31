// 계정 삭제 요청 페이지 — 로그인 없이 누구나 열 수 있어야 합니다 (Play 스토어 요구사항)
export function deleteAccountPage(contactEmail, sent = false) {
  return `<!doctype html>
<html lang="ko"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>계정 삭제 요청 · 모두의 교회</title>
<style>
  body { font-family: -apple-system, "Apple SD Gothic Neo", "Malgun Gothic", sans-serif;
         max-width: 640px; margin: 0 auto; padding: 32px 20px 80px;
         line-height: 1.8; color: #1b1b1b; background: #f8f5ef; }
  h1 { font-size: 26px; margin-bottom: 4px; }
  h2 { font-size: 19px; margin-top: 32px; border-top: 1px solid #e6ddd1; padding-top: 18px; }
  .lead { color: #857b71; }
  table { border-collapse: collapse; width: 100%; margin: 12px 0; background: #fff; }
  th, td { border: 1px solid #e6ddd1; padding: 10px 12px; text-align: left; font-size: 15px; }
  th { background: #e4ece4; width: 40%; }
  label { display: block; margin: 14px 0 4px; font-weight: 600; font-size: 15px; }
  input, textarea { width: 100%; box-sizing: border-box; font-family: inherit; font-size: 16px;
    padding: 12px; border: 1.5px solid #e6ddd1; border-radius: 10px; background: #fff; }
  textarea { min-height: 90px; }
  button { margin-top: 18px; width: 100%; padding: 16px; font-size: 17px; font-weight: 600;
    background: #1f4d3a; color: #fff; border: none; border-radius: 12px; cursor: pointer;
    font-family: inherit; }
  .ok { background: rgba(46,94,46,.1); color: #1f4d3a; padding: 16px; border-radius: 12px;
    font-size: 16px; }
  .note { font-size: 14px; color: #857b71; }
  code { background: #fff; padding: 1px 5px; border: 1px solid #e6ddd1; }
</style></head><body>

<h1>계정 삭제 요청</h1>
<p class="lead">모두의 교회 앱 · 계정과 내가 쓴 글을 지우고 싶으신 경우</p>

${sent ? `<div class="ok"><b>요청이 접수되었습니다.</b><br>
  확인 후 처리해 드립니다. 보통 며칠 안에 완료되며, 남기신 연락처가 있으면 알려 드립니다.</div>` : ''}

<h2>가장 빠른 방법</h2>
<p>앱에 로그인하실 수 있다면 <b>소속 교회의 담임목사님께 말씀하시는 것</b>이 가장 빠릅니다.
목사님이 앱의 <b>교회 성도 / 셀 관리</b>에서 바로 처리할 수 있습니다.
앱에서 본인이 쓴 글은 언제든 직접 지울 수 있습니다.</p>

<h2>무엇이 지워지나요</h2>
<table>
  <tr><th>지워지는 것</th><td>이름, 비밀번호, 직분·소속 셀 정보,<br>
      내가 쓴 묵상 글·기도제목·댓글, 좋아요 기록, 알림 설정</td></tr>
  <tr><th>남는 것</th><td>없습니다. 계정과 함께 모두 삭제됩니다.<br>
      다른 사람이 내 글에 단 댓글도 함께 지워집니다.</td></tr>
  <tr><th>처리 기간</th><td>요청 확인 후 30일 이내 (보통 며칠)</td></tr>
  <tr><th>되돌리기</th><td>삭제 후에는 복구할 수 없습니다</td></tr>
</table>

<h2>직접 신청하기</h2>
<p class="note">교회를 통하기 어려우시면 아래로 신청해 주세요.
본인 확인을 위해 교회 이름과 앱에 등록된 이름을 정확히 적어 주셔야 합니다.</p>

<form method="POST" action="/delete-account">
  <label for="church">교회 이름 *</label>
  <input id="church" name="church" required maxlength="60" placeholder="예) 강북바른교회">

  <label for="name">앱에 등록된 이름 *</label>
  <input id="name" name="name" required maxlength="40" placeholder="예) 홍길동">

  <label for="contact">연락받을 이메일 또는 전화 (선택)</label>
  <input id="contact" name="contact" maxlength="100" placeholder="처리 완료를 알려 드릴 곳">

  <label for="note">남기실 말씀 (선택)</label>
  <textarea id="note" name="note" maxlength="500"></textarea>

  <button type="submit">계정 삭제 요청하기</button>
</form>

<h2>문의</h2>
<p>다른 방법으로 요청하시려면 <code>${contactEmail}</code> 로 교회 이름과 성함을 적어 보내 주세요.</p>
<p class="note"><a href="/privacy">개인정보처리방침 보기</a></p>

</body></html>`;
}
