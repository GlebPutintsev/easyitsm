package ru.easyitsm.identity.internal;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ключи WebAuthn с эмулятором аутентификатора: регистрация в профиле, второй фактор при входе,
 * вход по passkey без пароля и отказ для ключа с чужого сайта.
 */
class WebAuthnFlowTest extends IdentityTestSupport {

    static final Base64.Decoder B64 = Base64.getUrlDecoder();
    static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();


    final ClientPlatform platform = new ClientPlatform(new Origin("http://localhost:5173"),
            new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));

    @Test
    void registerKeyThenUseItAsSecondFactorAndPasskey() throws Exception {
        String login = newUser();
        Browser browser = loggedIn(login);

        String options = browser.post("/api/v1/me/mfa/keys/options", "{}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String credential = register(options, platform);
        String added = browser.post("/api/v1/me/mfa/keys", "{\"ceremonyId\":\"" + JsonPath.read(options, "$.ceremonyId")
                        + "\",\"name\":\"YubiKey\",\"credential\":" + credential + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(JsonPath.<List<String>>read(added, "$.recoveryCodes")).hasSize(10);
        browser.get("/api/v1/me/mfa/keys").andExpect(jsonPath("$[0].name").value("YubiKey"));

        // Второй фактор после пароля.
        Browser next = new Browser(mvc).open();
        next.post("/api/v1/auth/login", loginJson(login, PASSWORD))
                .andExpect(jsonPath("$.status").value("mfa_required"))
                .andExpect(jsonPath("$.methods[0]").value("webauthn"));
        String mfaOptions = next.post("/api/v1/auth/mfa/webauthn/options", "{}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        next.post("/api/v1/auth/mfa/webauthn", "{\"ceremonyId\":\"" + JsonPath.read(mfaOptions, "$.ceremonyId")
                        + "\",\"credential\":" + assertion(mfaOptions, platform) + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        next.get("/api/v1/me").andExpect(status().isOk()).andExpect(jsonPath("$.security.keys").value(1));

        // Вход по passkey без логина.
        Browser passkey = new Browser(mvc).open();
        String pkOptions = passkey.post("/api/v1/auth/passkey/options", "{\"tenant\":\"demo\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        passkey.post("/api/v1/auth/passkey", "{\"tenant\":\"demo\",\"ceremonyId\":\""
                        + JsonPath.read(pkOptions, "$.ceremonyId") + "\",\"credential\":"
                        + assertion(pkOptions, platform) + "}")
                .andExpect(status().isOk());
        passkey.get("/api/v1/me").andExpect(jsonPath("$.login").value(login));

        // Тот же вызов повторно не принимается.
        String replayOptions = passkey.post("/api/v1/auth/passkey/options", "{\"tenant\":\"demo\"}")
                .andReturn().getResponse().getContentAsString();
        String signed = assertion(replayOptions, platform);
        String body = "{\"tenant\":\"demo\",\"ceremonyId\":\"" + JsonPath.read(replayOptions, "$.ceremonyId")
                + "\",\"credential\":" + signed + "}";
        new Browser(mvc).open().post("/api/v1/auth/passkey", body).andExpect(status().isOk());
        new Browser(mvc).open().post("/api/v1/auth/passkey", body).andExpect(status().isBadRequest());

        // Подпись для другого сайта отклоняется.
        var phishing = new ClientPlatform(new Origin("https://evil.example"), platform.getAuthenticatorAdaptor());
        String evilOptions = passkey.post("/api/v1/auth/passkey/options", "{\"tenant\":\"demo\"}")
                .andReturn().getResponse().getContentAsString();
        new Browser(mvc).open().post("/api/v1/auth/passkey", "{\"tenant\":\"demo\",\"ceremonyId\":\""
                        + JsonPath.read(evilOptions, "$.ceremonyId") + "\",\"credential\":"
                        + assertion(evilOptions, phishing) + "}")
                .andExpect(status().isUnauthorized());
    }

    static String register(String optionsJson, ClientPlatform client) {
        Map<String, Object> pk = JsonPath.read(optionsJson, "$.publicKey");
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) pk.get("user");
        var options = new PublicKeyCredentialCreationOptions(
                new PublicKeyCredentialRpEntity("localhost", "Easy ITSM"),
                new PublicKeyCredentialUserEntity(B64.decode((String) user.get("id")), (String) user.get("name"),
                        (String) user.get("displayName")),
                new DefaultChallenge(B64.decode((String) pk.get("challenge"))),
                List.of(new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                60_000L, List.of(),
                new AuthenticatorSelectionCriteria(null, true, ResidentKeyRequirement.REQUIRED,
                        UserVerificationRequirement.REQUIRED),
                AttestationConveyancePreference.NONE, null);
        PublicKeyCredential<AuthenticatorAttestationResponse, ?> credential = client.create(options);
        String id = ENC.encodeToString(credential.getRawId());
        return "{\"id\":\"" + id + "\",\"rawId\":\"" + id + "\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + ENC.encodeToString(credential.getResponse().getClientDataJSON()) + "\","
                + "\"attestationObject\":\"" + ENC.encodeToString(credential.getResponse().getAttestationObject())
                + "\",\"transports\":[]},\"clientExtensionResults\":{}}";
    }

    static String assertion(String optionsJson, ClientPlatform client) {
        Map<String, Object> pk = JsonPath.read(optionsJson, "$.publicKey");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allow = (List<Map<String, Object>>) pk.get("allowCredentials");
        var options = new PublicKeyCredentialRequestOptions(new DefaultChallenge(B64.decode((String) pk.get("challenge"))),
                60_000L, "localhost",
                allow.stream().map(a -> new PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY,
                        B64.decode((String) a.get("id")), null)).toList(),
                UserVerificationRequirement.REQUIRED, null);
        PublicKeyCredential<AuthenticatorAssertionResponse, ?> credential = client.get(options);
        var r = credential.getResponse();
        String id = ENC.encodeToString(credential.getRawId());
        return "{\"id\":\"" + id + "\",\"rawId\":\"" + id + "\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + ENC.encodeToString(r.getClientDataJSON()) + "\","
                + "\"authenticatorData\":\"" + ENC.encodeToString(r.getAuthenticatorData()) + "\","
                + "\"signature\":\"" + ENC.encodeToString(r.getSignature()) + "\""
                + (r.getUserHandle() == null ? "" : ",\"userHandle\":\"" + ENC.encodeToString(r.getUserHandle()) + "\"")
                + "},\"clientExtensionResults\":{}}";
    }
}
