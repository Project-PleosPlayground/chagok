// 영역 사양 공통 부분과 디자인 시스템 보기 페이지를 만든다.
//   node 기획/화면/공통/build.js
//
// 색·글자 값은 기획/디자인/디자인_시스템.md 4절 "팔레트" 표와 5절 "글자" 표에서 읽는다.
// 1. 그 값으로 tokens.css를 만든다. 팔레트 밖 값(투명도, 모양)은 tokens.extra.css에서 가져온다.
// 2. 기획/화면/영역사양_*.html마다 "공통:시작" ~ "공통:끝" 사이를
//    tokens.css + spec.css + spec.js로 바꿔 넣는다. 그 밖의 부분(그림, 박스, 표)은 건드리지 않는다.
// 3. 디자인 시스템 보기 페이지를 만든다.
// 앱 코드는 쓰지 않는다. 앱 테마는 손으로 맞추고, 단위 테스트(DesignSystemSyncTest)가 이 표와 같은지 본다.
//
// 영역 사양을 브라우저 미리보기 창(data: 주소로 연다)에서도 보이게 하려고, 공통 파일을 링크하지 않고 안에 넣는다.

const fs = require('fs');
const path = require('path');

const here = __dirname;
const screenDir = path.join(here, '..');
const DOC_NAME = '디자인_시스템.md';
const docPath = path.join(here, '..', '..', '디자인', DOC_NAME);
const START = '<!-- 공통:시작 (기획/화면/공통/build.js가 넣는다. 여기는 직접 고치지 않는다) -->';
const END = '<!-- 공통:끝 -->';

const doc = fs.readFileSync(docPath, 'utf8').replace(/\r\n/g, '\n');
const cells = line => line.trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim());

