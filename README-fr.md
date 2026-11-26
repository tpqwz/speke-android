# <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/src/monocleschat/ic_launcher-playstore.png" width="30"> LaNoxe

🇬🇧 [English](README.md) / 🇩🇪 [Deutsch](README-de.md) / 🇮🇹 [Italiano](README-it.md) / 🇵🇱 [Polski](README-pl.md) / 🇹🇷 [Türkçe](README-tr.md)

LaNoxe est un client XMPP moderne et sécurisé pour Android. Il est basé sur [Conversations](https://codeberg.org/iNPUTmice/Conversations), intègre des fonctionnalités de [blabber.im](https://codeberg.org/kriztan/blabber.im) et de [Cheogram](https://git.sr.ht/~singpolyma/cheogram-android), et ajoute de nombreuses modifications et fonctionnalités propres pour améliorer la facilité d'utilisation et la sécurité.

<img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/00.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/01.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/02.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/03.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/04.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/05.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/06.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/07.png" width="200" />

Écrans conçus par Pigeonalley (https://pigeonalley.com)

## Fonctionnalités

* Chiffrement de bout en bout avec [OMEMO](https://conversations.im/omemo/) ou [OpenPGP](https://www.openpgp.org/about/)
* Chiffrement de bout en bout post-quantique expérimental (PQ OMEMO2)
* Connexions chiffrées uniquement, TLS 1.3 exigé par défaut ; les suites cryptographiques obsolètes ont été supprimées
* Résolution du serveur vérifiée par DNSSEC et contrôle des certificats par DANE (peut être imposé)
* Protection contre les attaques par rétrogradation lors de la connexion : liaison de canal et protection SCRAM contre la rétrogradation (XEP-0440 / XEP-0474) ; jamais de retour à PLAIN après une connexion SCRAM
* Connexion par OAuth (OAUTHBEARER) sur les serveurs qui le proposent
* Connexions via Tor et I2P, y compris vers des serveurs .onion et .i2p
* Base de données locale chiffrée avec mot de passe de démarrage optionnel
* Envoi et réception d'images, de vidéos, de fichiers, de positions et de messages vocaux
* Mise en forme des messages (XEP-0393) : `*gras*`, `_italique_`, `~barré~`, texte à chasse fixe et blocs de code
* Appels audio et vidéo
* Discussions de groupe et canaux
* Historique des messages synchronisé sur tous vos appareils
* Photos de profil, messages de statut et stories
* Intégration du carnet d'adresses (votre carnet d'adresses n'est jamais partagé avec le serveur)
* Sauvegarde quotidienne de la base de données dans le stockage local
* Très faible consommation de batterie

## Télécharger

<a href="https://f-droid.org/app/de.monocles.chat"><img src="https://f-droid.org/badge/get-it-on-fr.png" alt="Disponible sur F-Droid" height="100"></a>
<a href="https://play.google.com/store/apps/details?id=eu.monocles.chat"><img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/art/GetItOnGooglePlay_Badge_Web_color_French.png" alt="Disponible sur Google Play" height="100"></a>

Les APK nightly et bêta sont disponibles sur Codeberg dans [Releases](https://codeberg.org/monocles/monocles_chat/releases).

LaNoxe nécessite Android 7.0 ou une version plus récente.

## Compiler depuis les sources

Vous avez besoin du SDK et du NDK Android ainsi que d'une chaîne d'outils Rust. La bibliothèque libsignal est compilée depuis le sous-module `deps/libsignal` au lieu d'utiliser des binaires précompilés.

```
git clone --recurse-submodules https://codeberg.org/monocles/monocles_chat.git
cd monocles_chat
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
./gradlew assembleMonocleschatFreeRelease
```

Si vous avez cloné sans les sous-modules, exécutez d'abord `git submodule update --init --recursive`.

Les tests unitaires ont aussi besoin de libsignal compilée pour votre propre ordinateur. Compilez-la une fois, puis à chaque changement du sous-module `deps/libsignal` ; sinon les tests qui l'utilisent échouent ou s'exécutent avec une version périmée :

```
deps/libsignal/java/build_jni.sh desktop
./gradlew testMonocleschatFreeDebugUnitTest
```

## Communauté

Suivez-nous sur <a rel="me" href="https://monocles.social/@monocles">monocles social</a>.

Nous proposons des discussions de groupe XMPP en anglais et en allemand :

* Support : [support@conference.monocles.eu](xmpp:support@conference.monocles.eu?join), pour les questions et l'aide en cas de problème
* Développement : [development@conference.monocles.de](xmpp:development@conference.monocles.de?join), si vous souhaitez participer au développement

## Comment puis-je aider à la traduction ?

Vous pouvez améliorer ou créer des traductions sur [Codeberg Translate](https://translate.codeberg.org/projects/monocles_chat/). Merci beaucoup.

## À l'aide ! J'ai rencontré un problème !

Le moyen le plus simple d'obtenir de l'aide est de rejoindre notre [discussion de support](xmpp:support@conference.monocles.eu?join) (anglais et allemand).

Si nous ne pouvons pas résoudre votre problème là-bas, vous pouvez ouvrir un [ticket](https://codeberg.org/monocles/monocles_chat/issues). Décrivez votre problème, expliquez comment le reproduire et joignez des journaux. Voir ci-dessous comment créer des fichiers journaux.

### Comment créer des journaux de débogage ? (adb)

#### GNU/Linux, macOS et autres systèmes de type Unix :

1. Installez d'abord l'**A**ndroid **D**ebug **B**ridge (adb), s'il n'est pas déjà installé.
    ###### Debian et dérivés comme Ubuntu / Linux Mint
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
    ###### autres systèmes
    Installez adb de la manière adaptée à votre système.

2. Ouvrez un terminal et allez dans le répertoire de votre choix avec `cd`.

3. Suivez les étapes [6] à [10] des instructions pour Windows.

4. Commencez à écrire le journal dans un fichier sur votre ordinateur. Nous utilisons `logcat.txt`. LaNoxe doit être en cours d'exécution. Entrez :
    ```
    $ adb -d logcat -v time --pid=$(adb -d shell pidof -s de.monocles.chat) > logcat.txt
    ```

5. Suivez les étapes restantes [12] et [13] des instructions pour Windows.

#### Windows :

1. Téléchargez les SDK Platform Tools de Google :

    https://developer.android.com/tools/releases/platform-tools
2. S'il n'est pas inclus, vous avez aussi besoin du pilote USB pour votre version de Windows :

    https://developer.android.com/studio/run/win-usb
3. Extrayez l'archive zip (par exemple dans `C:\ADB\`).
4. Ouvrez l'invite de commande (CMD) depuis le menu Démarrer : Démarrer > Exécuter : cmd
5. Allez dans le répertoire où vous avez extrait l'archive. Nous utilisons `C:\ADB\` :
    ```
    c:
    cd ADB
    ```
6. Sur votre smartphone, ouvrez les paramètres et cherchez `Options pour les développeurs`. Si cette option n'apparaît pas, déverrouillez-la d'abord : allez dans `Paramètres > À propos du téléphone`, trouvez `Numéro de build` (ou similaire) et appuyez dessus 7 fois de suite. Une notification vous indique que vous êtes désormais développeur, et les `Options pour les développeurs` apparaissent dans les paramètres.
7. Dans les `Options pour les développeurs`, activez `Débogage USB` (parfois appelé `Débogage Android`).
8. Connectez votre téléphone à votre ordinateur avec un câble USB. Les pilotes manquants devraient alors s'installer. Sous Windows, ils devraient être téléchargés automatiquement si vous avez suivi l'étape [2]. Sur la plupart des systèmes GNU/Linux, rien d'autre n'est nécessaire.
9. Si tout a fonctionné, retournez dans l'invite de commande et vérifiez que votre appareil est reconnu. Entrez `adb devices -l`. Vous devriez voir une sortie similaire à :
    ```
    > adb devices -l
    List of devices attached
    * daemon not running. starting it now on port 5037 *
    * daemon started successfully *
    042111560169500303f4   unauthorized
    ```
10. Si votre appareil est affiché comme `unauthorized`, acceptez la demande sur votre téléphone qui demande d'autoriser le débogage USB. En relançant `adb devices`, vous devriez voir :
    ```
    > adb devices
    List of devices attached
    042111560169500303f4    device
    ```
11. Commencez à écrire le journal dans le fichier `logcat.txt` dans `C:\ADB\`. Entrez ce qui suit (sans le `> `) :
    ```
    > adb -d logcat -v time | FINDSTR /I monocles > logcat.txt
    ```
12. Reproduisez maintenant le problème.

13. Arrêtez la journalisation (`Ctrl+C`). Examinez attentivement votre fichier journal et supprimez toute information personnelle ou privée. Envoyez-le ensuite avec une description détaillée du problème et de la manière de le reproduire via le [gestionnaire de tickets](https://codeberg.org/monocles/monocles_chat/issues).

## Licence

LaNoxe est un logiciel libre : vous pouvez le redistribuer et/ou le modifier selon les termes de la [GNU Affero General Public License](LICENSE) telle que publiée par la Free Software Foundation, uniquement dans sa version 3 (AGPL-3.0-only).

L'application inclut [libsignal](https://github.com/signalapp/libsignal) (Copyright © Signal Messenger, LLC), distribuée sous la GNU AGPLv3 uniquement. L'application dans son ensemble est donc distribuée selon les termes de l'AGPLv3, version 3 uniquement. Cela inclut l'obligation de mettre l'intégralité du code source correspondant à la disposition de ses utilisateurs. Le code issu de Conversations, blabber.im et Cheogram, la bibliothèque [libsignal-protocol-java](https://github.com/signalapp/libsignal-protocol-java) intégrée pour l'OMEMO hérité, ainsi que les contributions faites lorsque ce dépôt était sous GPLv3, est sous licence GNU GPLv3 ; la section 13 de la GPLv3 permet de le combiner avec du code sous AGPLv3.

LaNoxe est distribué dans l'espoir qu'il sera utile, mais SANS AUCUNE GARANTIE ; sans même la garantie implicite de QUALITÉ MARCHANDE ou d'ADÉQUATION À UN USAGE PARTICULIER. Voir le fichier [LICENSE](LICENSE) pour plus de détails.

Copyright © 2020-2026 Arne-Brün Vogelsang et contributeurs
