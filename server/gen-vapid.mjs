// 웹 알림에 쓸 열쇠(VAPID)를 만듭니다.
//   node gen-vapid.mjs
// 나온 값을 아래처럼 서버에 넣으세요 (화면에 붙여넣기 하면 됩니다):
//   npx wrangler secret put VAPID_PRIVATE_JWK
//   npx wrangler secret put VAPID_PUBLIC_KEY
//   npx wrangler secret put VAPID_SUBJECT      ← mailto:본인메일
import { webcrypto as crypto } from 'node:crypto';

const pair = await crypto.subtle.generateKey(
  { name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);

const priv = await crypto.subtle.exportKey('jwk', pair.privateKey);
delete priv.key_ops; delete priv.ext;
const raw = await crypto.subtle.exportKey('raw', pair.publicKey);
const pub = Buffer.from(raw).toString('base64url');

console.log('\n── VAPID_PRIVATE_JWK (아래 한 줄 전체) ──────────────────────');
console.log(JSON.stringify(priv));
console.log('\n── VAPID_PUBLIC_KEY ─────────────────────────────────────────');
console.log(pub);
console.log('\n이 두 값을 잘 보관하세요. 바뀌면 교인들이 알림을 다시 켜야 합니다.\n');
