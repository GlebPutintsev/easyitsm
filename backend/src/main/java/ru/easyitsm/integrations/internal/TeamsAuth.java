package ru.easyitsm.integrations.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Проверка запросов Bot Framework к боту Teams: JWT в {@code Authorization: Bearer …} подписан ключом из
 * {@code login.botframework.com} (ключи кешируются), издатель — {@code https://api.botframework.com}, аудитория —
 * Microsoft App ID бота, срок действия не истёк (допуск 1 минута).
 */
@Component
class TeamsAuth {

    private final IntegrationsProperties props;
    private final Map<String, NimbusJwtDecoder> decoders = new ConcurrentHashMap<>();

    TeamsAuth(IntegrationsProperties props) {
        this.props = props;
    }

    /** @return проверенный токен; serviceUrl из него сверяется с запросом */
    Jwt verify(String token, String appId) {
        return decoders.computeIfAbsent(appId, this::decoder).decode(token);
    }

    private NimbusJwtDecoder decoder(String appId) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(props.teams().jwksUri()).build();
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.teams().issuer()),
                new JwtClaimValidator<java.util.List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(appId))));
        return d;
    }
}
