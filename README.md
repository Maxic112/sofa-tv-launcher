# Sofa TV Launcher

A lightweight home screen for Android TV with remote key remapping built in.

[Русская версия](README.ru.md)

Sofa TV replaces three apps at once: the launcher, a button mapper and a sleep timer. It is
built for low-end TVs and boxes with 2 GB of RAM or less: plain Android views, no Compose or
Leanback, one process, software rendering by default. On a 2 GB TCL TV it uses about 25 MB
(PSS) while on screen.

## Features

- **Home screen.** Favorites on top, then every app in alphabetical order. 16:9 tiles that use
  the app's TV banner, or a frame with its icon. Grid columns and tile height are adjustable.
  Long-press OK on a tile to favorite, move, hide, open app info or uninstall.
- **Remote key remapping.** Press any key the accessibility service can see, then assign
  actions to its single press, double press and hold. Actions include launching an app,
  a shortcut, a custom intent or link, system actions (back, recents, notifications, volume,
  media keys, power menu, sleep) and the launcher's own actions. Per-app exclusions.
  Hold Volume − for 7 seconds to turn remapping off if something goes wrong.
- **Home key interception.** Home opens Sofa TV without disabling the stock launcher.
- **Status panel.** A line on the home screen and an optional overlay over other apps: clock,
  sleep timer, RAM, swap, storage, CPU temperature and frequency, network speed, Wi-Fi or
  Ethernet, IP address.
- **Sleep timer** with a one-minute warning that lets you extend it.
- **Memory cleanup**: standard background kill, TCL Safety Guard, or force stop through
  system settings (done by the accessibility service, no root).
- **Languages:** English and Russian; follows the system by default, can be set in settings.
- **Updates from GitHub Releases.** Checked once a day; when a new version is out, an
  "Update" button with an orange dot appears on the home screen. Settings → Updates shows the
  version, checks on demand and installs the update.

## Requirements

- Android TV 7.0 or newer (API 24+).
- Key remapping, Home interception, the overlay panel, the sleep timer and force stop need
  the accessibility service "Sofa TV: remote keys" to be turned on in the TV's accessibility
  settings. The home screen itself works without it.

## Install

Download the APK from [Releases](../../releases) and install it, for example over ADB:

```bash
adb connect <tv-ip>:5555
adb -s <tv-ip>:5555 install -r sofa-tv-launcher.apk
```

Then press Home and pick Sofa TV, or enable "Intercept Home" in its settings.

Later versions install from the app itself (Settings → Updates). The first time, Android asks
to allow Sofa TV to install apps.

## Build

Needs JDK 17+ and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```bash
./build.sh                 # release APK in app/build/outputs/apk/release/
./build.sh install <tv-ip> # build, install over ADB and open
```

Release signing reads `sofatv.storeFile`, `sofatv.storePassword`, `sofatv.keyAlias` and
`sofatv.keyPassword` from `~/.gradle/gradle.properties`. Without them the release build is
signed with the debug key.

The update check URL defaults to this repository's latest GitHub release. A test build can
point it at a local server serving the same JSON: `-Psofatv.updateUrl=http://<ip>:8000/latest.json`.

## Privacy

Sofa TV has no analytics and no ads. Its only network access is the daily update check on
GitHub. See [PRIVACY.md](PRIVACY.md).

## License

[Apache License 2.0](LICENSE). Icons are Material Symbols by Google, also Apache 2.0
(see [NOTICE](NOTICE)).
