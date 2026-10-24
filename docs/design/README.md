# Редизайн DSH

- `dsh-prototype.html` — интерактивный прототип всех экранов (открыть в браузере).
- `dsh-redesign-research.html` — исследование рынка и технический план.
- `tools/audit.js` — аудит состояния экрана в Playwright: контраст WCAG AA,
  касаемые зоны меньше 40 px, обрезанный текст. Используется так:
  `const audit = require('./audit.js'); const r = await audit(page, '#screen-files');`

Требования и порядок проверки макетов — в `CLAUDE.md` в корне репозитория.
