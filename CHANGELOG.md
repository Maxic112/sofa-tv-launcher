# Changelog

## 0.15.0 — 2026-10-26

- TV inputs: Settings → Home screen → Inputs. "Choose input" opens the firmware's input picker
  (`com.android.tv.action.VIEW_INPUTS`), an "Inputs" button in the header (on by default) does the same,
  and a row of input tiles (HDMI, tuner, AV) can be shown above or below the favorites.
- The "App list" settings section is now called "Home screen".
- Settings → Home screen: switches to hide the "Sleep timer" and "Clean memory" header buttons.
- New remote key actions: "Sofa TV: choose input" (the input picker) and "TV input…" (switch
  straight to a chosen input, e.g. HDMI 2).

## 0.14.2 — 2026-10-25

- Updates from GitHub Releases: a daily check, an "Update" button with an orange dot on the home
  screen when a new version is out, and Settings → Updates (version, check for updates,
  update and install). The version row moved there from the settings root.

## 0.13.0 — 2026-10-25

- Renamed from Easy TV to **Sofa TV Launcher**; the package is now `dev.sofatv.launcher`.
  It installs as a new app: settings from Easy TV are not carried over.
- English interface by default, Russian translation; a "Language" setting
  (system default, English, Русский).
- Prepared for publishing: English code comments, README, license, privacy policy.

## 0.12.2 and earlier

Developed privately as Easy TV: home screen with favorites and adjustable grid, key remapper,
Home interception, status panel, sleep timer, memory cleanup (standard, Safety Guard, force stop),
RAM usage page.
