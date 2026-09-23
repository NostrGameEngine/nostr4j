---
title: Platforms and setup
description: Set up Nostr4j on JVM, Android, iOS or the browser, including TeaVM JavaScript and WasmGC targets.
---

# Platforms & setup

Add the platform adapter for the environment where your app will run, so the library gets networking, cryptography and the other runtime services it needs. If you ship to several platforms, keep the shared application code in one module and put each adapter in its own platform module.

| Target | Dependency |
|---|---|
| Windows, Linux and macOS | `org.ngengine:nge-platform-jvm` |
| Android | `org.ngengine:nge-platform-android` |
| iOS | `org.ngengine:nge-platform-ios` |
| Browser | `org.ngengine:nge-platform-teavm` |

## JVM

With the JVM adapter on the classpath, platform discovery happens automatically. You can also initialize it explicitly at the start of your application before using any of the library's APIs.

```java
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;

NGEPlatform.set(new JVMAsyncPlatform());
```

The adapter requires Java 21 or newer. Set the platform once at application startup; it is shared by the whole process.

## Android

Add the Android adapter to your app module and declare `android.permission.INTERNET` in the manifest. Initialize it from `Application.onCreate()` using the application context:

```java
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.android.AndroidThreadedPlatform;

NGEPlatform.set(new AndroidThreadedPlatform(getApplicationContext()));
```

## iOS

Build the iOS application on macOS with Xcode and the [libJGLIOS toolchain](https://github.com/NostrGameEngine/libJGLIOS). Follow its setup instructions to configure the Gradle plugin, install native dependencies and build for a simulator or device.

With the iOS adapter in your app module, initialize it at startup:

```java
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.ios.IosPlatform;

NGEPlatform.set(new IosPlatform());
```

<span id="teavm"></span>

## Browser via TeaVM

Use the browser adapter with either JavaScript or WasmGC output. Initialize it from your Java entry point:

```java
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.teavm.TeaVMPlatform;

public static void main(String[] args) {
    NGEPlatform.set(new TeaVMPlatform());
    // Start the application here.
}
```

Choose the backend in your TeaVM Gradle configuration:

| Output | Configuration | Build task |
|---|---|---|
| JavaScript | `teavm.js` | `generateJavaScript` |
| WasmGC | `teavm.wasmGC` | `generateWasmGC` |

To set the entry point for a WasmGC application, use this in the TeaVM Gradle configuration:

```groovy
teavm.wasmGC {
    mainClass = 'example.Main'
}
```

These options belong to the [TeaVM Gradle plugin](https://teavm.org/docs/tooling/gradle.html). For WasmGC output, use a browser with WasmGC support. Deploy the `.wasm` file with its generated runtime and use the [TeaVM loader](https://teavm.org/docs/wasm-gc-backend/loader.html) to start it.

For JavaScript output, use ES2015 modules. Import the generated entry module and call its exported `main([])`. Include the platform bindings such as `org/ngengine/platform/teavm/TeaVMBinds.bundle.js` when packaging the output. For either target, serve the application over HTTP(S) and keep the generated runtime files and platform resources together.

## Local services

Loopback URIs are blocked by default. To use a local relay or another local service during JVM development, enable the override before platform initialization:

```bash
java -Dnge-platforms.allowLoopbackInURIs=true -jar app.jar
```

!!! warning
    Keep this setting limited to the development process, especially when an application accepts URLs from users.
