/* Sats Seas — p5.js client for the Nostr4J dc4 multiplayer pirate demo.
 *
 * Plain JS, no build step. Rendering: p5.js 1.9.4 (CDN).
 * Networking: window.SatsSeasNet (TeaVM bundle `demo.js`, Java net layer);
 *   falls back to offline "harbor mode" when the bundle is absent.
 *
 * Deterministic mirrors (keep in sync with site-demos Java):
 *   mulberry32  <-> Mulberry32.java
 *   npcSim      <-> NpcSim.java
 *   mapTrail    <-> MapTrail.java
 *   islandPlacer<-> IslandPlacer.java
 * Wire protocol: site-demos/.../game/PROTOCOL.md  ("ss1").
 *
 * Art direction: chunky 8-bit pixel art. All sprites are string pixel-maps
 * (see PAL), drawn once to offscreen graphics and blitted at 2x with
 * noSmooth. The HUD lives inside the canvas; the DOM stays almost empty.
 */
(function () {
  'use strict';

  /* ============================== utils ============================== */

  function mulberry32(seed) {
    var s = (seed ^ 0x9e3779b9) | 0;
    return function () {
      s = (s + 0x6d2b79f5) | 0;
      var z = s;
      z = Math.imul(z ^ (z >>> 15), z | 1);
      z ^= z + Math.imul(z ^ (z >>> 7), z | 61);
      return ((z ^ (z >>> 14)) >>> 0) / 4294967296;
    };
  }

  function strSeed(str) {
    var h = 0x811c9dc5;
    for (var i = 0; i < str.length; i++) {
      h ^= str.charCodeAt(i);
      h = Math.imul(h, 0x01000193);
    }
    return h >>> 0;
  }

  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function dist(ax, ay, bx, by) {
    var dx = ax - bx, dy = ay - by;
    return Math.sqrt(dx * dx + dy * dy);
  }
  function $(id) { return document.getElementById(id); }
  function el(id) { var e = $(id); return e || null; }
  function shortId(hex) { return hex ? hex.slice(0, 8) : '?'; }
  function nowMs() { return Date.now(); }

  /* ============================ constants ============================ */
  var WORLD = 4096;
  var DEEP_Y0 = 1800, DEEP_Y1 = 2300;
  var CURSED = [
    { x: 950, y: 950, r: 420 },
    { x: 3150, y: 3150, r: 420 },
  ];
  var SHARK_COUNT = 6, GHOST_COUNT = 3, MEGA_COUNT = 2;
  var SHARK_HP = 30, GHOST_HP = 120, MEGA_HP = 150;
  var ISLAND_R = 120, ISLAND_MARGIN = 260;
  var SHIP_R = 14;
  var CANNON_CD = 1.1, BALL_SPEED = 430, BALL_TTL = 1.35;
  var DIG_RANGE = 600, CLAIM_R = 30;
  var MAX_SPEED = 150, MAX_SPEED_LADEN = 118;

  /* ================== deterministic mirrors (Java) =================== */

  function npcSim(worldSeed, timeMs) {
    var t = timeMs, out = [];
    function rngFor(idx) {
      var r = mulberry32((worldSeed ^ (idx * 0x9e3779b9 + 0x85ebca6b)) >>> 0);
      return r;
    }
    var i, rnd, hx, hy, r, dir, w, p, wt, x, y, dx, dy;
    for (i = 0; i < SHARK_COUNT; i++) {
      rnd = rngFor(i);
      hx = 240 + rnd() * (WORLD - 480);
      hy = DEEP_Y0 + 40 + rnd() * (DEEP_Y1 - DEEP_Y0 - 80);
      r = 130 + rnd() * 170;
      dir = rnd() < 0.5 ? -1 : 1;
      w = dir * (0.00013 + rnd() * 0.00015);
      p = rnd() * Math.PI * 2;
      wt = w * t + p;
      x = hx + Math.cos(wt) * r;
      y = hy + Math.sin(w * 0.83 * t + p * 1.7) * r * 0.55;
      dx = -Math.sin(wt) * r * w;
      dy = Math.cos(w * 0.83 * t + p * 1.7) * r * 0.55 * w * 0.83;
      out.push({ id: i, kind: 'shark', x: x, y: y, a: Math.atan2(dy, dx), hp: SHARK_HP });
    }
    for (i = 0; i < GHOST_COUNT; i++) {
      rnd = rngFor(SHARK_COUNT + i);
      var c = CURSED[i % 2];
      hx = c.x + (rnd() - 0.5) * 560;
      hy = c.y + (rnd() - 0.5) * 560;
      r = 190 + rnd() * 150;
      dir = rnd() < 0.5 ? -1 : 1;
      w = dir * (0.00005 + rnd() * 0.00006);
      p = rnd() * Math.PI * 2;
      wt = w * t + p;
      x = hx + Math.cos(wt) * r;
      y = hy + Math.sin(w * 0.9 * t + p) * r * 0.7;
      dx = -Math.sin(wt) * r * w;
      dy = Math.cos(w * 0.9 * t + p) * r * 0.7 * w * 0.9;
      out.push({ id: SHARK_COUNT + i, kind: 'ghost', x: x, y: y, a: Math.atan2(dy, dx), hp: GHOST_HP });
    }
    for (i = 0; i < MEGA_COUNT; i++) {
      rnd = rngFor(SHARK_COUNT + GHOST_COUNT + i);
      hx = 240 + rnd() * (WORLD - 480);
      hy = DEEP_Y0 + 60 + rnd() * (DEEP_Y1 - DEEP_Y0 - 120);
      r = 170 + rnd() * 200;
      dir = rnd() < 0.5 ? -1 : 1;
      w = dir * (0.00020 + rnd() * 0.00020);
      p = rnd() * Math.PI * 2;
      wt = w * t + p;
      x = hx + Math.cos(wt) * r;
      y = hy + Math.sin(w * 0.8 * t + p * 1.3) * r * 0.5;
      dx = -Math.sin(wt) * r * w;
      dy = Math.cos(w * 0.8 * t + p * 1.3) * r * 0.5 * w * 0.8;
      out.push({ id: SHARK_COUNT + GHOST_COUNT + i, kind: 'mega', x: x, y: y, a: Math.atan2(dy, dx), hp: MEGA_HP });
    }
    return out;
  }

  function npcStats(kind) {
    if (kind === 'mega') return { r: 26, aggro: 400, dps: 28, name: 'megalodon' };
    if (kind === 'ghost') return { r: 20, aggro: 280, dps: 18, name: 'ghost ship' };
    return { r: 12, aggro: 170, dps: 10, name: 'shark' };
  }

  function mapTrail(mapSeed, ax, ay, bx, by, count) {
    var rnd = mulberry32((mapSeed ^ 0x5eed) >>> 0);
    var ph1 = rnd() * Math.PI * 2, ph2 = rnd() * Math.PI * 2;
    var dx = bx - ax, dy = by - ay;
    var d = Math.sqrt(dx * dx + dy * dy);
    var px = d > 0 ? -dy / d : 1, py = d > 0 ? dx / d : 0;
    var amp = d * 0.18, out = [];
    for (var k = 0; k < count; k++) {
      var t = k / (count - 1);
      var env = Math.sin(Math.PI * t);
      var off = (Math.sin(t * Math.PI * 3 + ph1) * 0.6 +
                 Math.sin(t * Math.PI * 7 + ph2) * 0.25) * env * amp;
      out.push([ax + dx * t + px * off, ay + dy * t + py * off]);
    }
    return out;
  }

  function placeIsland(seed, xs, ys, world, radius, margin) {
    var rnd = mulberry32((seed ^ 0x1234abcd) >>> 0);
    var minDist = radius * 2 + margin;
    var lo = radius + margin, hi = world - radius - margin;
    function free(x, y) {
      for (var i = 0; i < xs.length; i++) {
        var dx = x - xs[i], dy = y - ys[i];
        if (dx * dx + dy * dy < minDist * minDist) return false;
      }
      return true;
    }
    var bx = lo, by = lo;
    for (var a = 0; a < 256; a++) {
      var x = lo + rnd() * (hi - lo), y = lo + rnd() * (hi - lo);
      if (free(x, y)) return [x, y];
      bx = x; by = y;
    }
    return [bx, by];
  }

  /* ============================ net layer ============================ */

  var Net = {
    real: false, joined: false,
    api: null,
    onEvent: null,
    join: function (cfg, onEvent) {
      this.onEvent = onEvent;
      if (window.SatsSeasNet) {
        this.real = true; this.api = window.SatsSeasNet;
        try {
          var id = this.api.join(JSON.stringify(cfg), function (json) {
            Net.handleEvent(json);
          });
          return id || '';
        } catch (e) { throw e; }
      }
      throw new Error('Java network bundle is not ready. Try again in a moment.');
    },
    handleEvent: function (json) {
      var ev;
      try { ev = JSON.parse(json); } catch (e) { return; }
      if (this.onEvent) this.onEvent(ev);
    },
    newIdentity: function () {
      if (!window.SatsSeasNet) throw new Error('Java networking is not ready. Reload the page and try again.');
      return window.SatsSeasNet.newIdentity();
    },
    myId: function () {
      if (this.real) { try { return this.api.myId(); } catch (e) {} }
      return 'offline';
    },
    broadcastPos: function (json) {
      if (this.real) { try { this.api.broadcastPos(json); } catch (e) {} }
    },
    broadcastGame: function (json) {
      if (this.real && this.api.broadcastGame) { try { this.api.broadcastGame(json); return; } catch (e) {} }
      this.broadcastPos(json);
    },
    sendTo: function (peerId, channel, json) {
      if (this.real) { try { this.api.sendTo(peerId, channel, json); } catch (e) {} }
    },
    getTopology: function () {
      if (this.real) { try { this.api.getTopology(); } catch (e) {} }
    },
    leave: function () {
      if (this.real) { try { this.api.leave(); } catch (e) {} }
      this.joined = false;
    },
  };

  /* ========================= persistent state ======================== */

  var LS_KEY = 'sats-seas-v2';
  var SESSION_ID_KEY = 'sats-seas-demo-identity-v1';
  var S = null;
  var backendGame = null;
  var gameKeepalive = null;

  async function requestGame(mode) {
    var response = await fetch('/api/game', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode: mode }), signal: AbortSignal.timeout(20000),
    });
    var result = await response.json();
    if (!response.ok) throw new Error(result.error || 'The game service is unavailable (' + response.status + ').');
    result.mode = mode;
    return result;
  }

  function closeBackendGame() {
    if (gameKeepalive) { clearInterval(gameKeepalive); gameKeepalive = null; }
    var old = backendGame;
    backendGame = null;
    if (old && old.token) {
      fetch('/api/game/' + old.token, { method: 'DELETE', keepalive: true }).catch(function () {});
    }
  }

  async function keepBackendGame() {
    if (!backendGame) return;
    var url = backendGame.token ? '/api/game/' + backendGame.token : '/api/game';
    var body = backendGame.token ? '{}' : '{"mode":"shared"}';
    var response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body });
    if (!response.ok && S) el('sats-status').textContent = 'The simulated captains are no longer available (' + response.status + '). Leave and rejoin to retry.';
  }

  function randomProbe() {
    var bytes = new Uint8Array(8);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, function (value) { return value.toString(16).padStart(2, '0'); }).join('');
  }

  function loadSessionIdentity() {
    try {
      var value = sessionStorage.getItem(SESSION_ID_KEY) || '';
      return /^nsec1/.test(value) ? value : '';
    } catch (e) { return ''; }
  }

  function saveSessionIdentity(nsec) {
    try { sessionStorage.setItem(SESSION_ID_KEY, nsec); } catch (e) {}
  }

  function loadPersisted() {
    try {
      var raw = localStorage.getItem(LS_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }
  function persist() {
    if (!S) return;
    try {
      localStorage.setItem(LS_KEY, JSON.stringify({
        roomSeed: S.roomSeed,
        name: S.name, island: S.island, treasure: S.treasure,
        maps: S.maps, discovered: S.discovered, sats: S.sats,
      }));
    } catch (e) {}
  }

  function newSession(name, roomSeed, relays, shared, expectedBots) {
    var saved = loadPersisted();
    var nsec = loadSessionIdentity() || Net.newIdentity();
    saveSessionIdentity(nsec);
    var s = {
      name: name, roomSeed: roomSeed, relays: relays, nsec: nsec,
      shared: shared, expectedBots: expectedBots || [], botProbe: randomProbe(), botReplies: {}, lastBotProbe: 0,
      pub: '',
      island: null, treasure: { amount: 5, buried: false, bx: 0, by: 0 },
      maps: [], discovered: [], sats: 0,
      x: 0, y: 0, a: 0, vx: 0, vy: 0, hp: 100,
      peers: {}, pickups: [], balls: [], trails: {},
      npcHp: {}, npcDeadUntil: {}, npcFireCd: {},
      cannonCd: 0, sinking: 0, respawnAt: 0, dead: false,
      steerX: null, steerY: null, aimX: null, aimY: null,
      lastPos: 0, lastHello: 0, lastTopo: 0,
      lastDig: 0, digCooldowns: {}, ping: null, pendingClaim: null,
      offer: null, pendingOffer: null, chatCd: 0, topo: null, hlRoutes: false,
      toast: null, bubbles: {}, joinedAt: 0, lastPersist: 0, lastMobileHud: 0,
    };
    if (saved && saved.roomSeed === roomSeed) {
      s.island = saved.island || null;
      if (saved.treasure) s.treasure = saved.treasure;
      s.maps = saved.maps || [];
      s.discovered = saved.discovered || [];
      s.sats = saved.sats || 0;
    }
    return s;
  }

  // Single toast line. Replaces the old stacking queue.
  function toast(msg, ms) {
    if (!S) return;
    S.toast = { msg: String(msg).slice(0, 90), until: nowMs() + (ms || 2600) };
  }

  /* ======================= protocol (outgoing) ======================= */

  function msg(type, extra) {
    var o = { v: 'ss1', type: type };
    for (var k in extra) o[k] = extra[k];
    return JSON.stringify(o);
  }
  function bPos(o) { Net.broadcastPos(msg('pos', o)); }
  function broadcastGame(json) { Net.broadcastGame(json); }

  function sendHello(peerId) {
    var payload = msg('hello', {
      name: S.name, pub: S.pub,
      island: { x: S.island.x, y: S.island.y },
      hp: Math.round(S.hp),
    });
    if (peerId) Net.sendTo(peerId, 'sats-game', payload);
    else broadcastGame(payload);
  }

  function sendPos() {
    bPos({
      pub: S.pub, x: Math.round(S.x), y: Math.round(S.y), a: +S.a.toFixed(2),
      hp: Math.round(S.hp),
    });
  }

  /* ======================= protocol (incoming) ======================= */

  function onNetEvent(ev) {
    if (!S) return;
    switch (ev.t) {
      case 'ready':
        el('sats-status').textContent = S.shared
          ? 'Joined shared Harbor. Discovering peers; maximum two direct neighbors.'
          : 'Joined your random practice room. Waiting for the two simulated captains…';
        S.pub = ev.id; Net.joined = true;
        onReady(); break;
      case 'peer': onPeer(ev); break;
      case 'msg': onMsg(ev); break;
      case 'topo': S.topo = ev; drawTopo(); el('sats-status').dataset.topology = JSON.stringify(ev); break;
      case 'ban': onBanEvent(ev); break;
      case 'log':
        if (/fail|error/i.test(ev.message || ev.msg || '') && !/No connected mutually attested graph for broadcast/.test(ev.message || ev.msg || ''))
          el('sats-status').textContent = ev.message || ev.msg;
        break;
    }
  }

  function onReady() {
    if (!S.island) {
      var xs = S.discovered.map(function (d) { return d.ix; });
      var ys = S.discovered.map(function (d) { return d.iy; });
      if (S.shared) {
        for (var bot = 0; bot < 3; bot++) {
          var reserved = placeIsland(strSeed('bot-island:' + S.roomSeed + ':' + bot), xs, ys, WORLD, ISLAND_R, 760);
          xs.push(reserved[0]); ys.push(reserved[1]);
        }
      }
      var p = placeIsland(strSeed(S.roomSeed + ':' + (S.pub || S.nsec)), xs, ys, WORLD, ISLAND_R, 760);
      S.island = { x: p[0], y: p[1] };
    }
    S.x = S.island.x; S.y = S.island.y + ISLAND_R + 60; S.a = -Math.PI / 2;
    S.joinedAt = nowMs();
    if (!S.treasure.buried) placeTreasure();
    discoverIsland(S.pub, S.name, S.island.x, S.island.y);
    sendHello();
    persist();
    toast('Welcome aboard, captain.');
  }

  function onPeer(ev) {
    var id = ev.id;
    if (id === S.pub) return;
    if (ev.state === 'online') {
      if (!S.peers[id]) {
        S.peers[id] = {
          pub: id, name: 'sailor-' + shortId(id), x: -9999, y: -9999,
          rx: -9999, ry: -9999, a: 0, ra: 0, hp: 100,
          island: null, lastPosT: 0, lastSeen: nowMs(),
        };
      }
      S.peers[id].lastSeen = nowMs();
      if (S.expectedBots.indexOf(id) >= 0 && !S.botReplies[id]) {
        if (S.island) sendHello(id);
      }
    } else {
      delete S.peers[id];
      delete S.bubbles[id];
    }
  }

  function onBanEvent(ev) {
    delete S.peers[ev.peer];
    toast('A peer left the room.');
  }

  function onMsg(ev) {
    var body;
    try { body = JSON.parse(ev.body); } catch (e) { return; }
    if (!body || body.v !== 'ss1') return;
    var from = ev.from;
    if (from === S.pub) return;
    if (S.expectedBots.indexOf(from) >= 0 && body.type === 'probe_ack' && body.probe === S.botProbe) {
      S.botReplies[from] = true;
      if (S.expectedBots.every(function (id) { return S.botReplies[id]; })) el('sats-status').dataset.botReply = 'true';
      el('sats-status').textContent = 'Ready to sail.';
      return;
    }
    var P = S.peers[from];
    switch (body.type) {
      case 'hello': {
        if (!P) {
          P = S.peers[from] = {
            pub: from, name: 'sailor-' + shortId(from), x: -9999, y: -9999,
            rx: -9999, ry: -9999, a: 0, ra: 0, hp: 100,
            island: null, lastPosT: 0, lastSeen: nowMs(),
          };
        }
        P.name = String(body.name || P.name).slice(0, 16);
        if (typeof body.hp === 'number') P.hp = body.hp;
        if (body.island) {
          P.island = { x: body.island.x, y: body.island.y };
          discoverIsland(from, P.name, body.island.x, body.island.y);
        }
        P.lastSeen = nowMs();
        break;
      }
      case 'despawn':
        delete S.peers[from];
        delete S.bubbles[from];
        break;
      case 'pos': {
        if (!P) break;
        var t = nowMs();
        if (!Number.isFinite(body.x) || !Number.isFinite(body.y) || body.x < 0 || body.x > WORLD || body.y < 0 || body.y > WORLD) break;
        P.rx = body.x; P.ry = body.y; P.ra = Number.isFinite(body.a) ? body.a : 0;
        if (typeof body.hp === 'number') P.hp = body.hp;
        P.lastPosT = t; P.lastSeen = t;
        if (P.x < -9000) { P.x = P.rx; P.y = P.ry; P.a = P.ra; }
        break;
      }
      case 'hit':
        if (body.target === S.pub && S.sinking <= 0 && !S.respawnAt) {
          damage(body.dmg || 10, 'cannon fire');
        }
        break;
      case 'sink':
        if (P) { P.hp = 0; P.sunkUntil = nowMs() + 8000; }
        toast(peerName(from) + ' was sunk!');
        break;
      case 'scroll':
        if (body.victim === S.pub) break;
        if (S.pickups.some(function (item) { return item.id === body.id; })) break;
        S.pickups.push({
          id: body.id, x: body.x, y: body.y,
          victim: body.victim, victimName: body.victimName,
          ix: body.ix, iy: body.iy, bob: Math.random() * 6,
        });
        toast('A treasure map floats nearby!');
        break;
      case 'shot':
        if (body.from === S.pub) break;
        S.balls.push({
          x: body.x, y: body.y,
          vx: Math.cos(body.a) * BALL_SPEED, vy: Math.sin(body.a) * BALL_SPEED,
          ttl: 1.6, from: body.from, remote: true,
        });
        AU.play('cannon', 0.22, 0.85);
        break;
      case 'taken':
        S.pickups = S.pickups.filter(function (pk) { return pk.id !== body.id; });
        break;
      case 'dig':
        onDigRequest(from, body); break;
      case 'ping':
        if (S.pendingClaim && body.nonce === S.pendingClaim.nonce) {
          S.ping = { heat: body.heat, t: nowMs() };
          S.pendingClaim = null;
          if (body.heat > 0.93) toast('HOT! Dig again to uncover the treasure.');
          else toast('No treasure here yet. Follow the heat toward the island.');
        }
        break;
      case 'claim':
        onClaimRequest(from, body); break;
      case 'digResult':
        onDigResult(body); break;
      case 'claimed':
        onClaimed(body); break;
      case 'mapOffer':
        onMapOffer(from, body); break;
      case 'mapAccept':
        onMapAccept(from, body); break;
      case 'chat':
        chatBubble(from, String(body.text || '').slice(0, 60));
        break;
      case 'hit_ack':
        if (P && typeof body.hp === 'number') {
          P.hp = clamp(body.hp, 0, 100);
          if (P.hp === 0) P.sunkUntil = nowMs() + 8000;
        }
        break;
    }
  }

  function peerName(pub) {
    return (S.peers[pub] && S.peers[pub].name) || ('sailor-' + shortId(pub));
  }

  /* ==================== digging & treasure (owner) =================== */

  function onDigRequest(from, body) {
    if (!S.treasure.buried) return;
    var t = nowMs();
    if (t - (S.digCooldowns[from] || 0) < 2000) return;
    S.digCooldowns[from] = t;
    var d = dist(body.x, body.y, S.treasure.bx, S.treasure.by);
    var heat = clamp(1 - d / DIG_RANGE, 0, 1);
    Net.sendTo(from, 'sats-game', msg('ping', { heat: +heat.toFixed(3), nonce: body.nonce }));
  }

  function onClaimRequest(from, body) {
    if (!S.treasure.buried) {
      Net.sendTo(from, 'sats-game', msg('digResult', { ok: false, nonce: body.nonce }));
      return;
    }
    var d = dist(body.x, body.y, S.treasure.bx, S.treasure.by);
    if (d < CLAIM_R) {
      var sats = S.treasure.amount;
      S.treasure.buried = false;
      Net.sendTo(from, 'sats-game', msg('digResult', { ok: true, sats: sats, nonce: body.nonce }));
      broadcastGame(msg('claimed', { islandId: S.pub, by: from, sats: sats }));
      toast('Your treasure was dug up!');
      S.treasure.round = (S.treasure.round || 0) + 1;
      placeTreasure();
      persist();
    } else {
      Net.sendTo(from, 'sats-game', msg('digResult', { ok: false, nonce: body.nonce }));
    }
  }

  function onDigResult(body) {
    if (!S.pendingClaim || body.nonce !== S.pendingClaim.nonce) return;
    var mapId = S.pendingClaim.mapId;
    S.pendingClaim = null;
    if (body.ok) {
      S.sats += body.sats || 0;
      removeMap(mapId);
      AU.play('coin', 0.45, 1 + Math.random() * 0.15);
      toast('Treasure claimed! +' + (body.sats || 0) + ' sats.');
    } else {
      toast('Empty ground…');
    }
    persist();
  }

  function onClaimed(body) {
    S.pickups = S.pickups.filter(function (item) { return item.victim !== body.islandId; });
    var kept = [];
    for (var i = 0; i < S.maps.length; i++) {
      if (S.maps[i].victim !== body.islandId) kept.push(S.maps[i]);
      else delete S.trails[S.maps[i].id];
    }
    if (kept.length !== S.maps.length) {
      S.maps = kept;
      toast('A map went stale.');
      persist();
    }
  }

  /* ============================ map trade =========================== */

  function onMapOffer(from, body) {
    if (!body.map) return;
    S.offer = { from: from, map: body.map, price: body.price || 0 };
    showOffer();
  }

  function onMapAccept(from, body) {
    if (!body.ok || !S.pendingOffer) return;
    if (body.mapId !== S.pendingOffer.map.id) return;
    removeMap(body.mapId);
    S.pendingOffer = null;
    toast('Map traded.');
    persist();
  }

  function removeMap(id) {
    S.maps = S.maps.filter(function (m) { return m.id !== id; });
    delete S.trails[id];
  }

  /* ============================== combat ============================= */

  function damage(amount, src) {
    if (S.sinking > 0 || S.respawnAt) return;
    S.hp -= amount;
    flashDmg();
    var n = nowMs();
    if (amount > 0 && n - (S.hurtSndAt || 0) > 450) {
      S.hurtSndAt = n;
      AU.play('hurt', 0.4, 0.9 + Math.random() * 0.2);
    }
    if (S.hp <= 0) {
      S.hp = 0;
      startSinking(src);
    }
  }

  function startSinking(src) {
    S.sinking = 3.0;
    S.steerX = null; S.steerY = null;
    S.pendingClaim = null;
    toast('Sinking…');
  }

  function finishSinking() {
    var drops = [];
    drops.push({
      id: 'map-' + S.pub.slice(0, 8) + '-' + nowMs(),
      victim: S.pub, victimName: S.name, ix: S.island.x, iy: S.island.y,
    });
    for (var i = 0; i < S.maps.length; i++) drops.push(S.maps[i]);
    for (var j = 0; j < drops.length; j++) {
      var m = drops[j];
      broadcastGame(msg('scroll', {
        id: m.id, x: S.x + (Math.random() - 0.5) * 60, y: S.y + (Math.random() - 0.5) * 60,
        victim: m.victim, victimName: m.victimName, ix: m.ix, iy: m.iy,
      }));
    }
    S.maps = []; S.trails = {};
    broadcastGame(msg('sink', { pub: S.pub, x: Math.round(S.x), y: Math.round(S.y) }));
    S.vx = 0; S.vy = 0; S.sinking = 0;
    S.respawnAt = nowMs() + 5000;
    toast('Maps lost. Respawning soon…');
    persist();
  }

  function respawn() {
    S.x = S.island.x; S.y = S.island.y + ISLAND_R + 60;
    S.vx = 0; S.vy = 0; S.hp = 100; S.respawnAt = 0;
    S.steerX = null; S.steerY = null;
    S.a = -Math.PI / 2;
    toast('Washed ashore. Ready to sail.');
    sendHello();
  }

  function fireCannon(tx, ty) {
    if (S.sinking > 0 || S.respawnAt || S.cannonCd > 0) return;
    S.cannonCd = CANNON_CD;
    var dx = tx - S.x, dy = ty - S.y;
    var d = Math.sqrt(dx * dx + dy * dy) || 1;
    var a = Math.atan2(dy, dx);
    S.balls.push({
      x: S.x + (dx / d) * 20, y: S.y + (dy / d) * 20,
      vx: (dx / d) * BALL_SPEED, vy: (dy / d) * BALL_SPEED,
      ttl: BALL_TTL, from: S.pub, remote: false,
    });
    broadcastGame(msg('shot', {
      from: S.pub, x: Math.round(S.x), y: Math.round(S.y), a: +a.toFixed(3),
    }));
    cannonSound();
  }

  function fireAtAim() {
    if (!S) return;
    fireCannon(S.aimX === null ? S.x + Math.cos(S.a) * 200 : S.aimX,
      S.aimY === null ? S.y + Math.sin(S.a) * 200 : S.aimY);
  }

  /* ============================ p5 sketch ============================ */

  /* ============================ audio ============================
     Music + SFX, all CC0 from OpenGameArt (see assets/audio/CREDITS.md).
     Music streams via <audio>; SFX play through one shared AudioContext. */
  var sketch = null;
  var AU = {
    base: (function () {
      var s = document.querySelector('script[src$="sats-seas.js"]');
      var u = s ? s.getAttribute('src') : 'assets/js/sats-seas.js';
      return u.slice(0, u.lastIndexOf('/') + 1).replace(/js\/$/, '') + 'audio/';
    })(),
    ctx: null, bufs: {}, musicEl: null, onToggle: null,
    muted: (function () {
      try { return localStorage.getItem('sats-muted') === '1'; } catch (e) { return false; }
    })(),
    files: {
      coin: 'coin.ogg', cannon: 'cannon.ogg', hitmetal: 'hitmetal.ogg',
      hurt: 'hurt.ogg', roar: 'roar.ogg', died: 'npc-died.ogg',
      gem: 'gem.ogg', click: 'click.ogg',
    },
    init: function () {
      var AC = window.AudioContext || window.webkitAudioContext;
      if (AC && !this.ctx) {
        try { this.ctx = new AC(); } catch (e) {}
      }
      if (this.ctx && this.ctx.state === 'suspended') {
        try { this.ctx.resume(); } catch (e) {}
      }
      var self = this;
      Object.keys(this.files).forEach(function (k) {
        if (self.bufs[k] || !self.ctx) return;
        fetch(self.base + self.files[k]).then(function (r) {
          if (!r.ok) throw new Error('no audio');
          return r.arrayBuffer();
        }).then(function (b) { return self.ctx.decodeAudioData(b); })
          .then(function (buf) { self.bufs[k] = buf; })
          .catch(function () {});
      });
      this.startMusic();
    },
    startMusic: function () {
      if (this.muted) return;
      try {
        if (!this.musicEl) {
          var a = new Audio(this.base + 'pirate-loop.ogg');
          a.loop = true; a.volume = 0.32; a.preload = 'auto';
          this.musicEl = a;
        }
        var pr = this.musicEl.play();
        if (pr && pr.catch) pr.catch(function () {});
      } catch (e) {}
    },
    stopMusic: function () {
      try { if (this.musicEl) this.musicEl.pause(); } catch (e) {}
    },
    toggle: function () {
      this.muted = !this.muted;
      try { localStorage.setItem('sats-muted', this.muted ? '1' : '0'); } catch (e) {}
      if (this.muted) this.stopMusic(); else this.startMusic();
      if (this.onToggle) this.onToggle();
      return this.muted;
    },
    play: function (name, vol, rate) {
      if (this.muted || !this.ctx || !this.bufs[name]) return;
      try {
        var c = this.ctx, src = c.createBufferSource();
        src.buffer = this.bufs[name];
        src.playbackRate.value = rate || 1;
        var g = c.createGain();
        g.gain.value = (vol == null ? 0.5 : vol);
        src.connect(g); g.connect(c.destination);
        src.start();
      } catch (e) {}
    },
    // Procedural water splash: filtered noise burst. No file needed.
    splashSnd: function (vol) {
      if (this.muted || !this.ctx) return;
      try {
        var c = this.ctx, dur = 0.35, sr = c.sampleRate;
        var buf = c.createBuffer(1, Math.floor(sr * dur), sr);
        var d = buf.getChannelData(0);
        for (var i = 0; i < d.length; i++) {
          d[i] = (Math.random() * 2 - 1) * (1 - i / d.length);
        }
        var src = c.createBufferSource(); src.buffer = buf;
        var f = c.createBiquadFilter(); f.type = 'lowpass';
        f.frequency.setValueAtTime(2400, c.currentTime);
        f.frequency.exponentialRampToValueAtTime(300, c.currentTime + dur);
        var g = c.createGain(); g.gain.value = (vol == null ? 0.25 : vol);
        src.connect(f); f.connect(g); g.connect(c.destination);
        src.start();
      } catch (e) {}
    },
  };

  function cannonSound() {
    AU.play('cannon', 0.55, 0.92 + Math.random() * 0.16);
  }

  /* ------------------------- pixel-art sprites ------------------------ */
  // Every sprite is a string pixel-map; '.' is transparent.
  var PAL = {
    'O': '#14100c',
    'H': '#a06a35', 'h': '#6e4423', 'D': '#caa25e', 'd': '#a37f45',
    'M': '#3f2a16', 'S': '#f4efdd', 's': '#d8d0b6', 'R': '#c0392b',
    'W': '#f6f2e8', 'K': '#101018',
    'Y': '#f2c14e', 'y': '#b57e22',
    'G': '#2f9e44', 'g': '#237a32', 'T': '#7a5230', 't': '#5e3f24',
    'F': '#8a97a5', 'f': '#5d6a78',
    'E': '#dfe9f5', 'P': '#7fd4ff',
  };
  var SHIP_ROWS = [
    "......OOOO......",
    ".....OHHHHO.....",
    ".....OHHHHO.....",
    "....OHHHHHHO....",
    "....OHDDDDHO....",
    "....OHDDDDHO....",
    "....OSSSSSSO....",
    "....OSSMSSSO....",
    "....OSRRRRSO....",
    "....OSSMSSSO....",
    "....OSSSSSSO....",
    "....OHDDDDHO....",
    "....OHDDDDHO....",
    "....OHHHHHHO....",
    ".....OHHHHO.....",
    ".....ORRRRO.....",
    "......OOOO......",
    "................",
  ];
  var SHARK_ROWS = [
    "..................",
    ".......OOOO.......",
    "..O...OFFFFFOO....",
    ".OFFFOFfffffFFFO..",
    "OFFFffffffffffFKO.",
    ".OFFFOFfffffFFFO..",
    "..O...OFFFFFOO....",
    ".......OOOO.......",
    "..................",
  ];
  var SCROLL_ROWS = [
    ".OOOOOO.",
    "OSSSSSSO",
    "OSsSSsSO",
    "OSRRRRSO",
    "OSsSSsSO",
    "OSSSSSSO",
    ".OOOOOO.",
  ];
  var HEART_ROWS = [
    ".OO.OO.",
    "ORRRRRO",
    "ORRRRRO",
    ".ORRRO.",
    "..ORO..",
    "...O...",
  ];
  var COIN_ROWS = [
    "..OOOO..",
    ".OYYYYO.",
    "OYyYYYO.",
    "OYyYYYO.",
    "OYyYYYO.",
    "OYyYYYO.",
    ".OYYYYO.",
    "..OOOO..",
  ];
  var SKULL_ROWS = [
    "..OOOO..",
    ".OWWWWO.",
    "OWKWWKWO",
    "OWWWWWWO",
    ".OWWWWO.",
    ".OWKKWO.",
    "..OOOO..",
    "........",
  ];
  var PALM_ROWS = [
    ".....GG.....",
    "...GGGGGG...",
    "..GGGGGGGG..",
    ".GGGGGGGGGG.",
    "..GGgGGgGG..",
    ".....TT.....",
    ".....TT.....",
    ".....TT.....",
    ".....TT.....",
    "....TTTT....",
    "...TTTTTT...",
    "............",
  ];
  var CLOUD_ROWS = [
    "........WW........",
    "......WWWWWW......",
    ".....WWWWWWWW.....",
    "..W.WWWWWWWWWW.W..",
    ".WWWWWWWWWWWWWWWW.",
    ".WWWWWWWWWWWWWWWW.",
    "..WWWWWWWWWWWWWW..",
    "..................",
  ];

  function makeSprite(p, rows, pal) {
    pal = pal || PAL;
    var h = rows.length, w = rows[0].length;
    var g = p.createGraphics(w, h);
    g.noSmooth();
    g.clear();
    for (var y = 0; y < h; y++) {
      for (var x = 0; x < w; x++) {
        var c = pal[rows[y][x]];
        if (c) { g.noStroke(); g.fill(c); g.rect(x, y, 1, 1); }
      }
    }
    return g;
  }

  function boot() {
    if (!window.p5) { el('sats-status').textContent = 'Graphics library could not load. Reload the page to retry.'; return; }
    var form = el('sats-join');
    if (form) form.addEventListener('submit', async function (e) {
      e.preventDefault();
      var submitButton = form.querySelector('button[type=submit]');
      if (submitButton.disabled) return;
      submitButton.disabled = true;
      var name = (el('sats-name').value || 'sailor').slice(0, 16);
      try {
        if (!window.SatsSeasNet) throw new Error('Java networking is still loading. Try again in a moment.');
        var mode = el('sats-multiplayer').checked ? 'shared' : 'private';
        el('sats-status').textContent = mode === 'shared' ? 'Starting Harbor peers…' : 'Creating your practice room…';
        var config = await requestGame(mode);
        var relays = config.relays.slice();
        backendGame = config;
        startGame(name, config.roomKeySeed, relays, mode === 'shared', config.botPubkeys || []);
        gameKeepalive = setInterval(function () { keepBackendGame().catch(function () {}); }, 45000);
      }
      catch (error) { leaveGame(); el('sats-status').textContent = error.message; }
      finally { submitButton.disabled = false; }
    });
    var lv = el('sats-leave');
    if (lv) lv.addEventListener('click', leaveGame);
    el('sats-fire').addEventListener('click', fireAtAim);
    el('sats-dig').addEventListener('click', function () { if (S) digInteract(); });
    el('sats-trade').addEventListener('click', function () { if (S) tradeInteract(); });
    el('sats-chat-button').addEventListener('click', function () { if (S) openChat(); });
    var mu = el('sats-mute');
    if (mu) {
      var updMute = function () { mu.textContent = AU.muted ? 'MUS:OFF' : 'MUS:ON'; };
      updMute();
      AU.onToggle = updMute;
      mu.addEventListener('click', function () { AU.toggle(); AU.play('click', 0.4); });
    }
    var hl = el('sats-hl');
    if (hl) hl.addEventListener('change', function (e) {
      if (S) S.hlRoutes = e.target.checked;
    });
    window.addEventListener('beforeunload', function () {
      if (S && Net.joined) {
        try { broadcastGame(msg('despawn', { pub: S.pub })); } catch (e) {}
        Net.leave();
      }
      closeBackendGame();
      persist();
    });
  }

  function startGame(name, seed, relays, shared, expectedBots) {
    S = newSession(name, seed, relays, shared, expectedBots);
    delete el('sats-status').dataset.botReply;
    el('sats-join').style.display = 'none';
    el('sats-wrap').style.display = 'block';
    var cfg = { name: S.name, roomKeySeed: seed, relays: relays };
    if (/^nsec1/.test(S.nsec)) cfg.identityNsec = S.nsec;
    cfg.turnUri = backendGame.turnUri;
    el('sats-status').textContent = 'Connecting to the peer-to-peer room…';
    Net.join(cfg, onNetEvent);
    sketch = new p5(makeSketch, el('sats-canvas'));
    AU.init();
    wireOverlays();
  }

  function leaveGame() {
    closeBackendGame();
    if (S && Net.joined) {
      try { broadcastGame(msg('despawn', { pub: S.pub })); } catch (e) {}
    }
    Net.leave();
    persist();
    AU.stopMusic();
    if (sketch) { try { sketch.remove(); } catch (e) {} sketch = null; }
    S = null;
    el('sats-wrap').style.display = 'none';
    el('sats-join').style.display = 'block';
  }

  function makeSketch(p) {
    var sprShip, sprShark, sprScroll, sprHeart, sprHeartEmpty,
        sprCoin, sprSkull, sprPalm, sprCloud;
    var camX = 0, camY = 0;
    var lastT = 0, dmgFlash = 0, wake = [];

    p.setup = function () {
      var canvas = p.createCanvas(960, 600).elt;
      p.pixelDensity(1);
      p.noSmooth();
      function point(event) {
        var rect = canvas.getBoundingClientRect();
        return {
          x: clamp((event.clientX - rect.left) * 960 / rect.width, 0, 960),
          y: clamp((event.clientY - rect.top) * 600 / rect.height, 0, 600),
        };
      }
      function steer(event) {
        if (!S || !S.island) return;
        var pos = point(event);
        S.steerX = S.aimX = pos.x + camX;
        S.steerY = S.aimY = pos.y + camY;
      }
      canvas.addEventListener('pointermove', function (event) {
        if (event.pointerType !== 'touch' || event.buttons) steer(event);
        if (event.pointerType === 'touch') event.preventDefault();
      });
      canvas.addEventListener('pointerdown', function (event) {
        event.preventDefault();
        steer(event);
        if (S) fireAtAim();
      });
      sprShip = makeSprite(p, SHIP_ROWS, PAL);
      sprShark = makeSprite(p, SHARK_ROWS, PAL);
      sprScroll = makeSprite(p, SCROLL_ROWS, PAL);
      sprHeart = makeSprite(p, HEART_ROWS, PAL);
      sprHeartEmpty = makeSprite(p, HEART_ROWS, { 'O': '#14100c', 'R': '#3a2a2a' });
      sprCoin = makeSprite(p, COIN_ROWS, PAL);
      sprSkull = makeSprite(p, SKULL_ROWS, PAL);
      sprPalm = makeSprite(p, PALM_ROWS, PAL);
      sprCloud = makeSprite(p, CLOUD_ROWS, { 'W': '#e8f1fb' });
      lastT = nowMs();
    };

    /* ------------------------------ update ------------------------------ */
    p.draw = function () {
      var now = nowMs();
      var dt = Math.min(0.05, (now - lastT) / 1000) || 0.016;
      lastT = now;
      if (!S) { p.background(13, 43, 76); return; }
      if (!S.island) {
        p.background(13, 43, 76);
        p.fill(220); p.textAlign(p.CENTER); p.text('Connecting to the lobby…', 480, 300);
        return;
      }

      update(dt, now);
      render(p, now);

      if (Net.joined) {
        if (now - S.lastPos > 100) { S.lastPos = now; sendPos(); }
        if (now - S.lastHello > 30000) { S.lastHello = now; sendHello(); }
        if (now - S.lastTopo > 2500) { S.lastTopo = now; Net.getTopology(); }
        if (S.expectedBots.length && now - S.lastBotProbe > 5000) {
          S.lastBotProbe = now;
          S.expectedBots.forEach(function (id) {
            if (S.peers[id] && !S.botReplies[id]) Net.sendTo(id, 'sats-game', msg('probe', { probe: S.botProbe }));
          });
        }
      }
      if (now - S.lastPersist > 5000) { S.lastPersist = now; persist(); }
      if (now - S.lastMobileHud > 250) {
        S.lastMobileHud = now;
        var hud = el('sats-mobile-hud');
        if (hud) {
          var countdown = S.sinking > 0 ? S.sinking + 5 : S.respawnAt ? Math.max(0, (S.respawnAt - now) / 1000) : 0;
          hud.textContent = countdown > 0
            ? 'HP 0 · Respawn in ' + Math.ceil(countdown) + 's'
            : 'HP ' + Math.round(S.hp) + ' · Maps ' + S.maps.length + ' · Points ' + S.sats + ' · Ships ' + Object.keys(S.peers).length;
        }
      }
      if (dmgFlash > 0) dmgFlash -= dt;
    };

    function update(dt, now) {
      if (S.sinking > 0) {
        S.sinking -= dt;
        if (S.sinking <= 0) finishSinking();
      } else if (S.respawnAt) {
        if (now >= S.respawnAt) respawn();
      } else {
        var ix = (keys.right ? 1 : 0) - (keys.left ? 1 : 0);
        var iy = (keys.down ? 1 : 0) - (keys.up ? 1 : 0);
        var maxSp = S.maps.length ? MAX_SPEED_LADEN : MAX_SPEED;
        if (S.steerX !== null) {
          var dx = S.steerX - S.x, dy = S.steerY - S.y;
          var distance = Math.hypot(dx, dy);
          if (distance > 18) { ix = dx / distance; iy = dy / distance; }
          else { ix = 0; iy = 0; }
        }
        if (ix || iy) {
          var l = Math.sqrt(ix * ix + iy * iy);
          var tx = (ix / l) * maxSp, ty = (iy / l) * maxSp;
          S.vx += (tx - S.vx) * Math.min(1, dt * 4);
          S.vy += (ty - S.vy) * Math.min(1, dt * 4);
          S.a = Math.atan2(S.vy, S.vx);
        } else {
          S.vx *= Math.max(0, 1 - dt * 3);
          S.vy *= Math.max(0, 1 - dt * 3);
        }
        S.x = clamp(S.x + S.vx * dt, 0, WORLD);
        S.y = clamp(S.y + S.vy * dt, 0, WORLD);
        collideIslands();
        wake.push({ x: S.x, y: S.y, t: now });
        while (wake.length > 20) wake.shift();
      }
      if (S.cannonCd > 0) S.cannonCd -= dt;
      if (S.pendingClaim && now - S.pendingClaim.sentAt > 6000) {
        S.pendingClaim = null;
        toast('No reply yet. Try digging again.');
      }

      for (var id in S.peers) {
        var P = S.peers[id];
        if (P.rx < -9000) continue;
        var k = Math.min(1, dt * 14);
        P.x += (P.rx - P.x) * k;
        P.y += (P.ry - P.y) * k;
        P.a += Math.atan2(Math.sin(P.ra - P.a), Math.cos(P.ra - P.a)) * k;
      }

      var npcs = npcSim(worldSeed(), now);
      for (var i = S.balls.length - 1; i >= 0; i--) {
        var b = S.balls[i];
        b.x += b.vx * dt; b.y += b.vy * dt; b.ttl -= dt;
        var dead = false;
        if (b.ttl <= 0) { splash(b.x, b.y); AU.splashSnd(0.2); dead = true; }
        if (!dead && b.from === S.pub) {
          for (var pid in S.peers) {
            var Q = S.peers[pid];
            if (Q.hp <= 0) continue;
            if (dist(b.x, b.y, Q.x, Q.y) < SHIP_R + 5) {
              Net.sendTo(pid, 'sats-game', msg('hit', { target: pid, dmg: 14, by: S.pub }));
              splash(b.x, b.y); AU.play('hitmetal', 0.4, 0.9 + Math.random() * 0.2);
              dead = true; break;
            }
          }
        }
        if (!dead && b.from === S.pub) {
          for (var n = 0; n < npcs.length; n++) {
            var N = npcs[n];
            if (S.npcDeadUntil[N.id] > now) continue;
            var st = npcStats(N.kind);
            if (dist(b.x, b.y, N.x, N.y) < st.r + 4) {
              S.npcHp[N.id] = (S.npcHp[N.id] === undefined ? N.hp : S.npcHp[N.id]) - 14;
              splash(b.x, b.y); AU.play('hitmetal', 0.45, 0.9 + Math.random() * 0.2);
              dead = true;
              if (S.npcHp[N.id] <= 0) {
                S.npcDeadUntil[N.id] = now + 60000;
                AU.play('died', 0.5, N.kind === 'mega' ? 0.7 : 1);
                if (N.kind === 'mega') AU.play('roar', 0.5, 0.8);
                toast('A ' + st.name + ' was slain!');
              }
              break;
            }
          }
        }
        if (!dead && b.from !== S.pub && b.from.indexOf('npc') === 0) {
          if (dist(b.x, b.y, S.x, S.y) < SHIP_R + 4) {
            damage(12, 'ghost cannon'); splash(b.x, b.y); AU.splashSnd(0.25); dead = true;
          }
        }
        if (dead) S.balls.splice(i, 1);
      }

      if (S.sinking <= 0 && !S.respawnAt) {
        for (var m = 0; m < npcs.length; m++) {
          var M = npcs[m];
          if (S.npcDeadUntil[M.id] > now) continue;
          var mst = npcStats(M.kind);
          var d = dist(S.x, S.y, M.x, M.y);
          if (d < mst.aggro) {
            M.lunge = Math.min(70, (mst.aggro - d) * 0.8);
            if (d < 34) damage(mst.dps * dt, 'npc');
          }
          // Megalodon roar when it closes in.
          if (M.kind === 'mega' && d < 260 && now >= (S.roarCd || 0)) {
            S.roarCd = now + 7000;
            AU.play('roar', 0.55, 0.85 + Math.random() * 0.3);
          }
          // Ghost ships open fire with spectral cannonballs (local sim, like
          // contact damage: every client resolves its own NPC combat).
          if (M.kind === 'ghost' && d < 600 && d > 90) {
            if (now >= (S.npcFireCd[M.id] || 0)) {
              S.npcFireCd[M.id] = now + 1300 + (M.id % 3) * 350;
              var ga = Math.atan2(S.y - M.y, S.x - M.x);
              S.balls.push({
                x: M.x + Math.cos(ga) * 22, y: M.y + Math.sin(ga) * 22,
                vx: Math.cos(ga) * BALL_SPEED * 0.95, vy: Math.sin(ga) * BALL_SPEED * 0.95,
                ttl: 1.7, from: 'npc' + M.id, remote: false,
              });
              splash(M.x + Math.cos(ga) * 26, M.y + Math.sin(ga) * 26);
              AU.play('cannon', 0.28, 0.7 + Math.random() * 0.1);
            }
          }
        }
        for (var rid in S.peers) {
          var R = S.peers[rid];
          if (R.hp <= 0) continue;
          var rd = dist(S.x, S.y, R.x, R.y);
          if (rd < SHIP_R * 2 && rd > 0.01) {
            var closing = Math.abs(S.vx) + Math.abs(S.vy);
            if (closing > 90) {
              damage(6, 'ram');
              if (S.maps.length && Math.random() < 0.5) {
                var dropped = S.maps.pop();
                delete S.trails[dropped.id];
                broadcastGame(msg('scroll', {
                  id: dropped.id + '-ram' + now, x: Math.round(S.x), y: Math.round(S.y),
                  victim: dropped.victim, victimName: dropped.victimName,
                  ix: dropped.ix, iy: dropped.iy,
                }));
                toast('Ram! A map broke loose!');
              }
              S.vx *= -0.4; S.vy *= -0.4;
            }
          }
        }
      }

      for (var pi = S.pickups.length - 1; pi >= 0; pi--) {
        var pk = S.pickups[pi];
        if (dist(S.x, S.y, pk.x, pk.y) < 30) {
          S.pickups.splice(pi, 1);
          var map = { id: pk.id, victim: pk.victim, victimName: pk.victimName, ix: pk.ix, iy: pk.iy };
          S.maps.push(map);
          S.trails[map.id] = mapTrail(strSeed(map.id), S.x, S.y, map.ix, map.iy, 64);
          broadcastGame(msg('taken', { id: pk.id, by: S.pub }));
          toast('Treasure map acquired!');
          persist();
        }
      }

      for (var c = 0; c < CURSED.length; c++) {
        if (dist(S.x, S.y, CURSED[c].x, CURSED[c].y) < 700) S['cursedSeen' + c] = true;
      }
    }

    function collideIslands() {
      var all = allIslands();
      for (var i = 0; i < all.length; i++) {
        var isl = all[i];
        var d = dist(S.x, S.y, isl.x, isl.y);
        var min = ISLAND_R * 0.72;
        if (d < min && d > 0.01) {
          S.x = isl.x + ((S.x - isl.x) / d) * min;
          S.y = isl.y + ((S.y - isl.y) / d) * min;
        }
      }
    }

    function allIslands() {
      var out = [{ x: S.island.x, y: S.island.y, pub: S.pub, own: true }];
      for (var i = 0; i < S.discovered.length; i++) {
        var d = S.discovered[i];
        if (d.pub !== S.pub) out.push({ x: d.ix, y: d.iy, pub: d.pub, own: false });
      }
      return out;
    }

    function worldSeed() {
      return (strSeed(S.roomSeed || 'sats-seas-lobby')) >>> 0;
    }

    // Tiny debug hook for automated smoke tests / gameplay captures.
    window.SatsSeasDebug = {
      npcs: function () { return S ? npcSim(worldSeed(), nowMs()) : []; },
      me: function () { return S ? { x: S.x, y: S.y, hp: S.hp, sats: S.sats, island: S.island, respawnAt: S.respawnAt, sinking: S.sinking, cannonCd: S.cannonCd, heat: S.ping && S.ping.heat, waitingForDig: !!S.pendingClaim } : null; },
      practicePeers: function () { return S ? S.expectedBots.map(function (id) { var P = S.peers[id]; return P ? { id: id, x: P.rx, y: P.ry, a: P.a, hp: P.hp, island: P.island } : null; }) : []; },
      fireAt: function (tx, ty) { if (S) fireCannon(tx, ty); },
    };
    if (location.hostname === '127.0.0.1' || location.hostname === 'localhost') {
      window.SatsSeasDebug.takeDamage = function (amount) { if (S) damage(amount, 'smoke test'); };
      window.SatsSeasDebug.prepareTreasureClaim = function (botIndex) {
        if (!S || S.shared || botIndex < 0 || botIndex >= S.expectedBots.length) return false;
        var id = S.expectedBots[botIndex], bot = S.peers[id];
        if (!bot || !bot.island) return false;
        var angle = (strSeed('treasure:' + S.roomSeed + ':' + (botIndex + 3)) % 6283) / 1000;
        S.x = bot.island.x + Math.cos(angle) * 100;
        S.y = bot.island.y + Math.sin(angle) * 100;
        S.vx = 0; S.vy = 0; S.steerX = null; S.steerY = null;
        S.maps.push({ id: 'smoke-map-' + id.slice(0, 8), victim: id, victimName: bot.name, ix: bot.island.x, iy: bot.island.y });
        S.ping = null; S.pendingClaim = null; S.lastDig = 0;
        return true;
      };
    }

    function splash(x, y) {
      wake.push({ x: x, y: y, t: nowMs(), splash: true });
    }

    /* ------------------------------ render ------------------------------ */
    function render(p, now) {
      camX += ((S.x - 480) - camX) * 0.12;
      camY += ((S.y - 300) - camY) * 0.12;
      camX = clamp(camX, 0, WORLD - 960);
      camY = clamp(camY, 0, WORLD - 600);

      drawSea(p, now);
      drawZones(p);
      drawClouds(p, now);
      drawGulls(p, now);
      var islands = allIslands();
      for (var i = 0; i < islands.length; i++) drawIsland(p, islands[i], now);
      drawTrails(p, now);
      drawPickups(p, now);
      var npcs = npcSim(worldSeed(), now);
      for (var n = 0; n < npcs.length; n++) drawNpc(p, npcs[n], now);
      for (var pid in S.peers) if (S.peers[pid].hp > 0 && !(S.peers[pid].sunkUntil > now)) drawShip(p, S.peers[pid], false, now);
      if (!S.respawnAt) drawShip(p, S, true, now);
      drawBalls(p);
      drawWake(p, now);
      if (dmgFlash > 0) {
        p.noStroke();
        p.fill(200, 30, 30, dmgFlash * 110);
        p.rect(0, 0, 960, 600);
      }
      drawHud(p, now);
      drawHeat(p, now);
      drawMinimap(p);
      drawToast(p, now);
      drawHint(p, now);
    }

    function drawSea(p, now) {
      p.background(13, 43, 76);
      p.noStroke();
      // Animated wave crests: world-locked dashed sine rows, drifting with time.
      var T = 54;
      var y0 = Math.floor(camY / T) * T;
      var x0 = Math.floor(camX / 64) * 64;
      for (var ty = y0; ty < camY + 600 + T; ty += T) {
        var rowPhase = ty * 0.021 + now / 1500;
        for (var tx = x0; tx < camX + 960 + 64; tx += 64) {
          var wob = Math.sin(tx * 0.02 + rowPhase) * 7;
          var a = 40 + 38 * Math.sin(tx * 0.013 - now / 2300 + ty * 0.05);
          if (a < 16) continue;
          p.fill(125, 195, 240, a);
          var off = ((tx * 7 + ty * 13) % 34 + 34) % 34;
          p.rect(tx - camX + off, Math.round(ty - camY + wob), 26, 3);
          // occasional bright crest glint
          var g = ((tx * 31 + ty * 17) >> 5) & 63;
          if (g < 3) {
            p.fill(200, 235, 255, a + 40);
            p.rect(tx - camX + off + 28, Math.round(ty - camY + wob) - 1, 8, 2);
          }
        }
      }
      // sparse twinkling sparkles
      var S2 = 96;
      var sx0 = Math.floor(camX / S2) * S2, sy0 = Math.floor(camY / S2) * S2;
      for (var sy = sy0; sy < camY + 600 + S2; sy += S2) {
        for (var sx = sx0; sx < camX + 960 + S2; sx += S2) {
          var h = ((sx * 13 + sy * 7) >> 4) & 31;
          var tw = Math.sin(now / 700 + h) * 0.5 + 0.5;
          if (h < 3 && tw > 0.75) {
            p.fill(150, 200, 240, 90 * tw);
            var px = sx - camX + (h * 29) % 80;
            var py = sy - camY + (h * 53) % 80;
            p.rect(px, py, 3, 3);
          }
        }
      }
    }

    function drawZones(p) {
      p.noStroke();
      p.fill(9, 28, 58, 170);
      p.rect(0, DEEP_Y0 - camY, 960, DEEP_Y1 - DEEP_Y0);
      for (var i = 0; i < CURSED.length; i++) {
        var c = CURSED[i];
        p.fill(46, 18, 70, 90);
        p.ellipse(c.x - camX, c.y - camY, c.r * 2, c.r * 2);
        p.noFill(); p.stroke(120, 70, 170, 60); p.strokeWeight(2);
        p.ellipse(c.x - camX, c.y - camY, c.r * 2, c.r * 2);
        p.noStroke();
      }
    }

    function drawClouds(p, now) {
      var rnd = mulberry32((worldSeed() ^ 0xc10d5) >>> 0);
      p.tint(255, 185);
      for (var i = 0; i < 6; i++) {
        var bx = rnd() * (WORLD + 600) - 300;
        var by = rnd() * (WORLD + 400) - 200;
        var drift = (now / 1000) * (4 + i * 1.7);
        var wx = (((bx + drift) % (WORLD + 600)) + WORLD + 600) % (WORLD + 600) - 300;
        var sx = wx - camX, sy = by - camY;
        if (sx < -140 || sx > 1100 || sy < -60 || sy > 660) continue;
        var sc = 2.2 + (i % 3) * 0.9;
        p.image(sprCloud, sx, sy, 18 * sc, 8 * sc);
      }
      p.noTint();
    }

    function drawGulls(p, now) {
      p.stroke(235, 242, 250);
      p.strokeWeight(2);
      p.noFill();
      for (var i = 0; i < 4; i++) {
        var spd = 42 + i * 11;
        var x = ((now / 1000 * spd + i * 431) % 1300) - 170;
        var y = 56 + i * 44 + Math.sin(now / 620 + i * 2.1) * 13;
        if (Math.floor(now / 210 + i * 1.7) % 2 === 0) {
          p.line(x - 8, y, x, y - 6);
          p.line(x, y - 6, x + 8, y);
        } else {
          p.line(x - 8, y + 3, x, y);
          p.line(x, y, x + 8, y + 3);
        }
      }
      p.noStroke();
    }

    function drawFoam(p, x, y, r, now) {
      p.noFill();
      p.stroke(205, 232, 250, 110);
      p.strokeWeight(3);
      var n = 24, off = now / 2600;
      for (var i = 0; i < n; i += 2) {
        var a0 = (i / n) * Math.PI * 2 + off;
        var a1 = ((i + 0.9) / n) * Math.PI * 2 + off;
        p.arc(x, y, r * 2, r * 2, a0, a1);
      }
      p.noStroke();
    }

    function drawIsland(p, isl, now) {
      var sx = isl.x - camX, sy = isl.y - camY;
      if (sx < -280 || sx > 1240 || sy < -280 || sy > 880) return;
      var R = ISLAND_R;
      p.noStroke();
      p.fill(6, 20, 38, 130);                       // soft shadow
      p.ellipse(sx + 8, sy + 12, R * 2.05, R * 1.6);
      drawFoam(p, sx, sy, R + 12, now);              // animated foam ring
      p.fill(141, 116, 71);                         // sand outline
      p.ellipse(sx, sy, R * 2, R * 2);
      p.fill(231, 212, 155);                        // sand
      p.ellipse(sx, sy, R * 2 - 12, R * 2 - 12);
      p.fill(74, 140, 70);                          // grass base
      p.ellipse(sx, sy - 4, R * 1.28, R * 1.28);
      p.fill(96, 164, 80);                          // grass highlight
      p.ellipse(sx - 8, sy - 12, R * 0.95, R * 0.95);
      var rnd = mulberry32(strSeed('tuft' + Math.round(isl.x) + ',' + Math.round(isl.y)));
      p.fill(56, 118, 54);                          // grass tufts
      for (var i = 0; i < 12; i++) {
        var tx = sx + (rnd() - 0.5) * R * 1.05;
        var tyy = sy - 4 + (rnd() - 0.5) * R * 1.05;
        p.rect(Math.round(tx), Math.round(tyy), 3, 3);
      }
      var rp = mulberry32(strSeed('palm' + Math.round(isl.x) + ',' + Math.round(isl.y)));
      for (var k = 0; k < 4; k++) {                 // palms
        var px = sx + (rp() - 0.5) * R * 1.1;
        var py = sy - 4 + (rp() - 0.5) * R * 1.1;
        p.image(sprPalm, Math.round(px) - 12, Math.round(py) - 20, 24, 28);
      }
      if (isl.own) drawFlag(p, sx, sy - 4, now);
    }

    function drawFlag(p, x, y, now) {
      var wave = Math.sin(now / 300) > 0 ? 0 : 2;
      p.noStroke();
      p.fill(63, 42, 22);
      p.rect(Math.round(x) - 1, Math.round(y) - 34, 3, 34);   // pole
      p.fill(220, 60, 50);
      p.rect(Math.round(x) + 2, Math.round(y) - 34, 16 - wave, 10); // flag
      p.fill(240, 240, 240);
      p.rect(Math.round(x) + 2, Math.round(y) - 34, 5, 10);   // canton
    }

    function drawTrails(p, now) {
      p.noStroke();
      for (var id in S.trails) {
        var pts = S.trails[id];
        for (var i = 0; i < pts.length; i += 3) {
          var s = 3 + Math.sin(now / 280 + i * 0.6) * 1.4;
          p.fill(255, 236, 170, 210);
          var dx = pts[i][0] - camX, dy = pts[i][1] - camY;
          p.rect(Math.round(dx - s / 2), Math.round(dy - s / 2), Math.round(s), Math.round(s));
        }
        var last = pts[pts.length - 1];
        p.image(sprSkull, last[0] - camX - 8, last[1] - camY - 22, 16, 16);
      }
    }

    function drawPickups(p, now) {
      for (var i = 0; i < S.pickups.length; i++) {
        var pk = S.pickups[i];
        var bob = Math.round(Math.sin(now / 420 + pk.bob) * 4);
        p.noStroke();
        p.fill(200, 230, 250, 70);
        p.ellipse(pk.x - camX, pk.y - camY + 8, 22, 8);
        p.image(sprScroll, Math.round(pk.x - camX) - 8, Math.round(pk.y - camY) - 10 + bob, 16, 14);
      }
    }

    function drawNpc(p, N, now) {
      if (S.npcDeadUntil[N.id] > now) return;
      var x = N.x, y = N.y;
      if (N.lunge) { x += Math.cos(N.a) * N.lunge; y += Math.sin(N.a) * N.lunge; N.lunge = 0; }
      var sx = x - camX, sy = y - camY;
      if (sx < -60 || sx > 1020 || sy < -60 || sy > 660) return;
      p.push();
      p.translate(sx, sy);
      p.rotate(N.a);
      if (N.kind === 'shark') {
        p.image(sprShark, -18, -9, 36, 18);
      } else if (N.kind === 'mega') {
        p.tint(240, 130, 130);
        p.image(sprShark, -47, -23, 94, 46);
        p.noTint();
        p.fill(255, 45, 45);
        p.rect(30, -4, 5, 5);
      } else {
        var bob = Math.sin(now / 300 + N.id) * 3;
        p.translate(0, bob);
        p.fill(120, 255, 190, 26);
        p.ellipse(0, 0, 84, 84);
        p.tint(150, 235, 200, 185);
        p.image(sprShip, -24, -27, 48, 54);
        p.noTint();
      }
      p.pop();
      var near = dist(S.x, S.y, x, y) < 430;
      if (near && (N.kind === 'mega' || N.kind === 'ghost')) {
        p.noStroke();
        p.textAlign(p.CENTER); p.textSize(10);
        if (N.kind === 'mega') { p.fill(255, 90, 90); p.text('MEGALODON', sx, sy - 34); }
        else { p.fill(170, 255, 210); p.text('GHOST SHIP', sx, sy - 40); }
      }
      if (near && N.kind === 'ghost') p.image(sprSkull, sx - 8, sy - 58, 16, 16);
      var hp = S.npcHp[N.id];
      if (hp !== undefined && hp < N.hp) {
        p.noStroke();
        p.fill(20, 10, 10); p.rect(sx - 13, sy - 24, 26, 4);
        p.fill(210, 60, 50); p.rect(sx - 13, sy - 24, 26 * clamp(hp / N.hp, 0, 1), 4);
      }
    }

    function drawShip(p, E, self, now) {
      var x = E.x - camX, y = E.y - camY;
      if (x < -50 || x > 1010 || y < -50 || y > 650) return;
      var spr = sprShip;
      // wake shadow
      p.noStroke();
      p.fill(6, 20, 38, 90);
      p.ellipse(x, y + 12, 30, 10);
      if (self && S.sinking > 0) {
        p.push();
        p.translate(x, y);
        p.rotate(E.a + Math.PI / 2 + (3 - S.sinking) * 0.45);
        p.tint(255, clamp(S.sinking / 3, 0, 1) * 255);
        p.image(spr, -24, -27 + (3 - S.sinking) * 10, 48, 54);
        p.pop();
        p.noTint();
        return;
      }
      var bobA = Math.sin(now / 350 + (self ? 0 : E.pub.charCodeAt(0))) * 0.03;
      p.push();
      p.translate(x, y);
      p.rotate(E.a + Math.PI / 2 + bobA);
      p.image(spr, -24, -27, 48, 54);
      p.pop();
      // speech bubble
      var bub = S.bubbles[self ? S.pub : E.pub];
      if (bub && bub.until > now) drawBubble(p, x, y - 34, bub.text, now);
      else if (!self && dist(S.x, S.y, E.x, E.y) < 320) {
        p.noStroke();
        p.fill(225, 235, 245, 190);
        p.textSize(9); p.textAlign(p.CENTER);
        p.text(E.name, x, y - 30);
      }
      // hp pips under ship
      var hp = self ? S.hp : E.hp;
      p.noStroke();
      p.fill(10, 16, 28); p.rect(x - 13, y + 22, 26, 4);
      p.fill(hp > 50 ? '#58c96b' : hp > 25 ? '#f2c14e' : '#e33');
      p.rect(x - 13, y + 22, 26 * clamp(hp / 100, 0, 1), 4);
    }

    function drawBubble(p, x, y, text, now) {
      p.textSize(11);
      var w = Math.min(220, p.textWidth(text) + 16);
      var bx = clamp(x - w / 2, 4, 956 - w);
      p.noStroke();
      p.fill(8, 14, 26, 230);
      p.rect(bx, y - 20, w, 22);
      p.noFill(); p.stroke(140, 180, 220); p.strokeWeight(2);
      p.rect(bx, y - 20, w, 22);
      p.noStroke();
      p.fill(240, 244, 250);
      p.textAlign(p.CENTER); p.textSize(11);
      p.text(text, bx + w / 2, y - 5);
    }

    function drawBalls(p) {
      for (var i = 0; i < S.balls.length; i++) {
        var b = S.balls[i];
        var x = b.x - camX, y = b.y - camY;
        if (x < -20 || x > 980 || y < -20 || y > 620) continue;
        var ghost = b.from !== S.pub && ('' + b.from).indexOf('npc') === 0;
        var spd = Math.sqrt(b.vx * b.vx + b.vy * b.vy) || 1;
        // motion streak
        p.stroke(ghost ? 120 : 200, ghost ? 255 : 200, ghost ? 190 : 210, 90);
        p.strokeWeight(2);
        p.line(x, y, x - b.vx / spd * 8, y - b.vy / spd * 8);
        p.noStroke();
        if (ghost) {
          p.fill(120, 255, 190, 70);
          p.ellipse(x, y, 16, 16);
          p.fill(150, 255, 205);
          p.ellipse(x, y, 8, 8);
        } else {
          p.fill(14, 14, 18);
          p.ellipse(x, y, 8, 8);
          p.fill(230, 230, 240);
          p.rect(Math.round(x) - 2, Math.round(y) - 2, 2, 2);
        }
      }
    }

    function drawWake(p, now) {
      p.noStroke();
      for (var i = 0; i < wake.length; i++) {
        var w = wake[i];
        var age = (now - w.t) / 1400;
        if (age > 1) continue;
        if (w.splash) {
          p.fill(255, 220, 150, (1 - age) * 200);
          var r = 4 + age * 20;
          p.ellipse(w.x - camX, w.y - camY, r, r);
        } else if (i % 2 === 0) {
          p.fill(150, 200, 240, (1 - age) * 90);
          p.rect(Math.round(w.x - camX) - 2, Math.round(w.y - camY) - 2, 4, 4);
        }
      }
    }

    /* ------------------------------- HUD ------------------------------- */
    function panel(p, x, y, w, h) {
      p.noStroke();
      p.fill(8, 18, 34, 225);
      p.rect(x, y, w, h);
      p.noFill(); p.stroke(58, 105, 165); p.strokeWeight(2);
      p.rect(x, y, w, h);
      p.noStroke();
    }

    function drawHud(p, now) {
      var x = 10, y = 10, w = 208, h = 52;
      panel(p, x, y, w, h);
      for (var i = 0; i < 5; i++) {
        var img = (S.hp - i * 20) >= 20 ? sprHeart : (S.hp - i * 20) > 0 ? sprHeart : sprHeartEmpty;
        // half hearts: reuse full sprite clipped is overkill; threshold to full/empty
        p.image(img, x + 8 + i * 20, y + 7, 14, 12);
      }
      p.image(sprCoin, x + 8, y + 26, 14, 14);
      p.fill(242, 193, 78);
      p.textSize(13); p.textAlign(p.LEFT); p.textStyle(p.BOLD);
      p.text(S.sats, x + 26, y + 38);
      p.image(sprScroll, x + 66, y + 25, 14, 12);
      p.fill(220, 230, 240);
      p.text(S.maps.length, x + 84, y + 38);
      var n = 0;
      for (var id in S.peers) n++;
      p.fill(140, 165, 195);
      p.textSize(11);
      p.text(n + ' ships', x + 118, y + 38);
      p.textStyle(p.NORMAL);
    }

    function drawHeat(p, now) {
      if (!S.ping || now - S.ping.t > 8000) return;
      var heat = S.ping.heat;
      var x = 10, y = 600 - 40, w = 190, h = 30;
      panel(p, x, y, w, h);
      p.fill(140, 165, 195);
      p.textSize(10); p.textAlign(p.LEFT);
      p.text('DOWSING', x + 8, y + 14);
      p.noStroke();
      p.fill(20, 32, 52); p.rect(x + 8, y + 17, 110, 6);
      p.fill(heat > 0.93 ? '#e33' : heat > 0.6 ? '#f2c14e' : '#39c');
      p.rect(x + 8, y + 17, 110 * clamp(heat, 0, 1), 6);
      p.fill(240, 244, 250);
      p.textSize(11); p.textStyle(p.BOLD);
      p.text(heat > 0.93 ? 'HOT!' : heat > 0.6 ? 'warm' : heat > 0.25 ? 'cold' : 'ice', x + 126, y + 24);
      p.textStyle(p.NORMAL);
    }

    function drawMinimap(p) {
      var mm = 150, x0 = 960 - mm - 10, y0 = 600 - mm - 10;
      var sc = mm / WORLD;
      panel(p, x0, y0, mm, mm);
      p.fill(10, 34, 70);
      p.rect(x0 + 2, y0 + 2 + DEEP_Y0 * sc, mm - 4, (DEEP_Y1 - DEEP_Y0) * sc);
      var isl = allIslands();
      for (var i = 0; i < isl.length; i++) {
        var ix = x0 + isl[i].x * sc, iy = y0 + isl[i].y * sc;
        if (isl[i].own) {
          p.noStroke(); p.fill(220, 60, 50);
          p.rect(ix - 2, iy - 6, 3, 8);
          p.rect(ix + 1, iy - 6, 5, 4);
        } else {
          p.noStroke(); p.fill(231, 212, 155);
          p.rect(ix - 2, iy - 2, 5, 5);
        }
      }
      for (var c = 0; c < CURSED.length; c++) {
        if (S['cursedSeen' + c]) p.image(sprSkull, x0 + CURSED[c].x * sc - 6, y0 + CURSED[c].y * sc - 6, 12, 12);
      }
      var mmNpcs = npcSim(worldSeed(), nowMs());
      for (var mi = 0; mi < mmNpcs.length; mi++) {
        var MN = mmNpcs[mi];
        p.noStroke();
        if (MN.kind === 'mega') p.fill(255, 70, 70);
        else if (MN.kind === 'ghost') p.fill(140, 255, 200);
        else p.fill(130, 150, 170);
        p.rect(x0 + MN.x * sc - 1, y0 + MN.y * sc - 1, 3, 3);
      }
      for (var pid in S.peers) {
        var P = S.peers[pid];
        if (P.rx > -9000 || P.island) {
          var markerX = P.rx > -9000 ? P.rx : P.island.x;
          var markerY = P.ry > -9000 ? P.ry : P.island.y;
          p.noStroke(); p.fill(S.expectedBots.indexOf(pid) >= 0 ? '#f2c14e' : '#80c4ff');
          p.rect(x0 + markerX * sc - 2, y0 + markerY * sc - 2, 5, 5);
        }
      }
      p.push();
      p.translate(x0 + S.x * sc, y0 + S.y * sc);
      p.rotate(S.a);
      p.noStroke(); p.fill(255, 255, 255);
      p.triangle(5, 0, -3, -3, -3, 3);
      p.pop();
      p.noStroke(); p.fill(160, 185, 210);
      p.textSize(9); p.textAlign(p.LEFT);
      p.text('CHART', x0 + 6, y0 + 13);
    }

    function drawToast(p, now) {
      if (!S.toast || S.toast.until < now) return;
      var a = clamp((S.toast.until - now) / 400, 0, 1);
      p.textSize(13);
      var w = Math.min(560, p.textWidth(S.toast.msg) + 30);
      var x = 480 - w / 2, y = 600 - 78;
      p.noStroke();
      p.fill(8, 16, 30, 225 * a);
      p.rect(x, y, w, 28);
      p.noFill(); p.stroke(120, 170, 220, 220 * a); p.strokeWeight(2);
      p.rect(x, y, w, 28);
      p.noStroke();
      p.fill(240, 244, 250, 255 * a);
      p.textAlign(p.CENTER);
      p.text(S.toast.msg, 480, y + 19);
    }

    function drawHint(p, now) {
      p.noStroke();
      p.fill(230, 238, 248, 210);
      p.textSize(12); p.textAlign(p.CENTER);
      if (S.sinking > 0 || S.respawnAt) {
        var remaining = S.sinking > 0 ? S.sinking + 5 : Math.max(0, (S.respawnAt - now) / 1000);
        p.text('Respawning at your island in ' + Math.ceil(remaining) + 's', 480, 600 - 100);
      } else {
        p.text('Move pointer / drag to sail · Click / tap to fire · Use the action buttons below', 480, 600 - 100);
      }
    }

    /* ------------------------------ input ------------------------------ */
    p.keyPressed = function () {
      if (!S || chatOpen()) return;
      var k = (p.key || '').toLowerCase();
      if (k === ' ') { fireAtCursor(); return false; }
      if (k === 'e') { digInteract(); return false; }
      if (k === 't') { tradeInteract(); return false; }
      if (k === 'c') { openChat(); return false; }
      if (k === 'm') { AU.toggle(); return false; }
    };
    function fireAtCursor() {
      fireAtAim();
    }
    function flashDmg() { dmgFlash = 0.6; }
    window.__satsFlash = flashDmg;
  }

  var keys = { up: false, down: false, left: false, right: false };
  document.addEventListener('keydown', function (e) {
    if (chatOpen()) return;
    var k = e.key.toLowerCase();
    if (k === 'arrowup' || k === 'w') keys.up = true;
    if (k === 'arrowdown' || k === 's') keys.down = true;
    if (k === 'arrowleft' || k === 'a') keys.left = true;
    if (k === 'arrowright' || k === 'd') keys.right = true;
    if (['arrowup', 'arrowdown', 'arrowleft', 'arrowright', ' '].indexOf(k) >= 0) e.preventDefault();
  });
  document.addEventListener('keyup', function (e) {
    var k = e.key.toLowerCase();
    if (k === 'arrowup' || k === 'w') keys.up = false;
    if (k === 'arrowdown' || k === 's') keys.down = false;
    if (k === 'arrowleft' || k === 'a') keys.left = false;
    if (k === 'arrowright' || k === 'd') keys.right = false;
  });

  function flashDmg() {
    if (window.__satsFlash) window.__satsFlash();
  }

  /* ========================= interactions ========================= */

  function nearestForeignIsland() {
    var best = null, bd = ISLAND_R + 40;
    for (var i = 0; i < S.discovered.length; i++) {
      var d = S.discovered[i];
      if (d.pub === S.pub) continue;
      var distI = dist(S.x, S.y, d.ix, d.iy);
      if (distI < bd) { bd = distI; best = d; }
    }
    return best;
  }

  function digInteract() {
    if (S.sinking > 0 || S.respawnAt) return;
    var isl = nearestForeignIsland();
    if (!isl) { toast('Sail to another island to dig.'); return; }
    var map = null;
    for (var i = 0; i < S.maps.length; i++) {
      if (S.maps[i].victim === isl.pub) { map = S.maps[i]; break; }
    }
    if (!map) { toast('You need that island\u2019s map.'); return; }
    var owner = S.peers[isl.pub];
    if (!owner) { toast('The owner is away.'); return; }
    var t = nowMs();
    if (t - S.lastDig < 2100) return;
    if (S.pendingClaim) { toast('Waiting for the island owner…'); return; }
    S.lastDig = t;
    var nonce = Math.floor(Math.random() * 1e9).toString(36);
    if (S.ping && S.ping.heat > 0.93 && t - S.ping.t < 9000) {
      S.pendingClaim = { nonce: nonce, mapId: map.id, sentAt: t };
      Net.sendTo(isl.pub, 'sats-game', msg('claim', {
        islandId: isl.pub, x: Math.round(S.x), y: Math.round(S.y), nonce: nonce,
      }));
      toast('Digging…');
    } else {
      S.pendingClaim = { nonce: nonce, mapId: map.id, sentAt: t };
      S.ping = null;
      Net.sendTo(isl.pub, 'sats-game', msg('dig', {
        x: Math.round(S.x), y: Math.round(S.y), nonce: nonce,
      }));
    }
  }

  function tradeInteract() {
    if (!S.maps.length) { toast('No maps to trade.'); return; }
    var best = null, bd = 350;
    for (var id in S.peers) {
      var P = S.peers[id];
      var d = dist(S.x, S.y, P.x, P.y);
      if (d < bd) { bd = d; best = P; }
    }
    if (!best) { toast('No ship in trading range.'); return; }
    var map = S.maps[0];
    S.pendingOffer = { map: map, to: best.pub };
    Net.sendTo(best.pub, 'sats-game', msg('mapOffer', {
      to: best.pub,
      map: { id: map.id, victim: map.victim, victimName: map.victimName, ix: map.ix, iy: map.iy },
      price: 0,
    }));
    toast('Map offered to ' + peerName(best.pub) + '.');
  }

  /* ============================== overlays ============================= */

  function wireOverlays() {
    var oy = el('sats-offer-yes'), on = el('sats-offer-no');
    if (oy) oy.onclick = function () { answerOffer(true); };
    if (on) on.onclick = function () { answerOffer(false); };
    var chat = el('sats-chat');
    if (chat) chat.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        var text = chat.value.trim().slice(0, 60);
        chat.value = '';
        chat.style.display = 'none';
        if (text) {
          var t = nowMs();
          if (t - S.chatCd < 2000) { toast('Slow down.'); return; }
          S.chatCd = t;
          broadcastGame(msg('chat', { from: S.pub, name: S.name, text: text }));
          chatBubble(S.pub, text);
        }
        e.stopPropagation();
      }
    });
  }

  function placeTreasure() {
    var rnd = mulberry32(strSeed(S.roomSeed + S.pub + 'treasure:' + (S.treasure.round || 0)));
    var rr = ISLAND_R * 0.55 * Math.sqrt(rnd());
    var aa = rnd() * Math.PI * 2;
    S.treasure.buried = true;
    S.treasure.bx = S.island.x + Math.cos(aa) * rr;
    S.treasure.by = S.island.y + Math.sin(aa) * rr;
  }

  function showOffer() {
    var o = S.offer;
    var t = el('sats-offer-text');
    if (!t) return;
    t.textContent = peerName(o.from) + ' offers a treasure map. Take it?';
    el('sats-offer').style.display = 'block';
  }

  function answerOffer(ok) {
    var o = S.offer;
    S.offer = null;
    var box = el('sats-offer');
    if (box) box.style.display = 'none';
    if (!o) return;
    Net.sendTo(o.from, 'sats-game', msg('mapAccept', { mapId: o.map.id, ok: ok }));
    if (ok) {
      var map = { id: o.map.id, victim: o.map.victim, victimName: o.map.victimName, ix: o.map.ix, iy: o.map.iy };
      S.maps.push(map);
      S.trails[map.id] = mapTrail(strSeed(map.id), S.x, S.y, map.ix, map.iy, 64);
      toast('Map accepted!');
      persist();
    }
  }

  function chatOpen() { var c = el('sats-chat'); return c && c.style.display !== 'none'; }
  function openChat() { var c = el('sats-chat'); if (c) { c.style.display = 'block'; c.focus(); } }

  /* ============================ HUD & misc =========================== */
  // HUD is drawn inside the canvas (drawHud). These are silent by design:
  // no status wall, no scrolling log, no chat log. Chat arrives as
  // speech bubbles over ships.

  function chatBubble(pub, text) {
    if (!S) return;
    S.bubbles[pub] = { text: String(text).slice(0, 60), until: nowMs() + 4000 };
  }

  function discoverIsland(pub, name, ix, iy) {
    for (var i = 0; i < S.discovered.length; i++) {
      if (S.discovered[i].pub === pub) {
        S.discovered[i].name = name;
        return;
      }
    }
    S.discovered.push({ pub: pub, name: name, ix: ix, iy: iy });
  }

  /* ========================= topology (hidden) ========================= */
  // Minimal mesh graph, only drawn when the collapsed <details> is open.

  function drawTopo() {
    var panelEl = el('sats-topo-panel');
    var cv = el('sats-topo');
    if (!S || !S.topo || !cv || !panelEl || !panelEl.open) return;
    var topo = S.topo;
    var w = (cv.width = cv.clientWidth || 600), h = (cv.height = 220);
    var ctx = cv.getContext('2d');
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = '#0b1526';
    ctx.fillRect(0, 0, w, h);
    var nodes = topo.nodes || [], edges = topo.edges || [];
    if (!nodes.length) {
      ctx.fillStyle = '#7d8fa6';
      ctx.font = '12px monospace';
      ctx.fillText('mesh forming…', 12, 20);
      return;
    }
    var cx = w / 2, cy = h / 2, R = Math.min(w, h) / 2 - 24;
    var pos = {};
    for (var i = 0; i < nodes.length; i++) {
      var a = (i / nodes.length) * Math.PI * 2 - Math.PI / 2;
      pos[nodes[i].id] = [cx + Math.cos(a) * R, cy + Math.sin(a) * R * 0.82];
    }
    function edgeColor(t) {
      return t === 'rtc' ? '#39dc97' : t === 'turn' ? '#57b4ff' : '#5a6a82';
    }
    var self = topo.self;
    for (var e = 0; e < edges.length; e++) {
      var ed = edges[e], A = pos[ed.a], B = pos[ed.b];
      if (!A || !B) continue;
      ctx.strokeStyle = edgeColor(ed.t);
      ctx.globalAlpha = 0.85;
      ctx.lineWidth = (ed.a === self || ed.b === self) ? 2.5 : 1.2;
      ctx.beginPath(); ctx.moveTo(A[0], A[1]); ctx.lineTo(B[0], B[1]); ctx.stroke();
      ctx.globalAlpha = 1;
    }
    for (var n2 = 0; n2 < nodes.length; n2++) {
      var nd = nodes[n2], P = pos[nd.id];
      var isSelf = nd.id === self;
      ctx.fillStyle = isSelf ? '#ffd75e' : '#16263f';
      ctx.strokeStyle = isSelf ? '#ffd75e' : '#7d8fa6';
      ctx.lineWidth = isSelf ? 2.5 : 1.5;
      ctx.beginPath(); ctx.arc(P[0], P[1], isSelf ? 9 : 7, 0, 7); ctx.fill(); ctx.stroke();
      ctx.fillStyle = isSelf ? '#3a2c00' : '#c7d4e6';
      ctx.font = '9px monospace';
      ctx.textAlign = 'center';
      ctx.fillText(isSelf ? 'you' : nd.short, P[0], P[1] + 3);
    }
    ctx.textAlign = 'left';
    ctx.font = '11px monospace';
    ctx.fillStyle = '#7d8fa6';
    ctx.fillText(nodes.length + ' nodes · ' + edges.length + ' links', 10, h - 10);
  }

  /* ================================ boot ============================= */

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
