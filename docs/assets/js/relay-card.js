/* Relay-fetch demo card. Drives window.RelayDemo (Java/TeaVM bundle).
 * All relay content is HTML-escaped before insertion. */
(function () {
  'use strict';

  function el(id) { return document.getElementById(id); }

  var profiles = {};   // hex -> {name, picture, about, lud16}
  var authors = [];    // [{name, hex}]

  function api() { return window.RelayDemo; }

  function setStatus(msg) { el('relay-status').textContent = msg; }

  function selectedHexes() {
    return authors
      .filter(function (a, i) { return el('relay-author-' + i).checked; })
      .map(function (a) { return a.hex; });
  }

  function renderChips() {
    var row = el('relay-authors');
    row.replaceChildren();
    authors.forEach(function (a, i) {
      var label = document.createElement('label');
      label.className = 'chip';
      var cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.id = 'relay-author-' + i;
      cb.checked = true;
      cb.addEventListener('change', function () {
        label.classList.toggle('off', !cb.checked);
      });
      label.appendChild(cb);
      label.appendChild(document.createTextNode(a.name));
      row.appendChild(label);
    });
  }

  function profileFor(hex, done) {
    if (profiles[hex]) { done(profiles[hex]); return; }
    api().fetchProfile(hex,
      function (json) {
        try { profiles[hex] = JSON.parse(json); } catch (e) { profiles[hex] = {}; }
        done(profiles[hex]);
      },
      function (msg) { profiles[hex] = {}; done(profiles[hex]); }
    );
  }

  function renderNote(note) {
    var p = profiles[note.pubkey] || {};
    var name = p.name || (authors.find(function (a) { return a.hex === note.pubkey; }) || {}).name || note.pubkey.slice(0, 12);
    var li = document.createElement('li');

    var head = document.createElement('div');
    head.className = 'note-head';
    if (p.picture && /^https:\/\//i.test(p.picture)) {
      var img = document.createElement('img');
      img.src = p.picture;
      img.alt = '';
      img.loading = 'lazy';
      img.decoding = 'async';
      img.referrerPolicy = 'no-referrer';
      head.appendChild(img);
    }
    var who = document.createElement('span');
    who.className = 'who';
    who.textContent = name;
    head.appendChild(who);
    var when = document.createElement('span');
    when.className = 'when';
    when.textContent = new Date(note.createdAt * 1000).toLocaleString();
    head.appendChild(when);
    li.appendChild(head);

    var text = document.createElement('p');
    text.className = 'note-text';
    text.textContent = note.content; // textContent = auto-escaped
    li.appendChild(text);

    if (p.lud16) {
      var zapRow = document.createElement('div');
      zapRow.className = 'zap-row';
      var amt = document.createElement('input');
      amt.type = 'number';
      amt.min = '1';
      amt.value = '21';
      amt.title = 'Amount in sats';
      var zapBtn = document.createElement('button');
      zapBtn.type = 'button';
      zapBtn.textContent = 'Zap ' + name;
      zapBtn.addEventListener('click', function () {
        zapBtn.disabled = true;
        var sats = parseInt(amt.value, 10) || 21;
        api().zap(p.lud16, sats,
          function (json) {
            zapBtn.disabled = false;
            var inv;
            try { inv = JSON.parse(json); } catch (e) { setStatus('Bad invoice response'); return; }
            showInvoice(li, inv.bolt11, inv.amountSats);
          },
          function (msg) { zapBtn.disabled = false; setStatus('Zap failed: ' + msg); }
        );
      });
      zapRow.appendChild(amt);
      var satsLbl = document.createElement('span');
      satsLbl.textContent = 'sats';
      satsLbl.style.fontSize = '0.85rem';
      satsLbl.style.color = 'var(--muted)';
      zapRow.appendChild(satsLbl);
      zapRow.appendChild(zapBtn);
      li.appendChild(zapRow);
    }

    el('relay-notes').appendChild(li);
  }

  function showInvoice(li, bolt11, amountSats) {
    var old = li.querySelector('.invoice-box');
    if (old) old.remove();
    var box = document.createElement('div');
    box.className = 'invoice-box';
    var title = document.createElement('div');
    title.textContent = 'Pay ' + amountSats + ' sats with any Lightning wallet:';
    box.appendChild(title);
    var code = document.createElement('div');
    code.textContent = bolt11;
    box.appendChild(code);
    var copy = document.createElement('button');
    copy.type = 'button';
    copy.textContent = 'Copy invoice';
    copy.addEventListener('click', function () {
      navigator.clipboard.writeText(bolt11).then(
        function () { copy.textContent = 'Copied!'; },
        function () { copy.textContent = 'Copy failed'; }
      );
    });
    box.appendChild(copy);
    li.appendChild(box);
  }

  function fetch() {
    if (!api()) { setStatus('Demo bundle not loaded yet.'); return; }
    var hexes = selectedHexes();
    if (!hexes.length) { setStatus('Select at least one author.'); return; }
    var relayUrl = el('relay-url').value.trim() || 'wss://relay.damus.io';
    el('relay-notes').replaceChildren();
    el('relay-fetch').disabled = true;
    setStatus('Loading author profiles from ' + relayUrl + ' …');

    function fetchNotes() {
      setStatus('Fetching notes …');
      api().fetchNotes(relayUrl, JSON.stringify(hexes), 5,
        function (json) {
          try { renderNote(JSON.parse(json)); } catch (e) { /* skip bad note */ }
        },
        function (json) {
          el('relay-fetch').disabled = false;
          var n = 0;
          try { n = JSON.parse(json).count; } catch (e) {}
          setStatus(n ? ('Got ' + n + ' note' + (n === 1 ? '' : 's') + '.') : 'No notes found for these authors in the last 7 days.');
        },
        function (msg) {
          el('relay-fetch').disabled = false;
          setStatus('Error: ' + msg);
        }
      );
    }

    // Warm the profile cache so notes can include avatars and zap details,
    // but never make the main read wait indefinitely on profile metadata.
    var pending = hexes.length;
    var started = false;
    var fallback = setTimeout(startNotes, 5000);
    function startNotes() {
      if (started) return;
      started = true;
      clearTimeout(fallback);
      fetchNotes();
    }
    hexes.forEach(function (h) {
      profileFor(h, function () {
        if (--pending === 0) startNotes();
      });
    });
  }

  function init() {
    if (!api()) { setTimeout(init, 200); return; } // bundle still loading
    api().listAuthors(function (json) {
      try { authors = JSON.parse(json); } catch (e) { authors = []; }
      renderChips();
    });
    el('relay-fetch').addEventListener('click', fetch);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
