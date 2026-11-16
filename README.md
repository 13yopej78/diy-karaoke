# DIY Karaoke for Android TV (NVIDIA Shield)

A karaoke app for Android TV that plays:

- **MP3 + CDG** karaoke files (also zipped `.zip` bundles)
- **MIDI / KAR** karaoke files, with on-screen lyrics
- **Karaoke videos** (`.mp4`, `.mkv`, `.avi`, …)
- **YouTube** karaoke videos

Songs are read from a **USB drive** plugged into the Shield (or the Shield's internal storage).
Guests scan a **QR code** on the TV with their phone to search songs and add them to a shared queue,
like PiKaraoke. No phone app is needed.

## Install on the Shield

1. On the Shield, install **Downloader** (by AFTVnews) from the Play Store.
2. Allow Downloader to install apps: *Settings → Device Preferences → Security & restrictions →
   Unknown sources → Downloader → On*.
3. Open Downloader and enter this address:

   `https://github.com/13yopej78/diy-karaoke/releases/latest/download/diy-karaoke.apk`

4. Tap **Install**, then **Open**. Allow the storage permission when asked.

To update later, repeat step 3. The new version installs over the old one.

## Using it

- Plug the USB drive with your songs into the Shield. The app scans it automatically.
- Phones on the same Wi-Fi scan the QR code on the TV (or type the address shown).
- On the phone: **My Songs** = files on the USB drive, **YouTube** = search YouTube,
  **Queue** = reorder/remove songs, pause, skip, change key.

TV remote: **OK** = pause/resume · **▶▶** or **→ twice** = skip · **↑** = show now/next ·
**Back twice** = stop current song.

## Notes

- YouTube search works without a key. If it stops working, add a free YouTube Data API key in the
  phone page under ⚙.
- Some YouTube videos block playback outside YouTube; those are skipped automatically.
- MIDI sound uses Android's built-in synthesizer.

## How it's built

Every push to `main` is built by GitHub Actions (`.github/workflows/build.yml`) and published as
the `latest` release. The signing key in `app/diykaraoke.jks` is intentionally shared so that
updates install over earlier versions.
