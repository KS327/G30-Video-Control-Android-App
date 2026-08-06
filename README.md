# TankerAMR G30 Operator App

Android operator app for Skydroid G30:

- Scans the 192.168.144.x camera subnet.
- Displays up to six RTSP cameras with focus, 2×2 and 3×2 views.
- Uses the C12 visible stream on port 554 and thermal stream on port 555.
- Shows speed, roll and pitch telemetry received from Jetson.
- Provides CH05-gated LOCAL/INTERNET manual routing and LOCAL-only AUTONOMOUS controls.
- Queues at most six immediate 90-degree turn taps, including the active turn.

## Safety and control model

- CH05 up: manual control. LOCAL is the default; INTERNET requires arming.
- CH05 middle: hard-safe state. Arms and queued turns are cleared; LOCAL is restored after 0.5 seconds.
- CH05 down: AUTONOMOUS only. Leaving down always disarms it, but a READY process remains running.
- `START AUTONOMOUS` is available only in middle/down and changes to `STOP AUTONOMOUS` after startup.
- LOCAL Jetson command endpoint: `192.168.144.20:5005`.
- Jetson telemetry listener: UDP port `5006`.
- INTERNET address is explicitly configured by the operator; the app never guesses a Tailscale IP.

Commands and telemetry use versioned JSON (`v: 1`). Telemetry payload fields are `speed_mps`,
`roll_deg`, `pitch_deg`, and `source`. Telemetry becomes stale after 1.5 seconds and the UI displays `—`.

## Debug simulator

Debug APKs never send Tanker movement commands. Tap the amber control-state label and select simulated
CH05 up, middle, or down to test the complete G30 workflow without a powered TankerAMR. The screen is
clearly marked `SIMULATOR` / `SIM CH05 OVERRIDE`. Release builds use real RCSDK channels and UDP transport.

The connected G30 currently reports output CH05 as a constant 1500 through RCSDK even when the physical
switch is moved. Validate the authoritative CH05 mapping against Jetson SBUS telemetry during integrated
TankerAMR commissioning before enabling a release build.

## Required AAR files

Copy these proprietary Skydroid AAR files into `app/libs/` before building:

- `fpvplayer-v3.3.9.aar`
- `sky-ijkplayer-v1.1.aar`
- `rcsdk-v1.9.2.aar`
- `h16_airlink.aar`

## Default camera URLs

The app default manual URLs use the confirmed JINGYANG/XM pattern with stream=1:

`rtsp://admin:@<IP>:554/user=admin&password=&channel=1&stream=1.sdp`

Default camera order:

1. FRONT C12 (`192.168.144.108`)
2. CAMERA 2
3. CAMERA 3
4. CAMERA 4
5. CAMERA 5 (optional)
6. CAMERA 6 (optional)

Use stream=0 if maximum quality is required and the G30 can decode all four streams stably.

## Jetson secondary IP service

Files are included under `jetson/`:

- `add_tankeramr_camera_ip.sh`
- `tankeramr-camera-ip.service`

Install on Jetson:

```bash
sudo cp jetson/add_tankeramr_camera_ip.sh /usr/local/sbin/add_tankeramr_camera_ip.sh
sudo chmod +x /usr/local/sbin/add_tankeramr_camera_ip.sh
sudo cp jetson/tankeramr-camera-ip.service /etc/systemd/system/tankeramr-camera-ip.service
sudo systemctl daemon-reload
sudo systemctl enable tankeramr-camera-ip.service
sudo systemctl start tankeramr-camera-ip.service
ip -4 addr show dev enP8p1s0
```

Expected Ethernet addresses after boot:

- `192.168.1.50/24` for LiDAR
- `192.168.144.20/24` for cameras and G30 UDP control

No gateway is added for 192.168.144.20.
