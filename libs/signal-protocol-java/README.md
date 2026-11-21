# signal-protocol-java 2.6.2 (vendored)

This is the library behind legacy OMEMO (v0.3, `eu.siacs.conversations.crypto.axolotl`). It replaces
the Maven artifact `org.whispersystems:signal-protocol-java:2.6.2`. PQ OMEMO2 does not use it; that
lives in `deps/libsignal`.

## Provenance

- Upstream: <https://github.com/signalapp/libsignal-protocol-java>, commit
  `4f5e1ff299cea22cc75bb97249020a7da67b816d` ("Bump version to 2.6.2", 2017-07-12). The repository
  has no tag for 2.6.2.
- `src/main/java` comes from upstream `java/src/main/java` at that commit. It is byte-identical to
  the published `signal-protocol-java-2.6.2-sources.jar`, except for the three generated files
  below.
- `src/main/proto` holds upstream's `protobuf/*.proto` from that commit, unchanged.
- `src/test/java` holds upstream's `java/src/test` and `tests/src/test` from that commit. The only
  change is that an unused `android.util.Log` import was dropped from
  `NumericFingerprintGeneratorTest`.
- License: GPLv3 (`LICENSE`), which may be combined with the app's AGPLv3 (GPLv3 §13).

## The one deviation: regenerated protobuf code

`SignalProtos.java`, `StorageProtos.java` and `FingerprintProtos.java` are regenerated with
**protoc 25.9**, the compiler that matches the `protobuf-java` 3.25.9 runtime. Upstream's code came
from protoc 2.5.

- **Why:** CVE-2022-3171 (GHSA-h4h5-3hr4-j3g2) is a GC-pause DoS on crafted input. It sits in
  pre-21.7 *generated code*, so the runtime cannot fix it. The code parses peer-supplied legacy
  OMEMO `<key>` payloads. The old runtime, protobuf-java 2.5.0, additionally had CVE-2021-22569 and
  GHSA-735f-pc8j-v9w8.
- **Wire and storage format: unchanged.** The `.proto` files are untouched.
  `LegacyCompatibilityTest` checks this in three ways:
  - each regenerated file's `FileDescriptorProto` must equal the one embedded in the old gencode;
  - identities, session records, pre-key records and undelivered messages written by the original
    jar on protobuf 2.5 (`src/test/resources/legacy-2.6.2/`) must load and continue both ways;
  - fingerprints must be unchanged.
- The module's tests run with `-Dcom.google.protobuf.error_on_unsafe_pre22_gencode=true`, so any
  leftover old gencode makes them fail.

## Regenerating

```sh
libs/signal-protocol-java/regenerate-protos.sh
```

The script downloads protoc 25.9 from Maven Central and verifies its pinned SHA-256. Those are the
same bytes as the `protocolbuffers/protobuf` v25.9 GitHub release. It then writes the three files
into `src/main/java`. Gencode must never be newer than the runtime: bump `protobuf-java` in
`build.gradle` and protoc in the script together. Moving to protobuf-java 4.x needs protoc 26+.

## Tests

```sh
./gradlew :libs:signal-protocol-java:test
```

`DeviceConsistencyTest` is excluded. It needs VRF signatures, which only the native curve25519
provider implements, and upstream ran it on Android. OMEMO doesn't use device consistency.

Do not add the Maven artifact `org.whispersystems:signal-protocol-java` back: it would duplicate
these classes.
