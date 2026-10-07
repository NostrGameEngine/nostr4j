import { readFileSync } from 'node:fs';

const source = readFileSync('site-demos/src/backend/java/QuickStart.java', 'utf8');
const template = readFileSync('theme/home.html', 'utf8');
const snippet = source.match(/\/\/ snippet:start\n([\s\S]*?)\s*\/\/ snippet:end/);
const displayed = template.match(/<pre><code class="language-java">([\s\S]*?)<\/code><\/pre>/);
if (!snippet || !displayed) throw new Error('Quickstart source or homepage code block missing');
const code = snippet[1].split('\n').map(line => line.startsWith('        ') ? line.slice(8) : line).join('\n').trim();
if (code !== displayed[1].trim()) {
  throw new Error('Homepage quickstart differs from the compiled JVM source');
}
if (!source.includes('pool.publish(note).await()')) {
  throw new Error('Quickstart must await the aggregate publish policy');
}
console.log('Quickstart source and homepage example match.');
