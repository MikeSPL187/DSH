// Скачать tarball из реестра npm и сверить его integrity (sha512, base64), иначе выйти с ошибкой.
//   node fetch-npm.js <url> <sha512-...> <файл>
const crypto = require('node:crypto');
const fs = require('node:fs');

const [url, integrity, out] = process.argv.slice(2);
const [algorithm, expected] = integrity.split(/-(.*)/s);
(async () => {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
  const data = Buffer.from(await response.arrayBuffer());
  const actual = crypto.createHash(algorithm).update(data).digest('base64');
  if (algorithm !== 'sha512' || actual !== expected) throw new Error(`${url}: integrity ${algorithm}-${actual} != ${integrity}`);
  fs.writeFileSync(out, data);
  console.log(`${out}: ${data.length} bytes, ${integrity}`);
})().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
