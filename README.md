<a href="https://github.com/oxyroid/M3UAndroid">
  <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://socialify.git.ci/oxyroid/M3UAndroid/image?font=Raleway&forks=1&issues=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2Foxyroid%2FM3UAndroid%2Fmaster%2Fapp%2Fsmartphone%2Ficon.png&name=1&pattern=Plus&pulls=1&stargazers=1&theme=Dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://socialify.git.ci/oxyroid/M3UAndroid/image?font=Raleway&forks=1&issues=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2Foxyroid%2FM3UAndroid%2Fmaster%2Fapp%2Fsmartphone%2Ficon.png&name=1&pattern=Plus&pulls=1&stargazers=1&theme=Light" />
   <img alt="Star History Chart" src="https://socialify.git.ci/oxyroid/M3UAndroid/image?font=Raleway&forks=1&issues=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2Foxyroid%2FM3UAndroid%2Fmaster%2Fapp%2Fsmartphone%2Ficon.png&name=1&pattern=Plus&pulls=1&stargazers=1" />
    
    <source src="https://socialify.git.ci/oxyroid/M3UAndroid/image?font=Raleway&forks=1&issues=1&logo=https%3A%2F%2Fraw.githubusercontent.com%2Foxyroid%2FM3UAndroid%2Fmaster%2Fapp%2Fsmartphone%2Ficon.png&name=1&pattern=Plus&pulls=1&stargazers=1&theme=Auto" alt="M3UAndroid" width="640" height="320" />
  </picture>
</a>

