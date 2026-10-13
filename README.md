# CarTube

YouTube-spelare för Android utan reklam, med stöd för Android Auto.

- Sök och spela YouTube-videor utan reklam (via [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)).
- Prenumerationsflöde med de senaste videorna från kanalerna du följer. Importera dina
  YouTube-prenumerationer från Google Takeout, eller prenumerera direkt i appen.
- Kanalsidor, beskrivning, visningar, uppladdningsdatum och fler videor under spelaren.
- Helskärm, bakgrundsuppspelning, bild-i-bild, favoriter och historik.
- Casta till Chromecast (spelar den bästa strömmen med ljud och bild ihop, ofta 360p).
- Dela en YouTube-länk till CarTube (eller öppna den med CarTube) för att spela den direkt.
- **Android Auto:** CarTube visas som en mediaapp. Bläddra bland favoriter och historik,
  sök (även med rösten: "Hey Google, spela … på CarTube") och lyssna. När telefonen är
  ansluten till bilen spelas **bara ljudet**. Ingen video visas, varken på bilskärmen
  eller på telefonen.

## Installera

1. Öppna [senaste releasen](../../releases/tag/latest) på telefonen och ladda ner `CarTube.apk`.
2. Öppna filen och tillåt installation från okända källor när du blir tillfrågad.

### Få appen att synas i Android Auto

Android Auto visar bara appar från Play Butik om du inte slår på utvecklarläget:

1. Telefonen: **Inställningar → Anslutna enheter → Anslutningsinställningar → Android Auto**.
2. Scrolla längst ner och tryck på **Version** ungefär 10 gånger, bekräfta utvecklarläget.
3. Meny (⋮) → **Utvecklarinställningar** → slå på **Okända källor**.
4. I Android Auto-inställningarna: **Anpassa startprogram** och se till att CarTube är ikryssad.

## Synk mellan enheter

Logga in med Google under **Bibliotek → Synka mellan enheter**. Kanaler, favoriter och historik
sparas i en dold mapp på din egen Google Drive. Kräver en engångsinställning, se
[docs/SETUP.md](docs/SETUP.md).

## Bygga

Varje push byggs av GitHub Actions (`.github/workflows/build.yml`). Pushar till
standardgrenen publicerar APK:n som releasen `latest`. Lokalt: `./gradlew assembleDebug`.

Publicerade APK:er signeras med en hemlig nyckel som bara finns i repots Secrets
(se [docs/SETUP.md](docs/SETUP.md)). Nyckeln `app/debug.keystore` i repot är bara för
utveckling och används aldrig för APK:er som delas ut.

## Licens

NewPipeExtractor är licensierad under GPL-3.0, och därför gäller GPL-3.0 även för den här appen.
