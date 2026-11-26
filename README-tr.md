# <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/src/monocleschat/ic_launcher-playstore.png" width="30"> Speke

🇬🇧 [English](README.md) / 🇩🇪 [Deutsch](README-de.md) / 🇫🇷 [Français](README-fr.md) / 🇮🇹 [Italiano](README-it.md) / 🇵🇱 [Polski](README-pl.md)

Speke, Android için modern ve güvenli bir XMPP istemcisidir. [Conversations](https://codeberg.org/iNPUTmice/Conversations) tabanlıdır, [blabber.im](https://codeberg.org/kriztan/blabber.im) ve [Cheogram](https://git.sr.ht/~singpolyma/cheogram-android) uygulamalarından özellikler içerir ve kullanılabilirliği ve güvenliği artırmak için birçok kendi değişikliğini ve özelliğini ekler.

<img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/00.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/01.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/02.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/03.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/04.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/05.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/06.png" width="200" /> <img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/fastlane/metadata/android/en-US/phoneScreenshots/07.png" width="200" />

Ekranlar Pigeonalley tarafından tasarlanmıştır (https://pigeonalley.com)

## Özellikler

* [OMEMO](https://conversations.im/omemo/) veya [OpenPGP](https://www.openpgp.org/about/) ile uçtan uca şifreleme
* Deneysel kuantum sonrası uçtan uca şifreleme (PQ OMEMO2)
* Yalnızca şifreli bağlantılar, varsayılan olarak TLS 1.3 zorunlu; eski şifre takımları kaldırılmıştır
* DNSSEC ile doğrulanmış sunucu çözümleme ve DANE sertifika denetimi (zorunlu kılınabilir)
* Giriş sırasında sürüm düşürme saldırılarına karşı koruma: kanal bağlama ve SCRAM sürüm düşürme koruması (XEP-0440 / XEP-0474); SCRAM kullanıldıktan sonra asla PLAIN'e dönülmez
* Destekleyen sunucularda OAuth (OAUTHBEARER) ile giriş
* .onion ve .i2p sunucuları dahil Tor ve I2P üzerinden bağlantı
* İsteğe bağlı başlangıç parolasıyla şifrelenmiş yerel veritabanı
* Resim, video, dosya, konum ve sesli mesaj gönderme ve alma
* Mesaj biçimlendirme (XEP-0393): `*kalın*`, `_italik_`, `~üstü çizili~`, sabit genişlikli yazı ve kod blokları
* Sesli ve görüntülü aramalar
* Grup sohbetleri ve kanallar
* Tüm cihazlarınızda senkronize mesaj geçmişi
* Profil resimleri, durum mesajları ve hikayeler
* Adres defteri entegrasyonu (adres defteriniz asla sunucuyla paylaşılmaz)
* Veritabanının yerel depolamaya günlük yedeklenmesi
* Çok düşük pil tüketimi

## İndirme

<a href="https://f-droid.org/app/de.monocles.chat"><img src="https://f-droid.org/badge/get-it-on-tr.png" alt="F-Droid'de mevcut" height="100"></a>
<a href="https://play.google.com/store/apps/details?id=eu.monocles.chat"><img src="https://codeberg.org/monocles/monocles_chat/raw/branch/master/art/GetItOnGooglePlay_Badge_Web_color_Turkish.png" alt="Google Play'de mevcut" height="100"></a>

Gecelik (nightly) ve beta APK'ları Codeberg'de [Releases](https://codeberg.org/monocles/monocles_chat/releases) bölümünde bulabilirsiniz.

Speke, Android 7.0 veya üstünü gerektirir.

## Kaynak koddan derleme

Android SDK ve NDK ile bir Rust araç zinciri gerekir. libsignal kütüphanesi, önceden derlenmiş dosyalar yerine `deps/libsignal` alt modülünden kaynak koddan derlenir.

```
git clone --recurse-submodules https://codeberg.org/monocles/monocles_chat.git
cd monocles_chat
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
./gradlew assembleMonocleschatFreeRelease
```

Depoyu alt modüller olmadan klonladıysanız önce `git submodule update --init --recursive` komutunu çalıştırın.

Birim testleri ayrıca kendi bilgisayarınız için derlenmiş libsignal'a ihtiyaç duyar. Bunu bir kez ve `deps/libsignal` alt modülü her değiştiğinde yeniden derleyin; aksi halde onu kullanan testler başarısız olur ya da eski bir sürümle çalışır:

```
deps/libsignal/java/build_jni.sh desktop
./gradlew testMonocleschatFreeDebugUnitTest
```

## Topluluk

Bizi <a rel="me" href="https://monocles.social/@monocles">monocles social</a> üzerinden takip edin.

İngilizce ve Almanca XMPP grup sohbetlerimiz var:

* Destek: [support@conference.monocles.eu](xmpp:support@conference.monocles.eu?join), sorular ve sorunlarla ilgili yardım için
* Geliştirme: [development@conference.monocles.de](xmpp:development@conference.monocles.de?join), geliştirmeye katılmak istiyorsanız

## Çevirilere nasıl yardım edebilirim?

[Codeberg Translate](https://translate.codeberg.org/projects/monocles_chat/) üzerinden çevirileri iyileştirebilir veya yeni çeviriler oluşturabilirsiniz. Çok teşekkür ederiz.

## Yardım edin! Bir sorunla karşılaştım!

Yardım almanın en kolay yolu [destek grup sohbetimize](xmpp:support@conference.monocles.eu?join) katılmaktır (İngilizce ve Almanca).

Sorununuzu orada çözemezsek bir [sorun kaydı](https://codeberg.org/monocles/monocles_chat/issues) açabilirsiniz. Sorununuzu açıklayın, nasıl yeniden üretileceğini anlatın ve kayıt dosyalarını ekleyin. Kayıt dosyalarının nasıl oluşturulacağını aşağıda bulabilirsiniz.

### Hata ayıklama kayıtları nasıl oluşturulur? (adb)

#### GNU/Linux, macOS ve diğer Unix benzeri sistemler:

1. Henüz yüklü değilse önce **A**ndroid **D**ebug **B**ridge (adb) aracını yükleyin.
    ###### Debian ve Ubuntu / Linux Mint gibi türevleri
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
    ###### diğer sistemler
    adb'yi sisteminize uygun bir yöntemle yükleyin.

2. Bir terminal açın ve `cd` ile istediğiniz bir dizine gidin.

3. Windows talimatlarındaki [6] ile [10] arasındaki adımları izleyin.

4. Kaydı bilgisayarınızdaki bir dosyaya yazmaya başlayın. Biz `logcat.txt` kullanıyoruz. Speke çalışıyor olmalıdır. Şunu girin:
    ```
    $ adb -d logcat -v time --pid=$(adb -d shell pidof -s de.monocles.chat) > logcat.txt
    ```

5. Windows talimatlarındaki kalan [12] ve [13] numaralı adımları izleyin.

#### Windows:

1. Google'ın SDK Platform Tools paketini indirin:

    https://developer.android.com/tools/releases/platform-tools
2. Pakete dahil değilse Windows sürümünüz için USB sürücüsüne de ihtiyacınız var:

    https://developer.android.com/studio/run/win-usb
3. Zip arşivini çıkarın (örneğin `C:\ADB\` klasörüne).
4. Başlat menüsünden komut satırını (CMD) açın: Başlat > Çalıştır: cmd
5. Arşivi çıkardığınız dizine gidin. Biz `C:\ADB\` kullanıyoruz:
    ```
    c:
    cd ADB
    ```
6. Akıllı telefonunuzda ayarları açın ve `Geliştirici seçenekleri` öğesini arayın. Bu seçenek yoksa önce kilidini açmanız gerekir: `Ayarlar > Telefon hakkında` bölümüne gidin, `Derleme numarası` (veya benzeri) öğesini bulun ve art arda 7 kez dokunun. Artık geliştirici olduğunuzu bildiren bir bildirim görürsünüz ve `Geliştirici seçenekleri` ayarlarda görünür.
7. `Geliştirici seçenekleri` içinde `USB hata ayıklama` ayarını açın (bazen `Android hata ayıklama` olarak adlandırılır).
8. Telefonunuzu USB kablosuyla bilgisayarınıza bağlayın. Eksik sürücüler şimdi yüklenmelidir. [2] numaralı adımı izlediyseniz Windows'ta sürücüler otomatik olarak indirilir. Çoğu GNU/Linux sisteminde başka bir şey yapmanız gerekmez.
9. Her şey yolunda gittiyse komut satırına dönün ve cihazınızın tanınıp tanınmadığını kontrol edin. `adb devices -l` girin. Şuna benzer bir çıktı görmelisiniz:
    ```
    > adb devices -l
    List of devices attached
    * daemon not running. starting it now on port 5037 *
    * daemon started successfully *
    042111560169500303f4   unauthorized
    ```
10. Cihazınız `unauthorized` olarak görünüyorsa telefonunuzda USB hata ayıklamaya izin verilip verilmeyeceğini soran istemi kabul edin. `adb devices` komutunu yeniden çalıştırdığınızda şunu görmelisiniz:
    ```
    > adb devices
    List of devices attached
    042111560169500303f4    device
    ```
11. Kaydı `C:\ADB\` içindeki `logcat.txt` dosyasına yazmaya başlayın. Aşağıdakini girin (`> ` olmadan):
    ```
    > adb -d logcat -v time | FINDSTR /I monocles > logcat.txt
    ```
12. Şimdi sorunu yeniden oluşturun.

13. Kaydı durdurun (`Ctrl+C`). Kayıt dosyanızı dikkatlice inceleyin ve tüm kişisel veya özel bilgileri kaldırın. Ardından dosyayı, sorunun ayrıntılı açıklaması ve nasıl yeniden üretileceğiyle birlikte [sorun takip sistemi](https://codeberg.org/monocles/monocles_chat/issues) üzerinden gönderin.

## Lisans

Speke özgür bir yazılımdır: Free Software Foundation tarafından yayımlanan [GNU Affero Genel Kamu Lisansı](LICENSE)'nın yalnızca 3. sürümü (AGPL-3.0-only) koşulları altında yeniden dağıtabilir ve/veya değiştirebilirsiniz.

Uygulama, yalnızca GNU AGPL'nin 3. sürümüyle lisanslanan [libsignal](https://github.com/signalapp/libsignal) (Telif hakkı © Signal Messenger, LLC) kütüphanesini içerir. Bu nedenle uygulama bir bütün olarak yalnızca AGPLv3'ün 3. sürümü koşulları altında dağıtılır. Buna, ilgili kaynak kodun tamamını kullanıcılarına sunma yükümlülüğü de dahildir. Conversations, blabber.im ve Cheogram'dan alınan kod, eski OMEMO için paketlenen [libsignal-protocol-java](https://github.com/signalapp/libsignal-protocol-java) kütüphanesi ile bu depo GPLv3 altındayken yapılan katkılar GNU GPLv3 ile lisanslanmıştır; GPLv3'ün 13. bölümü bu kodun AGPLv3 koduyla birleştirilmesine izin verir.

Speke yararlı olması umuduyla dağıtılmaktadır, ancak HİÇBİR GARANTİSİ YOKTUR; SATILABİLİRLİK veya BELİRLİ BİR AMACA UYGUNLUK zımni garantisi bile yoktur. Ayrıntılar için [LICENSE](LICENSE) dosyasına bakın.

Telif hakkı © 2020-2026 Arne-Brün Vogelsang ve katkıda bulunanlar
