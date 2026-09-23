---
title: Platforms and setup
description: Choose and initialize the NGE platform on JVM, Android, iOS and TeaVM.
---

# Platforms & setup

Nostr4J contains protocol logic; `nge-platform` supplies networking, cryptography, scheduling and WebRTC for the runtime that actually executes it. Add exactly one platform implementation to an application.

| Target | Platform artifact | Runtime |
|---|---|---|
| Desktop / server JVM | `org.ngengine:nge-platform-jvm:0.2.4` | Java 21+ |
| Android | `org.ngengine:nge-platform-android:0.2.4` | Android API 33+ toolchain |
| iOS | `org.ngengine:nge-platform-ios:0.2.4` | macOS, Xcode, libJGLIOS |
| Browser via TeaVM | `org.ngengine:nge-platform-teavm:0.2.4` | Browser JavaScript |

The core `org.ngengine:nostr4j:0.3.1` artifact targets Java 11 bytecode. The higher JVM requirement comes from `nge-platform-jvm`, which uses newer runtime facilities.

## JVM

The JVM implementation is discovered automatically when it is on the classpath. Explicit initialization makes startup order clear and must happen before a key, relay or wallet asks for platform services:

```java
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;

NGEPlatform.set(new JVMAsyncPlatform());
```

`NGEPlatform.set(...)` is process-global and can only be called once. Libraries should not replace a platform chosen by the application.

## Android

Add `org.ngengine:nge-platform-android:0.2.4` to the Android app module and declare `android.permission.INTERNET` in the manifest. Initialize the adapter once, from `Application.onCreate()`, before creating a client:

```java
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.android.AndroidThreadedPlatform;

NGEPlatform.set(new AndroidThreadedPlatform(getApplicationContext()));
```

## iOS

Add `org.ngengine:nge-platform-ios:0.2.4` to the app module. Use the [libJGLIOS native toolchain](https://github.com/NostrGameEngine/libJGLIOS) to compile and package the Java application for iOS; it requires macOS, Xcode and JDK 21. Configure the `org.ngengine.libjglios` Gradle plugin with your `mainClass`, `bundleId` and `appName`, then use its simulator or device build tasks. The [libJGLIOS README](https://github.com/NostrGameEngine/libJGLIOS#readme) lists the plugin, native dependencies and build commands.

Initialize the iOS adapter at application startup, before creating a key, relay or wallet:

```java
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.ios.IosPlatform;

NGEPlatform.set(new IosPlatform());
```

## TeaVM

Add the TeaVM platform and initialize it from the browser entry point:

```java
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.teavm.TeaVMPlatform;

public static void main(String[] args) {
    NGEPlatform.set(new TeaVMPlatform());
    // construct Nostr4J objects after this point
}
```

TeaVM has no general Java reflection. Use explicit browser bridges such as `@JSBody` and `@JSFunctor` when interacting with JavaScript.

Configure TeaVM for ES2015 modules. Ship **the entire generated output directory**, including `org/ngengine/platform/teavm/TeaVMBinds.bundle.js`, not just the entry module. Import the generated ES module and call its exported `main([])`. Serve it over HTTP(S), not `file://`.

Bridge function interfaces must extend `JSObject` and carry `@JSFunctor`. An entry point invoked directly by JavaScript cannot suspend: dispatch work onto `NGEPlatform.get().newAsyncExecutor()` before using `AsyncTask.await()`, then close the executor. These rules are exercised by the [runnable quick start](examples.md#runnable-quick-start).

## Local services

The platform rejects loopback URIs by default to reduce server-side request forgery and accidental access to services on the user's machine. Enable loopback only for a controlled development process and before platform initialization:

```bash
java -Dnge-platforms.allowLoopbackInURIs=true -jar app.jar
```

Do not carry that override into a public server or an untrusted browser workflow.

## Snapshot builds

Snapshot builds also need the Sonatype snapshot repository:

```gradle
repositories {
    mavenCentral()
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots") }
}
```

For releases, pin released versions and resolve them from Maven Central.

## Where next

- [Getting started](getting-started.md)
- [Troubleshooting](troubleshooting.md)
- [Security](security.md)
