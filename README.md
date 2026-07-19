# wa-drive-bot

Bot que recibe links de YouTube por WhatsApp, los convierte a MP3 con `yt-dlp` y los sube a Drive:

```
<Carpeta raíz>/
└── Domingo 2026-07-19/
    ├── Playlist/      ← mp3s
    └── Notas/
```

Usa la **WhatsApp Business Cloud API oficial de Meta**

## Limitaciones a saber

- **Sin grupos**: la Cloud API no soporta grupos. Cada persona le escribe directo al número del bot.
- **Número dedicado**: el número que registres queda amarrado a la API (no se puede usar en la app normal). Un chip aparte funciona.
- **Para pruebas**: Meta da un número de prueba gratis con hasta 5 destinatarios registrados — perfecto para arrancar.
- **Costo**: conversaciones iniciadas por el usuario (te mandan link → respondes en <24h) son gratis. Tu flujo completo cae ahí.

## Requisitos

- Java 21, Maven
- `yt-dlp` y `ffmpeg`: `brew install yt-dlp ffmpeg`

## 1. Setup en Meta (una vez)

1. [developers.facebook.com](https://developers.facebook.com) → Create App → tipo **Business** → agrega el producto **WhatsApp**.
2. En **WhatsApp > API Setup** tienes: número de prueba, **Phone Number ID** y un token temporal (24h). Para producción crea un **System User** en Business Settings y genera un **token permanente** con permiso `whatsapp_business_messaging`.
3. Registra los números de tu equipo como destinatarios de prueba (o verifica tu propio número real del bot).

## 2. Webhook con URL pública

 Con cloudflared (gratis, sin cuenta para pruebas):

```bash
brew install cloudflared
cloudflared tunnel --url http://localhost:8080
```

Te da una URL tipo `https://xxx.trycloudflare.com`. En Meta: **WhatsApp > Configuration > Webhook**:
- Callback URL: `https://xxx.trycloudflare.com/webhook`
- Verify token: el mismo que pongas en `WA_VERIFY_TOKEN`
- Suscríbete al campo **messages**

> Para algo permanente: Cloudflare Tunnel con dominio propio, o el bot en un VPS.

## 3. Setup de Google Drive (OAuth de usuario, una sola vez)

El bot sube archivos **como la cuenta dueña del Drive de 2 TB**, así que la propiedad y la cuota quedan bien para siempre.

1. [console.cloud.google.com](https://console.cloud.google.com) → proyecto → habilita **Google Drive API**.
2. **APIs & Services → OAuth consent screen**: configúrala (tipo External está bien), agrega el scope de Drive, y **publícala** ("In production"). No necesitas que Google la verifique — solo tú la usas — pero si se queda en "Testing" el refresh token caduca a los 7 días.
3. **Credentials → Create credentials → OAuth client ID → Desktop app**. Descarga el JSON como `oauth-client.json` en la raíz del proyecto.
4. Genera el refresh token (abre el browser, **inicia sesión con la cuenta del Drive de 2 TB** y acepta):

   ```bash
   ./mvnw -q compile exec:java -Dexec.mainClass=mx.salvador.wabot.drive.TokenGenerator
   ```

   Te imprime `REFRESH TOKEN`, `Client ID` y `Client Secret` → van en las variables de entorno. Google mostrará una advertencia de "app no verificada"; dale *Advanced → Go to wa-drive-bot* (es tu propia app, es normal).
5. El ID de la carpeta raíz (de la URL de Drive) va en `DRIVE_ROOT_FOLDER_ID`. Como el bot actúa como el dueño, ya no hay que compartir nada con nadie.

## 4. Correr

```bash
export WA_ACCESS_TOKEN=EAAxxxx
export WA_PHONE_NUMBER_ID=1234567890
export WA_VERIFY_TOKEN=mi-token-secreto
export DRIVE_CLIENT_ID=xxxx.apps.googleusercontent.com
export DRIVE_CLIENT_SECRET=GOCSPX-xxxx
export DRIVE_REFRESH_TOKEN=1//xxxx
export DRIVE_ROOT_FOLDER_ID=1AbCdEf...
export ALLOWED_NUMBERS=5215512345678,5215587654321   # opcional

./mvnw quarkus:dev
```

Manda un WhatsApp al número del bot con uno o varios links de YouTube. El bot responde "⏳ Descargando..." y al terminar la lista de canciones + link a la carpeta del domingo.

## Producción en la Mac (launchd)

```bash
./mvnw package
# genera target/quarkus-app/ — se corre con:
java -jar target/quarkus-app/quarkus-run.jar
```

`~/Library/LaunchAgents/com.salvador.wadrivebot.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.salvador.wadrivebot</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/java</string>
    <string>-jar</string>
    <string>/Users/salvador/wa-drive-bot/target/quarkus-app/quarkus-run.jar</string>
  </array>
  <key>WorkingDirectory</key><string>/Users/salvador/wa-drive-bot</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/tmp/wadrivebot.log</string>
  <key>StandardErrorPath</key><string>/tmp/wadrivebot.err</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
    <key>WA_ACCESS_TOKEN</key><string>EAAxxxx</string>
    <key>WA_PHONE_NUMBER_ID</key><string>1234567890</string>
    <key>WA_VERIFY_TOKEN</key><string>mi-token-secreto</string>
    <key>DRIVE_CLIENT_ID</key><string>xxxx.apps.googleusercontent.com</string>
    <key>DRIVE_CLIENT_SECRET</key><string>GOCSPX-xxxx</string>
    <key>DRIVE_REFRESH_TOKEN</key><string>1//xxxx</string>
    <key>DRIVE_ROOT_FOLDER_ID</key><string>1AbCdEf...</string>
  </dict>
</dict>
</plist>
```
