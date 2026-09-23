---
title: Overview
---

# Nostr4J documentation

Learn the core client API, then explore protocol extensions, wallets, media and RTC rooms.

## Start

<div class="section-grid">
<a class="section-card" href="getting-started.html">
<h3>Getting started</h3>
<p>Add the dependency, publish your first event, read events back.</p>
</a>
<a class="section-card" href="platforms.html">
<h3>Platforms & setup</h3>
<p>Pick the platform artifact, Java version and initialization order for your target.</p>
</a>
</div>

## Core

<div class="section-grid">
<a class="section-card" href="concepts.html">
<h3>Events & filters</h3>
<p>Keys, events, signing and filters: the four ideas the library is built on.</p>
</a>
<a class="section-card" href="relays.html">
<h3>Relays & connections</h3>
<p>Pools, reconnects, ack policies, event stores and the sliding window.</p>
</a>
<a class="section-card" href="fetch-subscribe.html">
<h3>Fetch & subscribe</h3>
<p>Fetch policies, defaults, EOSE: how reading events really works.</p>
</a>
<a class="section-card" href="signers.html">
<h3>Keys & signers</h3>
<p>Local, browser and remote signers; encrypted keys and messages.</p>
</a>
</div>

## Supported NIPs

<div class="section-grid">
<a class="section-card" href="nips.html">
<h3>Supported NIPs</h3>
<p>Every supported NIP, one by one, with a usage example each.</p>
</a>
</div>

## Wallet

<div class="section-grid">
<a class="section-card" href="wallets.html">
<h3>Wallets</h3>
<p>Connect a wallet over NWC, check the balance, pay invoices.</p>
</a>
</div>

## Media

<div class="section-grid">
<a class="section-card" href="blossom.html">
<h3>Blossom</h3>
<p>Upload and retrieve content-addressed media with Nostr authorization.</p>
</a>
</div>

## RTC

<div class="section-grid">
<a class="section-card" href="rtc-rooms.html">
<h3>RTC rooms</h3>
<p>Realtime peer-to-peer rooms: joining, channels, signaling, direct vs TURN, and how to tell which transport you are really on.</p>
</a>
<a class="section-card" href="onion-routing.html">
<h3>Onion routing</h3>
<p>Multi-hop messaging across the room mesh: transparent sends, topology snapshots, and the public routing pieces.</p>
</a>
<a class="section-card" href="turn-server.html">
<h3>TURN server</h3>
<p>Run and harden the WebSocket fallback service used when direct WebRTC is unavailable.</p>
</a>
</div>

## Reference

<div class="section-grid">
<a class="section-card" href="security.html">
<h3>Security</h3>
<p>Key handling, untrusted relay data and network protection.</p>
</a>
<a class="section-card" href="../api/index.html">
<h3>Javadoc</h3>
<p>Browse generated Javadoc for the complete public API surface.</p>
</a>
</div>

## If you just want to look around

The [live demos](../demos.md) run the real library in your browser (the Java code is compiled to JavaScript). The peer-to-peer game demonstrates RTC onion routing. The source is on [GitHub](https://github.com/NostrGameEngine/nostr4j).

## Conventions used in these pages

Code samples are plain Java and use the real class names from the library. Almost every call that touches the network returns an `AsyncTask`; chain the next step with `.then()` in applications and reserve `.get()` / `.await()` for tests and command-line tools. Amounts are millisatoshis unless a method explicitly says otherwise.
