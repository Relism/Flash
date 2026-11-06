package dev.relism.flash.ext.security.saml;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a browser does in a SAML flow, and no more: keeps each host's cookies, follows redirects, submits
 * forms. The application's configured origin is mapped to where the test server listens.
 */
final class Browser {

    private static final Pattern INPUT = Pattern.compile("<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION = Pattern.compile("<form[^>]*action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, Map<String, String>> cookies = new HashMap<>();
    private final String origin;
    private final String app;

    Browser(String origin, String app) {
        this.origin = origin;
        this.app = app;
    }

    HttpResponse<String> get(String url) throws Exception {
        return send(HttpRequest.newBuilder(uri(url)).GET(), url);
    }

    HttpResponse<String> post(String url, Map<String, String> form) throws Exception {
        StringJoiner body = new StringJoiner("&");
        form.forEach((name, value) -> body.add(encode(name) + "=" + encode(value)));
        return send(HttpRequest.newBuilder(uri(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())), url);
    }

    /** A JSON API call, {@code GET} without a body, following redirects as JSON calls too. */
    HttpResponse<String> json(String url, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(url)).header("Accept", "application/json");
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return follow(send(request, url), "application/json");
    }

    /** Follows redirects from {@code response} to the first page that is not one. */
    HttpResponse<String> follow(HttpResponse<String> response) throws Exception {
        return follow(response, "*/*");
    }

    private HttpResponse<String> follow(HttpResponse<String> response, String accept) throws Exception {
        for (int hop = 0; response.statusCode() / 100 == 3 && hop < 20; hop++) {
            String next = response.uri().resolve(response.headers().firstValue("Location").orElseThrow()).toString();
            response = send(HttpRequest.newBuilder(uri(next)).header("Accept", accept), next);
        }
        return response;
    }

    /** Submits the page's first form with its fields, {@code fields} over them: what a user or an auto-submitting page does. */
    HttpResponse<String> submit(HttpResponse<String> page, Map<String, String> fields) throws Exception {
        Matcher action = ACTION.matcher(page.body());
        if (!action.find()) throw new IllegalStateException("No form in " + page.uri() + ":\n" + page.body());
        Map<String, String> form = new LinkedHashMap<>();
        for (Matcher input = INPUT.matcher(page.body()); input.find(); ) form.put(input.group(1), unescape(input.group(2)));
        form.putAll(fields);
        return post(page.uri().resolve(unescape(action.group(1))).toString(), form);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String url) throws Exception {
        URI target = uri(url);
        Map<String, String> jar = cookies.computeIfAbsent(target.getAuthority(), host -> new LinkedHashMap<>());
        if (!jar.isEmpty()) {
            StringJoiner header = new StringJoiner("; ");
            jar.forEach((name, value) -> header.add(name + "=" + value));
            request.header("Cookie", header.toString());
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        for (String set : response.headers().allValues("Set-Cookie")) {
            String pair = set.split(";", 2)[0];
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq).strip(), value = pair.substring(eq + 1);
            if (value.isEmpty() || set.toLowerCase().contains("max-age=0")) jar.remove(name);
            else jar.put(name, value);
        }
        return response;
    }

    /** Where the browser actually goes: the configured origin is the test server. */
    private URI uri(String url) {
        return URI.create(url.startsWith(origin) ? app + url.substring(origin.length()) : url);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unescape(String html) {
        return html.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x3a;", ":").replace("&#x2f;", "/").replace("&#x3d;", "=").replace("&#x2b;", "+").replace("&lt;", "<").replace("&gt;", ">");
    }
}
