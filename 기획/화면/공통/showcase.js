// 디자인 시스템을 한 페이지로 그린다: 기획/디자인/디자인_시스템_보기.html
// build.js가 부른다. 디자인_시스템.md, tokens.css, Image/ 의 SVG를 읽어 만든다.
// 이 파일이나 디자인 시스템 문서를 고친 뒤 node 기획/화면/공통/build.js 를 돌린다.

const fs = require('fs');
const path = require('path');

// 컴포넌트 그림이 지금 디자인 시스템과 다른 것(새 시안이 먼저 나와 그림이 이전 모양). 디자인 시스템 6절 "미션" 참고.
const OUTDATED = new Set(['mission_box', 'mission_blank']);

const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const md = s => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>');

function cells(line) {
  return line.trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim());
}

// 디자인 시스템 6절의 컴포넌트 표를 읽는다.
function readComponents(doc) {
  const a = doc.indexOf('## 6. 컴포넌트'), b = doc.indexOf('## 7.');
  const lines = doc.slice(a, b).split('\n');
  const groups = [];
  let group = null, head = null;
  for (const line of lines) {
    const h = line.match(/^###\s+(.+)/);
    if (h && !line.startsWith('####')) { group = { title: h[1].trim(), items: [] }; groups.push(group); head = null; continue; }
    if (!line.startsWith('|')) { head = null; continue; }
    const c = cells(line);
    if (c[0] === '컴포넌트' || c[0] === '이름') { head = c; continue; }
    if (/^-+$/.test(c[0].replace(/:/g, ''))) continue;
    const m = c[0].match(/^`([\w]+)`$/);
    if (!m || !head || !group) continue;
    const col = name => { const i = head.findIndex(x => x.startsWith(name)); return i >= 0 ? c[i] : ''; };
    group.items.push({
      name: m[1],
      parts: col('구성'),
      state: col('상태') || col('규칙') || col('쓰는 곳'),
      size: col('크기'),
      shape: col('모양'),
      pic: col('그림') || col('참고 그림'),
    });
  }
  return groups.filter(g => g.items.length);
}

// 같은 이름으로 시작하는 그림 파일(상태별) 찾기
function svgsFor(name, files) {
  return files.filter(f => f === name + '.svg' || f.startsWith(name + '_')).filter(f => {
    // coin_bar 같은 이름이 다른 컴포넌트 이름의 앞부분일 때 섞이지 않게: 남은 부분이 상태 꼬리여야 한다
    const rest = f.slice(name.length).replace(/\.svg$/, '');
    return rest === '' || /^_(default|pressed|selected|in_progress|success|failed|caution|good|info|danger|exp|point)$/.test(rest);
  });
}

// heading 절(다음 같은 단계 이상의 제목 전까지)의 첫 표. 열은 머리글 이름으로 찾는다(끝의 " (단위)"는 뗀다)
function docTable(doc, heading) {
  const a = doc.indexOf('\n' + heading + '\n');
  if (a < 0) return [];
  const rest = doc.slice(a + heading.length + 2);
  const end = rest.search(new RegExp('\n#{1,' + heading.match(/^#+/)[0].length + '} '));
  const lines = (end < 0 ? rest : rest.slice(0, end)).split('\n').filter(l => l.startsWith('|')).map(cells);
  if (!lines.length) return [];
  const head = lines[0].map(h => h.replace(/\s*\(.*\)$/, ''));
  return lines.slice(1)
    .filter(c => !c.every(x => /^:?-+:?$/.test(x)))
    .map(c => Object.fromEntries(head.map((h, i) => [h, c[i] || ''])));
}

function readRoles(doc) {
  return docTable(doc, '### 글자').filter(r => /^`\w+`$/.test(r['역할'] || '')).map(r =>
    ({ role: r['역할'].replace(/`/g, ''), size: r['크기'], weight: r['굵기'] }));
}

function rootVars(css, prefix) {
  const out = [];
  for (const m of css.matchAll(new RegExp('--(' + prefix + '[\\w-]*)\\s*:\\s*([^;]+);', 'g'))) out.push([m[1], m[2].trim()]);
  return out;
}

module.exports = function showcase({ root, tokens, extraNames }) {
  const docPath = path.join(root, '기획', '디자인', '디자인_시스템.md');
  const doc = fs.readFileSync(docPath, 'utf8').replace(/\r\n/g, '\n');
  const compDir = path.join(root, '기획', '디자인', 'Image', 'Component');
  const iconDir = path.join(root, '기획', '디자인', 'Image', 'Icon');
  const compFiles = fs.readdirSync(compDir).filter(f => f.endsWith('.svg')).sort();
  const iconFiles = fs.readdirSync(iconDir).filter(f => f.endsWith('.svg')).sort();
  const svg = (dir, f) => fs.readFileSync(path.join(dir, f), 'utf8').replace(/<\?xml[^>]*>/, '');
  const img = (dir, f) => `<img alt="${esc(f)}" src="data:image/svg+xml;base64,${Buffer.from(svg(dir, f)).toString('base64')}">`;

  const colors = rootVars(tokens, 'app-');
  const typeVars = rootVars(tokens, 'type-');
  const tokenRoles = [...new Set(typeVars.map(([n]) => n.replace(/^type-/, '').replace(/-(family|size|weight)$/, '')))];
  const roleVal = (r, k) => (typeVars.find(([n]) => n === `type-${r}-${k}`) || [])[1] || '';
  const radii = rootVars(tokens, 'radius_');
  const shadows = rootVars(tokens, 'shadow_');
  const groups = readComponents(doc);
  const used = new Set();

  // 팔레트: 디자인 시스템 4절 "팔레트" 표의 계열, 이름, 역할 열을 계열별 줄로 그린다. 값은 tokens.css를 쓴다.
  const families = [];
  for (const r of docTable(doc, '### 팔레트').filter(r => /^`\w+`$/.test(r['이름'] || ''))) {
    const fam = r['계열'] || '', name = r['이름'].replace(/`/g, ''), role = r['역할'] || '';
    let g = families.find(x => x.fam === fam);
    if (!g) families.push(g = { fam, items: [] });
    g.items.push({ name, role });
  }
  const val = n => (colors.find(([k]) => k === 'app-' + n) || [])[1] || '팔레트에 없음';
  const inPalette = new Set(families.flatMap(g => g.items.map(i => i.name)));
  const derived = colors.filter(([k]) => !inPalette.has(k.replace(/^app-/, '')));
  const swatch = (n, sub, note) => `<div class="sw"><div class="chip" style="background:var(--app-${n})"></div><b>${esc(n)}</b><span>${esc(sub)}</span>${note ? `<small>${md(note)}</small>` : ''}</div>`;
  const colorHtml = families.map(g => `<h3>${esc(g.fam)}</h3><div class="ramp">${g.items.map(i => swatch(i.name, val(i.name), i.role)).join('')}</div>`).join('\n')
    + (derived.length ? `<h3>팔레트 색으로 만든 값</h3><div class="ramp">${derived.map(([k, v]) => swatch(k.replace(/^app-/, ''), v, '')).join('')}</div>` : '');

  const typeRows = [
    ...tokenRoles.map(r => ({ role: r, size: roleVal(r, 'size').replace('px', ''), weight: roleVal(r, 'weight'), src: '디자인 시스템' })),
    ...readRoles(doc).filter(r => !tokenRoles.includes(r.role)).map(r => ({ role: r.role, size: r.size, weight: { Regular: 400, Medium: 500, SemiBold: 600, Bold: 700 }[r.weight] || r.weight, src: '디자인 시스템' })),
  ].map(r => `<tr><td><code>${esc(r.role)}</code></td><td>${esc(r.size)}</td><td>${esc(r.weight)}</td><td class="muted">${r.src}</td><td style="font-size:${parseFloat(r.size) || 16}px;font-weight:${r.weight || 400}">도전 중인 미션 3/10</td></tr>`).join('\n');

  const radiusHtml = [...radii, ['pill', '높이의 반']].map(([n, v]) =>
    `<div class="shape"><div style="border-radius:${n === 'pill' ? '999px' : `var(--${n})`}"></div><b>${esc(n)}</b><span>${esc(v)}</span></div>`).join('\n');
  const shadowHtml = shadows.map(([n, v]) =>
    `<div class="shape"><div style="box-shadow:var(--${n});border-radius:var(--radius_l)"></div><b>${esc(n)}</b><span>${esc(v)}</span></div>`).join('\n');

  const compHtml = groups.map(g => `<h3>${esc(g.title)}</h3>\n<div class="comps">` + g.items.map(it => {
    const named = compFiles.filter(f => new RegExp('(^|[^\w])' + f.replace('.svg', '') + '($|[^\w])').test(it.pic || ''));
    const files = [...new Set([...svgsFor(it.name, compFiles), ...named])];
    files.forEach(f => used.add(f));
    const pics = files.length
      ? files.map(f => `<figure>${img(compDir, f)}<figcaption>${esc(f.replace('.svg', ''))}${OUTDATED.has(it.name) ? ' · 이전 모양' : ''}</figcaption></figure>`).join('')
      : '<p class="muted">그림 없음 · 앱에서 그린다</p>';
    return `<article><h4><code>${esc(it.name)}</code></h4>${it.size ? `<p class="meta">크기 ${md(it.size)}${it.shape ? ` · ${md(it.shape)}` : ''}</p>` : ''}<p>${md(it.parts)}</p>${it.state ? `<p class="muted">${md(it.state)}</p>` : ''}<div class="pics">${pics}</div></article>`;
  }).join('\n') + '</div>').join('\n');

  const unused = compFiles.filter(f => !used.has(f));
  const iconHtml = iconFiles.map(f => `<figure class="icon">${img(iconDir, f)}<figcaption>${esc(f.replace('.svg', ''))}</figcaption></figure>`).join('\n');

  // 새 미션뷰 시안의 mission_box는 그림이 없어 토큰으로 그려 본다(실제 크기의 절반).
  const box = (bg, border, inner) => `<div class="mb" style="background:${bg};${border}">${inner}</div>`;
  const chips = `<span class="rw"><i style="background:var(--app-yellow)"></i>120</span><span class="rw"><i style="background:#D8B27A"></i>10</span>`;
  const head = t => `<div class="mb-top"><b>${t}</b><span>${chips}</span></div><p class="mb-desc">10분 동안 급가속 없이 부드럽게 출발하고 가속하기</p>`;
  const mock = `<div class="mock">
${box('var(--app-surface_blue)', 'border:none', head('부드러운 출발의 달인') + `<div class="mb-bottom"><span class="lab">달성률</span><span class="bar"><i style="width:40%"></i></span><span class="lab">남은 시간</span><b class="num">00:06:00</b></div>`)}
<figcaption>도전 중</figcaption>
${box('var(--app-white)', 'border:2px solid var(--app-line)', head('깜빡이 약속 지키기'))}
<figcaption>제공 (진행·남은 시간 없음)</figcaption>
${box('var(--app-surface_blue)', 'border:2px dashed var(--app-line)', '<p class="mb-blank">아래에서 도전할 미션을 골라 보세요.</p>')}
<figcaption>mission_blank (빈 칸)</figcaption>
</div>`;

  const html = `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>디자인 시스템 보기</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/static/pretendard.min.css">
<style>
${tokens}
:root { --ink:#16181d; --sub:#5f6672; --faint:#9aa1ab; --line:#e4e7ec; --soft:#f6f7f9; }
* { box-sizing:border-box; }
body { margin:0; background:var(--soft); color:var(--ink); font-family:Pretendard,"Malgun Gothic",sans-serif; font-size:14px; line-height:1.5; }
main { max-width:1280px; margin:0 auto; padding:32px 16px 64px; }
h1 { font-size:24px; margin:0 0 4px; } h2 { font-size:18px; margin:40px 0 12px; } h3 { font-size:15px; margin:28px 0 10px; color:var(--sub); }
h4 { margin:0 0 6px; font-size:14px; }
.lead { color:var(--sub); margin:0 0 8px; }
nav { display:flex; flex-wrap:wrap; gap:8px; margin:16px 0 0; } nav a { color:var(--sub); text-decoration:none; border:1px solid var(--line); border-radius:999px; padding:2px 10px; background:#fff; font-size:13px; }
code { font-family:ui-monospace,Consolas,monospace; font-size:12px; background:var(--soft); border:1px solid var(--line); border-radius:4px; padding:0 4px; }
.muted { color:var(--faint); font-size:12px; }
.grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:10px; }
.sw { background:#fff; border:1px solid var(--line); border-radius:10px; padding:8px; display:flex; flex-direction:column; gap:2px; font-size:12px; }
.sw .chip { height:44px; border-radius:6px; border:1px solid var(--line); margin-bottom:4px; }
.sw span { color:var(--sub); font-family:ui-monospace,Consolas,monospace; } .sw small { color:var(--faint); font-size:11px; line-height:1.4; }
.ramp { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:10px; } .sw em { color:var(--l2,#ea580c); font-style:normal; font-size:11px; }
table { width:100%; border-collapse:collapse; background:#fff; border:1px solid var(--line); border-radius:10px; }
td, th { padding:8px 10px; border-bottom:1px solid var(--line); text-align:left; vertical-align:middle; } th { font-size:12px; color:var(--faint); }
.shapes { display:flex; flex-wrap:wrap; gap:16px; }
.shape { display:flex; flex-direction:column; gap:2px; font-size:12px; width:150px; } .shape div { height:72px; background:#fff; border:1px solid var(--line); margin-bottom:6px; } .shape span { color:var(--sub); }
.comps { display:grid; grid-template-columns:repeat(auto-fill,minmax(300px,1fr)); gap:12px; }
article { background:#fff; border:1px solid var(--line); border-radius:12px; padding:14px; } article p { margin:4px 0; } .meta { font-size:12px; color:var(--sub); }
.pics { display:flex; flex-wrap:wrap; gap:10px; margin-top:8px; align-items:flex-end; }
figure { margin:0; display:flex; flex-direction:column; align-items:flex-start; gap:4px; max-width:100%; }
figure img { max-width:260px; max-height:160px; background:repeating-conic-gradient(#f3f4f6 0 25%, #fff 0 50%) 0 0/12px 12px; border-radius:6px; }
figcaption { font-size:11px; color:var(--faint); }
.icon img { width:48px; height:48px; object-fit:contain; } .icons { display:flex; flex-wrap:wrap; gap:14px; }
.mock { display:flex; flex-direction:column; gap:6px; }
.mb { width:416px; height:84px; border-radius:12px; padding:10px 12px; display:flex; flex-direction:column; justify-content:space-between; }
.mb-top { display:flex; justify-content:space-between; align-items:center; } .mb-top b { font-size:14px; }
.rw { display:inline-flex; align-items:center; gap:4px; background:#fff; border:1px solid var(--app-line); border-radius:999px; padding:0 8px; height:20px; font-size:12px; margin-left:4px; } .rw i { width:10px; height:10px; border-radius:50%; display:inline-block; }
.mb-desc { margin:0; color:var(--app-text_secondary); font-size:12px; }
.mb-bottom { display:flex; align-items:center; gap:8px; justify-content:flex-end; font-size:12px; } .lab { color:var(--app-text_secondary); } .num { font-size:12px; }
.bar { width:84px; height:4px; background:var(--app-line); border-radius:999px; overflow:hidden; } .bar i { display:block; height:100%; background:var(--app-orange); }
.mb-blank { margin:auto; color:var(--app-text_secondary); font-size:13px; }
</style>
</head>
<body>
<main>
<h1>디자인 시스템 보기</h1>
<p class="lead">생성 파일이다. <code>기획/디자인/디자인_시스템.md</code>, <code>Image/</code> 그림으로 <code>node 기획/화면/공통/build.js</code>가 만든다. 직접 고치지 않는다. 규칙과 값의 정본은 디자인 시스템 문서다.</p>
<nav><a href="#color">색</a><a href="#type">글자</a><a href="#shape">모서리·그림자</a><a href="#mission">미션 카드</a><a href="#comp">컴포넌트</a><a href="#icon">아이콘</a></nav>

<h2 id="color">색</h2>
<p class="lead">디자인 시스템 4절 팔레트. 계열마다 진한 것에서 옅은 것 순이다.</p>
${colorHtml}

<h2 id="type">글자</h2>
<table><thead><tr><th>역할</th><th>크기 (sp)</th><th>굵기</th><th>출처</th><th>보기</th></tr></thead><tbody>
${typeRows}
</tbody></table>

<h2 id="shape">모서리·그림자</h2>
<div class="shapes">${radiusHtml}</div>
<h3>그림자</h3>
<div class="shapes">${shadowHtml}</div>

<h2 id="mission">미션 카드 (새 시안, 토큰으로 그림, 절반 크기)</h2>
<p class="lead">mission_box의 새 모양은 컴포넌트 그림이 아직 없어 디자인 시스템 값으로 그렸다.</p>
${mock}

<h2 id="comp">컴포넌트</h2>
<p class="lead">디자인 시스템 6절 표 순서다. 그림은 전달용이고, 앱은 표의 정보로 다시 그린다. 그림과 다른 점은 디자인 시스템 각 절에 있다.</p>
${compHtml}
${unused.length ? `<h3>표에 없는 그림</h3><div class="pics">${unused.map(f => `<figure>${img(compDir, f)}<figcaption>${esc(f.replace('.svg', ''))}</figcaption></figure>`).join('')}</div>` : ''}

<h2 id="icon">아이콘</h2>
<p class="lead">아이콘은 그림을 그대로 쓴다(VectorDrawable).</p>
<div class="icons">${iconHtml}</div>
</main>
</body>
</html>
`;
  const out = path.join(root, '기획', '디자인', '디자인_시스템_보기.html');
  fs.writeFileSync(out, html);
  return { out, components: groups.reduce((n, g) => n + g.items.length, 0), unused };
};
