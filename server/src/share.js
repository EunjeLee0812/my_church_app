// ══════════════════════════════════════════════════════════════════════════
//  공유된 묵상 한 편 — /s/<token>
//  교회 밖 사람도 링크만 있으면 이 글 하나만 볼 수 있습니다.
//  (다른 글·명단·댓글·교회 정보는 전혀 보여 주지 않습니다)
// ══════════════════════════════════════════════════════════════════════════

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function kfull(s) {
  const [y, m, d] = String(s || '').slice(0, 10).split('-').map(Number);
  if (!y) return '';
  const dow = ['일', '월', '화', '수', '목', '금', '토'][new Date(y, m - 1, d).getDay()];
  return `${y}년 ${m}월 ${d}일 (${dow})`;
}

const STYLE = `
  :root { --bg:#F8F5EF; --surface:#FFFDFA; --green:#1F4D3A; --greenlight:#E4ECE4;
          --ink:#2B2622; --muted:#857B71; --line:#E6DDD1; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--ink);
    font-family:-apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Malgun Gothic", "Noto Sans KR", sans-serif;
    font-size:18px; line-height:1.75; -webkit-text-size-adjust:100%; }
  main { max-width:640px; margin:0 auto; padding:28px 16px 60px; }
  .church { color:var(--green); font-weight:700; font-size:16px; }
  .card { background:var(--surface); border:1px solid var(--line); border-radius:16px; padding:20px; margin-top:12px; }
  .who { font-weight:700; font-size:19px; }
  .date { color:var(--muted); font-size:15px; }
  h1 { font-size:22px; margin:14px 0 4px; line-height:1.4; }
  .body { white-space:pre-wrap; word-break:keep-all; overflow-wrap:anywhere; margin-top:10px; }
  .photos img { display:block; width:100%; height:auto; border-radius:12px; margin-top:12px; background:var(--greenlight); }
  .foot { color:var(--muted); font-size:14px; text-align:center; margin-top:28px; }
  .gone { text-align:center; padding:60px 16px; color:var(--muted); }
`;

export function sharePage(row, images, origin) {
  if (!row) {
    return `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>볼 수 없는 글입니다</title><style>${STYLE}</style></head><body><main>
<div class="gone">이 묵상은 지워졌거나 더 이상 공유되지 않습니다.</div></main></body></html>`;
  }
  const who = `${row.member_name} ${row.member_title || ''}`.trim();
  const heading = row.title ? row.title : `${who}님의 묵상`;
  const desc = String(row.body || '').replace(/\s+/g, ' ').slice(0, 90) || `${row.church_name} 묵상 나눔`;
  const ogImg = images[0] ? origin + images[0].url : '';
  return `<!doctype html>
<html lang="ko"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${esc(heading)} · ${esc(row.church_name)}</title>
<meta property="og:type" content="article">
<meta property="og:title" content="${esc(heading)}">
<meta property="og:description" content="${esc(desc)}">
<meta property="og:site_name" content="${esc(row.church_name)} 묵상 나눔">
${ogImg ? `<meta property="og:image" content="${esc(ogImg)}">` : ''}
<style>${STYLE}</style></head><body><main>
  <div class="church">${esc(row.church_name)} 묵상 나눔</div>
  <div class="card">
    <div class="who">${esc(who)}</div>
    <div class="date">${esc(kfull(row.date))}</div>
    ${row.title ? `<h1>${esc(row.title)}</h1>` : ''}
    ${row.body ? `<div class="body">${esc(row.body)}</div>` : ''}
    ${images.length ? `<div class="photos">${images.map((im) =>
      `<img src="${esc(im.url)}" alt="묵상 사진" loading="lazy">`).join('')}</div>` : ''}
  </div>
  <div class="foot">모두의 교회 앱에서 나눈 묵상입니다</div>
</main></body></html>`;
}
