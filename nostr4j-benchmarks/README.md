
```bash
./gradlew :nostr4j-benchmarks:scalabilityTest
```



```bash
./gradlew :nostr4j-benchmarks:jmh
```


```bash
./gradlew :nostr4j-benchmarks:jmh \
  -PjmhArgs='EventTrackerBenchmark -wi 8 -i 10 -f 3 -w 1s -r 1s -prof gc -rf json -rff results.json'
```
