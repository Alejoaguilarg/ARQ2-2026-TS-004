package co.com.finova.identity.api;

import co.com.finova.identity.model.token.gateways.TokenSigner;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// spec.md §3.1.3 internal endpoint: not routed by the Gateway, reachable only inside the Docker network.
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final TokenSigner tokens;

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return tokens.publicKeys();
    }
}
