// Banco de pruebas del lector WiFi: localizar cada impacto nuevo comparando fotos sucesivas.
// Simula la cámara fija junto a la diana (2048×1536, inclinada, JPEG, ruido, cambios de luz y
// pequeños movimientos del papel) y dispara 10 impactos uno a uno, con algunos pegados a otros.
//
// Uso (necesita Node y Playwright con Chromium):
//   node herramientas/banco-lector.js app/src/main/assets/index.html
//   FONDO=40 node herramientas/banco-lector.js ...    gris de lo que se ve a través del agujero (0-255)
//   SIN_PISTA=1 ...  sin la posición aproximada que manda el lector
//   V=1 ...   muestra cada impacto
const { chromium } = require('playwright');
const file = process.argv[2], FONDO = +(process.env.FONDO || 150);
function rngS(seed){ return () => { seed |= 0; seed = seed + 0x6D2B79F5 | 0; let t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
function makeCases(){
  const cases = [];
  for (let k = 0; k < 8; k++){
    const r = rngS(500 + k), shots = [];
    const gauss = () => Math.sqrt(-2 * Math.log(r() + 1e-12)) * Math.cos(2 * Math.PI * r());
    const sig = [5, 8, 12, 16][k % 4];
    while (shots.length < 10) shots.push([gauss() * sig, gauss() * sig]);
    // impactos pegados a uno anterior (1,5 a 3,5 mm)
    for (let j = 0; j < 1 + k % 3; j++){
      const i = 3 + j * 3, b = shots[i - 1 - (j % 2)], a = r() * 2 * Math.PI, sep = 1.5 + r() * 2;
      shots[i] = [b[0] + Math.cos(a) * sep, b[1] + Math.sin(a) * sep];
    }
    cases.push({ seed: k, shots, frac: [0.45, 0.6, 0.75][k % 3], persp: [0.95, 0.85, 0.75, 0.8][k % 4], rot: 0 * r(), noise: 6 + (k % 3) * 3, mueve: k % 2 === 1 });
  }
  return cases;
}
(async () => {
  const b = await chromium.launch();
  const pg = await b.newPage({ viewport: { width: 400, height: 900 } });
  pg.on('pageerror', e => console.log('ERR', e.message));
  await pg.goto('file://' + require('path').resolve(file));
  await pg.waitForTimeout(400);
  if (process.env.SIN_PISTA) await pg.evaluate(() => { window.__sinPista = true; });
  let T = { n: 0, ok: 0, dud: 0, nada: 0, err: 0, maxe: 0, dScore: 0 };
  for (const c of makeCases()){
    const res = await pg.evaluate(async ({ c, FONDO }) => {
      function rng(seed){ return () => { seed |= 0; seed = seed + 0x6D2B79F5 | 0; let t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
      const d = DISC.pistola, WW = 2048, HH = 1536, s = WW * c.frac / 170, rnd = rng(c.seed + 9);
      let pista = { x: 0, y: 0 };
      const SIN_PISTA = !!window.__sinPista;
      const holeShape = c.shots.map(() => Array.from({ length: 41 }, () => 0.94 + rnd() * 0.1));
      const frame = async (nShots, f) => {
        const cv = document.createElement('canvas'); cv.width = WW; cv.height = HH;
        const x = cv.getContext('2d');
        const luz = 1 + (rnd() - 0.5) * 0.06;
        x.fillStyle = '#3c403e'; x.fillRect(0, 0, WW, HH);
        x.save();
        const mx = c.mueve ? (rnd() - 0.5) * 2 * f : 0, my = c.mueve ? (rnd() - 0.5) * 2 * f : 0;
        if (nShots){ const [sx, sy] = c.shots[nShots - 1]; pista = { x: (WW / 2 + 30 + mx + sx * s + (rnd() - 0.5) * 16) / WW, y: (HH / 2 - 10 + my - sy * s * c.persp + (rnd() - 0.5) * 16) / HH }; }
        x.translate(WW / 2 + 30 + mx, HH / 2 - 10 + my); x.rotate(c.rot); x.scale(1, c.persp);
        x.fillStyle = '#d8dad0'; x.fillRect(-85 * s, -85 * s, 170 * s, 170 * s);
        x.strokeStyle = '#1a1a1a'; x.lineWidth = 0.22 * s;
        for (let n = 1; n <= 6; n++){ x.beginPath(); x.arc(0, 0, ringR(d, n) * s, 0, Math.PI * 2); x.stroke(); }
        x.fillStyle = '#151515'; x.beginPath(); x.arc(0, 0, d.negro * s, 0, Math.PI * 2); x.fill();
        x.strokeStyle = '#d9dbd2';
        for (let n = 8; n <= 10; n++){ x.beginPath(); x.arc(0, 0, ringR(d, n) * s, 0, Math.PI * 2); x.stroke(); }
        x.beginPath(); x.arc(0, 0, d.interior * s, 0, Math.PI * 2); x.stroke();
        x.font = 'bold ' + Math.round(3.4 * s) + 'px sans-serif'; x.textAlign = 'center'; x.textBaseline = 'middle';
        for (let n = 1; n <= 8; n++){
          const r = (ringR(d, n) + ringR(d, n + 1)) / 2 * s;
          x.fillStyle = r < d.negro * s ? '#d9dbd2' : '#1a1a1a';
          for (const [ux, uy] of [[0,-1],[1,0],[0,1],[-1,0]]) x.fillText(String(n), ux * r, uy * r);
        }
        for (let i = 0; i < nShots; i++){
          const [sx, sy] = c.shots[i], px = sx * s, py = -sy * s;
          x.beginPath();
          for (let j = 0; j <= 40; j++){
            const a = j / 40 * Math.PI * 2, r = PELLET * s * holeShape[i][j];
            const X = px + Math.cos(a) * r, Y = py + Math.sin(a) * r;
            j ? x.lineTo(X, Y) : x.moveTo(X, Y);
          }
          x.closePath(); const g = FONDO; x.fillStyle = `rgb(${g},${g},${g})`; x.fill();
          x.strokeStyle = 'rgba(40,40,40,0.5)'; x.lineWidth = 0.25 * s; x.stroke();
        }
        x.restore();
        const im = x.getImageData(0, 0, WW, HH), dd = im.data;
        for (let i = 0; i < dd.length; i += 4){ const n = (rnd() - 0.5) * c.noise; for (let k = 0; k < 3; k++) dd[i + k] = dd[i + k] * luz + n; }
        x.putImageData(im, 0, 0);
        const url = cv.toDataURL('image/jpeg', 0.88);
        return await new Promise(ok => { const img = new Image(); img.onload = () => ok(img); img.src = url; });
      };
      St.expOverride = 0; St.disc = 'pistola'; St.session = null;
      Lec.on = true;
      lectorCargar(await frame(0, 0));
      await new Promise(res => { const chk = () => St.msg.includes('Buscando') ? setTimeout(chk, 5) : res(); chk(); });
      const ini = St.shots.length, out = [];
      for (let i = 0; i < c.shots.length; i++){
        const im = await frame(i + 1, 1);
        const antes = St.shots.length;
        lectorEvento(SIN_PISTA ? { tipo: 'impacto', x: 0, y: 0 } : { tipo: 'impacto', ...pista }, im);
        if (St.shots.length === antes){ out.push(null); continue; }
        const sh = St.shots[St.shots.length - 1], inf = shotInfo(sh);
        out.push([inf.mx, inf.my, inf.dec, sh.dudoso ? 1 : 0]);
      }
      Lec.on = false;
      return { ini, out, cal: !!St.cal };
    }, { c, FONDO });
    let ok = 0, dud = 0, nada = 0, err = 0, maxe = 0, ds = 0;
    res.out.forEach((o, i) => {
      const t = c.shots[i];
      if (!o){ nada++; if (process.env.V) console.log(`   ${i + 1}: sin impacto (real ${t.map(v => v.toFixed(1))})`); return; }
      const e = Math.hypot(o[0] - t[0], o[1] - t[1]);
      const sc = Math.min(10.9, Math.floor((11 - Math.hypot(t[0], t[1]) / 8) * 10 + 1e-6) / 10);
      if (e <= 1) ok++; err += e; maxe = Math.max(maxe, e); ds += Math.abs(sc - o[2]); if (o[3]) dud++;
      if (process.env.V) console.log(`   ${i + 1}: error ${e.toFixed(2)} mm, ${o[2]} (real ${sc})${o[3] ? ' dudoso' : ''}`);
    });
    const n = res.out.filter(Boolean).length;
    T.n += 10; T.ok += ok; T.dud += dud; T.nada += nada; T.err += err; T.maxe = Math.max(T.maxe, maxe); T.dScore += ds;
    console.log(`caso ${c.seed}: frac=${c.frac} persp=${c.persp} ${c.mueve ? 'papel se mueve' : ''} → de partida ${res.ini}, a <1 mm ${ok}/10, sin marcar ${nada}, dudosos ${dud}, error medio ${(n ? err / n : 0).toFixed(2)} mm, máx ${maxe.toFixed(2)} mm`);
  }
  const n = T.n - T.nada;
  console.log(`TOTAL (fondo ${FONDO}): a <1 mm ${T.ok}/${T.n}, sin marcar ${T.nada}, dudosos ${T.dud}, error medio ${(T.err / n).toFixed(3)} mm, máx ${T.maxe.toFixed(2)} mm, error de puntuación medio ${(T.dScore / n).toFixed(3)}`);
  await b.close();
})();
