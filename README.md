# TankerAMR G30 Operator App

Android operator app for Skydroid G30:

- Scans the 192.168.144.x camera subnet.
- Displays up to six RTSP cameras with focus, 2×2 and 3×2 views.
- Uses the C12 visible stream on port 554 and thermal stream on port 555.
- Shows speed, roll and pitch telemetry received from Jetson.
- Provides CH05-gated LOCAL/INTERNET manual routing and LOCAL-only AUTONOMOUS controls.
- Shows the authoritative number of pending 90-degree turns and queues at most six, including the active turn.

## Safety and control model

- CH05 up: manual control. LOCAL is the default; INTERNET requires arming.
- CH05 middle: hard-safe state. Arms and queued turns are cleared; LOCAL is restored after 0.5 seconds.
- CH05 down: AUTONOMOUS only. Leaving down always disarms it, but a READY process remains running.
- `START AUTONOMOUS` is hidden while CH05 is up, requires confirmation, and changes to `STOP AUTONOMOUS` only after Jetson reports READY.
- Start/stop completion dialogs use authoritative Jetson state. Stopping Nav2 and Collision Monitor leaves Livox, FAST-LIO, speed and tilt telemetry active.
- LOCAL/INTERNET route changes are sent through the currently reachable route; returning to LOCAL also sends a recovery copy to the configured LAN address.
- LOCAL Jetson command endpoint: `192.168.144.20:5005`.
- Jetson telemetry listener: UDP port `5006`.
- INTERNET address is explicitly configured by the operator; the app never guesses a Tailscale IP.

Commands and telemetry use versioned JSON (`v: 1`). Telemetry payload fields are `speed_mps`,
`roll_deg`, `pitch_deg`, and `source`. Telemetry becomes stale after 1.5 seconds and the UI displays `—`.

## Debug simulator

Debug APKs never send Tanker movement commands. Tap the amber control-state label and select simulated
CH05 up, middle, or down to test the complete G30 workflow without a powered TankerAMR. The screen is
clearly marked `SIMULATOR` / `SIM CH05 OVERRIDE`. Commissioning and release builds trust authoritative
Jetson SBUS telemetry for CH05; RCSDK channels are sent only for armed INTERNET manual control.

The connected G30 reports CH05 unreliably through RCSDK. Jetson SBUS is the safety authority:
282=AUTONOMOUS/down, 1002=disabled/middle, and 1722=manual/up.

## Required AAR files

Copy these proprietary Skydroid AAR files into `app/libs/` before building:

- `fpvplayer-v3.3.9.aar`
- `sky-ijkplayer-v1.1.aar`
- `rcsdk-v1.9.2.aar`
- `h16_airlink.aar`

## Default camera URLs

The four installed IP cameras use their verified HEVC 1920x1080 @ 25 fps main stream:

`rtsp://admin:@<IP>:554/user=admin&password=&channel=1&stream=0.sdp`

Default camera order:

1. FRONT C12 (`192.168.144.108`)
2. CAMERA 2 (`192.168.144.100`)
3. CAMERA 3 (`192.168.144.110`)
4. CAMERA 4 (`192.168.144.130`)
5. CAMERA 5 (`192.168.144.120`)
6. CAMERA 6 (reserved)

C12 starts on visible video after every fresh app launch. The CAMERAS editor provides
`RESTORE VERIFIED` to recover the unique 1080p camera mapping.

## Jetson network and runtime

Expected Ethernet addresses:

- `192.168.1.50/24` for LiDAR
- `192.168.144.20/24` for cameras and G30 UDP control

No gateway is added for 192.168.144.20.

The deployed runtime is organized under `/home/jetson/KinSen`, with settings in
`config/settings.json`, runtime state in `run/`, logs in `logs/`, and timestamped rollback copies in
`backups/`. Livox, FAST-LIO and scan conversion are always-on telemetry services. Nav2 and Collision
Monitor are separately managed by START/STOP AUTONOMOUS.
