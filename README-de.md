# <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/src/monocleschat/ic_launcher-playstore.png" width="30"> monocles chat

🇬🇧 [English](README.md) / 🇫🇷 [Français](README-fr.md) / 🇮🇹 [Italiano](README-it.md) / 🇵🇱 [Polski](README-pl.md) / 🇹🇷 [Türkçe](README-tr.md)

monocles chat ist ein moderner und sicherer XMPP-Client für Android. Er basiert auf [Conversations](https://codeberg.org/iNPUTmice/Conversations), enthält Funktionen aus [blabber.im](https://codeberg.org/kriztan/blabber.im) und [Cheogram](https://git.sr.ht/~singpolyma/cheogram-android) und bringt viele eigene Änderungen und Funktionen mit, die Benutzerfreundlichkeit und Sicherheit verbessern.

<img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/00.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/01.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/02.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/03.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/04.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/05.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/06.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/07.png" width="200" />

Vorschaubilder erstellt von Pigeonalley (https://pigeonalley.com)

## Funktionen

* Ende-zu-Ende-Verschlüsselung mit [OMEMO](https://conversations.im/omemo/) oder [OpenPGP](https://www.openpgp.org/about/)
* Experimentelle Post-Quanten-Ende-zu-Ende-Verschlüsselung (PQ OMEMO2)
* Ausschließlich verschlüsselte Verbindungen, standardmäßig nur TLS 1.3; veraltete Cipher-Suites wurden entfernt
* DNSSEC-geprüfte Serverauflösung und DANE-Zertifikatsprüfung (kann erzwungen werden)
* Schutz vor Downgrade-Angriffen beim Login: Channel Binding und SCRAM-Downgrade-Schutz (XEP-0440 / XEP-0474); nach einem SCRAM-Login nie wieder Rückfall auf PLAIN
* Anmeldung per OAuth (OAUTHBEARER) bei Servern, die es anbieten
* Verbindungen über Tor und I2P, auch zu .onion- und .i2p-Servern
* Verschlüsselte lokale Datenbank mit optionalem Startpasswort
* Senden und Empfangen von Bildern, Videos, Dateien, Standorten und Sprachnachrichten
* Textformatierung (XEP-0393): `*fett*`, `_kursiv_`, `~durchgestrichen~`, Festbreitenschrift und Codeblöcke
* Audio- und Videoanrufe
* Gruppenchats und Kanäle
* Synchronisierter Nachrichtenverlauf auf all deinen Geräten
* Profilbilder, Statusnachrichten und Stories
* Adressbuch-Integration (dein Adressbuch wird nie mit dem Server geteilt)
* Tägliches Backup der Datenbank in den lokalen Speicher
* Sehr geringer Akkuverbrauch

## Download

<a href="https://f-droid.org/app/de.monocles.chat"><img src="https://f-droid.org/badge/get-it-on-de.png" alt="Jetzt bei F-Droid" height="100"></a>
<a href="https://play.google.com/store/apps/details?id=eu.monocles.chat"><img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/art/GetItOnGooglePlay_Badge_Web_color_German.png" alt="Jetzt bei Google Play" height="100"></a>

Nightly- und Beta-APKs gibt es auf Codeberg unter [Releases](https://codeberg.org/monocles/monocles_chat/releases).

monocles chat benötigt Android 7.0 oder neuer.

## Aus dem Quellcode bauen

Du brauchst das Android SDK und NDK sowie eine Rust-Toolchain. Die libsignal-Bibliothek wird aus dem Submodul `deps/libsignal` selbst gebaut, statt vorkompilierte Binärdateien zu verwenden.

```
git clone --recurse-submodules https://codeberg.org/monocles/monocles_chat.git
cd monocles_chat
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
./gradlew assembleMonocleschatFreeRelease
```

Falls du ohne Submodule geklont hast, führe vorher `git submodule update --init --recursive` aus.

Die Unit-Tests brauchen zusätzlich libsignal für deinen eigenen Rechner. Baue es einmal und erneut, sobald sich das Submodul `deps/libsignal` ändert; sonst schlagen die Tests, die es nutzen, fehl oder laufen gegen einen veralteten Stand:

```
deps/libsignal/java/build_jni.sh desktop
./gradlew testMonocleschatFreeDebugUnitTest
```

## Community

Folge uns auf <a rel="me" href="https://monocles.social/@monocles">monocles social</a>.

Wir betreiben deutsch- und englischsprachige XMPP-Gruppenchats:

* Support: [support@conference.monocles.eu](xmpp:support@conference.monocles.eu?join), für Fragen und Hilfe bei Problemen
* Entwicklung: [development@conference.monocles.de](xmpp:development@conference.monocles.de?join), wenn du dich an der Entwicklung beteiligen möchtest

## Wie kann ich bei der Übersetzung helfen?

Du kannst auf [Codeberg Translate](https://translate.codeberg.org/projects/monocles_chat/) Übersetzungen verbessern oder neu erstellen. Vielen Dank.

## Ich habe Probleme, was soll ich tun?

Am einfachsten ist es, wenn du unserem [Support-Gruppenchat](xmpp:support@conference.monocles.eu?join) beitrittst (deutsch und englisch).

Solltest du dort nicht weiterkommen, kannst du ein [Issue](https://codeberg.org/monocles/monocles_chat/issues) erstellen. Beschreibe darin dein Problem, welche Schritte nötig sind, um es zu reproduzieren, und hänge Logs an. Wie du Logs erstellst, steht weiter unten.

### Wie erstelle ich Debug-Logs? (adb)

#### GNU/Linux, macOS und andere Unix-artige Systeme:

1. Installiere die **A**ndroid **D**ebug **B**ridge (adb), falls noch nicht vorhanden.
    ###### Debian und Derivate wie Ubuntu / Linux Mint
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
    ###### andere Systeme
    Installiere adb auf die für dein System passende Weise.

2. Öffne ein Terminal und wechsle mit `cd` in ein Verzeichnis deiner Wahl.

3. Folge den Schritten [6] bis [10] der Windows-Anleitung.

4. Starte die Ausgabe der Logs in eine Datei auf deinem Computer, hier `logcat.txt`. monocles chat muss dabei laufen. Gib ein:
    ```
    $ adb -d logcat -v time --pid=$(adb -d shell pidof -s de.monocles.chat) > logcat.txt
    ```

5. Fahre mit den verbleibenden Schritten [12] und [13] der Windows-Anleitung fort.

#### Windows:

1. Lade die SDK-Plattform-Tools von Google herunter:

    https://developer.android.com/tools/releases/platform-tools
2. Falls nicht enthalten, brauchst du außerdem den USB-Treiber für deine Windows-Version:

    https://developer.android.com/studio/run/win-usb
3. Entpacke das Zip-Archiv (z. B. nach `C:\ADB\`).
4. Öffne die Kommandozeile (CMD) über das Startmenü: Start > Ausführen: cmd
5. Wechsle in das Verzeichnis, in das du das Archiv entpackt hast, hier `C:\ADB\`:
    ```
    c:
    cd ADB
    ```
6. Öffne auf deinem Telefon die Einstellungen und suche nach `Entwickleroptionen`. Falls dieser Punkt fehlt, musst du ihn erst freischalten: Gehe zu `Einstellungen > Über das Telefon`, suche dort nach `Buildnummer` (oder Ähnlichem) und tippe sie 7-mal hintereinander an. Ein Hinweis bestätigt dir, dass du nun Entwickler bist, und die `Entwickleroptionen` erscheinen in den Einstellungen.
7. Aktiviere in den `Entwickleroptionen` den Eintrag `USB-Debugging`.
8. Schließe dein Telefon per USB-Kabel an deinen Computer an. Fehlende Treiber sollten nun installiert werden. Unter Windows geschieht das automatisch, wenn du Schritt [2] ausgeführt hast. Unter den meisten GNU/Linux-Systemen ist nichts weiter zu tun.
9. Wenn alles geklappt hat, prüfe in der Kommandozeile, ob dein Gerät erkannt wird. Gib `adb devices -l` ein. Die Ausgabe sollte etwa so aussehen:
    ```
    > adb devices -l
    List of devices attached
    * daemon not running. starting it now on port 5037 *
    * daemon started successfully *
    042111560169500303f4   unauthorized
    ```
10. Wird dein Telefon als `unauthorized` angezeigt, erscheint auf dem Telefon die Frage `USB-Debugging zulassen?`. Bestätige sie mit `OK`. Bei einem erneuten `adb devices` sollte dann Folgendes stehen:
    ```
    > adb devices
    List of devices attached
    042111560169500303f4    device
    ```
11. Starte die Ausgabe der Logs in die Datei `logcat.txt` im Verzeichnis `C:\ADB\`. Gib dazu Folgendes ein (ohne `> `):
    ```
    > adb -d logcat -v time | FINDSTR /I monocles > logcat.txt
    ```
12. Führe nun die Schritte aus, die zum Fehler führen.

13. Beende das Loggen mit `Strg+C`. Sieh dir die `logcat.txt` genau an und entferne persönliche Angaben. Sende die Datei dann mit einer Beschreibung des Fehlers und der Schritte, die zu ihm führen, über den [Issue-Tracker](https://codeberg.org/monocles/monocles_chat/issues).

## Lizenz

monocles chat ist freie Software: Du kannst sie unter den Bedingungen der [GNU Affero General Public License](LICENSE) in Version 3, wie von der Free Software Foundation veröffentlicht, weitergeben und/oder verändern. Es gilt ausschließlich Version 3 (AGPL-3.0-only).

Die App enthält [libsignal](https://github.com/signalapp/libsignal) (Copyright © Signal Messenger, LLC), das ausschließlich unter Version 3 der GNU AGPL lizenziert ist. Daher wird die App als Ganzes unter den Bedingungen der AGPLv3, ausschließlich Version 3, verbreitet. Dazu gehört die Pflicht, den vollständigen zugehörigen Quellcode für ihre Nutzer verfügbar zu machen. Code aus Conversations, blabber.im und Cheogram, die für Legacy-OMEMO mitgelieferte Bibliothek [libsignal-protocol-java](https://github.com/signalapp/libsignal-protocol-java) sowie Beiträge aus der Zeit, als dieses Repository unter der GPLv3 stand, stehen unter der GNU GPLv3; Abschnitt 13 der GPLv3 erlaubt die Kombination mit AGPLv3-Code.

monocles chat wird in der Hoffnung verbreitet, dass es nützlich ist, aber OHNE JEDE GEWÄHRLEISTUNG, sogar ohne die implizite Gewährleistung der MARKTREIFE oder der EIGNUNG FÜR EINEN BESTIMMTEN ZWECK. Details stehen in der Datei [LICENSE](LICENSE).

Copyright © 2020-2026 Arne-Brün Vogelsang und Mitwirkende
