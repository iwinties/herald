---
id: MEM-herald-onn-setup
title: 'Herald on the onn 4K Pro: grants, sleep, overlays, focus, RTSP, ADB'
status: active
type: gotcha
scope: repo:herald
created: '2026-11-26'
last_verified: '2026-11-26'
verified_against: herald@29594790697550907d5803b7204988d562347915
sources:
- herald:app/src/io/github/iwinties/herald/HeraldService.java
- herald:docs/SETUP.md
- herald:tools/adb.sh
tags:
- herald
- onn
---

Observed on an onn 4K Pro (Android 14 / API 34) with the 0.1.0 and 0.2.0 builds (2026-11-26).

- Install needs three grants beyond `pm install`: `appops set io.github.iwinties.herald
  SYSTEM_ALERT_WINDOW allow` (cards and the pairing code are overlays), `dumpsys deviceidle whitelist
  +io.github.iwinties.herald` (Doze exemption) and, on API 33+, `pm grant ... POST_NOTIFICATIONS`.
  Herald starts itself at boot: /v1/info answered 59 s after a reboot with no manual start.
- Doze/sleep: with the exemption, Herald's MQTT connection stayed up for 10 minutes with the box
  asleep, and incoming HTTP works while asleep.
- Overlays draw at most about 80 % opaque on Android 12+ (pass-through overlay cap), even with a solid
  background colour.
- Focus: a card with buttons takes focus without pausing YouTube; `sound_playing` turns false about
  6 s after a pause; duck dips YouTube without pausing it.
- Chime then voice: with the voice prepared before the chime the gap between their starts is about
  1270 ms (chime 1070 ms + 200 ms pause) on the TV and on the box speaker.
- RTSP: UDP RTP never reaches the box from Docker-published RTSP servers (12 s timeouts); the TCP
  default plays (first frame about 4.5 s cold, about 0.3 s if the stream was opened seconds earlier).
- ADB: `tools/adb.sh` runs its own adb server in the build container (port 5038, key in
  ~/.config/herald-adb, herald `out/` mounted at /apk). A key paired through Wireless debugging also
  works on the classic port 5555, and Wireless debugging can be switched off again afterwards.
  "Revoke USB debugging authorizations" sits directly above "Wireless debugging" in Developer
  options: check the focused item before pressing select.
