package co.com.finova.identity.api;

import co.com.finova.identity.model.token.gateways.TokenSigner;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwksControllerTest {

    @Test
    void servesThePublicKeySetAsJson() throws Exception {
        TokenSigner signer = mock(TokenSigner.class);
        when(signer.publicKeys()).thenReturn(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "k-1"))));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new JwksController(signer)).build();

        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.keys[0].kid").value("k-1"));
    }
}
