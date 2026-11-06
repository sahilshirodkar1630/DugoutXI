// Regenerates docs/TEST_CASES.md from the test sources, so the list and the tests cannot drift apart.
//
//   node scripts/generate-test-cases.mjs
//
// Backend: every *Test.java under src/test/java. A class that extends AbstractIntegrationTest is an integration
// test; anything else is a unit test. Each @Test or @ParameterizedTest method is one test, named from its method
// name, and the class's own javadoc, when it has one, is printed under its heading.
// Frontend: every *.test.ts(x) under frontend/src, one line per it(...).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const javaRoot = path.join(root, 'src/test/java/com/sahilprojects/bettingApp');
const webRoot = path.join(root, 'frontend/src');

const walk = (dir) =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]);
const rel = (base, file) => path.relative(base, file).split(path.sep).join('/');
// Plain code-point order, which puts a package's own classes before its sub-packages.
const byPath = (a, b) => (a.rel < b.rel ? -1 : a.rel > b.rel ? 1 : 0);

const words = (method) =>
  method
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/([A-Z])([A-Z][a-z])/g, '$1 $2')
    .replace(/([A-Za-z])([0-9])/g, '$1 $2')
    .split(' ')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');

function classDoc(src, className) {
  const at = src.search(new RegExp(`\\n(?:@[^\\n]*\\n)*(?:public\\s+)?(?:abstract\\s+)?class\\s+${className}\\b`));
  if (at < 0) return null;
  const before = src.slice(0, at);
  const m = before.match(/\/\*\*([\s\S]*?)\*\/\s*(?:@[^\n]*\s*)*$/);
  if (!m) return null;
  return m[1]
    .split('\n')
    .map((l) => l.replace(/^\s*\*\s?/, '').trim())
    .join('\n')
    .trim()
    .split(/\n\s*\n/)
    .map((p) => p.replace(/\s*\n\s*/g, ' ').replace(/\{@code ([^}]*)\}/g, '`$1`').replace(/<\/?pre>/g, ''))
    .join('\n\n');
}

const backend = walk(javaRoot)
  .filter((f) => f.endsWith('Test.java'))
  .map((f) => ({ file: f, rel: rel(javaRoot, f) }))
  .sort(byPath)
  .map(({ file, rel: r }) => {
    const src = fs.readFileSync(file, 'utf8');
    const name = path.basename(file, '.java');
    if (/abstract\s+class/.test(src)) return null;
    const tests = [...src.matchAll(/@(?:Test|ParameterizedTest)\b[\s\S]*?void\s+(\w+)\s*\(/g)].map((m) => words(m[1]));
    if (tests.length === 0) return null;
    const kind = /extends\s+AbstractIntegrationTest/.test(src) ? 'integration' : 'unit';
    return { name, rel: r, kind, tests, doc: classDoc(src, name) };
  })
  .filter(Boolean);

const frontend = walk(webRoot)
  .filter((f) => /\.test\.tsx?$/.test(f))
  .map((f) => ({ file: f, rel: rel(path.join(root, 'frontend'), f) }))
  .sort(byPath)
  .map(({ file, rel: r }) => {
    const src = fs.readFileSync(file, 'utf8');
    const tests = [...src.matchAll(/\bit\(\s*(['"`])((?:\\.|(?!\1).)*)\1/g)].map((m) => {
      const t = m[2].replace(/\\(['"`])/g, '$1');
      return t.charAt(0).toUpperCase() + t.slice(1);
    });
    return { name: path.basename(file), rel: r, tests };
  })
  .filter((f) => f.tests.length > 0);

const count = (list) => list.reduce((n, c) => n + c.tests.length, 0);
const out = [];
out.push('# Test cases', '');
out.push('Generated from the test sources, so this list and the tests cannot drift apart.');
out.push(`**${count(backend)} backend tests across ${backend.length} classes, ${count(frontend)} frontend tests across ${frontend.length} files.**`);
out.push('', 'Regenerate it with `node scripts/generate-test-cases.mjs` after adding or renaming a test.');
out.push('', '');
out.push('Backend tests need Docker: the integration tests start a real PostgreSQL 16 through Testcontainers and run');
out.push('every migration against it, so the schema, the triggers and the views are all exercised rather than mocked.');
out.push('', '```bash', 'mvn test                 # the backend', 'cd frontend && npm test  # the frontend', '```', '');
out.push('## Backend', '');
for (const c of backend) {
  out.push(`### ${c.name} (${c.kind}, ${c.tests.length} test${c.tests.length === 1 ? '' : 's'})`, '', `\`${c.rel}\``, '');
  if (c.doc) out.push(c.doc, '');
  for (const t of c.tests) out.push(`- ${t}`);
  out.push('');
}
out.push('## Frontend', '');
for (const f of frontend) {
  out.push(`### ${f.name} (${f.tests.length} test${f.tests.length === 1 ? '' : 's'})`, '', `\`${f.rel}\``, '');
  for (const t of f.tests) out.push(`- ${t}`);
  out.push('');
}
fs.writeFileSync(path.join(root, 'docs/TEST_CASES.md'), out.join('\n'));
console.log(`docs/TEST_CASES.md: ${count(backend)} backend tests in ${backend.length} classes, `
  + `${count(frontend)} frontend tests in ${frontend.length} files`);
