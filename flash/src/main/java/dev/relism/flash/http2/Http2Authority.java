package dev.relism.flash.http2;

import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLSession;

/** Validates a coalesced request authority against the certificate selected for its connection. */
final class Http2Authority {
  private Http2Authority() {}

  static boolean isServed(String authority, SSLSession session) {
    if (session == null || authority == null) return true;
    String host = host(authority);
    try {
      Certificate[] certificates = session.getLocalCertificates();
      if (certificates == null || certificates.length == 0
          || !(certificates[0] instanceof X509Certificate certificate)) {
        return true;
      }
      Collection<List<?>> names = certificate.getSubjectAlternativeNames();
      if (names == null) return true;
      for (List<?> name : names) {
        int type = (Integer) name.get(0);
        if ((type == 2 || type == 7) && matches(host, name.get(1).toString())) return true;
      }
      return false;
    } catch (CertificateParsingException failure) {
      return true;
    }
  }

  static boolean matches(String authority, String certificateName) {
    String host = host(authority).toLowerCase(Locale.ROOT);
    String name = certificateName.toLowerCase(Locale.ROOT);
    if (!name.startsWith("*.")) return host.equals(name);
    String suffix = name.substring(1);
    if (!host.endsWith(suffix)) return false;
    int prefixLength = host.length() - suffix.length();
    return prefixLength > 0 && host.indexOf('.') == prefixLength;
  }

  private static String host(String authority) {
    if (authority.startsWith("[")) {
      int closing = authority.indexOf(']');
      return closing < 0 ? authority : authority.substring(1, closing);
    }
    int colon = authority.lastIndexOf(':');
    return colon > 0 && authority.indexOf(':') == colon ? authority.substring(0, colon) : authority;
  }
}
