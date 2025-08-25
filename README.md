# Nostr4j

A high-performance Nostr library for the JVM, designed to be high throughput, memory efficient, and transpilable to JavaScript.

The library is designed to be flexible and extensible. It provides some key abstractions to make development easier, but does not enforce any specific architecture or design, while also exposing the low-leve building blocks on top of which the abstractions are built.

The library has built-in support for:

- nips: 01, 04, 05, 07, 09, 24, 39, 40, 44, 46, 47, 49, 50
- managed nwc wallets
- wallet abstraction
- signer abstraction
- configurable ack policies (with some default implementations such as: quorum, all, any)
- automatic relays lifecycle management
- webrtc over nostr
- blossom 

and more...


## Usage

```gradle
repositories {
    mavenCentral()
    // Uncomment this if you want to use a -SNAPSHOT version
    //maven { 
    //    url = uri("https://central.sonatype.com/repository/maven-snapshots")
    //}
}

dependencies {
    implementation 'org.ngengine:nostr4j:<version>'
}
```

as `<version>` use one of the versions listed in the [releases page](/releases) or `0.0.0-SNAPSHOT` for the latest snapshot.