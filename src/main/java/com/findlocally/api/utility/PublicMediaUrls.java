package com.findlocally.api.utility;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PublicMediaUrls {
    private final String baseUrl;
    public PublicMediaUrls(@Value("${app.media.public-base-url:}") String baseUrl) { this.baseUrl = baseUrl; }

    public String url(String key) {
        try {
            if (key == null || key.isBlank() || key.startsWith("/") || key.indexOf('\\') >= 0
                    || java.util.Arrays.stream(key.split("/")).anyMatch(part -> part.equals(".") || part.equals(".."))) {
                throw new IllegalArgumentException();
            }
            URI base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
                    || base.getQuery() != null || base.getFragment() != null || base.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            String path = new URI(null, null, "/" + key, null).getRawPath().substring(1);
            URI result = base.resolve(path).normalize();
            if (!result.toString().startsWith(base.toString())) throw new IllegalArgumentException();
            return result.toString();
        } catch (IllegalArgumentException | URISyntaxException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure a valid app.media.public-base-url");
        }
    }
}
