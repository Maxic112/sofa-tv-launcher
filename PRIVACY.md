# Privacy

Sofa TV Launcher does not collect, store or send any personal data.

- The app has no analytics, crash reporting or ads.
- The only network request is the update check: once a day (and when you press "Check for
  updates") the app asks the GitHub API for the latest release of
  https://github.com/Maxic112/sofa-tv-launcher and, when you choose to update, downloads its APK
  from GitHub. No data about you or the device is sent beyond what any HTTP request carries
  (IP address, the app version in the User-Agent); GitHub's own privacy policy applies to it.
- Settings (favorites, hidden apps, key rules, preferences) are stored only on the device,
  in the app's private storage.

## Accessibility service

The optional accessibility service "Sofa TV: remote keys" is used only for features you turn on:

- **Key events** are received to intercept Home and to run the actions you assigned to keys.
  They are processed on the device and not stored, except for the key codes you add in settings.
- **The name of the app on screen** is used for per-app exclusions, the "previous app" action
  and to keep the current app untouched during memory cleanup.
- **Screen content** is read only while memory cleanup runs in "force stop" mode, to find and
  press the "Force stop" and "OK" buttons on app pages in system settings.

## Other permissions

- Network state and Wi-Fi state: to show the IP address, connection type and Wi-Fi signal
  in the status panel.
- Kill background processes: for the standard memory cleanup method.
- Request install packages: to install a downloaded update (Android asks you to allow it first).
- Request delete packages: to uninstall an app from the tile menu (the system asks for
  confirmation).
