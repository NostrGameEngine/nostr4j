import {spawn} from 'node:child_process';
import {mkdtemp, writeFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import assert from 'node:assert/strict';

const target = process.argv[2] || 'home';
const base = process.env.DEMO_URL || 'http://127.0.0.1:8000/nostr4j';
const profile = await mkdtemp(join(tmpdir(), 'nostr4j-browser-'));
const chrome = spawn('google-chrome', ['--headless', '--no-sandbox', '--disable-gpu', '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1', '--user-data-dir=' + profile, 'about:blank'], {stdio: ['ignore', 'ignore', 'pipe']});
let socket;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const diagnostics = [];
const requests = new Map();
let seq = 0;
try {
  const endpoint = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Chrome startup timeout')), 30000);
    chrome.stderr.on('data', chunk => {
      const match = String(chunk).match(/DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)/);
      if (match) { clearTimeout(timer); resolve('http://127.0.0.1:' + match[1]); }
    });
  });
  const pages = await fetch(endpoint + '/json/list').then(r => r.json());
  socket = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => socket.addEventListener('open', resolve, {once: true}));
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const pending = requests.get(message.id);
      requests.delete(message.id);
      if (message.error) pending.reject(new Error(JSON.stringify(message.error)));
      else pending.resolve(message.result);
    }
    if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text);
    if (message.method === 'Runtime.consoleAPICalled') {
      const line = message.params.args.map(arg => arg.value || arg.description || '').join(' ');
      if (message.params.type === 'error' || message.params.type === 'warning') diagnostics.push(line.slice(0, 300));
      if (diagnostics.length > 30) diagnostics.shift();
    }
    if (message.method === 'Network.webSocketFrameReceived') {
      const frame = message.params.response.payloadData;
      if (frame.startsWith('["OK"') && frame.includes('false')) diagnostics.push(frame.slice(0, 400));
    }
  });
  function call(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++seq;
      const timer = setTimeout(() => { requests.delete(id); reject(new Error('CDP timeout: ' + method)); }, 45000);
      requests.set(id, {resolve: value => { clearTimeout(timer); resolve(value); }, reject: error => { clearTimeout(timer); reject(error); }});
      socket.send(JSON.stringify({id, method, params}));
    });
  }
  async function evaluate(expression) {
    const result = await call('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || 'Evaluation failed');
    return result.result.value;
  }
  async function until(expression, milliseconds = 15000) {
    const end = Date.now() + milliseconds;
    while (Date.now() < end) { if (await evaluate(expression)) return; await pause(300); }
    throw new Error('Timed out: ' + expression);
  }
  await call('Runtime.enable');
  await call('Page.enable');
  await call('Network.enable');
  await call('Page.navigate', {url: base + (target === 'home' || target === 'layout' ? '/' : '/demos.html')});
  await until('document.readyState === "complete"');
  if (target === 'home') {
    await call('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await evaluate('document.getElementById("quickstart-run").click()');
    await until('!document.getElementById("quickstart-run").disabled', 45000);
    const output = await evaluate('document.getElementById("quickstart-output").textContent');
    console.log(output);
    assert.match(output, /Received: Hello from Nostr4J!/);
    assert.match(output, /Subscription closed/);
    assert.ok(await evaluate('(() => { const button = document.getElementById("quickstart-run").getBoundingClientRect(); const log = document.getElementById("quickstart-output").getBoundingClientRect(); return document.getElementById("quickstart-output").parentElement.classList.contains("home-intro") && log.top >= button.bottom && Math.abs(log.width - button.width) < 1; })()'), 'Example log is not directly below its full-width button');
    assert.ok(await evaluate('(() => { const intro = document.querySelector(".home-intro").getBoundingClientRect(); const code = document.querySelector(".home-demo .source-panel pre").getBoundingClientRect(); return Math.abs(intro.height - code.height) < 1; })()'), 'Code did not grow with the example log');
  } else if (target === 'ping') {
    await evaluate('document.getElementById("demo-tab-rtc").click()');
    await evaluate('document.getElementById("rtc-ping").click()');
    await until('!document.getElementById("rtc-ping").disabled', 85000);
    console.log(await evaluate('document.getElementById("rtc-output").textContent'));
    assert.equal(await evaluate('document.getElementById("rtc-demo").dataset.result'), 'pong');
  } else if (target === 'publish') {
    await evaluate('document.getElementById("demo-tab-publish").click()');
    await evaluate('document.getElementById("publish-content").value = "Nostr4J browser publication test"');
    await evaluate('document.getElementById("publish-send").click()');
    await until('!document.getElementById("publish-send").disabled', 45000);
    const output = await evaluate('document.getElementById("publish-output").textContent');
    console.log(output);
    assert.match(output, /Event ID: [a-f0-9]{64}/);
    assert.match(output, /Received: Nostr4J browser publication test/);
  } else if (target === 'game' || target === 'private') {
    let roomsBeforeLeave = 0;
    await until('Boolean(window.SatsSeasNet && window.p5)');
    await evaluate('document.getElementById("demo-tab-game").click()');
    await evaluate(`window.demoEvents = []; const join = window.SatsSeasNet.join; window.SatsSeasNet.join = (config, callback) => join(config, json => { const event = JSON.parse(json); window.demoEvents.push(event); callback(json); });`);
    if (target === 'private') await evaluate('document.getElementById("sats-multiplayer").checked = false');
    await evaluate('document.getElementById("sats-join").requestSubmit()');
    await until('Boolean(document.querySelector("#sats-canvas canvas"))');
    if (target === 'game') {
      try { await until('demoEvents.some(e => e.t === "msg")', 65000); }
      finally {
        console.log(await evaluate('JSON.stringify(demoEvents.filter(e => e.t !== "topo" && e.t !== "msg").slice(-30))'));
        console.log('Topology:', await evaluate('document.getElementById("sats-status").dataset.topology'));
        console.log('Messages:', await evaluate('demoEvents.filter(e => e.t === "msg").length'));
      }
      assert.ok(await evaluate('demoEvents.some(e => e.t === "ready")'));
      await until('demoEvents.some(e => e.t === "msg" && (() => { try { return JSON.parse(e.body).type === "hello"; } catch (_) { return false; } })())', 30000);
      const islands = await evaluate('Array.from(new Map(demoEvents.filter(e => e.t === "msg").map(e => { try { return [e.from, JSON.parse(e.body)]; } catch (_) { return [e.from, {}]; } }).filter(([, body]) => body.type === "hello" && body.island)).values()).map(e => e.island)');
      islands.push(await evaluate('window.SatsSeasDebug.me().island'));
      for (let i = 0; i < islands.length; i++) for (let j = i + 1; j < islands.length; j++) {
        assert.ok(Math.hypot(islands[i].x - islands[j].x, islands[i].y - islands[j].y) >= 1000, 'Shared islands overlap or are too close');
      }
      assert.equal(await evaluate('demoEvents.filter(e => e.t === "msg").some(e => { try { return JSON.parse(e.body).type === "chat"; } catch (_) { return false; } })'), false, 'Bots sent unsolicited chat');
      await until('(() => { const t = JSON.parse(document.getElementById("sats-status").dataset.topology || "{}"); return t.nodes?.length >= 4 && t.edges?.length >= 3; })()', 45000);
      const topology = JSON.parse(await evaluate('document.getElementById("sats-status").dataset.topology'));
      const degrees = new Map(topology.nodes.map(node => [node.id, 0]));
      for (const edge of topology.edges) {
        degrees.set(edge.a, (degrees.get(edge.a) || 0) + 1);
        degrees.set(edge.b, (degrees.get(edge.b) || 0) + 1);
      }
      assert.ok([...degrees.values()].every(degree => degree <= 2), 'A peer exceeded the direct-link limit');
      const direct = new Set(topology.edges.flatMap(edge => edge.a === topology.self ? [edge.b] : edge.b === topology.self ? [edge.a] : []));
      const destination = await evaluate(`demoEvents.find(e => e.t === "peer" && e.node && e.node !== ${JSON.stringify(topology.self)} && !${JSON.stringify([...direct])}.includes(e.node))?.id || null`);
      assert.match(destination || '', /^[a-f0-9]{64}$/, 'No non-neighbor peer discovered');
      const probe = 'a1b2c3d4e5f60718';
      await evaluate(`window.SatsSeasNet.sendTo(${JSON.stringify(destination)}, "sats-game", JSON.stringify({v:"ss1", type:"chat", text:"onion probe", probe:${JSON.stringify(probe)}}))`);
      await until(`demoEvents.some(e => e.t === "msg" && e.from === ${JSON.stringify(destination)} && (() => { try { return JSON.parse(e.body).probe === ${JSON.stringify(probe)}; } catch (_) { return false; } })())`, 45000);
      console.log('Onion-routed echo from non-neighbor:', destination.slice(0, 12));
    } else {
      await until('document.getElementById("sats-status").dataset.botReply === "true"', 65000);
      await until('demoEvents.some(e => e.t === "msg" && (() => { try { return JSON.parse(e.body).type === "pos"; } catch (_) { return false; } })())', 15000);
      await until('window.SatsSeasDebug?.practicePeers().length === 2 && window.SatsSeasDebug.practicePeers().every(p => p?.island && p.x >= 0)', 20000);
      const initial = await evaluate('window.SatsSeasDebug.practicePeers()');
      const player = await evaluate('window.SatsSeasDebug.me()');
      for (const bot of initial) {
        assert.ok(Math.hypot(bot.island.x - player.island.x, bot.island.y - player.island.y) >= 1000, 'Practice island too close to player');
        assert.ok(Math.hypot(bot.x - bot.island.x, bot.y - bot.island.y) < 250, 'Practice captain did not sail near its island');
      }
      assert.ok(Math.hypot(initial[0].island.x - initial[1].island.x, initial[0].island.y - initial[1].island.y) >= 1000, 'Practice captains have adjacent islands');
      await until(`(() => { const p = window.SatsSeasDebug.practicePeers(); return p.every((bot, i) => Math.hypot(bot.x - ${JSON.stringify(initial)}[i].x, bot.y - ${JSON.stringify(initial)}[i].y) > 20); })()`, 12000);
      const bot = await evaluate('window.SatsSeasDebug.practicePeers()[0]');
      const botId = bot.id;
      const digNonce = 'dig' + Date.now().toString(36);
      await evaluate(`window.SatsSeasNet.sendTo(${JSON.stringify(botId)}, "sats-game", JSON.stringify({v:"ss1",type:"dig",x:${Math.round(bot.island.x)},y:${Math.round(bot.island.y)},nonce:${JSON.stringify(digNonce)}}))`);
      await until(`demoEvents.some(e => e.t === "msg" && e.from === ${JSON.stringify(botId)} && (() => { try { const b = JSON.parse(e.body); return b.type === "ping" && b.nonce === ${JSON.stringify(digNonce)}; } catch (_) { return false; } })())`, 12000);
      await pause(1100);
      await evaluate(`window.SatsSeasNet.sendTo(${JSON.stringify(botId)}, "sats-game", JSON.stringify({v:"ss1",type:"hit",target:${JSON.stringify(botId)},dmg:14}))`);
      await until(`window.SatsSeasDebug.practicePeers()[0]?.hp === ${bot.hp - 14}`, 12000);
      for (let hit = 2; hit <= 8; hit++) {
        await pause(1100);
        await evaluate(`window.SatsSeasNet.sendTo(${JSON.stringify(botId)}, "sats-game", JSON.stringify({v:"ss1",type:"hit",target:${JSON.stringify(botId)},dmg:14}))`);
        await until(`window.SatsSeasDebug.practicePeers()[0]?.hp === ${Math.max(0, 100 - hit * 14)}`, 12000);
      }
      await pause(2000);
      assert.equal(await evaluate('window.SatsSeasDebug.practicePeers()[0]?.hp'), 0, 'Practice captain immediately recovered after sinking');
      await until(`demoEvents.some(e => e.t === "msg" && e.from === ${JSON.stringify(botId)} && (() => { try { return JSON.parse(e.body).type === "scroll"; } catch (_) { return false; } })())`, 12000);
      await until('window.SatsSeasDebug.practicePeers()[0]?.hp === 100', 12000);
      assert.ok(await evaluate('(() => { const bot = window.SatsSeasDebug.practicePeers()[0]; return Math.hypot(bot.x - bot.island.x, bot.y - bot.island.y) < 250; })()'), 'Practice captain did not respawn near its island');
      await evaluate('window.SatsSeasDebug.takeDamage(100)');
      await until('window.SatsSeasDebug.me()?.hp === 0');
      await until('window.SatsSeasDebug.me()?.respawnAt > 0', 5000);
      await until('window.SatsSeasDebug.me()?.hp === 100', 8000);
      assert.ok(await evaluate('(() => { const me = window.SatsSeasDebug.me(); return Math.hypot(me.x - me.island.x, me.y - me.island.y) < 200; })()'), 'Player did not respawn by own island');
      assert.equal(await evaluate('window.SatsSeasDebug.prepareTreasureClaim(0)'), true);
      await evaluate('document.getElementById("sats-dig").click()');
      await until('window.SatsSeasDebug.me()?.heat > .93 && !window.SatsSeasDebug.me()?.waitingForDig', 12000);
      await pause(2200);
      await evaluate('document.getElementById("sats-dig").click()');
      await until('window.SatsSeasDebug.me()?.sats === 5', 12000);
      await call('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: false});
      await call('Emulation.setTouchEmulationEnabled', {enabled: true, maxTouchPoints: 1});
      await pause(500);
      assert.ok(await evaluate('window.innerWidth <= 390'), 'Mobile viewport emulation did not apply');
      assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Mobile game overflows horizontally');
      assert.match(await evaluate('document.getElementById("sats-mobile-hud").textContent'), /HP 100 · Maps/);
      assert.ok(await evaluate('Array.from(document.querySelectorAll(".sats-actions button")).every(button => button.getBoundingClientRect().height >= 44)'), 'Mobile action targets too small');
      assert.ok(await evaluate('(() => { const r = document.querySelector("#sats-canvas canvas").getBoundingClientRect(); return Math.abs(r.width / r.height - 1.6) < .05; })()'), 'Mobile canvas aspect ratio is distorted');
      await evaluate('document.querySelector("#sats-canvas canvas").scrollIntoView({behavior: "instant", block: "center"})');
      await pause(500);
      await evaluate('window.__touchSeen = false; document.querySelector("#sats-canvas canvas").addEventListener("pointerdown", () => { window.__touchSeen = true; }, {once: true})');
      const touch = await evaluate('(() => { const r = document.querySelector("#sats-canvas canvas").getBoundingClientRect(); return {x: r.left + r.width * .8, y: r.top + r.height * .75}; })()');
      assert.ok(await evaluate(`document.elementFromPoint(${touch.x}, ${touch.y})?.tagName === "CANVAS"`), 'Canvas is outside the mobile viewport');
      const beforeTouch = await evaluate('window.SatsSeasDebug.me()');
      await call('Input.dispatchTouchEvent', {type: 'touchStart', touchPoints: [{x: touch.x, y: touch.y, id: 1}]});
      await call('Input.dispatchTouchEvent', {type: 'touchEnd', touchPoints: []});
      await until('window.__touchSeen', 2000);
      assert.ok(await evaluate('window.SatsSeasDebug.me().cannonCd > 0'), 'Touch did not fire the cannon');
      await until(`(() => { const me = window.SatsSeasDebug.me(); return Math.hypot(me.x - ${beforeTouch.x}, me.y - ${beforeTouch.y}) > 5; })()`, 5000);
      assert.ok(await evaluate('demoEvents.some(e => e.t === "peer")'));
      roomsBeforeLeave = await evaluate('fetch("/api/config").then(r => r.json()).then(c => c.privateGameRooms)');
      assert.ok(roomsBeforeLeave >= 1);
      console.log('Practice peer replied over the data channel.');
    }
    await call('Page.captureScreenshot', {format: 'png', captureBeyondViewport: true}).then(result => writeFile('/tmp/nostr4j-' + target + '.png', Buffer.from(result.data, 'base64')));
    await evaluate('document.getElementById("sats-leave").click()');
    if (target === 'private') await until(`fetch("/api/config").then(r => r.json()).then(c => c.privateGameRooms < ${roomsBeforeLeave})`);
  } else if (target === 'layout') {
    assert.equal(await evaluate('document.querySelector(".intro-lede").textContent.trim()'), 'High Performance Nostr Library for the JVM.');
    assert.deepEqual(await evaluate('Array.from(document.querySelectorAll(".platform-logos li span"), item => item.textContent.trim())'), ['Linux', 'Windows', 'Mac', 'Android', 'iOS', 'Browser']);
    assert.ok(await evaluate('Array.from(document.querySelectorAll(".platform-logos img")).every(image => image.complete && image.naturalWidth > 0)'), 'Platform icon failed to load');
    assert.ok(await evaluate('(() => { const code = document.querySelector(".home-demo code").textContent; return code.includes("NGEPlatform.set(new JVMAsyncPlatform())") && code.includes("NostrKeyPair keys = new NostrKeyPair()") && code.includes("NostrPool pool = new NostrPool()") && code.includes("subscription.addEventListener(") && code.includes("pool.publish(note)") && !code.includes("public class") && !code.includes("public static void main") && !code.includes("import "); })()'), 'Displayed example is incomplete or contains class boilerplate');
    assert.equal(await evaluate('document.querySelectorAll(".home-intro > p").length'), 1);
    assert.equal(await evaluate('document.querySelector("#quickstart-run").textContent.trim()'), 'Run Example');
    assert.equal(await evaluate('document.querySelector("#quickstart-run").parentElement.className'), 'home-intro');
    assert.equal(await evaluate('document.querySelector(".capabilities").previousElementSibling.className'), 'home-hero');
    assert.equal(await evaluate('document.querySelector(".home-install").previousElementSibling.className'), 'capabilities');
    await call('Emulation.setDeviceMetricsOverride', {width: 1280, height: 900, deviceScaleFactor: 1, mobile: false});
    await pause(300);
    const codeBeforeLog = await evaluate('document.querySelector(".home-demo .source-panel pre").getBoundingClientRect().height');
    await evaluate('document.getElementById("quickstart-output").hidden = false; document.getElementById("quickstart-output").textContent = "Example log"');
    assert.ok(await evaluate(`document.querySelector(".home-demo .source-panel pre").getBoundingClientRect().height > ${codeBeforeLog}`), 'Code did not stretch when log appeared');
    for (const width of [1280, 390]) {
      await call('Emulation.setDeviceMetricsOverride', {width, height: 900, deviceScaleFactor: 1, mobile: false});
      await pause(500);
      assert.ok(await evaluate(`window.innerWidth === ${width}`), 'Viewport emulation failed at ' + width);
      assert.ok(await evaluate('(() => { const button = document.getElementById("quickstart-run").getBoundingClientRect(); const log = document.getElementById("quickstart-output").getBoundingClientRect(); return log.top >= button.bottom && Math.abs(log.width - button.width) < 1; })()'), 'Example log placement failed at ' + width);
      for (const tab of ['desktop', 'android', 'ios', 'browser']) {
        await evaluate(`document.getElementById("tab-${tab}").click()`);
        assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Horizontal overflow at ' + width + ' on ' + tab);
      }
      await evaluate('document.getElementById("tab-desktop").click()');
      assert.ok(await evaluate(`(() => { const tabs = document.querySelector(".platform-picker .tab-list").getBoundingClientRect(); const panel = document.getElementById("platform-desktop").getBoundingClientRect(); return ${width} > 800 ? panel.left > tabs.right : panel.top >= tabs.bottom; })()`), 'Install layout is misplaced at ' + width);
      await call('Page.captureScreenshot', {format: 'png', captureBeyondViewport: true}).then(result => writeFile('/tmp/nostr4j-home-' + width + '.png', Buffer.from(result.data, 'base64')));
    }
    await evaluate('document.getElementById("tab-ios").click()');
    assert.equal(await evaluate('document.getElementById("platform-ios").hidden'), false);
    await evaluate('document.getElementById("tab-browser").click()');
    assert.equal(await evaluate('document.getElementById("platform-ios").hidden'), true);
  } else if (target === 'relay') {
    await until('Boolean(window.RelayDemo)');
    await evaluate('document.getElementById("demo-tab-relay").click(); document.getElementById("relay-fetch").click()');
    await until('document.querySelectorAll("#relay-notes li").length > 0', 30000);
    console.log('Public notes:', await evaluate('document.querySelectorAll("#relay-notes li").length'));
  } else if (target === 'wallet') {
    await until('Boolean(window.NWCDemo)');
    await evaluate('document.getElementById("demo-tab-wallet").click(); document.getElementById("nwc-uri").value = "not-a-wallet-uri"; document.getElementById("nwc-connect").click()');
    await until('document.getElementById("nwc-status").textContent.includes("failed")');
    assert.equal(await evaluate('document.getElementById("nwc-uri").value'), '');
    assert.equal(await evaluate('document.getElementById("nwc-txs").disabled'), true);
    console.log('Wallet rejects invalid input and clears it; no wallet credentials used.');
  }
  assert.equal(errors.length, 0, errors.join('\n'));
  console.log('PASS', target);
} finally {
  console.log('Diagnostics:', diagnostics.join('\n'));
  if (errors.length) console.error('Browser exceptions:', errors.join('\n'));
  socket?.close();
  chrome.kill('SIGTERM');
  await new Promise(resolve => { if (chrome.exitCode !== null) resolve(); else chrome.once('exit', resolve); });
  await rm(profile, {recursive: true, force: true, maxRetries: 5, retryDelay: 300});
}
