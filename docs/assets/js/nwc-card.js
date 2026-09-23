/* NWC wallet demo card. Drives window.NWCDemo (Java/TeaVM bundle).
 * The NWC secret is handled as a password field, never logged, never stored;
 * it lives only inside the demo bundle's memory until Disconnect. */
(function () {
  'use strict';

  function el(id) { return document.getElementById(id); }
  function api() { return window.NWCDemo; }
  function setStatus(msg) { el('nwc-status').textContent = msg; }

  function setConnected(connected, alias) {
    el('nwc-connect').disabled = connected;
    el('nwc-disconnect').disabled = !connected;
    el('nwc-txs').disabled = !connected;
    if (!connected) {
      el('nwc-uri').value = ''; // drop the secret from the page immediately
      el('nwc-info').replaceChildren();
      el('nwc-txlist').replaceChildren();
    }
  }

  function connect() {
    if (!api()) { setStatus('Demo bundle not loaded yet.'); return; }
    var uri = el('nwc-uri').value.trim();
    if (!uri) { setStatus('Paste an NWC connection string first.'); return; }
    el('nwc-connect').disabled = true;
    setStatus('Connecting …');
    api().connect(uri,
      function (json) {
        var st = {};
        try { st = JSON.parse(json); } catch (e) {}
        if (st.stage === 'connecting') {
          setStatus('Connecting' + (st.relay ? ' via ' + st.relay : '') + ' …');
        } else if (st.stage === 'ready') {
          setStatus('Connected.');
        }
      },
      function (json) {
        var info = {};
        try { info = JSON.parse(json); } catch (e) {}
        var box = el('nwc-info');
        box.replaceChildren();
        var bal = document.createElement('div');
        bal.className = 'nwc-balance';
        bal.textContent = Number(info.balanceSats || 0).toLocaleString() + ' sats';
        box.appendChild(bal);
        var meta = document.createElement('div');
        meta.className = 'nwc-meta';
        meta.textContent = (info.alias ? info.alias + ' · ' : '') +
          (info.supportsPay ? 'can pay invoices' : 'read-only (no pay_invoice)');
        box.appendChild(meta);
        setConnected(true);
        setStatus('Connected.');
      },
      function (msg) {
        el('nwc-connect').disabled = false;
        setStatus('Connection failed: ' + msg);
      }
    );
    el('nwc-uri').value = ''; // hand the secret to the bundle, drop it from the page now
  }

  function disconnect() {
    if (api()) { try { api().disconnect(); } catch (e) {} }
    setConnected(false);
    setStatus('Disconnected.');
  }

  function transactions() {
    if (!api()) return;
    el('nwc-txs').disabled = true;
    setStatus('Loading transactions …');
    api().transactions(
      function (json) {
        el('nwc-txs').disabled = false;
        setStatus('');
        var txs = [];
        try { txs = JSON.parse(json); } catch (e) {}
        var list = el('nwc-txlist');
        list.replaceChildren();
        if (!txs.length) {
          setStatus('No recent transactions.');
          return;
        }
        txs.forEach(function (t) {
          var li = document.createElement('li');
          var kind = document.createElement('span');
          kind.textContent = t.type === 'incoming' ? '↓ received' : '↑ sent';
          li.appendChild(kind);
          var mid = document.createElement('span');
          var d = document.createElement('div');
          d.textContent = new Date(t.createdAt * 1000).toLocaleString();
          mid.appendChild(d);
          if (t.description) {
            var desc = document.createElement('div');
            desc.className = 'desc';
            desc.textContent = t.description;
            mid.appendChild(desc);
          }
          li.appendChild(mid);
          var amt = document.createElement('span');
          amt.className = 'amt ' + (t.type === 'incoming' ? 'in' : 'out');
          var sats = Math.round((t.amountMsats || 0) / 1000);
          amt.textContent = (t.type === 'incoming' ? '+' : '−') + sats.toLocaleString() + ' sats';
          li.appendChild(amt);
          list.appendChild(li);
        });
      },
      function (msg) {
        el('nwc-txs').disabled = false;
        setStatus('Error: ' + msg);
      }
    );
  }

  function init() {
    if (!api()) { setTimeout(init, 200); return; } // bundle still loading
    el('nwc-connect').addEventListener('click', connect);
    el('nwc-disconnect').addEventListener('click', disconnect);
    el('nwc-txs').addEventListener('click', transactions);
    el('nwc-uri').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') connect();
    });
    window.addEventListener('pagehide', function () {
      if (api()) { try { api().disconnect(); } catch (e) {} }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
