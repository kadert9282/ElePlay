# 🎬 ElePlay

**Android video downloader with embedded FFmpeg, CPython, and yt-dlp runtime.**

![ElePlay Icon](docs/screenshots/1774183707799-019d1596-c748-7b0a-8a42-7673157fb46b.png)

---

## ✨ Features

- 📥 Download videos from YouTube and hundreds of other sites
- 🎯 Select any format — 144p to 4K, audio-only, or merged video+audio
- ⚡ Smart downloader: automatically picks best video + best audio
- 🔧 Full runtime inside the APK — no Termux, no root, no extra installs
- 📊 Real-time progress with speed and ETA
- 🧠 Modular: you can extend it with your own Python/JS scripts

---

## 📱 Requirements

- **Android 7.0 (API 24) or higher**
- **ARM64 (arm64-v8a)** devices only (most modern Android phones)

---

## 🚀 Quick start

1. Download the APK from [Releases](https://github.com/ren10-14/ElePlay/releases)
2. Install on your device (allow installation from unknown sources)
3. Open the app
4. Paste a YouTube link
5. Choose quality
6. Download

---

## 🛠️ Build from source

This repository contains **only the source code**.  
To build ElePlay, you need to download the runtime package from [Releases](https://github.com/ren10-14/ElePlay/releases) and extract it to `app/src/main/assets/`.
git clone https://github.com/ren10-14/ElePlay.git

Download eleplay-runtime-v1.0.0.zip from Releases
Extract to app/src/main/assets/
Open in Android Studio → Build → Run
text

---

## 📦 What’s inside

| Component | Purpose |
|-----------|---------|
| **FFmpeg 8.0.1** | Video/audio processing, merging |
| **CPython 3.11.11** | Full Python interpreter |
| **yt-dlp** | YouTube extraction engine |
| **QuickJS** | Lightweight JavaScript engine |

All binaries are **ARM64‑only** (modern Android devices).

---

## 📄 License

GPL‑2.0‑or‑later. See [LICENSE](LICENSE) for details.

ElePlay includes:
- [FFmpeg](https://ffmpeg.org/) (GPL)
- [CPython](https://python.org) (PSF License)
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense)
- [QuickJS](https://bellard.org/quickjs/) (MIT)

---

## 👤 Author

**ren10-14** — [GitHub](https://github.com/ren10-14)

---

## ☕ Support

If you like this project, star it ⭐ — it keeps me motivated.

*Made with too much caffeine and way too much curiosity.*
