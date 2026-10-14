# Open PowerWatch Sync clean-room Android utility

A minimal Android BLE rescue utility for legacy PowerWatch / PowerWatch X time sync.

This project is intentionally separate from the patched legacy APK work. It contains:

- no original APK;
- no vendor assets;
- no decompiled source files;
- no hardcoded private MAC or serial.

## Goal

- scan/select a PowerWatch-like BLE device;
- connect to the vendor BLE service;
- send current UTC + phone timezone;
- save settings;
- send normal exit/SOFF;
- show a visible Sync OK / error log.

## Build

Install Android Studio or Android SDK commandline tools, then run:

```bash
gradle assembleDebug
```

For local/offline builds, create `local.properties` with your Android SDK path:

```properties
sdk.dir=/path/to/android-sdk
```

## Safety

The app does not send factory reset, shipmode, or debugger-lock commands.
