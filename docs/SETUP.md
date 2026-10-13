# Engångsinställningar för CarTube

Två saker behöver göras en gång av den som äger repot. Ingen av dem kan göras från koden,
eftersom de kräver inloggning på ditt GitHub- och Google-konto.

## 1. Signeringsnyckel (GitHub Secrets)

APK:n signeras med en hemlig nyckel som bara finns i repots krypterade Secrets, aldrig i koden.
Utan den publiceras ingen ny version på releasesidan.

1. Öppna repot på GitHub → **Settings** → **Secrets and variables** → **Actions**.
2. Tryck **New repository secret** och lägg till:
   - Namn `SIGNING_KEYSTORE_BASE64`, värde: hela innehållet i `keystore-base64.txt`.
   - Namn `SIGNING_PASSWORD`, värde: lösenordet i `password.txt`.
3. Spara de två filerna på ett säkert ställe (t.ex. en lösenordshanterare). Förlorar du
   nyckeln kan befintliga installationer inte uppdateras, bara installeras om.

Fingeravtryck (SHA-1) för nyckeln, som behövs i steg 2:

```
63:62:ED:87:92:B7:73:81:64:96:9F:BF:C2:26:3A:73:FA:F0:73:A1
```

Varje bygge skriver också ut fingeravtrycket i loggen under steget *Show signing certificate*.

## 2. Google-inloggning för synk (Google Cloud)

Synken sparar varje användares kanaler, favoriter och historik i en dold mapp på hens egen
Google Drive. Google kräver att appen registreras i ett (gratis) Google Cloud-projekt.

1. Gå till <https://console.cloud.google.com> och logga in. Skapa ett nytt projekt, t.ex. "CarTube".
2. Sök upp **Google Drive API** och tryck **Aktivera** (Enable).
3. Öppna **Google Auth Platform** (OAuth-medgivandeskärm):
   - **Branding**: appnamn "CarTube" och din e-post som support- och kontaktadress.
   - **Audience** (Målgrupp): välj **External** och låt appen stå i **Testing**.
     Lägg till e-postadressen för alla som ska få logga in under **Test users**
     (upp till 100 personer, t.ex. familj och vänner).
   - **Data access**: lägg till omfånget `.../auth/drive.appdata`.
4. Under **Clients** → **Create client** → typ **Android**:
   - Package name: `com.cartube`
   - SHA-1: fingeravtrycket ovan.
5. Klart. Ingen kod behöver ändras; logga in i appen under **Bibliotek → Synka mellan enheter**.

Någon som inte finns bland testanvändarna kan använda appen som vanligt, men inte synka.
