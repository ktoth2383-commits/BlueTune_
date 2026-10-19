# BlueTune 🎧

Android app Bluetooth hangeszközök „tuningolásához”: fejhallgató, fülhallgató, hangszóró, autós rendszer.
Kotlin + Jetpack Compose, opcionális Firebase felhővel, GitHub Actions APK-buildeléssel.

## Funkciók

**Hangzás**
- 10 sávos grafikus EQ (31 Hz – 16 kHz, ±15 dB) függőleges csúszkákkal, élő frekvenciagörbével
- Basszus és magas (treble) csúszka (shelf jellegű, a sávokra rárakódik)
- Előerősítés (preamp), L/R balansz
- Bass Boost, térhatás (Virtualizer / 3D)
- Hangerő boost (LoudnessEnhancer, +15 dB-ig) torzításvédő limiterrel
- Médiahangerő csúszka + hallásvédelmi maximum (a hangerőgombokkal sem lehet túllépni)

**Bluetooth**
- Aktív eszköz felismerése: A2DP, LE Audio (fejhallgató/hangszóró/Auracast), hallókészülék
- Akkuszint és aktív kodek kiolvasása (SBC / AAC / aptX / aptX HD / LDAC / LC3, mintavétel, bitmélység) – ahol a rendszer engedi
- **Eszközönkénti automatikus profil**: minden eszköz a saját hangzását és hangerejét kapja csatlakozáskor
- Gyorslinkek: Bluetooth, Hang, Fejlesztői beállítások (kodekválasztás, abszolút hangerő)
- Hangteszt: szinuszjel bal/jobb/mindkét csatornán, 20 Hz – 20 kHz sweep

**Presetek**
- 17 beépített (Rock, Pop, Hip-hop, Elektronikus, Vokál, Podcast, Autó, Fülhallgató korrekció, Esti, Film…)
- Saját presetek mentése, törlése, JSON export/import (megosztás bármilyen appon)

**Háttér**
- Az app megnyitása után háttérszolgáltatásként fut; az értesítésben **⏹ Leállítás** és EQ be/ki gomb
  (leállítás után a következő megnyitásig nem indul újra magától)
- Gyorsbeállítás-csempe (Quick Settings tile)
- Automatikus indítás bekapcsoláskor és Bluetooth csatlakozáskor
- Megjelenik a Spotify / YouTube Music „Hangszínszabályzó” menüjében, és rárakódik a lejátszók saját audio sessionjére is

**Fiók (Firebase, opcionális – az EQ fiók nélkül is működik)**
- Regisztráció és belépés **email + jelszóval**, email-megerősítés, elfelejtett jelszó, jelszócsere
- Hírlevél-feliratkozás (külön, nem előre bepipált hozzájárulás) → automatikus szinkron a **Brevóba**
- „Új verzió érhető el” levél kiküldése egy gombnyomásra (GitHub Actions)
- Felhős mentés/visszaállítás (presetek + eszközprofilok + aktuális hangzás) – új telefonra is átvihető
- Közösségi presetek: közzététel (megerősített email után), böngészés lájkok szerint, alkalmazás, mentés
- Fiók végleges törlése az appban és weben is (Play-követelmény), adatvédelmi tájékoztató

**Kiadás**
- Saját aláírókulcs GitHub Secretsből, APK + Play-re feltölthető AAB, SHA-256 + aláírás-ujjlenyomat minden kiadáshoz
- API 36 célzás (Play-követelmény 2026. aug. 31. óta)

