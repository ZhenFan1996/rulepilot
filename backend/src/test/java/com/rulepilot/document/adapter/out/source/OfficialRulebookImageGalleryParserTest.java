package com.rulepilot.document.adapter.out.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class OfficialRulebookImageGalleryParserTest {

    private final OfficialRulebookImageGalleryParser parser = new OfficialRulebookImageGalleryParser();

    @Test
    void acceptsASingleObservedPageInsteadOfInventingATwoPageMinimum() {
        URI source = URI.create("https://www.gstonegames.com/game/doc-42.html");
        var document = Jsoup.parse(
                "<div id='preview_imgs'><img data-original='https://oss.gstonegames.com/static/image/document/only.jpg'></div>",
                source.toASCIIString());

        assertThat(parser.parse(source, document))
                .get()
                .satisfies(gallery -> assertThat(gallery.pages()).containsExactly(
                        URI.create("https://oss.gstonegames.com/static/image/document/only.jpg")));
    }

    @Test
    void preservesEveryPageOfALongRulebookWithinThePdfBoundary() {
        URI source = URI.create("https://www.gstonegames.com/game/doc-84.html");
        StringBuilder html = new StringBuilder("<div id='preview_imgs'>");
        for (int page = 1; page <= 60; page++) {
            html.append("<img data-original='https://oss.gstonegames.com/static/image/document/page-")
                    .append(page)
                    .append(".jpg'>");
        }
        html.append("</div>");

        assertThat(parser.parse(source, Jsoup.parse(html.toString(), source.toASCIIString())))
                .get()
                .satisfies(gallery -> {
                    assertThat(gallery.pages()).hasSize(60);
                    assertThat(gallery.pages().getFirst()).hasPath("/static/image/document/page-1.jpg");
                    assertThat(gallery.pages().getLast()).hasPath("/static/image/document/page-60.jpg");
                });
    }
}
