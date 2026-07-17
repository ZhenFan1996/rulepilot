package com.rulepilot.document.adapter.in.web;

import com.rulepilot.document.DocumentProcessing;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-versions/{versionId}/pages")
@Profile("!test")
public class DocumentPageController {

    private final DocumentProcessing documents;

    public DocumentPageController(DocumentProcessing documents) {
        this.documents = documents;
    }

    @GetMapping
    List<PageResponse> pages(@PathVariable UUID versionId) {
        return documents.pages(versionId).stream().map(PageResponse::from).toList();
    }

    record PageResponse(int pageNumber, String text, int characterCount) {
        static PageResponse from(DocumentProcessing.PageView page) {
            return new PageResponse(page.pageNumber(), page.text(), page.characterCount());
        }
    }
}
