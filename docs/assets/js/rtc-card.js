// UI and allocation only; signaling, signing and RTC run in compiled Java.
const root = document.getElementById('rtc-demo');
const button = document.getElementById('rtc-ping');
const output = document.getElementById('rtc-output');
const diagnostics = document.getElementById('rtc-diagnostics');
const diagnosticsOutput = document.getElementById('rtc-diagnostics-output');
let session;
let timer;
let running = false;
let sent = false;
let discovered = false;
let loaded = false;
function diagnostic(message) {
  const lines = diagnosticsOutput.textContent ? diagnosticsOutput.textContent.split('\n') : [];
  lines.push(message);
  diagnosticsOutput.textContent = lines.slice(-40).join('\n');
}
function fail(message, result) {
  if (!running) return;
  output.textContent = message;
  root.dataset.result = result;
  diagnostics.hidden = false;
  diagnostics.open = true;
  void stop();
}
async function stop() {
  running = false;
  clearTimeout(timer);
  if (window.RTCDemo) window.RTCDemo.stop();
  const old = session;
  session = undefined;
  if (old) {
    try { await fetch('/api/ping/' + old.token, {method: 'DELETE', keepalive: true}); }
    catch (_) { /* Backend expires abandoned sessions after 90 seconds. */ }
  }
  button.disabled = false;
}
function onEvent(json) {
  if (!running) return;
  const event = JSON.parse(json);
  if (event.t === 'discovered' && !sent) {
    discovered = true;
    output.textContent = 'Peer found. Establishing the RTC connection…';
  } else if (event.t === 'peer' && event.state === 'up' && !sent) {
    discovered = true;
    output.textContent = 'RTC socket opened. Waiting for the data channel…';
  } else if (event.t === 'receiver' && !sent) {
    sent = true;
    output.textContent = 'Data channel ready. Sending ping…';
    window.RTCDemo.ping();
  } else if (event.t === 'pong') {
    root.dataset.result = 'pong';
    output.textContent = 'PONG · ' + event.rttMs + ' ms';
    void stop();
  }
}
button.addEventListener('click', async () => {
  if (running) return;
  running = true;
  sent = false;
  discovered = false;
  button.disabled = true;
  root.dataset.result = 'pending';
  diagnostics.hidden = true;
  diagnostics.open = false;
  diagnosticsOutput.textContent = '';
  output.textContent = 'Pinging…\n';
  try {
    if (!loaded) {
      const module = await import('../demos/rtc/demo.js');
      module.main([]);
      loaded = true;
    }
    const response = await fetch('/api/ping', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({mode: 'auto'}), signal: AbortSignal.timeout(15000)});
    const config = await response.json();
    if (!response.ok) throw new Error(config.error || 'Backend unavailable');
    session = config;
    config.mode = 'auto';
    timer = setTimeout(() => {
      const message = !discovered ? 'No peer found.' : !sent ? 'Peer found, but the RTC data channel did not connect.' : 'Ping sent, but no PONG arrived.';
      fail(message, 'timeout');
    }, 65000);
    window.RTCDemo.start(JSON.stringify(config), diagnostic, onEvent, error => fail('Ping failed: ' + error, 'error'));
  } catch (error) {
    fail('Ping failed: ' + error.message, 'error');
  }
});
window.addEventListener('pagehide', () => { void stop(); });
