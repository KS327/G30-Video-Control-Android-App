# TankerAMR G30 Operator App

Android operator app for the Skydroid G30 controller. This source is version **2.5.1**, version code **27**, with application ID `sg.hexcel.tankeramr`.

## Main functions

- C12 visible and thermal video, plus the front-middle, back-left and back-right IP cameras.
- Four-camera and focused viewing, camera address editing and camera discovery.
- Jetson telemetry including speed, roll, pitch, yaw and sensor/SiDex status.
- Local RF and Internet control status, fixed-angle turn requests and C12 gimbal controls.
- SiDex start/stop confirmation, course mapping and a collision-monitor toggle with Jetson acknowledgement.

## Control modes

The Jetson's SBUS telemetry is the authority for CH05:

- **Up:** local RF control and SiDex controls. Starting SiDex requires operator confirmation and Jetson readiness.
- **Middle:** drive disabled; use this position to stop the robot immediately.
- **Down:** Internet manual control, which must be armed before driving.

The app sends commands to the editable Jetson destination on UDP 5005 and receives telemetry on UDP 5006. Robot services, route configuration and collision processing run on Jetson and are maintained separately.

## Open in Android Studio

1. Download or clone this repository to the development laptop.
2. In Android Studio, select **Open** and choose the folder containing `settings.gradle` and `gradlew.bat`.
3. Set the Android SDK location for this computer. Android Studio creates `local.properties`; this file is intentionally excluded from Git.
4. Use **Java 21**, Android SDK **35**, and Build Tools **34.0.0**. Keep the supplied Gradle wrapper **9.3.0**, Android Gradle Plugin **8.5.2** and Kotlin **1.9.24** when reproducing this version.
5. Select the **commissioning** build variant for a real G30. The **debug** variant enables the simulator and is not the real-controller build.

## Test and build on Windows

Open PowerShell in the project folder after configuring Java 21 and the Android SDK:

```powershell
.\gradlew.bat testCommissioningUnitTest assembleCommissioning
```

The installer is generated at `app/build/outputs/apk/commissioning/app-commissioning.apk`. Building does not install the app or move the robot.

Commissioning uses the developer debug-signing configuration. Obtain the matching company signing key through the approved handover process when an in-place update of the installed G30 app is required. Signing keys are not stored in this repository.

## Required Skydroid libraries

The existing project includes these dependencies in `app/libs/`:

- `fpvplayer-v3.3.9.aar`
- `sky-ijkplayer-v1.1.aar`
- `rcsdk-v1.9.2.aar`
- `h16_airlink.aar`

These are vendor libraries, not project-owned source. Use them under the applicable vendor terms.

## Files kept out of Git

`.gitignore` excludes Android Studio settings, local SDK paths, generated builds, APKs, Gradle caches and signing-key files. The repository keeps source code, resources, unit tests, build configuration, Gradle wrapper and required libraries. It does not contain the Jetson installation or the separate company recovery backup.
