# <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/src/monocleschat/ic_launcher-playstore.png" width="30"> monocles chat

🇬🇧 [English](README.md) / 🇩🇪 [Deutsch](README-de.md) / 🇫🇷 [Français](README-fr.md) / 🇮🇹 [Italiano](README-it.md) / 🇹🇷 [Türkçe](README-tr.md)

monocles chat to nowoczesny i bezpieczny klient XMPP na Androida. Jest oparty na aplikacji [Conversations](https://codeberg.org/iNPUTmice/Conversations), zawiera funkcje z [blabber.im](https://codeberg.org/kriztan/blabber.im) i [Cheogram](https://git.sr.ht/~singpolyma/cheogram-android), a także wprowadza wiele własnych zmian i funkcji poprawiających wygodę użytkowania i bezpieczeństwo.

<img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/00.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/01.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/02.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/03.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/04.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/05.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/06.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/07.png" width="200" />

Ekrany zaprojektowane przez Pigeonalley (https://pigeonalley.com)

## Funkcje

* Szyfrowanie end-to-end za pomocą [OMEMO](https://conversations.im/omemo/) lub [OpenPGP](https://www.openpgp.org/about/)
* Eksperymentalne postkwantowe szyfrowanie end-to-end (PQ OMEMO2)
* Wyłącznie szyfrowane połączenia, domyślnie wymagany TLS 1.3; przestarzałe zestawy szyfrów zostały usunięte
* Wyszukiwanie serwera weryfikowane przez DNSSEC i sprawdzanie certyfikatów przez DANE (można wymusić)
* Ochrona przed atakami typu downgrade przy logowaniu: channel binding i ochrona SCRAM przed downgrade (XEP-0440 / XEP-0474); po logowaniu SCRAM nigdy powrót do PLAIN
* Logowanie przez OAuth (OAUTHBEARER) na serwerach, które je oferują
* Połączenia przez Tor i I2P, także z serwerami .onion i .i2p
* Szyfrowana lokalna baza danych z opcjonalnym hasłem przy uruchamianiu
* Wysyłanie i odbieranie zdjęć, filmów, plików, lokalizacji i wiadomości głosowych
* Formatowanie wiadomości (XEP-0393): `*pogrubienie*`, `_kursywa_`, `~przekreślenie~`, czcionka o stałej szerokości i bloki kodu
* Połączenia audio i wideo
* Czaty grupowe i kanały
* Historia wiadomości synchronizowana na wszystkich Twoich urządzeniach
* Zdjęcia profilowe, opisy statusu i relacje (stories)
* Integracja z książką adresową (Twoja książka adresowa nigdy nie jest udostępniana serwerowi)
* Codzienna kopia zapasowa bazy danych w pamięci lokalnej
* Bardzo niskie zużycie baterii

## Pobieranie

<a href="https://f-droid.org/app/de.monocles.chat"><img src="https://f-droid.org/badge/get-it-on-pl.png" alt="Pobierz z F-Droid" height="100"></a>
<a href="https://play.google.com/store/apps/details?id=eu.monocles.chat"><img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/art/GetItOnGooglePlay_Badge_Web_color_Polish.png" alt="Pobierz z Google Play" height="100"></a>

Wersje nightly i beta (APK) są dostępne na Codebergu w sekcji [Releases](https://codeberg.org/monocles/monocles_chat/releases).

monocles chat wymaga Androida 7.0 lub nowszego.

## Kompilacja ze źródeł

Potrzebujesz Android SDK i NDK oraz zestawu narzędzi Rust. Biblioteka libsignal jest kompilowana ze źródeł z podmodułu `deps/libsignal` zamiast używania gotowych plików binarnych.

```
git clone --recurse-submodules https://codeberg.org/monocles/monocles_chat.git
cd monocles_chat
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
./gradlew assembleMonocleschatFreeRelease
```

Jeśli sklonowałeś/-aś repozytorium bez podmodułów, najpierw uruchom `git submodule update --init --recursive`.

Testy jednostkowe potrzebują też libsignal skompilowanej dla twojego komputera. Skompiluj ją raz i ponownie po każdej zmianie podmodułu `deps/libsignal`; w przeciwnym razie testy, które z niej korzystają, kończą się błędem albo działają na nieaktualnej wersji:

```
deps/libsignal/java/build_jni.sh desktop
./gradlew testMonocleschatFreeDebugUnitTest
```

## Społeczność

Obserwuj nas na <a rel="me" href="https://monocles.social/@monocles">monocles social</a>.

Prowadzimy angielsko- i niemieckojęzyczne czaty grupowe XMPP:

* Wsparcie: [support@conference.monocles.eu](xmpp:support@conference.monocles.eu?join), pytania i pomoc w razie problemów
* Rozwój: [development@conference.monocles.de](xmpp:development@conference.monocles.de?join), jeśli chcesz wziąć udział w rozwijaniu aplikacji

## Jak mogę pomóc w tłumaczeniu?

Możesz poprawiać lub tworzyć tłumaczenia na [Codeberg Translate](https://translate.codeberg.org/projects/monocles_chat/). Dziękujemy bardzo.

## Pomocy! Napotkałem/-am problem!

Najprostszym sposobem na uzyskanie pomocy jest dołączenie do naszego [czatu wsparcia](xmpp:support@conference.monocles.eu?join) (angielski i niemiecki).

Jeśli nie uda nam się tam rozwiązać problemu, możesz utworzyć [zgłoszenie](https://codeberg.org/monocles/monocles_chat/issues). Opisz problem, wyjaśnij, jak go odtworzyć, i dołącz dzienniki zdarzeń. Poniżej opisano, jak je utworzyć.

### Jak tworzyć dzienniki zdarzeń? (adb)

#### GNU/Linux, macOS i inne systemy uniksowe:

1. Najpierw zainstaluj **A**ndroid **D**ebug **B**ridge (adb), jeśli nie jest jeszcze zainstalowany.
    ###### Debian i pochodne, np. Ubuntu / Linux Mint
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
    ###### inne systemy
    Zainstaluj adb w sposób właściwy dla Twojego systemu.

2. Otwórz terminal i przejdź do wybranego katalogu za pomocą `cd`.

3. Wykonaj kroki od [6] do [10] z instrukcji dla systemu Windows.

4. Rozpocznij zapisywanie dziennika do pliku na komputerze. Używamy `logcat.txt`. Aplikacja monocles chat musi być uruchomiona. Wpisz:
    ```
    $ adb -d logcat -v time --pid=$(adb -d shell pidof -s de.monocles.chat) > logcat.txt
    ```

5. Wykonaj pozostałe kroki [12] i [13] z instrukcji dla systemu Windows.

#### Windows:

1. Pobierz SDK Platform Tools od Google:

    https://developer.android.com/tools/releases/platform-tools
2. Jeśli nie są dołączone, potrzebujesz także sterownika USB dla Twojej wersji systemu Windows:

    https://developer.android.com/studio/run/win-usb
3. Rozpakuj archiwum zip (np. do `C:\ADB\`).
4. Otwórz wiersz poleceń (CMD) z menu Start: Start > Uruchom: cmd
5. Przejdź do katalogu, do którego rozpakowano archiwum. Używamy `C:\ADB\`:
    ```
    c:
    cd ADB
    ```
6. Na smartfonie otwórz ustawienia i wyszukaj `Opcje programistyczne`. Jeśli ich nie widzisz, najpierw je odblokuj: przejdź do `Ustawienia > Informacje o telefonie`, znajdź `Numer kompilacji` (lub podobny) i stuknij go 7 razy z rzędu. Pojawi się powiadomienie, że jesteś teraz programistą, a w ustawieniach pojawią się `Opcje programistyczne`.
7. W `Opcjach programistycznych` włącz `Debugowanie USB` (czasem nazywane `Debugowanie Androida`).
8. Podłącz telefon do komputera kablem USB. Brakujące sterowniki powinny zostać zainstalowane. W systemie Windows zostaną pobrane automatycznie, jeśli wykonano krok [2]. W większości systemów GNU/Linux nie trzeba robić nic więcej.
9. Jeśli wszystko się udało, wróć do wiersza poleceń i sprawdź, czy urządzenie zostało rozpoznane. Wpisz `adb devices -l`. Powinieneś/-aś zobaczyć coś podobnego do:
    ```
    > adb devices -l
    List of devices attached
    * daemon not running. starting it now on port 5037 *
    * daemon started successfully *
    042111560169500303f4   unauthorized
    ```
10. Jeśli urządzenie jest oznaczone jako `unauthorized`, zaakceptuj na telefonie prośbę o zezwolenie na debugowanie USB. Po ponownym wpisaniu `adb devices` powinieneś/-aś zobaczyć:
    ```
    > adb devices
    List of devices attached
    042111560169500303f4    device
    ```
11. Rozpocznij zapisywanie dziennika do pliku `logcat.txt` w `C:\ADB\`. Wpisz poniższe polecenie (bez `> `):
    ```
    > adb -d logcat -v time | FINDSTR /I monocles > logcat.txt
    ```
12. Teraz odtwórz problem.

13. Zatrzymaj zapisywanie (`Ctrl+C`). Dokładnie przejrzyj plik dziennika i usuń wszelkie dane osobiste lub prywatne. Następnie wyślij go wraz ze szczegółowym opisem problemu i sposobu jego odtworzenia przez [system zgłoszeń](https://codeberg.org/monocles/monocles_chat/issues).

## Licencja

monocles chat jest wolnym oprogramowaniem: możesz je rozpowszechniać i/lub modyfikować na warunkach [GNU Affero General Public License](LICENSE) opublikowanej przez Free Software Foundation, wyłącznie w wersji 3 (AGPL-3.0-only).

Aplikacja zawiera [libsignal](https://github.com/signalapp/libsignal) (Copyright © Signal Messenger, LLC), udostępnianą wyłącznie na licencji GNU AGPL w wersji 3. Dlatego aplikacja jako całość jest rozpowszechniana na warunkach AGPLv3, wyłącznie w wersji 3. Obejmuje to obowiązek udostępnienia użytkownikom pełnego odpowiadającego kodu źródłowego. Kod pochodzący z Conversations, blabber.im i Cheogram, dołączona biblioteka [libsignal-protocol-java](https://github.com/signalapp/libsignal-protocol-java) używana do starszego OMEMO oraz wkład wniesiony w czasie, gdy to repozytorium było objęte GPLv3, jest objęty licencją GNU GPLv3; sekcja 13 GPLv3 pozwala łączyć go z kodem na licencji AGPLv3.

monocles chat jest rozpowszechniany w nadziei, że będzie użyteczny, ale BEZ JAKIEJKOLWIEK GWARANCJI, nawet domyślnej gwarancji PRZYDATNOŚCI HANDLOWEJ albo PRZYDATNOŚCI DO OKREŚLONYCH ZASTOSOWAŃ. Szczegóły znajdziesz w pliku [LICENSE](LICENSE).

Copyright © 2020-2026 Arne-Brün Vogelsang i współtwórcy
