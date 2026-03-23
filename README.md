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
- 🎭 DLNA casting support
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

## 📺 TV setup from this repo (beginner guide)

If you have never installed an app on a TV before, start here. This repository now includes a guided Windows installer flow for the TV build:

- `scripts\install-tv.cmd` - double-clickable wrapper for Windows users
- `scripts\install-tv.ps1` - full PowerShell installer with `-Build`, `-Launch`, `-UninstallFirst`, and `-DryRun`

### What you need

- A Windows PC on the same network as your TV
- An Android TV / Google TV device
- Developer options enabled on the TV
- One playlist source to test with:
  - M3U title + URL
  - Xtream title + basic server URL + username + password
  - Optional EPG URL if you want guide data

### Step 1: turn on developer options and debugging on the TV

1. Open `Settings` on the TV.
2. On Google TV, go to `System` -> `About` -> `Android TV OS build`.
3. On classic Android TV, go to `Device Preferences` -> `About` -> `Build`.
4. Click that build entry several times until developer options are enabled.
5. Open `Developer options` (`Settings` -> `System` -> `Developer options` on Google TV).
6. Turn on `Network debugging` or `USB debugging`.
7. Find the TV IP address from the network settings screen.

If the TV shows a trust or RSA prompt later, choose `Allow`.

### Step 2: install with the one-click script

The easiest first-time path is to double-click:

```text
scripts\install-tv.cmd
```

The script will guide you, ask for the TV IP if needed, connect with ADB, install the APK, and optionally launch the app.
If the TV APK does not exist yet, the PowerShell installer builds it automatically.

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

### Step 3: manual install if you want full control

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

### Step 4: first run inside the app

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

### Step 5: what to expect after import

Once your data is imported:

- `Live TV` items play directly
- `Movies` open their detail page before playback
- `Series` open a detail page with metadata and episode buttons

The TV slice added in this repo specifically improves Xtream `Movies` and `Series` browsing and episode playback.

### Troubleshooting

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