// heading 절(다음 같은 단계 이상의 제목 전까지)의 글
function section(heading) {
  const a = doc.indexOf('\n' + heading + '\n');
  if (a < 0) throw new Error(`${DOC_NAME}에서 "${heading}" 절을 찾지 못했다`);
  const level = heading.match(/^#+/)[0].length;
  const rest = doc.slice(a + heading.length + 2);
  const end = rest.search(new RegExp('\n#{1,' + level + '} '));
  return end < 0 ? rest : rest.slice(0, end);
}
// 절 안 표의 줄들. 열은 머리글 이름으로 찾는다(머리글 끝의 " (단위)"는 뗀다). 머리글·구분 줄은 빼고 돌려준다
function tableRows(text, where, need) {
  const lines = text.split('\n').filter(l => l.startsWith('|')).map(cells);
  if (!lines.length) throw new Error(`${DOC_NAME} ${where}에서 표를 찾지 못했다`);
  const head = lines[0].map(h => h.replace(/\s*\(.*\)$/, ''));
  const missing = need.filter(n => !head.includes(n));
  if (missing.length) throw new Error(`${DOC_NAME} ${where} 표에 없는 열: ${missing.join(', ')}. 머리글: ${head.join(' | ')}`);
  return lines.slice(1)
    .filter(c => !c.every(x => /^:?-+:?$/.test(x)))
    .map(c => Object.fromEntries(head.map((h, i) => [h, c[i] || ''])));
}
const codeName = cell => ((cell || '').match(/^`(\w+)`$/) || [])[1];

const warnings = [];

// 색: 4절 "팔레트" 표의 이름, 값 열
const colors = new Map();
for (const r of tableRows(section('### 팔레트'), '4절 팔레트', ['이름', '값'])) {
  const name = codeName(r['이름']);
  if (!name) { warnings.push(`팔레트: 이름 "${r['이름']}"이 백틱으로 감싼 이름이 아니라 건너뛰었다`); continue; }
  const hex = r['값'];
  if (!/^#[0-9A-Fa-f]{6}$/.test(hex)) { warnings.push(`팔레트: ${name}의 값 "${hex}"가 #RRGGBB가 아니다`); continue; }
  if (colors.has(name)) { warnings.push(`팔레트: ${name}이 두 번 있다. 앞의 값을 쓴다`); continue; }
  colors.set(name, hex.toUpperCase());
}

// 글자: 5절 "글자" 표의 역할, 크기, 굵기 열. 글꼴은 표 위 문장 "글꼴은 X."
const WEIGHT = { thin: 100, extralight: 200, light: 300, regular: 400, normal: 400, medium: 500, semibold: 600, bold: 700, extrabold: 800, black: 900 };
const typeText = section('### 글자');
const family = (typeText.match(/글꼴은 ([A-Za-z][\w ]*?)\./) || [])[1];
if (!family) warnings.push('글자: "글꼴은 X." 문장을 찾지 못했다');
const fonts = [];
for (const r of tableRows(typeText, '5절 글자', ['역할', '크기', '굵기'])) {
  const role = codeName(r['역할']);
  if (!role) { warnings.push(`글자: 역할 "${r['역할']}"이 백틱으로 감싼 이름이 아니라 건너뛰었다`); continue; }
  if (!/^\d+(\.\d+)?$/.test(r['크기'])) { warnings.push(`글자: ${role}의 크기 "${r['크기']}"가 숫자가 아니라 건너뛰었다`); continue; }
  const w = r['굵기'];
  const weight = /^\d+$/.test(w) ? w : WEIGHT[w.toLowerCase().replace(/[\s_-]/g, '')];
  if (!weight) warnings.push(`글자: ${role}의 굵기 "${w}"를 읽지 못했다`);
  fonts.push({ role, family, size: r['크기'], weight });
}
if (!colors.size || !fonts.length) throw new Error(`${DOC_NAME}에서 색 ${colors.size}개, 글자 ${fonts.length}개를 읽었다. 표 모양을 확인한다`);

// 팔레트 밖 값
const extraText = fs.readFileSync(path.join(here, 'tokens.extra.css'), 'utf8');
const extra = extraText.split('\n').filter(line => {
  const m = line.match(/--app-([\w]+)\s*:/);
  if (m && colors.has(m[1])) { warnings.push(`tokens.extra.css: ${m[1]}이 팔레트에 있어 팔레트 값을 쓴다. extra에서 지운다`); return false; }
  return true;
}).join('\n');

const tokens = [
  '/* 생성 파일. 디자인_시스템.md 4절 팔레트, 5절 글자에서 build.js가 만든다. 직접 고치지 않는다. */',
  ':root {',
  ...[...colors].map(([n, v]) => `  --app-${n}: ${v};`),
  ...fonts.flatMap(f => [
    f.family && `  --type-${f.role}-family: "${f.family}";`,
    f.size && `  --type-${f.role}-size: ${f.size}px;`,
    f.weight && `  --type-${f.role}-weight: ${f.weight};`,
  ].filter(Boolean)),
  '}',
  '',
  extra.trim(),
  '',
].join('\n');
fs.writeFileSync(path.join(here, 'tokens.css'), tokens);

// 영역 사양에 넣기
const spec = fs.readFileSync(path.join(here, 'spec.css'), 'utf8');
const js = fs.readFileSync(path.join(here, 'spec.js'), 'utf8');
const block = `${START}\n<style>\n${tokens}\n${spec}</style>\n<script>\n${js}</script>\n${END}`;

const files = fs.readdirSync(screenDir).filter(f => /^영역사양_.*\.html$/.test(f));
for (const f of files) {
  const p = path.join(screenDir, f);
  const html = fs.readFileSync(p, 'utf8');
  const a = html.indexOf(START), b = html.indexOf(END);
  if (a < 0 || b < a) { warnings.push(`${f}: 공통 표시가 없어 건너뛰었다`); continue; }
  fs.writeFileSync(p, html.slice(0, a) + block + html.slice(b + END.length));
  console.log(`넣음  ${f}`);
}

console.log(`tokens.css  색 ${colors.size}개, 글자 역할 ${fonts.length}개`);

// 디자인 시스템 보기 페이지
const extraNames = new Set([...extra.matchAll(/--app-([\w]+)\s*:/g)].map(m => m[1]));
const shown = require('./showcase.js')({ root: path.join(here, '..', '..', '..'), tokens, extraNames });
console.log(`보기 페이지  ${path.relative(path.join(here, '..', '..', '..'), shown.out)}, 컴포넌트 ${shown.components}개`);
if (shown.unused.length) console.log(`확인  디자인 시스템 표에 없는 그림: ${shown.unused.join(', ')}`);
for (const w of warnings) console.log(`확인  ${w}`);
