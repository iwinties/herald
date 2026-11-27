---
id: MEM-herald-build-deps
title: 'Herald offline build: AAR resources, compile-only jars, Paho descriptor, HLS/DASH'
status: active
type: gotcha
scope: repo:herald
created: '2026-11-26'
last_verified: '2026-11-26'
verified_against: herald@29594790697550907d5803b7204988d562347915
sources:
- herald:tools/build.sh
- herald:tools/deps.lock
tags:
- herald
- build
---

Herald builds without Gradle in the herald-build:36-m3-1.11.1 container (tools/build.sh, verified
against the script on 2026-11-26 at main 2959479 and by the T12 release build).

- Media3 ExoPlayer is an AAR with resources: its `res/` is unpacked, compiled with aapt2 and linked
  with `--extra-packages androidx.media3.exoplayer`, or ExoPlayer's R class does not resolve
  (`CHECK exoplayer R resolves`).
- AAR classes.jar files go on the javac classpath; annotation-only jars are compile-only
  (`--classpath`) and are not dexed.
- Paho MQTT needs its `META-INF/services/org.eclipse.paho.client.mqttv3.spi.NetworkModuleFactory`
  descriptor copied into the APK, or no network module is found at connect time
  (`CHECK paho service descriptor`).
- HLS and DASH are separate Media3 modules (`HlsMediaSource`, `DashMediaSource`) and must both be in
  the APK (`CHECK hls and dash modules`).
- d8 runs with `--min-api 26`; build-tools 36.1.0 `aapt2 dump badging` prints `minSdkVersion:'26'`
  and targetSdkVersion 36 (`CHECK min 26 target 36`). All dependency files are pinned with sha256 in
  tools/deps.lock.

