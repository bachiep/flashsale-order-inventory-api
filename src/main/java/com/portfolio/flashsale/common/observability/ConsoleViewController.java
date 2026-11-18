package com.portfolio.flashsale.common.observability;

import io.swagger.v3.oas.annotations.Hidden;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Hidden
public class ConsoleViewController {

    private static final MediaType TEXT_HTML_UTF8 = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);

    private final String indexHtml;

    public ConsoleViewController() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/index.html");
        this.indexHtml = resource.getContentAsString(StandardCharsets.UTF_8);
    }

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> console() {
        return ResponseEntity.ok()
                .contentType(TEXT_HTML_UTF8)
                .body(indexHtml);
    }
}
