# <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/src/monocleschat/ic_launcher-playstore.png" width="30"> LaNoxe

🇬🇧 [English](README.md) / 🇩🇪 [Deutsch](README-de.md) / 🇫🇷 [Français](README-fr.md) / 🇵🇱 [Polski](README-pl.md) / 🇹🇷 [Türkçe](README-tr.md)

LaNoxe è un client XMPP moderno e sicuro per Android. È basato su [Conversations](https://codeberg.org/iNPUTmice/Conversations), include funzionalità di [blabber.im](https://codeberg.org/kriztan/blabber.im) e [Cheogram](https://git.sr.ht/~singpolyma/cheogram-android) e aggiunge molte modifiche e funzionalità proprie per migliorare usabilità e sicurezza.

<img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/00.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/01.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/02.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/03.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/04.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/05.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/06.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/07.png" width="200" />

Schermate progettate da Pigeonalley (https://pigeonalley.com)

## Funzionalità

* Crittografia end-to-end con [OMEMO](https://conversations.im/omemo/) o [OpenPGP](https://www.openpgp.org/about/)
* Crittografia end-to-end post-quantistica sperimentale (PQ OMEMO2)
* Solo connessioni cifrate, TLS 1.3 richiesto per impostazione predefinita; le suite di cifratura obsolete sono state rimosse
* Risoluzione del server verificata con DNSSEC e controllo dei certificati con DANE (può essere imposto)
* Protezione dagli attacchi di downgrade al login: channel binding e protezione SCRAM dal downgrade (XEP-0440 / XEP-0474); mai più PLAIN dopo un login SCRAM
* Login con OAuth (OAUTHBEARER) sui server che lo supportano
* Connessioni tramite Tor e I2P, anche verso server .onion e .i2p
* Database locale cifrato con password di avvio opzionale
* Invio e ricezione di immagini, video, file, posizioni e messaggi vocali
* Formattazione dei messaggi (XEP-0393): `*grassetto*`, `_corsivo_`, `~barrato~`, testo a spaziatura fissa e blocchi di codice
* Chiamate audio e video
* Chat di gruppo e canali
* Cronologia dei messaggi sincronizzata su tutti i tuoi dispositivi
* Immagini del profilo, messaggi di stato e storie
* Integrazione con la rubrica (la tua rubrica non viene mai condivisa con il server)
* Backup giornaliero del database nella memoria locale
* Consumo della batteria molto basso

## Download

<a href="https://f-droid.org/app/de.monocles.chat"><img src="https://f-droid.org/badge/get-it-on-it.png" alt="Scaricalo su F-Droid" height="100"></a>
<a href="https://play.google.com/store/apps/details?id=eu.monocles.chat"><img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/art/GetItOnGooglePlay_Badge_Web_color_Italian.png" alt="Disponibile su Google Play" height="100"></a>

Gli APK nightly e beta sono disponibili su Codeberg nella sezione [Releases](https://codeberg.org/monocles/monocles_chat/releases).

LaNoxe richiede Android 7.0 o superiore.

## Compilare dal codice sorgente

Servono l'SDK e l'NDK di Android e una toolchain Rust. La libreria libsignal viene compilata dal sottomodulo `deps/libsignal` invece di usare binari precompilati.

```
git clone --recurse-submodules https://codeberg.org/monocles/monocles_chat.git
cd monocles_chat
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
./gradlew assembleMonocleschatFreeRelease
```

Se hai clonato senza sottomoduli, esegui prima `git submodule update --init --recursive`.

I test unitari richiedono anche libsignal compilata per il tuo computer. Compilala una volta e di nuovo ogni volta che il sottomodulo `deps/libsignal` cambia; altrimenti i test che la usano falliscono o girano con una versione superata:

```
deps/libsignal/java/build_jni.sh desktop
./gradlew testMonocleschatFreeDebugUnitTest
```

## Community

Seguici su <a rel="me" href="https://monocles.social/@monocles">monocles social</a>.

Gestiamo chat di gruppo XMPP in inglese e tedesco:

* Supporto: [support@conference.monocles.eu](xmpp:support@conference.monocles.eu?join), per domande e aiuto in caso di problemi
* Sviluppo: [development@conference.monocles.de](xmpp:development@conference.monocles.de?join), se vuoi partecipare allo sviluppo

## Come posso aiutare con le traduzioni?

Puoi migliorare o creare traduzioni su [Codeberg Translate](https://translate.codeberg.org/projects/monocles_chat/). Grazie mille.

## Aiuto! Ho riscontrato un problema!

Il modo più semplice per ottenere aiuto è entrare nella nostra [chat di supporto](xmpp:support@conference.monocles.eu?join) (inglese e tedesco).

Se non riusciamo a risolvere il problema lì, puoi aprire una [segnalazione](https://codeberg.org/monocles/monocles_chat/issues). Descrivi il problema, spiega come riprodurlo e allega i log. Più sotto trovi come creare i file di log.

### Come creare log di debug? (adb)

#### GNU/Linux, macOS e altri sistemi Unix-like:

1. Installa prima l'**A**ndroid **D**ebug **B**ridge (adb), se non è già presente.
    ###### Debian e derivate come Ubuntu / Linux Mint
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
    ###### altri sistemi
    Installa adb nel modo adatto al tuo sistema.

2. Apri un terminale e spostati in una directory a tua scelta con `cd`.

3. Segui i passaggi da [6] a [10] delle istruzioni per Windows.

4. Avvia la scrittura del log in un file sul tuo computer. Usiamo `logcat.txt`. LaNoxe deve essere in esecuzione. Inserisci:
    ```
    $ adb -d logcat -v time --pid=$(adb -d shell pidof -s de.monocles.chat) > logcat.txt
    ```

5. Segui i restanti passaggi [12] e [13] delle istruzioni per Windows.

#### Windows:

1. Scarica gli SDK Platform Tools di Google:

    https://developer.android.com/tools/releases/platform-tools
2. Se non sono inclusi, ti serve anche il driver USB per la tua versione di Windows:

    https://developer.android.com/studio/run/win-usb
3. Estrai l'archivio zip (ad esempio in `C:\ADB\`).
4. Apri il prompt dei comandi (CMD) dal menu Start: Start > Esegui: cmd
5. Spostati nella directory in cui hai estratto l'archivio. Usiamo `C:\ADB\`:
    ```
    c:
    cd ADB
    ```
6. Sullo smartphone apri le impostazioni e cerca `Opzioni sviluppatore`. Se non è presente, devi prima sbloccarla: vai in `Impostazioni > Info sul telefono`, trova `Numero build` (o simile) e toccalo 7 volte di seguito. Una notifica ti conferma che ora sei uno sviluppatore e le `Opzioni sviluppatore` compaiono nelle impostazioni.
7. In `Opzioni sviluppatore` attiva `Debug USB` (a volte chiamato `Debug Android`).
8. Collega il telefono al computer con un cavo USB. I driver mancanti dovrebbero venire installati. Su Windows dovrebbero essere scaricati automaticamente se hai seguito il passaggio [2]. Sulla maggior parte dei sistemi GNU/Linux non serve altro.
9. Se tutto ha funzionato, torna al prompt dei comandi e verifica che il dispositivo venga riconosciuto. Inserisci `adb devices -l`. Dovresti vedere un output simile a:
    ```
    > adb devices -l
    List of devices attached
    * daemon not running. starting it now on port 5037 *
    * daemon started successfully *
    042111560169500303f4   unauthorized
    ```
10. Se il dispositivo risulta `unauthorized`, accetta sul telefono la richiesta di consentire il debug USB. Eseguendo di nuovo `adb devices` dovresti vedere:
    ```
    > adb devices
    List of devices attached
    042111560169500303f4    device
    ```
11. Avvia la scrittura del log nel file `logcat.txt` in `C:\ADB\`. Inserisci quanto segue (senza `> `):
    ```
    > adb -d logcat -v time | FINDSTR /I monocles > logcat.txt
    ```
12. Ora riproduci il problema.

13. Interrompi la registrazione (`Ctrl+C`). Controlla attentamente il file di log e rimuovi ogni informazione personale o privata. Poi invialo con una descrizione dettagliata del problema e di come riprodurlo tramite il [tracker delle segnalazioni](https://codeberg.org/monocles/monocles_chat/issues).

## Licenza

LaNoxe è software libero: puoi ridistribuirlo e/o modificarlo secondo i termini della [GNU Affero General Public License](LICENSE) pubblicata dalla Free Software Foundation, esclusivamente nella versione 3 (AGPL-3.0-only).

L'app include [libsignal](https://github.com/signalapp/libsignal) (Copyright © Signal Messenger, LLC), rilasciata esclusivamente sotto la versione 3 della GNU AGPL. L'app nel suo complesso è quindi distribuita secondo i termini dell'AGPLv3, esclusivamente versione 3. Ciò include l'obbligo di rendere disponibile ai suoi utenti il codice sorgente corrispondente completo. Il codice proveniente da Conversations, blabber.im e Cheogram, la libreria [libsignal-protocol-java](https://github.com/signalapp/libsignal-protocol-java) inclusa per l'OMEMO legacy, così come i contributi fatti quando questo repository era sotto GPLv3, è rilasciato sotto la GNU GPLv3; la sezione 13 della GPLv3 consente di combinarlo con codice AGPLv3.

LaNoxe è distribuito nella speranza che sia utile, ma SENZA ALCUNA GARANZIA; senza neppure la garanzia implicita di COMMERCIABILITÀ o di IDONEITÀ PER UN PARTICOLARE SCOPO. Per i dettagli consulta il file [LICENSE](LICENSE).

Copyright © 2020-2026 Arne-Brün Vogelsang e collaboratori
