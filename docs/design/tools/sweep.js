// Полный обход прототипа в мобильном режиме: снимки всех экранов и состояний + аудит
const { chromium } = require('playwright'); const audit = require('./audit.js');
const fs = require('fs');
(async () => {
  const out = process.argv[2], W = +process.argv[3] || 412, H = +process.argv[4] || 915, themes = (process.argv[5] || 'light,dark').split(',');
  fs.mkdirSync(out + '/sweep', { recursive: true });
  const b = await chromium.launch(); const report = [];
  for (const theme of themes) {
    const p = await b.newPage({ viewport: { width: W, height: H } });
    const errs = []; p.on('pageerror', e => errs.push(e.message));
    await p.goto('file://' + out + '/dsh-prototype.html'); await p.waitForTimeout(800);
    await p.evaluate((t) => { document.querySelector(`[data-theme-set="${t}"]`).click(); }, theme);
    const nav = async (g, wait = 1100) => { await p.evaluate((g) => document.querySelector(`[data-go="${g}"]`).click(), g); await p.waitForTimeout(wait); };
    let n = 0;
    const shot = async (name, scope, scrollSel) => {
      const views = [0];
      if (scrollSel) { const h = await p.evaluate((s) => { const e = document.querySelector(s); return e ? [e.scrollHeight, e.clientHeight] : [0, 0]; }, scrollSel); for (let y = h[1] * 0.8; y < h[0] - h[1] * 0.2; y += h[1] * 0.8) views.push(Math.round(y)); }
      for (const [k, y] of views.entries()) {
        if (scrollSel) { await p.evaluate(([s, y]) => { const e = document.querySelector(s); if (e) e.scrollTop = y; }, [scrollSel, y]); await p.waitForTimeout(150); }
        const file = `sweep/${W}-${theme}-${String(++n).padStart(2, '0')}-${name}${k ? '-' + k : ''}.png`;
        await p.screenshot({ path: out + '/' + file });
        const r = await audit(p, scope);
        const issues = [...r.contrast.map(x => 'контраст ' + x), ...r.touch.filter(x => !x.includes('INPUT')).map(x => 'зона ' + x), ...r.clipped.map(x => 'обрезано ' + x), ...r.overflow.map(x => 'за краем ' + x), ...r.align];
        if (issues.length) report.push(`${file}\n   ${issues.join('\n   ')}`);
      }
      if (scrollSel) await p.evaluate((s) => { const e = document.querySelector(s); if (e) e.scrollTop = 0; }, scrollSel);
    };
    const click = async (sel, wait = 800) => { await p.evaluate((s) => document.querySelector(s).click(), sel); await p.waitForTimeout(wait); };
    const last = (id) => `#${id} .fs-view:last-child`;
    await nav('home'); await shot('home', '#app', '#thread');
    await nav('drawer', 1300); await shot('drawer', '#drawer', '#drawer .dscroll');
    await nav('model'); await shot('model', '#sheet-model', '#sheet-model');
    await nav('plus'); await shot('plus', '#sheet-plus', '#sheet-plus');
    await nav('agent', 6500); await shot('agent-approve', '#main', '#thread');
    await click('[data-ap="yes"]', 9000); await shot('agent-done', '#main', '#thread');
    await nav('offline', 2500); await shot('offline', '#main');
    await nav('danger', 300); await shot('danger-session', '#main'); await p.waitForTimeout(1500); await shot('danger-screen', '#screen-danger', '#screen-danger .dz-scroll');
    await nav('terminal'); await shot('terminal', '#screen-terminal', '#tout');
    await nav('files'); await shot('files', '#screen-files', last('fsBody'));
    await click(`${last('fsBody')} [data-path="app/"]`); await click(`${last('fsBody')} [data-path="app/src/"]`); await shot('files-nested', '#screen-files');
    await click('#fsBack'); await click('#fsBack');
    await click(`${last('fsBody')} .rc[data-path="gradle/libs.versions.toml"]`); await shot('files-file', '#screen-files', last('fsBody'));
    await click(`${last('fsBody')} .fv-banner`); await shot('files-diff', '#screen-files');
    await click('#fsBack'); await click('#fsBack');
    await click(`${last('fsBody')} [data-more="README.md"]`); await shot('files-more', '#sheet-file'); await click('#scrim');
    await click('#fsAdd'); await shot('files-add', '#sheet-fsadd'); await click('#scrim');
    await click('#fsTabs button:nth-of-type(2)'); await shot('files-changes', '#screen-files');
    await click('#fsFind'); await p.fill('#fsQ', 'gradle'); await p.waitForTimeout(400); await shot('files-search', '#screen-files');
    await nav('plugins'); await shot('plugins', '#screen-plugins', last('plBody'));
    await click(`${last('plBody')} .plr[data-pl="arch"]`); await shot('plugin-detail', '#screen-plugins', last('plBody'));
    await click(`${last('plBody')} [data-pllog="arch"]`); await shot('plugin-log', '#screen-plugins');
    await click('#plBack'); await click('#plBack');
    await click(`${last('plBody')} .pl-alert`); await shot('plugin-blocked', '#screen-plugins', last('plBody'));
    await click('#plBack'); await click('#plAdd'); await shot('plugin-add', '#sheet-pladd');
    await click('#plFromFile', 1300); await shot('plugin-trust', '#sheet-trust', '#sheet-trust'); await click('#scrim');
    await nav('automation'); await shot('automation', '#screen-automation', last('auBody'));
    await click(`${last('auBody')} .ar[data-au="arch"]`); await shot('automation-edit', '#screen-automation', last('auBody'));
    await click('#auBack'); await click('#auAdd'); await shot('automation-new', '#screen-automation', last('auBody'));
    await nav('settings'); await shot('settings', '#screen-settings', last('stBody'));
    for (const pg of ['models', 'look', 'agent', 'host', 'storage', 'security', 'notif', 'about']) {
      await click(`${last('stBody')} [data-stgo="${pg}"]`); await shot('settings-' + pg, '#screen-settings', last('stBody')); await click('#stBack');
    }
    await nav('onboard', 1500); await shot('onboard-0', '#onboard');
    await click('#obNext', 1000); await shot('onboard-1', '#onboard'); await click('#obPaste', 1300); await shot('onboard-1ok', '#onboard');
    await click('#obNext', 1000); await click('#obWs .ob-opt:nth-of-type(2)', 300); await shot('onboard-2', '#onboard');
    await click('#obNext', 2500); await shot('onboard-3', '#onboard');
    if (errs.length) report.push(`${theme} ОШИБКИ JS: ${errs.join(' | ')}`);
    await p.close();
  }
  await b.close();
  console.log(report.join('\n') || 'чисто');
})();
