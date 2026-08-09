package dev.relism.flash.tls;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only: builds self-signed PKCS12 keystores via the JDK's own {@code keytool} so TLS
 * tests need no external certificate fixtures and no extra crypto test-dependency.
 */
public final class TestKeystores {
    private TestKeystores() {}

    public record Entry(String alias, String cn, String... sans) {
        public static Entry of(String alias, String cn, String... sans) { return new Entry(alias, cn, sans); }
    }

    public static Path build(Path dir, String fileName, String password, Entry... entries)
            throws IOException, InterruptedException {
        Path keystore = dir.resolve(fileName);
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        for (Entry e : entries) {
            List<String> cmd = new ArrayList<>(List.of(
                    keytool, "-genkeypair", "-noprompt",
                    "-alias", e.alias(),
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "3650",
                    "-keystore", keystore.toString(),
                    "-storetype", "PKCS12",
                    "-storepass", password,
                    "-dname", "CN=" + e.cn()));
            if (e.sans().length > 0) {
                StringBuilder san = new StringBuilder();
                for (String s : e.sans()) {
                    if (!san.isEmpty()) san.append(',');
                    san.append("dns:").append(s);
                }
                cmd.add("-ext");
                cmd.add("SAN=" + san);
            }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) throw new IOException("keytool failed: " + output);
        }
        return keystore;
    }

    /** A client-side {@link SSLContext} that trusts any server certificate — self-signed test certs only. */
    public static SSLContext trustAllClientContext() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager trustAll = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] { trustAll }, null);
        return ctx;
    }
}
