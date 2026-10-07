import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const jar = process.argv[2];
if (!jar) throw new Error('Usage: node scripts/check-doc-examples.mjs <demo-backend.jar>');

// Supply the application values referenced by each example, without running
// network requests, signer prompts or wallet payments during validation.
const context = {
  pool: 'NostrPool', signalingPool: 'NostrPool', signer: 'NostrSigner',
  keys: 'NostrKeyPair', clientKeyPair: 'NostrKeyPair', roomKeyPair: 'NostrKeyPair',
  unsigned: 'UnsignedNostrEvent', unsignedEvent: 'UnsignedNostrEvent',
  draft: 'UnsignedNostrEvent', store: 'EventStore', notes: 'NostrFilter',
  filter: 'NostrFilter', numEvents: 'int', timeout: 'Duration',
  ids: 'Nip39ExternalIdentities',
  event: 'SignedNostrEvent', signed: 'SignedNostrEvent', signedNote: 'SignedNostrEvent',
  sourceEvent: 'SignedNostrEvent', eventToZap: 'SignedNostrEvent', receiptEvent: 'SignedNostrEvent',
  author: 'NostrPublicKey', pubkey: 'NostrPublicKey', myPubkey: 'NostrPublicKey',
  alice: 'NostrPublicKey', bob: 'NostrPublicKey', mentionedUser: 'NostrPublicKey',
  recipient: 'NostrPublicKey', sender: 'NostrPublicKey', theirPublicKey: 'NostrPublicKey',
  privateKey: 'NostrPrivateKey', myPrivateKey: 'NostrPrivateKey', secret: 'NostrPrivateKey',
  userSuppliedNsec: 'String', passphrase: 'String', bunkerUrl: 'String',
  connectionString: 'String', bolt11: 'String', paymentHash: 'String',
  recipientPubkeyHex: 'String', eventId: 'String',
  appMetadata: 'Nip46AppMetadata', nip01metadata: 'Nip01UserMetadata',
  payerMetadata: 'Nip01UserMetadata', payerSigner: 'NostrSigner', wallet: 'NWCWallet',
  amountMsats: 'long', from: 'Instant', until: 'Instant', imageBytes: 'byte[]',
  expectedInvoice: 'ZapInvoice', expectedProvider: 'NostrPublicKey',
  expectedRequest: 'SignedNostrEvent', expectedPreimage: 'String',
  expectedLnUrl: 'LnUrl', expectedSender: 'NostrPublicKey',
  room: 'NostrRTCRoom', peer: 'NostrRTCPeer', message: 'byte[]', announcement: 'byte[]',
  remotePeerNpub: 'String', savedNsec: 'String', sharedConnectionId: 'String',
  knownRemotePeerNpub: 'String',
};

const packages = [
  'java.io', 'java.nio', 'java.nio.charset', 'java.time', 'java.util',
  'org.ngengine.nostr4j', 'org.ngengine.nostr4j.event', 'org.ngengine.nostr4j.keypair',
  'org.ngengine.nostr4j.signer', 'org.ngengine.nostr4j.store', 'org.ngengine.nostr4j.proto',
  'org.ngengine.nostr4j.pool.ackpolicy', 'org.ngengine.nostr4j.pool.fetchpolicy',
  'org.ngengine.nostr4j.rtc', 'org.ngengine.nostr4j.rtc.signal', 'org.ngengine.nostr4j.io',
  'org.ngengine.nostr4j.nip01', 'org.ngengine.nostr4j.nip05', 'org.ngengine.nostr4j.nip09',
  'org.ngengine.nostr4j.nip24', 'org.ngengine.nostr4j.nip39', 'org.ngengine.nostr4j.nip44',
  'org.ngengine.nostr4j.nip46', 'org.ngengine.nostr4j.nip49', 'org.ngengine.nostr4j.nip50',
  'org.ngengine.nostr4j.nip57', 'org.ngengine.wallets', 'org.ngengine.wallets.nip47',
  'org.ngengine.wallets.nip47.keysend', 'org.ngengine.blossom4j', 'org.ngengine.lnurl',
  'org.ngengine.platform', 'org.ngengine.platform.jvm',
];
const lines = packages.map(name => `import ${name}.*;`);
lines.push('class DocumentationSnippetChecks {',
  'static void showQr(String url) {}', 'static void showQrCode(String url) {}');
const examples = [];
let included = 0;

for (const file of readdirSync('docs/docs').filter(name => name.endsWith('.md')).sort()) {
  // Platform-specific adapters need their own Android/iOS/TeaVM toolchains.
  if (file === 'platforms.md') continue;
  const markdown = readFileSync(path.join('docs/docs', file), 'utf8');
  for (const match of markdown.matchAll(/^```java\s*\n([\s\S]*?)^```/gm)) {
    const code = match[1].trim();
    if (code.startsWith('--8<--')) {
      // These snippets are already compiled from their actual backend sources.
      included++;
      continue;
    }
    const lambdaParameters = new Set();
    for (const lambda of code.matchAll(/\(([^()]*)\)\s*->/g)) {
      for (const parameter of lambda[1].split(',')) {
        lambdaParameters.add(parameter.trim().split(/\s+/).at(-1));
      }
    }
    for (const lambda of code.matchAll(/\b(\w+)\s*->/g)) lambdaParameters.add(lambda[1]);
    const params = Object.entries(context).filter(([name]) => {
      const used = new RegExp(`\\b${name}\\b`).test(code);
      const declared = new RegExp(`\\b[\\w.]+(?:<[^;=\\n]+>)?(?:\\[\\])?\\s+${name}\\s*(?:=|;)`).test(code);
      return used && !declared && !lambdaParameters.has(name);
    }).map(([name, type]) => {
      if (name === 'signer' && /\bsigner\.(listen|connect|sendRPC)\(/.test(code)) type = 'NostrNIP46Signer';
      return `${type} ${name}`;
    });
    lines.push(`static void example${examples.length}(${params.join(', ')}) throws Exception {`);
    const start = lines.length + 1;
    lines.push(...code.split('\n'), '}');
    examples.push({ file, start, end: lines.length - 1 });
  }
}
lines.push('}');

const temp = mkdtempSync(path.join(tmpdir(), 'nostr4j-doc-snippets-'));
try {
  const source = path.join(temp, 'DocumentationSnippetChecks.java');
  writeFileSync(source, lines.join('\n'));
  const javac = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'javac') : 'javac';
  const result = spawnSync(javac, ['-encoding', 'UTF-8', '-cp', path.resolve(jar), '-d', temp, source], {
    encoding: 'utf8',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const diagnostics = result.stderr.replaceAll(`${temp}${path.sep}`, '').replace(/DocumentationSnippetChecks\.java:(\d+):/g, (location, number) => {
      const example = examples.find(item => Number(number) >= item.start && Number(number) <= item.end);
      return example ? `${example.file} (example line ${Number(number) - example.start + 1}):` : location;
    });
    throw new Error(`Documentation Java examples do not compile:\n${diagnostics}`);
  }
  console.log(`Compiled ${examples.length} inline Java examples; ${included} examples use compiled backend sources.`);
} finally {
  rmSync(temp, { recursive: true, force: true });
}
