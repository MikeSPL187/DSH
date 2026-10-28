// Аудит видимого состояния: контраст текста, размер касаемых зон, обрезанный текст.
module.exports = async function audit(p, scopeSel) {
  return p.evaluate((scopeSel) => {
    const scope = document.querySelector(scopeSel) || document.body;
    const out = { contrast: [], touch: [], clipped: [] };
    const sr = scope.getBoundingClientRect();
    const vis = (el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el);
      if (r.width < 1 || r.height < 1 || cs.visibility === "hidden" || cs.display === "none") return false;
      for (let e = el; e && e !== document.body; e = e.parentElement) { const c = getComputedStyle(e); if (c.opacity === "0" || c.visibility === "hidden") return false; }
      return r.right > sr.left && r.left < sr.right && r.bottom > sr.top && r.top < sr.bottom; };
    const parse = (c) => { const m = c.match(/[\d.]+/g); if (!m) return [0,0,0,0]; const v = m.map(Number);
      // color(srgb r g b / a) — каналы 0..1
      if (c.startsWith("color(")) { const a = c.includes("/") ? v[3] : 1; return [v[0]*255, v[1]*255, v[2]*255, a]; } return v; };
    const lum = ([r,g,b]) => { const f = (v) => { v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }; return .2126*f(r) + .7152*f(g) + .0722*f(b); };
    const bgOf = (el) => { let stack = []; for (let e = el; e; e = e.parentElement) { const c = parse(getComputedStyle(e).backgroundColor); const a = c[3] === undefined ? 1 : c[3]; if (a > 0) { stack.push([c, a]); if (a >= .99) break; } }
      let col = [255,255,255]; for (let i = stack.length - 1; i >= 0; i--) { const [c, a] = stack[i]; col = col.map((v, k) => v * (1 - a) + c[k] * a); } return col; };
    // Градиентный фон (аватар, плашки): проверяем по каждому цвету градиента — берётся худший, а не цвет страницы под ним.
    const bgsOf = (el) => { for (let e = el; e; e = e.parentElement) {
        const cs = getComputedStyle(e);
        if ((parse(cs.backgroundColor)[3] ?? 1) >= .99) break;
        if (cs.backgroundImage.includes("gradient(")) {
          // Полупрозрачные точки градиента (мягкий акцент в тёмной теме) ложатся на фон под элементом.
          const stops = cs.backgroundImage.match(/(?:rgba?|color)\([^)]+\)/g); const base = bgOf(e.parentElement || e);
          if (stops?.length) return stops.map((c) => { const v = parse(c), a = v[3] ?? 1; return v.slice(0, 3).map((x, k) => x * a + base[k] * (1 - a)); });
        }
      } return [bgOf(el)]; };
    const label = (el) => (el.textContent || el.getAttribute("aria-label") || el.className || el.tagName).trim().replace(/\s+/g, " ").slice(0, 40);
    scope.querySelectorAll("*").forEach((el) => {
      if (!vis(el)) return;
      const own = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim());
      const cs = getComputedStyle(el);
      if (own && cs.backgroundClip !== "text" && !cs.webkitBackgroundClip?.includes("text")) {
        const fg = parse(cs.color); if ((fg[3] ?? 1) < .05) return;
        const fa = fg[3] ?? 1;
        const ratio = Math.min(...bgsOf(el).map((bg) => { const f = fg.slice(0,3).map((v, k) => v * fa + bg[k] * (1 - fa)); const L1 = lum(f), L2 = lum(bg); return (Math.max(L1, L2) + .05) / (Math.min(L1, L2) + .05); }));
        const size = parseFloat(cs.fontSize), bold = +cs.fontWeight >= 600, large = size >= 24 || (bold && size >= 18.66);
        const need = large ? 3 : 4.5;
        const disabled = el.closest("[disabled]") || el.closest("[aria-checked='false'].pick");
        if (ratio < need && !disabled) out.contrast.push(`${ratio.toFixed(2)} < ${need} · ${size}px · «${label(el)}»`);
      }
      if (el.matches("button, [role=button], [role=switch], [role=radio], [role=checkbox], input, a")) {
        const r = el.getBoundingClientRect();
        // Поле ввода внутри <label>: касаемая зона — вся подпись (нажатие в любом её месте фокусирует поле).
        const zone = el.tagName === "INPUT" && el.closest("label") ? el.closest("label").getBoundingClientRect() : r;
        if (Math.min(zone.width, zone.height) < 40 && !el.closest(".toggle") ) out.touch.push(`${Math.round(r.width)}×${Math.round(r.height)} · «${label(el)}»`);
      }
      if ((cs.textOverflow === "ellipsis" || cs.overflow === "hidden") && own && el.scrollWidth > el.clientWidth + 1) out.clipped.push(`«${label(el)}»`);
    });
    // Выход за край карточки: элемент шире своего блока с фоном или скруглением
    out.overflow = [];
    const card = (el) => { for (let e = el.parentElement; e && e !== scope.parentElement; e = e.parentElement) { const c = getComputedStyle(e); if ((parse(c.backgroundColor)[3] ?? 1) > 0 && parseFloat(c.borderRadius) > 0) return e; } return null; };
    scope.querySelectorAll("*").forEach((el) => {
      if (!vis(el) || el.closest("svg")) return;
      const c = card(el); if (!c) return;
      const r = el.getBoundingClientRect(), cr = c.getBoundingClientRect();
      if (r.width > cr.width + 1) return; // горизонтальные ленты с прокруткой
      const cs = getComputedStyle(c);
      if (cs.overflowX === "auto" || cs.overflowX === "scroll") return;
      if (r.right > cr.right + 1 || r.left < cr.left - 1) out.overflow.push(`${Math.round(r.right - cr.right)}px · «${label(el)}» в «${label(c).slice(0, 20)}»`);
    });
    // Заголовок в шапке по центру кнопки «Назад»
    out.align = [];
    scope.querySelectorAll(".sbar, .topbar").forEach((bar) => {
      if (!vis(bar)) return;
      const t = bar.querySelector(".title"), btn = bar.querySelector(".iconbtn");
      if (!t || !btn) return;
      // Видимый текст заголовка (без пустой подписи) по центру кнопки
      const parts = [...t.children].filter((c) => c.textContent.trim() && vis(c));
      if (!parts.length) return;
      const tr = { top: Math.min(...parts.map((c) => c.getBoundingClientRect().top)), bottom: Math.max(...parts.map((c) => c.getBoundingClientRect().bottom)) }, br = btn.getBoundingClientRect();
      const d = Math.abs((tr.top + tr.bottom) / 2 - (br.top + br.bottom) / 2);
      if (d > 3) out.align.push(`заголовок смещён на ${Math.round(d)}px · «${label(t)}»`);
    });
    return out;
  }, scopeSel);
};
