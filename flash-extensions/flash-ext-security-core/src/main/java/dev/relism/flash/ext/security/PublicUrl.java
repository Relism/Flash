package dev.relism.flash.ext.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * The guard for a URL someone other than the operator chose — an identity provider an organization
 * registers, a client's metadata document: fetching it must not become a request forgery against the
 * server's own network.
 */
public final class PublicUrl {

    private PublicUrl() {}

    /**
     * ponytail: resolved here and again by the HTTP client, so DNS rebinding between the two is not covered.
     *
     * @throws IllegalArgumentException {@code url} is not https, or one of its host's addresses is not public
     */
    public static void require(String url) throws UnknownHostException {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme())) throw new IllegalArgumentException("Not https: " + url);
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress() || (address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc)) {
                throw new IllegalArgumentException("Not a public address: " + url);
            }
        }
    }
}
