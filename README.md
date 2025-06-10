# Nostr4j

A high-performance Nostr library for the JVM, crosscompilable to javascript.


## Usage

```gradle
repositories {
    mavenCentral()
    // Uncomment this if you want to use a -SNAPSHOT version
    //maven { 
    //    url = uri("https://central.sonatype.com/repository/maven-snapshots")
    //}
    maven {
        url = "https://maven.rblb.it/NostrGameEngine/libdatachannel-java"
    }
}

dependencies {
    implementation 'org.ngengine:nostr4j:<version>'
}
```

as `<version>` use one of the versions listed in the [releases page](/releases) or `0.0.0-SNAPSHOT` for the latest snapshot.