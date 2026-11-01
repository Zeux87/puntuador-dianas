// Banco de pruebas de la detección de impactos.
// Genera 14 dianas sintéticas grandes (agrupaciones realistas, dobles de 2 a 3,5 mm, ruido, desenfoque
// y perspectiva), las pasa por la app y compara lo detectado con los impactos reales.
//
// Uso (necesita Node y Playwright con Chromium):
//   node herramientas/banco-deteccion.js app/src/main/assets/index.html          sin número esperado
//   node herramientas/banco-deteccion.js app/src/main/assets/index.html exp      con 10 impactos esperados
//   DISC=carabina node herramientas/banco-deteccion.js app/src/main/assets/index.html
//   V=1 ...   muestra las coordenadas de los perdidos y los falsos
const { chromium } = require('playwright');
const file = process.argv[2], useExp = process.argv[3] === 'exp', DISCK = process.env.DISC || 'pistola';
function rngS(seed){ return () => { seed |= 0; seed = seed + 0x6D2B79F5 | 0; let t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
// Casos: impactos en mm (x derecha, y arriba)
function makeCases(){
  const cases = [];
  for (let k = 0; k < 14; k++){
    const r = rngS(100 + k), shots = [];
    const sig = DISCK === 'carabina' ? [1.5, 2.5, 4, 5.5][k % 4] : [5, 8, 12, 16][k % 4];
    const gauss = () => Math.sqrt(-2 * Math.log(r() + 1e-12)) * Math.cos(2 * Math.PI * r());
    const ox = gauss() * (DISCK === 'carabina' ? 1.5 : 4), oy = gauss() * (DISCK === 'carabina' ? 1.5 : 4);
    while (shots.length < 10) shots.push([ox + gauss() * sig, oy + gauss() * sig]);
    // dobles: algunos impactos se pegan a otro (2–3,5 mm de separación)
    const nd = k % 4;
    for (let j = 0; j < nd; j++){
      const b = shots[j * 2], a = r() * 2 * Math.PI, sep = 2.0 + r() * 1.5;
      shots[j * 2 + 1] = [b[0] + Math.cos(a) * sep, b[1] + Math.sin(a) * sep];
    }
    cases.push({ disc: DISCK, seed: k, shots, S: k % 2 ? 3000 : 3800, frac: 0.55 + (k % 5) * 0.08, blur: (k % 3) * 0.8, rot: 0 * r(), persp: 0.93 + r() * 0.06, noise: 10 + (k % 4) * 4 });
  }
  return cases;
}
(async () => {
  const b = await chromium.launch();
  const pg = await b.newPage({ viewport: { width: 400, height: 900 } });
  pg.on('pageerror', e => console.log('ERR', e.message));
  await pg.goto('file://' + require('path').resolve(file));
  await pg.waitForTimeout(400);
  const cases = makeCases();
  let tot = { n: 0, hit: 0, miss: 0, fals: 0, err: 0, ms: 0, dScore: 0 };
  for (const c of cases){
    const res = await pg.evaluate(({ c, useExp }) => {
      function rng(seed){ return () => { seed |= 0; seed = seed + 0x6D2B79F5 | 0; let t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
      const S = c.S, d = DISC[c.disc], s = S * c.frac / (c.disc === 'carabina' ? 52 : 170), cv = document.createElement('canvas'); cv.width = S; cv.height = S;
      const x = cv.getContext('2d'), rnd = rng(c.seed + 7);
      x.fillStyle = '#4a504d'; x.fillRect(0, 0, S, S);
      x.save(); x.translate(S / 2 + 20, S / 2 - 15); x.rotate(c.rot); x.scale(1, c.persp);
      const HW = c.disc === 'carabina' ? 26 : 85; x.fillStyle = '#d8dad0'; x.fillRect(-HW * s, -HW * s, 2 * HW * s, 2 * HW * s);
      x.strokeStyle = '#1a1a1a'; x.lineWidth = 0.22 * s;
      const nIn = c.disc === 'carabina' ? 3 : 6; for (let n = 1; n <= nIn; n++){ x.beginPath(); x.arc(0, 0, ringR(d, n) * s, 0, Math.PI * 2); x.stroke(); }
      x.fillStyle = '#151515'; x.beginPath(); x.arc(0, 0, d.negro * s, 0, Math.PI * 2); x.fill();
      x.strokeStyle = '#d9dbd2';
      if (c.disc === 'carabina'){ for (let n = 5; n <= 9; n++){ x.beginPath(); x.arc(0, 0, ringR(d, n) * s, 0, Math.PI * 2); x.stroke(); } x.fillStyle = '#d9dbd2'; x.beginPath(); x.arc(0, 0, 0.25 * s, 0, 7); x.fill(); }
      else { for (let n = 8; n <= 10; n++){ x.beginPath(); x.arc(0, 0, ringR(d, n) * s, 0, Math.PI * 2); x.stroke(); }
      x.beginPath(); x.arc(0, 0, d.interior * s, 0, Math.PI * 2); x.stroke(); }
      x.font = 'bold ' + Math.round((c.disc === 'carabina' ? 1.1 : 3.4) * s) + 'px sans-serif'; x.textAlign = 'center'; x.textBaseline = 'middle';
      for (let n = 1; n <= 8; n++){
        if (c.disc === 'carabina' && n > 3 && n < 4) continue;
        const r = (ringR(d, n) + ringR(d, n + 1)) / 2 * s;
        x.fillStyle = r < d.negro * s ? '#d9dbd2' : '#1a1a1a';
        for (const [ux, uy] of [[0,-1],[1,0],[0,1],[-1,0]]) x.fillText(String(n), ux * r, uy * r);
      }
      for (const [mx, my] of c.shots){
        const px = mx * s, py = -my * s;
        x.beginPath();
        for (let i = 0; i <= 40; i++){
          const a = i / 40 * Math.PI * 2, r = PELLET * s * (0.93 + rnd() * 0.12);
          const X = px + Math.cos(a) * r, Y = py + Math.sin(a) * r;
          i ? x.lineTo(X, Y) : x.moveTo(X, Y);
        }
        x.closePath(); x.fillStyle = '#f6f6ee'; x.fill();
      }
      x.restore();
      if (c.blur){ const c2 = document.createElement('canvas'); c2.width = S; c2.height = S; const x2 = c2.getContext('2d'); x2.filter = `blur(${c.blur * S / 3000}px)`; x2.drawImage(cv, 0, 0); x.clearRect(0,0,S,S); x.drawImage(c2, 0, 0); }
      const im = x.getImageData(0, 0, S, S), dd = im.data;
      for (let i = 0; i < dd.length; i += 4){ const n = (rnd() - 0.5) * c.noise; dd[i] += n; dd[i + 1] += n; dd[i + 2] += n; }
      x.putImageData(im, 0, 0);
      St.expOverride = useExp ? 10 : 0; St.disc = c.disc;
      const t0 = performance.now();
      loadSource(cv, false);
      return new Promise(res => { const chk = () => { if (St.msg.includes('Buscando')) return setTimeout(chk, 5); res(performance.now() - t0); }; chk(); }).then(ms => {
      const det = St.shots.map(sh => { const i = shotInfo(sh); return [i.mx, i.my, i.dec, sh.dudoso ? 1 : 0]; });
      return { det, ms, msg: St.msg }; });
    }, { c, useExp });
    // emparejar (codicioso por distancia, máx 2 mm)
    const truth = c.shots.map(p => ({ p, used: false })), dets = res.det.map(p => ({ p, used: false }));
    const pairs = [];
    for (const t of truth) for (const q of dets) pairs.push([Math.hypot(t.p[0] - q.p[0], t.p[1] - q.p[1]), t, q]);
    pairs.sort((a, b) => a[0] - b[0]);
    let hit = 0, err = 0, dScore = 0;
    for (const [dd, t, q] of pairs){ if (dd > 2) break; if (t.used || q.used) continue; t.used = q.used = true; hit++; err += dd;
      const sc = Math.min(10.9, Math.floor((11 - Math.hypot(t.p[0], t.p[1]) / (DISCK === 'carabina' ? 2.5 : 8)) * 10 + 1e-6) / 10); dScore += Math.abs(sc - q.p[2]); }
    tot.missSep = (tot.missSep || 0) + truth.filter(t => !t.used && truth.every(o => o === t || Math.hypot(o.p[0] - t.p[0], o.p[1] - t.p[1]) >= 1.5)).length;
    const miss = truth.filter(t => !t.used).length, fals = dets.filter(q => !q.used).length;
    tot.n += 10; tot.hit += hit; tot.miss += miss; tot.fals += fals; tot.err += err; tot.ms += res.ms; tot.dScore += dScore;
    if (process.env.V) console.log('  falsos:', dets.filter(q => !q.used).map(q => q.p.map(v => v.toFixed(1)).join(',')).join(' | '), ' perdidos:', truth.filter(t => !t.used).map(t => t.p.map(v => v.toFixed(1)).join(',')).join(' | '));
    console.log(`caso ${c.seed}: S=${c.S} frac=${c.frac.toFixed(2)} blur=${c.blur.toFixed(1)} → ok ${hit}/10, perdidos ${miss}, falsos ${fals}, error medio ${(hit ? err / hit : 0).toFixed(2)} mm, ${res.ms.toFixed(0)} ms`);
  }
  console.log(`TOTAL: aciertos ${tot.hit}/${tot.n} (${(tot.hit / tot.n * 100).toFixed(1)} %), perdidos ${tot.miss} (separables ${tot.missSep}), falsos ${tot.fals}, error medio ${(tot.err / tot.hit).toFixed(3)} mm, error de puntuación medio ${(tot.dScore / tot.hit).toFixed(3)}, tiempo medio ${(tot.ms / 14).toFixed(0)} ms`);
  await b.close();
})();
