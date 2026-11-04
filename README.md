# Halqa

Halqa is an Android TV app for watching the videos posted in your Telegram chats and channels. It groups them into
series and episodes, like a streaming app: open a series, pick an episode, choose a quality, and it plays.

Halqa is based on [Tele](https://github.com/saieshshirodkar/Tele) by Syndicate, released under the MIT licence.

## What it does

- **Series, not a flat list.** Videos are grouped by the series name and episode number found in their captions
  (Arabic, English and Turkish formats such as `الحلقة 35`, `Episode 5`, `S01E05` and `35. Bölüm`).
- **One tile per episode.** When a channel posts the same episode once per quality, those uploads become one episode,
  and OK opens a quality picker (4K, 1080p, 720p, …).
- **Trailers and clips left out.** Promos, teasers and short clips without an episode number are hidden, and a clip
  shorter than half an episode doesn't count as one of its qualities.
- **Home screen.** A "New episodes" row with the latest episode of each series, and an "All series" row.
- **QR sign-in.** Scan a code with Telegram on your phone (Settings › Devices › Link Desktop Device).
  Sign out from the account circle on the home screen to switch accounts.
- **Streaming.** Videos stream from Telegram through a small local server into
  [EZV Player](https://play.google.com/store/search?q=EZV%20Player&c=apps), or any installed video player.

## What you need

- An Android TV device. The build targets 32-bit ARM (`armeabi-v7a`), which runs on both 32-bit and most 64-bit TVs.
- A Telegram account.
- Your own Telegram API ID and API hash from [my.telegram.org/apps](https://my.telegram.org/apps).
  Keep them private.
- A video player on the TV. EZV Player is recommended.

## Building it

1. Clone this repository.
2. Create `local.properties` in the project root with your Telegram API keys. This file is git-ignored, so your keys
   stay on your machine:
   ```
   TELEGRAM_API_ID=your_api_id
   TELEGRAM_API_HASH=your_api_hash
   ```
   You can leave the keys out; the app then asks for them on first launch and stores them on the TV.
3. Build with JDK 17 and install:
   ```
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

A release build also needs `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD` in `local.properties`.

Don't share an APK built with your keys in `local.properties`: the keys are compiled into it.

## How it's built

- Kotlin, Android TV Leanback grids and Glide
- [TDLib](https://github.com/tdlib/td), Telegram's official client library
- Coroutines and Flow
- ZXing for the sign-in QR code

## Licence

MIT. See [LICENSE](LICENSE).
