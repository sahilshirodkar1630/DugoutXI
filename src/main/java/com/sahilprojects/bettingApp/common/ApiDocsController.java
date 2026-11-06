package com.sahilprojects.bettingApp.common;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * The API reference page at /docs: Scalar, reading the OpenAPI description at /v3/api-docs.
 *
 * Scalar is a single script, so this is one small HTML page rather than a library on the classpath. The version is
 * pinned so the page does not change under us. Like the description itself, it only exists when
 * springdoc.api-docs.enabled is true.
 */
@RestController
@Hidden
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class ApiDocsController {

    static final String SCALAR = "https://cdn.jsdelivr.net/npm/@scalar/api-reference@1.72.3";

    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <title>Dugout XI API</title>
              </head>
              <body>
                <script id="api-reference" data-url="/v3/api-docs"></script>
                <script>
                  document.getElementById('api-reference').dataset.configuration = JSON.stringify({
                    theme: 'default',
                    layout: 'modern',
                    hideClientButton: false,
                    defaultHttpClient: { targetKey: 'shell', clientKey: 'curl' },
                    authentication: { preferredSecurityScheme: 'bearerAuth' },
                    metaData: { title: 'Dugout XI API' },
                  });
                </script>
                <script src="%s"></script>
              </body>
            </html>
            """.formatted(SCALAR);

    @GetMapping(value = {"/docs", "/docs/"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> docs() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)))
                .contentType(MediaType.TEXT_HTML)
                .body(PAGE);
    }
}
