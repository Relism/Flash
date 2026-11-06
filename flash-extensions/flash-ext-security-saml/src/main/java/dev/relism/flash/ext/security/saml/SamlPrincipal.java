package dev.relism.flash.ext.security.saml;

import dev.relism.flash.ext.security.Principal;

import java.util.List;
import java.util.Map;

/**
 * A user a SAML identity provider vouched for. {@link #name()} is the {@code pairwise-id}, else the
 * {@code subject-id}, else the {@code NameID} (SAML2Int SDP-SP14): unique per {@link #issuer()},
 * never across issuers.
 *
 * @param idp          the {@link SamlIdp#id()}
 * @param issuer       the provider's entity ID
 * @param nameId       as the provider sent it, which logging out must repeat exactly; {@code null} when it sent none
 * @param sessionIndex the provider's session, for logging out of it
 * @param attributes   every attribute by its {@code Name}, values in order
 */
public record SamlPrincipal(String idp, String issuer, String name, NameId nameId, String sessionIndex,
                            Map<String, List<String>> attributes) implements Principal {

    /** The values of the attribute {@code name}, a {@code List<String>}, or {@code null}. */
    @Override
    public Object claim(String name) {
        return attributes.get(name);
    }

    /** The first value of the attribute {@code name}, or {@code null}. */
    public String attribute(String name) {
        List<String> values = attributes.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /** A {@code saml:NameID} with its qualifiers, {@code null} where absent. */
    public record NameId(String value, String format, String nameQualifier, String spNameQualifier, String spProvidedId) {}
}
