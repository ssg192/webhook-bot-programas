package mx.salvador.wabot.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.DriveScopes;

import java.io.FileReader;
import java.util.List;

/**
 * Utilería de UNA SOLA corrida para generar el refresh token.
 *
 * Uso:
 *   1. En GCP crea credenciales OAuth de tipo "Desktop app" y descarga
 *      el JSON como oauth-client.json
 *   2. ./mvnw -q compile exec:java \
 *        -Dexec.mainClass=mx.salvador.wabot.drive.TokenGenerator
 *   3. Se abre el browser -> inicia sesión CON LA CUENTA DEL DRIVE DE 2TB
 *      -> acepta los permisos
 *   4. Copia el refresh token que se imprime y ponlo en la config
 *      (DRIVE_REFRESH_TOKEN). El client id/secret salen del mismo JSON.
 *
 * IMPORTANTE: en GCP, la pantalla de consentimiento (OAuth consent screen)
 * debe estar en modo "In production" (publicada), aunque no la verifique
 * Google. En modo "Testing" el refresh token caduca a los 7 días.
 */
public class TokenGenerator {

    public static void main(String[] args) throws Exception {
        String clientSecretsFile = args.length > 0 ? args[0] : "oauth-client.json";

        var secrets = GoogleClientSecrets.load(
                GsonFactory.getDefaultInstance(), new FileReader(clientSecretsFile));

        var flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                secrets,
                List.of(DriveScopes.DRIVE))
                .setAccessType("offline")          // sin esto NO hay refresh token
                .setApprovalPrompt("force")        // fuerza refresh token aunque ya hayas consentido antes
                .build();

        Credential cred = new AuthorizationCodeInstalledApp(
                flow, new LocalServerReceiver.Builder().setPort(8888).build())
                .authorize("user");

        System.out.println("\n================================================");
        System.out.println("REFRESH TOKEN (guárdalo en DRIVE_REFRESH_TOKEN):");
        System.out.println(cred.getRefreshToken());
        System.out.println("================================================");
        System.out.println("Client ID:     " + secrets.getDetails().getClientId());
        System.out.println("Client Secret: " + secrets.getDetails().getClientSecret());
        System.out.println("\nListo. Ya puedes borrar oauth-client.json del server si quieres");
        System.out.println("(el bot solo necesita los 3 valores de arriba en la config).");
    }
}