**Windows (.exe)** – `desktop/`
- Egyetlen, telepítés nélkül futó `BlueTune.exe` (C# / .NET 10, a futtatókörnyezet is benne van)
- Tálcán fut a háttérben; bezárásra nem lép ki, a tálcamenüben: EQ be/ki, presetek, **Leállítás**; indulás a Windows-zal
- 10 sávos EQ, basszus, magas, Bass Boost, balansz, hangerő-boost, túlvezérlés-védelem, hangerő + hallásvédelmi korlát
- Eszközönkénti automatikus profil (Bluetooth fejhallgató, hangszóró, monitor…), presetek telefonnal közös formátumban
- Hangmotor: az ingyenes Equalizer APO (a Windowsnak nincs saját rendszerszintű EQ-ja)

**iPhone (iOS)** – `ios/`
- SwiftUI, a GitHub macOS-gépe buildeli (aláíratlan `.ipa`, Sideloadlyval ingyen telepíthető)
- Saját lejátszó (az iOS nem engedi más appok hangjának módosítását) a teljes EQ-val, limiterrel, térhatással
- Háttérben és lezárt képernyőn is szól, lezárási képernyő vezérlők, **Leállítás** gomb
- Kimenetválasztó, eszközönkénti profil, ugyanaz a fiók / mentés / közösség / hírlevél, közös preset-formátum

👉 **Teljes beállítási útmutató: [UTMUTATO.md](UTMUTATO.md)**

## APK buildelése GitHubon (Android Studio nélkül)

1. Hozz létre egy új GitHub repót, és töltsd fel a projekt tartalmát (a `.github` mappával együtt).
2. A push után az **Actions** fülön elindul a build (kb. 4–6 perc).
3. A kész futás alján az **Artifacts** résznél letöltöd a `BlueTune-apk` zipet → `BlueTune.apk`.
4. Ha `v1.0.0` formájú taget pusholsz, a workflow automatikusan **GitHub Release**-t is készít az APK-val.

```bash
git init && git add . && git commit -m "BlueTune"
git branch -M main
git remote add origin https://github.com/<felhasznalo>/BlueTune.git
git push -u origin main
# kiadás:
git tag v1.0.0 && git push --tags
```

## Helyi build

Android Studio (Ladybug vagy újabb) → Open → a mappa. Vagy parancssorból JDK 17-tel: `./gradlew assembleDebug`.

## Firebase, Brevo, aláírás, Play Áruház

Mindez lépésről lépésre az **[UTMUTATO.md](UTMUTATO.md)**-ben. Röviden, a szükséges GitHub Secretek:

| Secret | Mire kell |
|--------|-----------|
| `GOOGLE_SERVICES_JSON` | Firebase az appban (fiók, mentés, közösség) |
| `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | saját aláírókulcs |
| `FIREBASE_SERVICE_ACCOUNT` | hírlevél-szinkron |
| `BREVO_API_KEY`, `BREVO_LIST_ID`, `BREVO_SENDER_EMAIL`, `BREVO_SENDER_NAME` | Brevo |

Mindegyik opcionális – nélkülük is lefordul az app, csak az adott funkció marad ki.

## Jó tudni (Android korlátai)

- A **globális EQ** (session 0) a legtöbb telefonon működik, de gyártófüggő. Ha valamelyik appra nem hat, a Beállításokban hagyd bekapcsolva a „Lejátszó-session mód”-ot – a Spotify, YT Music, Poweramp stb. így külön is megkapják az effektet.
- Ha egyszerre több EQ app fut (pl. Samsung Sound Assistant, Wavelet), elvehetik egymástól az irányítást – az app ezt jelzi, és a „Motor újraindítása” gombbal visszaveheted.
- Az **akku és a kodek** kiolvasása nem nyilvános Android API-n át megy, ezért nem minden készüléken elérhető. A kodeket kiválasztani csak a Fejlesztői beállításokban lehet (az app oda linkel).
- Android 12+ alatt a háttérből indítást a rendszer korlátozza – kapcsold ki az app akkuoptimalizálását (Beállítás fül → Akkubeállítások), így Bluetooth csatlakozáskor magától elindul.
- Saját kulcs nélkül a release APK a debug kulccsal van aláírva (telepíthető, de Play-re nem tölthető fel).

## Szerkezet

```
app/src/main/java/hu/bluetune/
├── audio/      EffectChain (DynamicsProcessing/Equalizer/BassBoost/Virtualizer/Loudness), AudioEngine, VolumeControl, ToneGenerator
├── bt/         BluetoothMonitor (eszközök, akku, kodek)
├── cloud/      CloudRepo (email+jelszó fiók, hírlevél, mentés, közösség, fióktörlés)
├── data/       AppPrefs, PresetStore, DeviceProfileStore
├── model/      EqSettings, Preset, beépített presetek
├── service/    EqService (előtér), EqTileService, StartupReceiver, SessionReceiver
└── ui/         MainActivity + képernyők (Hangzás, Presetek, Eszközök, Fiók, Beállítás)
ios/            iPhone app (SwiftUI, XcodeGen: project.yml)
desktop/        Windows app (C# WinForms): Audio/ (Core Audio, Equalizer APO), Core/ (motor, tárolás), UI/
docs/           weboldal: adatvédelmi tájékoztató, fióktörlés (GitHub Pages)
tools/          Brevo-szinkron és frissítés-értesítő (Python, GitHub Actions futtatja)
firestore.rules adatbázis-szabályok
```
