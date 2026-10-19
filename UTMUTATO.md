# BlueTune – beállítási útmutató

Sorrendben haladj, minden lépés **ingyenes** (a Play Áruház a végén, külön).
Becsült idő: 45–60 perc.

| # | Lépés | Költség |
|---|-------|---------|
| 1 | GitHub repó + első build | ingyenes |
| 2 | Firebase (fiók, adatbázis) | ingyenes (Spark csomag) |
| 3 | Weboldal: adatvédelem + fióktörlés | ingyenes (GitHub Pages) |
| 4 | Brevo hírlevél | ingyenes (napi 300 levélig) |
| 5 | Saját aláírókulcs | ingyenes |
| 6 | „Nem vírusos” igazolás | ingyenes |
| 7 | Google Play (később) | egyszeri 25 $ |
| 8 | Windows (.exe) | ingyenes |
| 9 | iPhone (iOS) | build ingyenes; telepítés ingyen 7 napos, vagy 99 $/év |

---

## 1. GitHub repó + első build

1. github.com → **New repository** → név: `BlueTune`.
   - **Public** legyen, ha a weboldalt (3. lépés) ingyen GitHub Pages-en akarod futtatni.
     Ha privátot szeretnél, csinálj mellé egy külön *publikus* `bluetune-site` repót csak a `docs/` mappának.
2. Töltsd fel a projekt tartalmát (webes felületen: *Add file → Upload files*, a `.github` mappával együtt; vagy git-tel).
3. **Actions** fül → az „Android build” lefut (~5 perc) → a futás alján **Artifacts → BlueTune-build**.
   Ebben van: `BlueTune.apk` (telepíthető), `BlueTune-play.aab` (Play-hez), `SHA256SUMS.txt`, `SIGNATURE.txt`.

> Az első build még Firebase és saját kulcs nélkül fut – a figyelmeztetések ilyenkor normálisak.

---

## 2. Firebase