<a href="https://t.me/m3u_android"><img src="https://img.shields.io/badge/Telegram-Channel-2CA5E0?style=flat&logo=telegram"></a>
<a href="https://t.me/m3u_android_chat"><img src="https://img.shields.io/badge/Telegram-Discussion-2CA5E0?style=flat&logo=telegram"></a>
![GitHub release](https://img.shields.io/github/v/release/oxyroid/M3UAndroid?color=blue)
![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android)

**M3UAndroid** is a feature-rich streaming media player built with modern Android development practices. Perfect for phones, tablets, and TV devices, delivering a seamless viewing experience powered by Jetpack Compose.

## ✨ Key Features

- 📺 Adaptive UI for mobile & TV
- 🎭 DLNA + Chromecast sender support
- 🔍 Smart stream analysis
- 🌐 Xtream protocol compatibility
- 📥 Playlist management
- 🚀 Lightweight & ad-free
- 🇺🇳 Multi-language support

## 📸 Screenshots

| Mobile Experience | TV Experience |
|--------------------|---------------|
| <img src=".github/images/phone/deviceframes.png" width="400"> | <img src=".github/images/tv/playlist.png" width="400"> |
|  | <img src=".github/images/tv/foryou.png" width="400"> |
|  | <img src=".github/images/tv/player.png" width="400"> |

> TV UI is going to be remade in the future...

## ⬇️ Download Now
[![Recommand - Telegram Channel](https://img.shields.io/badge/Telegram-Channel-2CA5E0?style=for-the-badge&logo=telegram)](https://t.me/m3u_android)
[![GitHub Release](https://img.shields.io/badge/Download-GitHub%20Release-black?style=for-the-badge&logo=github)](https://github.com/oxyroid/M3UAndroid/releases/latest)
[![F-Droid](https://img.shields.io/badge/Download-F--Droid-1976D2?style=for-the-badge&logo=android)](https://f-droid.org/packages/com.m3u.androidApp)
[![IzzyOnDroid](https://img.shields.io/badge/Download-IzzyOnDroid-8A4182?style=for-the-badge)](https://apt.izzysoft.de/fdroid/index/apk/com.m3u.androidApp)

**Nightly Builds**: [Pre-release Packages](https://nightly.link/oxyroid/M3UAndroid/workflows/android/master/artifact.zip)

## 📺 TV and casting setup (Google TV, Samsung TV, Chromecast)

There are **three different scenarios** to keep separate:

1. **Google TV / Android TV devices**
   - Supported today by installing the TV APK from this repo
   - This includes devices such as **Chromecast with Google TV** and **Google TV Streamer**
2. **Samsung TV**
   - Supported today through the smartphone app's **experimental DLNA** flow if the TV exposes a DLNA/UPnP renderer
3. **Mobile app -> Chromecast receiver (Google Cast)**
   - Supported today for the phone app's **Google Cast sender** flow
   - Best results are with normal `http` or `https` streams that do not depend on DRM or custom request headers

If your goal is **cast from the mobile app to a Chromecast dongle or Google Cast receiver**, skip to **Path C** below first.

### Path A: Google TV / Android TV devices

If your device runs **Google TV** or **Android TV**, you can install the TV app directly.

#### What you need

- A Windows PC on the same network as your TV device
- A Google TV / Android TV device
- Developer options enabled on that device
- One playlist source to test with:
  - M3U title + URL
  - Xtream title + basic server URL + username + password
  - Optional EPG URL if you want guide data

#### Step 1: turn on developer options and debugging

1. Open `Settings` on the TV device.
2. On **Google TV**, go to `System` -> `About` -> `Android TV OS build`.
3. On **classic Android TV**, go to `Device Preferences` -> `About` -> `Build`.
4. Click that build entry several times until developer options are enabled.
5. Open `Developer options` (`Settings` -> `System` -> `Developer options` on Google TV).
6. Turn on `Network debugging` or `USB debugging`.
7. Find the TV IP address from the network settings screen.

If the TV shows a trust or RSA prompt later, choose `Allow`.

#### Step 2: install with the one-click script

The easiest first-time path is to double-click:

```text
scripts\install-tv.cmd
```

The script will guide you, ask for the TV IP if needed, connect with ADB, install the APK, and optionally launch the app.
If no `app\tv\build\outputs\apk\debug\tv-*.apk` file exists yet, the PowerShell installer automatically runs `:app:tv:assembleDebug` first.
If that build fails, the script stops and leaves the error message on screen.

If you prefer to run it from a terminal, use:

```powershell
.\scripts\install-tv.cmd -TvIp 192.168.1.50 -Build -Launch
```

Use a preview first if you want to see exactly what will happen without changing anything:

```powershell
.\scripts\install-tv.cmd -TvIp 192.168.1.50 -Build -Launch -DryRun
```

Useful options:

- `-Build` builds `:app:tv:assembleDebug` before installing
- `-Launch` opens the TV app after install
- `-UninstallFirst` removes the old app before reinstalling
- `-DryRun` prints the commands without executing them
- `-SkipConnect` skips `adb connect` and uses an already-connected device

#### Step 3: manual install if you want full control

If you want to do everything manually, the generated TV APK lives at:

```text
app\tv\build\outputs\apk\debug\tv-<version>.apk
```

Example commands on Windows:

```powershell
$adb = 'C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe'
& $adb connect 192.168.1.50:5555
$apk = Get-ChildItem '.\app\tv\build\outputs\apk\debug\tv-*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
& $adb install -r $apk.FullName
```

#### Step 4: first run inside the app

After install, open `M3UAndroid`, move to the user icon in the top-right corner, and choose:

```text
Subscribe
```

Then choose one of the supported source types:

- `M3U`
  - Enter a title
  - Enter the playlist URL
- `EPG`
  - Enter a title
  - Enter the EPG URL
- `Xtream`
  - Enter a title
  - Enter the `Basic URL` in the form `http://server:port`
  - Enter username
  - Enter password

For Xtream, use the server root URL, not a full `player_api.php` or `get.php` URL.

#### Step 5: what to expect after import

Once your data is imported:

- `Live TV` items play directly
- `Movies` open their detail page before playback
- `Series` open a detail page with metadata and episode buttons

The TV slice added in this repo specifically improves Xtream `Movies` and `Series` browsing and episode playback.

#### Google TV troubleshooting

- If `adb` is not found, set `ANDROID_HOME` or install Android platform-tools.
- If `adb install` says `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall first and retry:

```powershell
.\scripts\install-tv.cmd -TvIp 192.168.1.50 -UninstallFirst -Launch
```

- If the TV does not appear to connect, confirm:
  - the PC and TV are on the same network
  - debugging is enabled on the TV
  - you accepted the trust prompt on the TV
- If the installer mentions authorization or the device stays unavailable, look at the TV screen and accept the debugging prompt before retrying.
- If network debugging is unavailable on your TV, use USB debugging or sideload the APK with a USB drive and a file manager.
- If you only want to preview what the installer will do, run with `-DryRun` first.

### Path B: Samsung TV (current smartphone DLNA flow)

Samsung TVs usually do **not** run this Android TV app natively.

The current path for Samsung TV is the **smartphone app's experimental DLNA/UPnP screencast flow**.
This is different from Google Cast / Chromecast.

#### What works today

- The smartphone player can discover **DLNA devices**
- The player shows a **Cast** icon when the `Screencast` preference is enabled
- The player now opens a **Cast & external playback** sheet
- That sheet contains both **Chromecast / Google Cast** and a **DLNA Devices** section
- You can send the current stream URL to a Samsung TV if the TV advertises itself as a DLNA renderer

#### Step 1: prepare the Samsung TV

1. Put the Samsung TV and your Android phone on the same Wi-Fi network.
2. On the Samsung TV, check whether your model exposes **DLNA**, **AllShare**, **UPnP**, or a similar media-renderer feature.
3. If your TV has a media sharing or device connection screen, leave it enabled.
4. Keep the TV awake on its normal home screen while testing discovery.

#### Step 2: enable screencast in the phone app

1. Open the **smartphone** app.
2. Open the app settings.
3. Go to the **Optional** settings section.
4. Turn on **Screencast**.

Without this setting, the Cast button will not appear in the player controls.

#### Step 3: cast to the Samsung TV

1. Start playing a channel or video on the phone.
2. Open the on-screen player controls.
3. Tap the **Cast** icon.
4. Wait for the **Cast & external playback** sheet to search for devices.
   - Discovery starts after a short delay and typically needs several seconds on the network.
   - A good first expectation is to wait about **5-15 seconds** before deciding nothing was found.
5. In the **DLNA Devices** section, tap your Samsung TV in the device list.
6. Wait for the TV to start playback.

The DLNA device sheet also contains **Open in external app** if you want to hand the stream to another player on the phone instead.

#### Samsung TV troubleshooting

- If the Cast icon is missing, make sure **Screencast** is enabled in the phone app settings.
- If the Samsung TV never appears in **DLNA Devices**, the TV may not expose a DLNA renderer on your network.
- If the TV appears but playback fails, the stream may not be compatible with that TV's DLNA implementation.
- The current DLNA flow is marked **EXPERIMENTAL** in the UI, so reliability depends heavily on the TV model.
- If you want the most reliable experience on a Samsung TV, use an external **Google TV / Android TV** dongle and follow **Path A** instead.

### Path C: mobile app -> Chromecast receiver (Google Cast)

If your goal is:

- phone app -> **Chromecast dongle**
- phone app -> **Google Cast receiver**
- phone app -> **TV with built-in Chromecast**

then the app now has a **first-pass Google Cast sender implementation** in the smartphone player.

#### Important distinction

- **Chromecast with Google TV** can run the TV app directly using **Path A**
- A **Chromecast receiver** expects **Google Cast sender support** from the phone app
- This repository now supports **both**:
  - **Google Cast** for Chromecast / Google Cast receivers
  - **DLNA** for Samsung TVs and other UPnP renderers

#### What works today

- The smartphone player can open the native **Google Cast** device picker
- After you connect, the current phone playback is handed off to the Cast receiver
- The same Cast icon still gives you access to the Samsung-friendly **DLNA** flow in the same sheet
- The phone pauses its local player after a successful Cast handoff

#### Step 1: enable screencast in the phone app

1. Open the **smartphone** app.
2. Open the app settings.
3. Go to the **Optional** settings section.
4. Turn on **Screencast**.

Without this setting, the Cast icon does not appear in the player controls.

#### Step 2: start something on the phone first

1. Start playing a live channel, movie, or other stream on the phone.
2. Wait until the player is actually running.

The Chromecast sender uses the stream that is currently active in the phone player.

#### Step 3: open the Cast sheet and choose Google Cast

1. Open the on-screen player controls.
2. Tap the **Cast** icon.
3. In the **Cast & external playback** sheet, tap **Chromecast / Google Cast**.
4. Choose your Chromecast device from the Google Cast dialog.

If you are already connected, tapping the Cast icon again opens the Cast controller/dialog for that session.

#### Step 4: wait for playback handoff

After the connection succeeds:

- the current stream is loaded on the Chromecast receiver
- the phone pauses local playback
- you can keep using the phone app to browse and start another channel if needed
- if you start another channel on the phone while the cast session stays connected, that new selection is pushed to the Chromecast too

For playback controls during a cast session, use the **Cast dialog**, system media controls, or the TV/Chromecast remote.

#### Why this matters for IPTV

Chromecast devices fetch the media URL themselves.
For many M3U and Xtream streams, that is the hard part:

- the stream may only work with the exact headers used by the phone app
- the stream may depend on cookies or tokens
- the provider may behave differently when the Chromecast device IP fetches the stream directly

#### Current Chromecast limitations

The new sender flow is useful, but it is still limited by how Chromecast works:

- **DRM-protected** streams are currently blocked
- Playlists that require a **custom user-agent** may fail on Chromecast
- Streams that depend on **cookies**, **custom headers**, or provider-side IP checks may still fail
- Some IPTV stream formats are simply not supported by the Chromecast receiver

So this is a solid first-pass sender implementation, but it is **not yet a full proxy-backed IPTV relay**.

#### Chromecast troubleshooting

- If **Chromecast / Google Cast** does not appear in the cast sheet, Google Play services or Cast support may be unavailable on that phone.
- If you pick a device and nothing starts, test with a simpler `http`/`https` stream first.
- If a provider requires a custom user-agent, the app warns you because Chromecast playback may fail.
- If you need the most reliable TV playback for difficult IPTV providers, use **Path A** on a Google TV / Android TV device instead of remote casting from the phone.

## 🛠 Tech Stack

- 100% Kotlin-first approach
- 🎨 Jetpack Compose UI toolkit
- 🧬 MVVM architecture pattern
- 🚦 Coroutines & Flows
- 🗃️ Room database
- 💉 Hilt dependency injection
- 📦 Modular architecture
- 🎥 ExoPlayer + FFmpeg core

## 🌍 Localization

Help us translate the app! Current support:

| Core Languages | Community Translations |
|----------------|------------------------|
| 🇬🇧 [English](i18n/src/main/res/values) | 🇫🇷 [French](i18n/src/main/res/values-fr-rFR) by [@Gouar](https://github.com/Gouar) |
| 🇨🇳 [Simplified Chinese](i18n/src/main/res/values-zh-rCN) | 🇩🇪 [German](i18n/src/main/res/values-de-rDE) by [@PhynixP](https://github.com/PhynixP) |
|  | 🇮🇩 [Indonesian](i18n/src/main/res/values-id-rID) by [@ca-kraa](https://github.com/ca-kraa) |
|  | 🇮🇹 [Italian](i18n/src/main/res/values-it-rIT) by [@LucaMaroglio](https://github.com/LucaMaroglio) |
|  | 🇧🇷 [Portuguese (BR)](i18n/src/main/res/values-pt-rBR) by [@Suburbanno](https://github.com/Suburbanno) |
|  | 🇷🇴 [Romanian](i18n/src/main/res/values-ro-rRO) by [@iboboc](https://github.com/iboboc) |
|  | 🇪🇸 [Spanish](i18n/src/main/res/values-es-rES) by [@sguinetti](https://github.com/sguinetti) |
|  | 🇪🇸 [Spanish (MX)](i18n/src/main/res/values-es-rMX) by [@sguinetti](https://github.com/sguinetti) |
|  | 🇸🇪 [Swedish](i18n/src/main/res/values-sv-rSE) by [@optiix](https://github.com/optiix) |
|  | 🇹🇷 [Turkish](i18n/src/main/res/values-tr-rTR) by [@patr0nq](https://github.com/patr0nq) |

## 🤝 Contribution

We welcome all contributions! Here's how you can help:
- 🐛 Report bugs via Issues
- 💡 Suggest new features
- 📝 Improve documentation
- 🔧 Submit code changes

## 📈 Project Growth

<a href="https://star-history.com/#oxyroid/M3UAndroid&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=oxyroid/M3UAndroid&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=oxyroid/M3UAndroid&type=Date" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=oxyroid/M3UAndroid&type=Date" />
  </picture>
</a>

## 📜 License

Distributed under the **GPL 3.0**. See [LICENSE](LICENSE) for details.
