# TankerAMR G30 Video Control Final

Final Android app for Skydroid G30:

- Scans the 192.168.144.x camera subnet.
- Displays four JINGYANG/XM RTSP camera streams.
- Uses UDP only for L1/L2/R1/R2 TankerAMR turn activation commands.
- Default UDP target: 192.168.144.20:5005.
- Bottom button order: L2, L1, UDP target, R1, R2.

## Required AAR files

Copy these proprietary Skydroid AAR files into `app/libs/` before building:

- `fpvplayer-v3.3.9.aar`
- `sky-ijkplayer-v1.1.aar`
- `rcsdk-v1.9.2.aar`
- `h16_airlink.aar`

## Default camera URLs

The app default manual URLs use the confirmed JINGYANG/XM pattern with stream=1:

`rtsp://admin:@<IP>:554/user=admin&password=&channel=1&stream=1.sdp`

Default order:

1. 192.168.144.100
2. 192.168.144.110
3. 192.168.144.130
4. 192.168.144.120

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