1. [console.firebase.google.com](https://console.firebase.google.com) → **Projekt létrehozása** → név: `BlueTune`
   (a Google Analytics-et nyugodtan kikapcsolhatod).
2. **Android app hozzáadása** (Android ikon):
   - csomagnév: `hu.bluetune`
   - becenév: BlueTune
   - SHA-1: most kihagyhatod
   - Töltsd le a **google-services.json** fájlt.
3. **Build → Authentication → Get started → Sign-in method → Email/Password → Enable** → Mentés.
   - Az „Email link (passwordless)” maradjon kikapcsolva. Más szolgáltatót (Google, Anonymous) **ne** kapcsolj be.
4. **Authentication → Templates** (Sablonok):
   - fent a nyelvet állítsd **Magyar**-ra,
   - mindhárom sablonnál (Email cím megerősítése, Jelszó visszaállítása, Email cím módosítása) a **Feladó neve** legyen `BlueTune`.
5. **Authentication → Settings → User actions**: hagyd bekapcsolva az *Email enumeration protection*-t (védi a regisztrált címeket).
6. **Build → Firestore Database → Create database**
   - hely: **eur3 (Europe)** – így az adatok az EU-ban maradnak,
   - indulás *production mode*-ban.
7. Firestore → **Rules** fül → töröld ki a tartalmát, másold be a projekt `firestore.rules` fájlját → **Publish**.
8. A `google-services.json` a GitHubra – **Secretként, ne fájlként**:
   repó → **Settings → Secrets and variables → Actions → New repository secret**
   - Name: `GOOGLE_SERVICES_JSON`
   - Secret: a fájl teljes tartalma (Jegyzettömbben nyisd meg, Ctrl+A, Ctrl+C).
9. **Web app** a fióktörlő oldalhoz: Projektbeállítások (fogaskerék) → Általános → *Saját alkalmazások* → **</>** ikon →
   név: `BlueTune web` (Hosting NEM kell) → a kapott `firebaseConfig` négy értékét (`apiKey`, `authDomain`, `projectId`, `appId`)
   írd be a `docs/firebase-config.js` fájlba.
10. **Szolgáltatásfiók a hírlevél-szinkronhoz:** Projektbeállítások → **Service accounts** → *Generate new private key* →
    letöltődik egy JSON. Ezt is Secretként mentsd: `FIREBASE_SERVICE_ACCOUNT` = a fájl teljes tartalma.
    ⚠️ Ez a kulcs teljes hozzáférést ad a projekthez – sehova máshova ne töltsd fel, és utána töröld a gépedről.

Indíts újra egy buildet (Actions → Android build → *Run workflow*). Az új APK-ban már működik a **Fiók** fül.

---

## 3. Weboldal (adatvédelmi tájékoztató + fióktörlés)

A Google Play kötelezően kér egy adatvédelmi tájékoztatót és egy weboldalt, ahol a felhasználó app nélkül is törölheti a fiókját.
Mindkettő kész a `docs/` mappában.

1. `docs/privacy.html`: a **[KAPCSOLATI EMAIL]** helyére írd be a címet, amin elérhető vagy (ez nyilvános lesz).
2. repó → **Settings → Pages** → *Source: Deploy from a branch* → Branch: `main`, mappa: `/docs` → Save.
3. Pár perc múlva elérhető: `https://<felhasználónév>.github.io/BlueTune/`
4. Ezt a címet írd be a `gradle.properties` fájlba:
   ```
   bluetune.siteUrl=https://<felhasználónév>.github.io/BlueTune
   ```
   Így az appban a „Adatvédelem” és „Fióktörlés weben” linkek ide mutatnak.
5. Próbáld ki a `delete-account.html` oldalt egy tesztfiókkal.

> Az adatvédelmi szöveget a kód alapján írtam meg, hogy pontosan azt írja le, amit az app csinál – de nem jogász írta.
> Ha később bevételt termel az app vagy cég mögé kerül, érdemes egyszer átnézetni.

---

## 4. Brevo (frissítés-értesítő levelek)

Így működik: az app a feliratkozást (csak ha a felhasználó bepipálta) a Firestore `subscribers` gyűjteményébe írja →
egy GitHub Actions folyamat 6 óránként átmásolja a **megerősített** címeket a Brevo listádra → onnan küldöd a levelet.
Leiratkozás / fióktörlés esetén a cím a Brevóból is törlődik.

1. [brevo.com](https://www.brevo.com) → ingyenes regisztráció (Free csomag: napi 300 levél).
2. **Feladó**: *Settings → Senders, domains & dedicated IPs → Senders → Add a sender* → név `BlueTune`, email: a saját címed → erősítsd meg.
   (Gmail-es feladóval is megy; jobb kézbesíthetőséghez később érdemes saját domaint használni.)
3. **Lista**: *Contacts → Lists → Create a list* → név: `BlueTune frissítések` → a lista **ID**-ja a listák táblázatában látszik (egy szám).
4. **API kulcs**: jobb fent a neved → *SMTP & API → API Keys → Generate a new API key* → másold ki (csak egyszer látszik).
5. GitHub Secretek (Settings → Secrets and variables → Actions):

   | Név | Érték |
   |-----|-------|
   | `BREVO_API_KEY` | az API kulcs |
   | `BREVO_LIST_ID` | a lista ID-ja (szám) |
   | `BREVO_SENDER_EMAIL` | a megerősített feladó cím |
   | `BREVO_SENDER_NAME` | `BlueTune` |

6. Próba: **Actions → Hírlevél szinkron → Run workflow** (a „Csak próba” be van pipálva) → a naplóban látod, kit adna hozzá.
   Ha jó, futtasd a pipa nélkül is. Utána magától fut 6 óránként.

### Levél küldése új verziónál
**Actions → Frissítés-értesítő küldése → Run workflow:**
- `version`: pl. `1.2.0`
- `changes`: pl. `Új Autó preset\nGyorsabb indulás\nHibajavítások`
- `mode`: először **test**, `test_email`: a saját címed → megnézed, hogy néz ki
- ha tetszik: ugyanez **send** módban → kimegy minden feliratkozónak.

> Ha 60 napig nincs commit a repóban, a GitHub leállítja az időzített futást – ilyenkor az Actions fülön egy kattintással újraindítható.

---

## 5. Saját aláírókulcs (digitális aláírás)

Minden Android-app digitálisan alá van írva. Most a GitHub egy ideiglenes *debug* kulccsal ír alá – ezt cseréljük a **saját kulcsodra**.
Ez igazolja, hogy minden frissítés tőled jön: más nem tud „BlueTune frissítést” kiadni, mert nincs meg neki a kulcsod.

### Kulcs létrehozása (egyszer, a saját gépeden)
Kell hozzá Java (`keytool`). Ha van Android Studio, az tartalmazza. PowerShellben:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore bluetune-upload.jks -alias bluetune `
  -keyalg RSA -keysize 4096 -validity 10000
```
(Ha nincs Android Studio: telepítsd az ingyenes [Temurin JDK 17](https://adoptium.net)-et, és simán `keytool ...`.)

Megkérdezi a jelszót és a nevedet/helyet. A jelszót **írd fel**.

### Feltöltés a GitHubra (Secretként)
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("bluetune-upload.jks")) | Set-Clipboard
```
Ez a vágólapra másolja. Secretek:

| Név | Érték |
|-----|-------|
| `KEYSTORE_BASE64` | a vágólap tartalma |
| `KEYSTORE_PASSWORD` | a kulcstár jelszava |
| `KEY_ALIAS` | `bluetune` |
| `KEY_PASSWORD` | a kulcs jelszava (ha nem adtál meg külön, ugyanaz) |

### ⚠️ Mentsd el biztonságosan!
A `bluetune-upload.jks` fájlt és a jelszót tedd el **legalább két helyre** (pl. pendrive + jelszókezelő).
Ha elveszik, a már telepített appokat nem tudod frissíteni. (A Play Áruházban ez visszaállítható, mert ott a Google tárolja
a végleges kulcsot, de GitHubos terjesztésnél nem.) **Soha ne töltsd fel a repóba** – a `.gitignore` ezt eleve tiltja.

Következő buildtől a `SIGNATURE.txt` a te tanúsítványod ujjlenyomatát mutatja – ez minden kiadásnál ugyanaz lesz.

---

## 6. „Nem vírusos” igazolás – ingyen

Androidra nem kell (és nem is lehet) külön vírusmentességi tanúsítványt vásárolni. Ami ténylegesen számít:

1. **Saját aláírás** (5. lépés) – bizonyítja, hogy az app tőled származik és senki nem módosította.
2. **Ellenőrzőösszeg + ujjlenyomat** – minden GitHub Release-hez automatikusan csatolódik a `SHA256SUMS.txt` és `SIGNATURE.txt`.
3. **VirusTotal** – [virustotal.com](https://www.virustotal.com) → töltsd fel a `BlueTune.apk`-t → ~70 víruskereső vizsgálja meg.
   A kapott linket tedd bele a GitHub Release leírásába („VirusTotal: 0/70”).
4. **Google Play Protect** – a Play Áruházba feltöltött appokat a Google automatikusan átvizsgálja; ez a legerősebb bizalmi jel.
5. **Android fejlesztői ellenőrzés** – a Google fokozatosan bevezeti, hogy a nem Play-ről telepített appok is ellenőrzött
   fejlesztőhöz legyenek kötve (2026-ban néhány országban indult, 2027-től globális). Play-fiókkal ez automatikusan megvan;
   GitHubos terjesztésnél az ingyenes Android Developer Console-ban kell regisztrálni.

---

## 7. Google Play Áruház (amikor készen állsz)

### Költség és feltételek
- [play.google.com/console](https://play.google.com/console) → **egyszeri 25 $** regisztráció, személyi igazolvánnyal való azonosítás.
- **Személyes fióknál** (2023. nov. 13. után létrehozva) éles kiadás előtt kötelező egy **zárt teszt legalább 12 tesztelővel,
  14 egymást követő napig**. Kezdd el időben gyűjteni a tesztelőket (barátok, család – Gmail-cím kell).
- Az app már **API 36-ot céloz** (ez 2026. augusztus 31. óta kötelező új appoknál).

### Feltöltés
1. *Create app* → név `BlueTune`, nyelv magyar, App, Ingyenes.
2. **Play App Signing**: fogadd el (alapértelmezett). A `BlueTune-play.aab`-t a saját kulcsoddal aláírva töltöd fel
   (ez lesz a *feltöltési kulcs*), a Google pedig a saját, biztonságosan tárolt kulcsával írja alá a végleges appot.
3. *Testing → Closed testing* → töltsd fel a `BlueTune-play.aab`-t → add hozzá a tesztelőket.

### App content (kötelező kérdőívek) – javasolt válaszok
- **Privacy policy:** `https://<felhasználónév>.github.io/BlueTune/privacy.html`
- **Data safety:**
  - Gyűjt adatot: Igen → **Személyes adatok → Email cím** (Fiókkezelés, Fejlesztői kommunikáció), *opcionális*.
  - **Alkalmazástevékenység → Egyéb, felhasználó által létrehozott tartalom** (presetek, mentés), opcionális.
  - Megosztás harmadik féllel: Nem (a Firebase és a Brevo adatfeldolgozó, nem „megosztás”).
  - Titkosítás átvitel közben: **Igen**. Törlés kérhető: **Igen** → URL: `.../delete-account.html`
- **Account deletion URL:** `.../delete-account.html`
- **Foreground service (előtér-szolgáltatás) nyilatkozat:** típus *Special use* → leírás: „Rendszerszintű hangszínszabályzó,
  amely a háttérben alkalmazza a felhasználó által beállított EQ-t a Bluetooth hangkimenetre; az értesítésből kikapcsolható.”
  + egy rövid képernyővideó (YouTube nem listázott link), ahogy bekapcsolod az EQ-t és megjelenik az értesítés.
- **Content rating:** kérdőív → minden „Nem” → várhatóan PEGI 3.
- **Target audience:** 16+ (a fiók miatt).
- **Ads:** Nem tartalmaz hirdetést.

### Áruházi adatlap
- Rövid leírás (max. 80 karakter), pl.: *„10 sávos EQ, basszus és hangerő-boost Bluetooth fejhallgatóhoz és autóhoz”*
- Ikon 512×512, kiemelt grafika 1024×500, legalább 2 képernyőkép – ebben szívesen segítek.

---

## 8. Windows (.exe)

### Letöltés
Minden push után az **Actions → Windows build → Artifacts → BlueTune-windows** alatt ott a `BlueTune.exe`
(egyetlen fájl, telepítés nélkül fut, a .NET is benne van). `v*` tagnél a GitHub Release-be is bekerül.

### Első indítás (egyszer kell)
A Windows nem ad saját, rendszerszintű hangszínszabályzót, ezért a BlueTune az ingyenes, nyílt forrású
**Equalizer APO**-t használja hangmotorként (erre épül pl. a Peace is). A BlueTune ezt kezeli, te csak a csúszkákat látod.

1. Töltsd le és telepítsd: [Equalizer APO](https://sourceforge.net/projects/equalizerapo/) (a BlueTune-ban is van rá gomb).
2. A telepítő végén megnyíló **Configurator**-ban pipáld be:
   - a **Bluetooth fejhallgatódat / hangszóródat** (ha két bejegyzés van – „Stereo” és „Hands-Free” –, a *Stereo*-t),
   - és ha akarod, a gép hangszóróját is.
   Ha később új eszközt veszel, a BlueTune-ban az *Eszközök kiválasztása (Configurator)* gombbal pótolhatod.
3. **Indítsd újra a gépet** (ez az Equalizer APO-nak kell).
4. Indítsd a `BlueTune.exe`-t → kattints a **Beállítás** gombra → a Windows rákérdez (UAC) → **Igen**.
   Ez az egyetlen lépés, ami rendszergazdai jogot kér: a BlueTune elmenti az Equalizer APO régi beállítását
   (`config.txt.bluetune-backup`), és onnantól ő vezérli.

> Ha egy Bluetooth eszközön nem hallod a hatást: Configurator → az eszköznél *Troubleshooting options* →
> pipáld be az *Install APO as SFX/EFX* opciót → újraindítás.

### Háttérben futás
- Az ablak bezárása (X) **nem lép ki** – a BlueTune a tálcán (az óra mellett) fut tovább, az EQ aktív marad.
- Tálcaikon: bal klikk = megnyitás, jobb klikk = EQ be/ki, presetek, **⏹ Leállítás (kilépés)**.
- Leállításkor az effekt lekerül a hangról.
- Alapból **a Windows-zal együtt indul** (a tálcára, ablak nélkül) – ez a Beállításokban vagy a tálcamenüben kikapcsolható.
- Ha újra elindítod az exe-t, nem indul második példány, csak előjön az ablak.

### Mit tud Windowson
10 sávos EQ, előerősítés, basszus, magas, Bass Boost, balansz, hangerő-boost, túlvezérlés-védelem, hangerő-csúszka
és hallásvédelmi korlát, **eszközönkénti automatikus profil** (fejhallgató, hangszóró, monitor külön hangzással),
18 beépített preset + saját presetek. Az export/import formátuma **azonos a telefonos appéval**, így a presetjeidet
átviheted telefon ↔ gép között.
Ami csak Androidon van: térhatás (Windowson a beépített *Windows Sonic* való erre – van rá gomb), akku- és kodekkijelzés
(a Windows a Bluetooth-beállításokban mutatja).

### „A Windows megvédte a számítógépet” üzenet
Új, aláíratlan exe-nél a SmartScreen figyelmeztet. Ilyenkor **További információ → Futtatás mindenképp**.
Ez nem vírusjelzés, csak azt jelenti, hogy a fájlt még kevesen futtatták. Ellenőrizni így tudod:
- a `SHA256SUMS-windows.txt` alapján (PowerShell: `Get-FileHash BlueTune.exe`),
- és a [VirusTotal](https://www.virustotal.com)-on.
A figyelmeztetés teljesen csak fizetős kódaláíró tanúsítvánnyal tűnik el (pl. Azure Artifact Signing, havi díjas) –
ez akkor éri meg, ha sokan töltik le.

---

## 9. iPhone (iOS)

### Mit tud – és mit nem
Az iOS **nem engedi**, hogy egy app más appok (Spotify, YouTube, Apple Music) hangját módosítsa, és a Bluetooth
akkuszintet / kodeket sem adja ki. Ezért iPhone-on a BlueTune **saját lejátszóval** szól, és a te zenéidre alkalmazza
ugyanazt a hangzást, mint Androidon:

- lejátszó: zenék importálása (Fájlok app, iCloud Drive) vagy bemásolása, véletlen sorrend, ismétlés, tekerés
- 10 sávos EQ, előerősítés, basszus, magas, Bass Boost, térhatás, balansz, hangerő-boost, **limiter**
- **háttérben és lezárt képernyőn is szól**; a lezárási képernyőn / Vezérlőközpontban lejátszás, szünet, léptetés
- a lejátszó fülön a **⏹ Leállítás** mindent leállít (utána az app nem fut a háttérben)
- kimenetválasztó (Bluetooth, AirPlay, autó) és **eszközönkénti automatikus profil**
- ugyanaz a fiók (email + jelszó), hírlevél, felhős mentés, közösségi presetek, fióktörlés
- a presetek formátuma azonos az androidos és a windowsos appéval

### Build
Minden push után az **Actions → iOS build → Artifacts → BlueTune-ios** alatt ott a `BlueTune-unsigned.ipa`.
A GitHub egy Macen buildeli – neked nem kell Mac.

> **Publikus repónál ingyenes.** Privát repónál a GitHub a macOS-perceket tízszeres szorzóval számolja
> (havi 2000 ingyenes percből így kb. 200 macOS-perc marad, egy build ~10–15 perc).

### Firebase iOS-hez (opcionális)
1. Firebase konzol → Projektbeállítások → *Saját alkalmazások* → **iOS app hozzáadása** → Bundle ID: `hu.bluetune`.
2. Töltsd le a **GoogleService-Info.plist**-et.
3. GitHub Secret: `GOOGLE_SERVICE_INFO_PLIST` = a fájl teljes tartalma (Jegyzettömbbel megnyitva).
4. `ios/BlueTune/Config.swift`: a `siteURL` legyen ugyanaz, mint Androidon (adatvédelem + fióktörlés linkek).

### Telepítés a telefonra

**A) Ingyen, Windowsról – Sideloadly + ingyenes Apple ID (tesztelésre)**
1. Telepítsd az [Apple Devices](https://apps.microsoft.com/detail/9np83lwlpz9k) (vagy iTunes) appot és a [Sideloadly](https://sideloadly.io)-t.
2. Dugd be az iPhone-t USB-n, a telefonon fogadd el: *Megbízol ebben a számítógépben?* → Igen.
3. Sideloadly: húzd bele a `BlueTune-unsigned.ipa`-t → írd be az Apple ID-d → **Start**.
4. iPhone: *Beállítások → Adatvédelem és biztonság → Fejlesztői mód* → bekapcsolás (újraindul),
   majd *Beállítások → Általános → VPN és eszközkezelés* → a saját Apple ID-d → **Megbízom**.
5. Korlát: ingyenes Apple ID-vel az app **7 napig** fut, utána a Sideloadlyval újra kell telepíteni
   (a beállítások és zenék megmaradnak).

**B) Rendesen – Apple Developer Program (99 $/év)**
TestFlight-on akár 10 000 tesztelőnek kiküldheted, és az App Store-ba is feltöltheted.
Ha lesz fiókod, kiegészítem a GitHub-folyamatot aláírt buildre és automatikus TestFlight-feltöltésre
(ehhez a tanúsítványt és az App Store Connect API-kulcsot kell majd Secretként megadni).

### Zene feltöltése
- Az appban a **+** gombbal: Fájlok app, iCloud Drive, Letöltések.
- Vagy Fájlok app → *Saját iPhone* → **BlueTune** mappába másolás.
- Windowsról: Apple Devices / iTunes → az iPhone → *Fájlok* → BlueTune → húzd be a zenéket.
- Formátumok: MP3, M4A/AAC, WAV, AIFF, FLAC, ALAC (a DRM-védett Apple Music számok nem játszhatók le).
