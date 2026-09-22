package dev.relism.flash.ext.security.oauthserver;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server's ES256 keys: the first signs, all verify, so a key is rotated by putting its successor first
 * and dropping it once the tokens it signed have expired. Access tokens are RFC 9068 JWTs, {@code typ} {@code at+jwt}.
 */
final class SigningKeys {

    private final ECKey signing;
    private final String publicJwks;
    private final DefaultJWTProcessor<SecurityContext> verifier = new DefaultJWTProcessor<>();

    SigningKeys(String jwkSet) {
        try {
            JWKSet keys = JWKSet.parse(jwkSet);
            signing = keys.getKeys().getFirst().toECKey();
            if (!signing.isPrivate() || !Curve.P_256.equals(signing.getCurve())) throw new IllegalArgumentException("The first key must be a private P-256 key");
            publicJwks = keys.toPublicJWKSet().toString();
            verifier.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt")));
            verifier.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, new ImmutableJWKSet<>(keys.toPublicJWKSet())));
            verifier.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(null, Set.of("iss", "sub", "aud", "exp", "client_id")));
        } catch (java.text.ParseException | ClassCastException invalid) {
            throw new IllegalArgumentException("Not a JWK set with a private EC key first", invalid);
        }
    }

    /** A JWK set holding one new private P-256 key — what {@link OAuthServerExtension#signingKeys(String)} takes. */
    static String generate() {
        try {
            return new JWKSet(new ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).keyID(UUID.randomUUID().toString()).generate()).toString(false);
        } catch (com.nimbusds.jose.JOSEException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    String sign(Map<String, Object> claims) {
        try {
            JWTClaimsSet.Builder set = new JWTClaimsSet.Builder();
            claims.forEach(set::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("at+jwt")).keyID(signing.getKeyID()).build(), set.build());
            jwt.sign(new ECDSASigner(signing));
            return jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The token's claims, once its type, signature, expiry and required claims check out. */
    Map<String, Object> verify(SignedJWT token) throws Exception {
        return verifier.process(token, null).getClaims();
    }

    String publicJwks() {
        return publicJwks;
    }
}
