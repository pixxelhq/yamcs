package org.yamcs.security.encryption.aes;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import org.json.JSONObject;
import org.yamcs.logging.Log;


public class VaultClient {

    private static final Log log = new Log(VaultClient.class);

    private final String vaultToken;
    private final String vaultNamespace;
    private final String vaultAddr;

    public VaultClient(String vaultToken, String vaultNamespace, String vaultAddr) {
        this.vaultToken = vaultToken;
        this.vaultNamespace = vaultNamespace;
        this.vaultAddr = vaultAddr;
    }

    public String decrypt(String ciphertext) throws Exception {
        String apiUrl = vaultAddr + "/v1/transit/decrypt/mcs-keys";
        URL url = new URL(apiUrl);

        JSONObject jsonPayload = new JSONObject();
        jsonPayload.put("ciphertext", ciphertext);

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("X-Vault-Token", vaultToken);
        connection.setRequestProperty("X-Vault-Namespace", vaultNamespace);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(jsonPayload.toString().getBytes("UTF-8"));
            os.flush();
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            log.warn("error: {}", connection.getResponseMessage());
            throw new RuntimeException("Failed : HTTP error code : " + responseCode);
        }

        StringBuilder response;
        try (BufferedReader br = new BufferedReader(new InputStreamReader((connection.getInputStream())))) {
            String line;
            response = new StringBuilder();
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
        }

        JSONObject jsonResponse = new JSONObject(response.toString());
        String base64EncodedData = jsonResponse.getJSONObject("data").getString("plaintext");

        // Decode the base64 encoded string
        byte[] decodedBytes = Base64.getDecoder().decode(base64EncodedData);
        return new String(decodedBytes, "UTF-8");
    }
}
