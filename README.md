# <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/src/monocleschat/ic_launcher-playstore.png" width="30"> LaNoxe

🇩🇪 [Deutsch](README-de.md) / 🇫🇷 [Français](README-fr.md) / 🇮🇹 [Italiano](README-it.md) / 🇵🇱 [Polski](README-pl.md) / 🇹🇷 [Türkçe](README-tr.md)

LaNoxe is a modern and secure XMPP client for Android. It is a fork of [monocles chat](https://codeberg.org/monocles/monocles_chat), based on [Conversations](https://codeberg.org/iNPUTmice/Conversations), and includes features from [blabber.im](https://codeberg.org/kriztan/blabber.im) and [Cheogram](https://git.sr.ht/~singpolyma/cheogram-android).

<img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/00.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/01.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/02.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/03.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/04.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/05.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/06.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/07.png" width="200" />

Screens designed by Pigeonalley (https://pigeonalley.com)

## Features

* End-to-end encryption with [OMEMO](https://conversations.im/omemo/) or [OpenPGP](https://www.openpgp.org/about/)
* Experimental post-quantum end-to-end encryption (PQ OMEMO2)
* Encrypted connections only, TLS 1.3 required by default; outdated cipher suites are removed
* DNSSEC-verified server lookup and DANE certificate checks (can be enforced)
* Protection against login downgrade attacks: channel binding and SCRAM downgrade protection (XEP-0440 / XEP-0474); never falls back to PLAIN once SCRAM was used
* Login with OAuth (OAUTHBEARER) on servers that offer it
* Connections over Tor and I2P, including .onion and .i2p servers
* Encrypted local database with an optional startup password
* Send and receive images, videos, files, locations and voice messages
* Message styling (XEP-0393): `*bold*`, `_italic_`, `~strike~`, monospace and code blocks
* Audio and video calls
* Group chats and channels
* Synchronized message history across your devices
* Profile pictures, status messages and stories
* Address book integration (your address book is never shared with the server)
* Daily backup of the database to local storage
* Very low battery consumption

## Download

<a href="https://f-droid.org/app/de.monocles.chat"><img src="https://f-droid.org/badge/get-it-on-en.png" alt="Get it on F-Droid" height="100"></a>
<a href="https://play.google.com/store/apps/details?id=eu.monocles.chat"><img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/art/GetItOnGooglePlay_Badge_Web_color_English.png" alt="Get it on Google Play" height="100"></a>

Nightly and beta APKs are available on Codeberg under [Releases](https://codeberg.org/monocles/monocles_chat/releases).

LaNoxe requires Android 7.0 or newer.

## Building from source

You need the Android SDK and NDK, and a Rust toolchain. The libsignal library is built from source from the `deps/libsignal` submodule instead of using prebuilt binaries.

```
git clone --recurse-submodules https://codeberg.org/monocles/monocles_chat.git
cd monocles_chat
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
./gradlew assembleMonocleschatFreeRelease
```

If you already cloned without submodules, run `git submodule update --init --recursive` first.

The unit tests also need libsignal built for your own computer. Build it once, and again whenever the `deps/libsignal` submodule changes; otherwise the tests that use it fail or run against an outdated build:

```
deps/libsignal/java/build_jni.sh desktop
./gradlew testMonocleschatFreeDebugUnitTest
```

## Community

Follow us on <a rel="me" href="https://monocles.social/@monocles">monocles social</a>.

We run English and German speaking XMPP group chats:

* Support: [support@conference.monocles.eu](xmpp:support@conference.monocles.eu?join), for questions and help with problems
* Development: [development@conference.monocles.de](xmpp:development@conference.monocles.de?join), if you want to take part in development

## How can I help with translations?

You can improve or create translations on [Codeberg Translate](https://translate.codeberg.org/projects/monocles_chat/). Thank you very much.

## Help! I've run into a problem!

The easiest way to get help is to join our [support group chat](xmpp:support@conference.monocles.eu?join) (English and German).

If we can't fix your problem there, you can open an [issue](https://codeberg.org/monocles/monocles_chat/issues). Describe your problem, explain how to reproduce it and attach logs. See below for how to create log files.

### How do I create debug logs? (adb)

#### GNU/Linux, macOS and other Unix-like systems:

1. First install the **A**ndroid **D**ebug **B**ridge (adb), if it isn't already installed.
    ###### Debian and derivatives like Ubuntu / Linux Mint
    ```
    sudo apt-get update
    sudo apt-get install adb
    ```
    ###### openSUSE
    ```
    sudo zypper ref
    sudo zypper install android-tools
    ```
    ###### macOS (Homebrew)
    ```
    brew install android-platform-tools
    ```
    ###### other systems
    Install adb in the way that suits your system.

2. Open a terminal and go to a directory of your choice with `cd`.

3. Follow steps [6] to [10] of the Windows instructions.

4. Start writing the log to a file on your computer. We use `logcat.txt`. LaNoxe must be running. Enter:
    ```
    $ adb -d logcat -v time --pid=$(adb -d shell pidof -s de.monocles.chat) > logcat.txt
    ```

5. Follow the remaining steps [12] and [13] of the Windows instructions.

#### Windows:

1. Download Google's SDK Platform Tools:

    https://developer.android.com/tools/releases/platform-tools
2. If they aren't included, you also need the USB driver for your version of Windows:

    https://developer.android.com/studio/run/win-usb
3. Extract the zip archive (for example to `C:\ADB\`).
4. Open the command line (CMD) from the Start menu: Start > Run: cmd
5. Go to the directory you extracted the zip to. We use `C:\ADB\`:
    ```
    c:
    cd ADB
    ```
6. On your smartphone, open the settings and look for `Developer options`. If you don't see this option, unlock it first: go to `Settings > About phone`, find `Build number` (or similar) and tap it 7 times in a row. A notification tells you that you are now a developer, and `Developer options` appears in your settings.
7. In `Developer options`, turn on `USB debugging` (sometimes called `Android debugging`).
8. Connect your phone to your computer with a USB cable. Any missing drivers should now be installed. On Windows they should be downloaded automatically if you did step [2]. On most GNU/Linux systems you don't need to do anything else.
9. If everything worked, go back to the command line and check that your device is recognized. Enter `adb devices -l`. You should see output similar to:
    ```
    > adb devices -l
    List of devices attached
    * daemon not running. starting it now on port 5037 *
    * daemon started successfully *
    042111560169500303f4   unauthorized
    ```
10. If your device is shown as `unauthorized`, accept the prompt on your phone that asks whether to allow USB debugging. If you run `adb devices` again, you should see:
    ```
    > adb devices
    List of devices attached
    042111560169500303f4    device
    ```
11. Start writing the log to a file on your computer. We use `logcat.txt` in `C:\ADB\`. Enter the following (without the `> `):
    ```
    > adb -d logcat -v time | FINDSTR /I monocles > logcat.txt
    ```
12. Now reproduce the problem.

13. Stop logging (`Ctrl+C`). Look through your log file carefully and remove any personal or private information. Then send it with a detailed description of the problem and how to reproduce it, using the [issue tracker](https://codeberg.org/monocles/monocles_chat/issues).

## License

LaNoxe is free software: you can redistribute it and/or modify it under the terms of the [GNU Affero General Public License](LICENSE) version 3 only (AGPL-3.0-only), as published by the Free Software Foundation.

The app includes [libsignal](https://github.com/signalapp/libsignal) (Copyright © Signal Messenger, LLC), which is licensed under the GNU AGPLv3 only. The app as a whole is therefore distributed under the terms of the AGPLv3, version 3 only. That includes the requirement to make the complete corresponding source code available to its users. Code taken from Conversations, blabber.im and Cheogram, the bundled [libsignal-protocol-java](https://github.com/signalapp/libsignal-protocol-java) used for legacy OMEMO, and contributions made while this repository was licensed under the GPLv3, are licensed under the GNU GPLv3; section 13 of the GPLv3 allows it to be combined with AGPLv3 code.

LaNoxe is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [LICENSE](LICENSE) file for details.

Copyright © 2020-2026 Arne-Brün Vogelsang and contributors
