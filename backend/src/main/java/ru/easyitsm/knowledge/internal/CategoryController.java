package ru.easyitsm.knowledge.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.knowledge.internal.KnowledgeDtos.CategoryView;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/** Категории базы знаний: читают все, управляют редактор знаний и администратор. */
@RestController
@RequestMapping("/api/v1/knowledge/categories")
class CategoryController {

    private final KbCategoryRepository categories;

    CategoryController(KbCategoryRepository categories) {
        this.categories = categories;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<CategoryView> list() {
        return categories.findAllOrdered().stream().map(KbCategory::toView).toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('KNW', 'ADM')")
    @Transactional
    public ResponseEntity<CategoryView> create(@Valid @RequestBody KnowledgeDtos.CreateCategory cmd) {
        var c = categories.save(new KbCategory(cmd.name().strip(), cmd.description(),
                cmd.sortOrder() == null ? 0 : cmd.sortOrder()));
        return ResponseEntity.created(URI.create("/api/v1/knowledge/categories/" + c.getId()))
                .eTag(ETags.of(c.getVersion())).body(c.toView());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('KNW', 'ADM')")
    @Transactional
    public ResponseEntity<CategoryView> update(@PathVariable UUID id,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @Valid @RequestBody KnowledgeDtos.UpdateCategory cmd) {
        KbCategory c = categories.findById(id).orElseThrow(() -> ApiException.notFound("Категория"));
        ETags.requireMatch(ifMatch, c.getVersion());
        if (cmd.name() != null) {
            c.setName(cmd.name().strip());
        }
        if (cmd.description() != null) {
            c.setDescription(cmd.description());
        }
        if (cmd.sortOrder() != null) {
            c.setSortOrder(cmd.sortOrder());
        }
        c = categories.save(c);
        return ResponseEntity.ok().eTag(ETags.of(c.getVersion())).body(c.toView());
    }
}
