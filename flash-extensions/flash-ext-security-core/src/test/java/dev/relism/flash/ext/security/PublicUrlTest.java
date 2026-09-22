package dev.relism.flash.ext.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicUrlTest {

    @Test
    void onlyHttpsOnAPublicAddressPasses() {
        assertDoesNotThrow(() -> PublicUrl.require("https://1.1.1.1/.well-known/openid-configuration"));
        for (String url : new String[]{"http://1.1.1.1/", "https://127.0.0.1/", "https://10.0.0.8/", "https://192.168.1.1/",
                "https://169.254.169.254/latest/meta-data", "https://[::1]/", "https://[fd00::1]/", "https://0.0.0.0/"}) {
            assertThrows(IllegalArgumentException.class, () -> PublicUrl.require(url), url);
        }
    }
}
