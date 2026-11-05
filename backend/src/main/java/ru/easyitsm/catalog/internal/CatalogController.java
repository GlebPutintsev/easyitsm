package ru.easyitsm.catalog.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.catalog.internal.form.FormValidationException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.platform.web.TraceIdFilter;

/** Каталог услуг (10.4, 8.2 ТЗ): чтение для всех, правка для ADM, заказ и избранное. */
@RestController
@RequestMapping("/api/v1/catalog")
class CatalogController {

    private final CatalogService service;

    CatalogController(CatalogService service) {
        this.service = service;
    }

    // ---------- категории ----------

    @GetMapping("/categories")
    CursorPage<CatalogViews.CategoryView> categories(@RequestParam(defaultValue = "false") boolean all) {
        return new CursorPage<>(service.categories(all), null);
    }

    @PostMapping("/categories")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<CatalogViews.CategoryView> createCategory(@Valid @RequestBody CatalogCommands.CategoryInput in) {
        var view = service.createCategory(in);
        return ResponseEntity.created(URI.create("/api/v1/catalog/categories/" + view.id()))
                .eTag(ETags.of(view.version())).body(view);
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<CatalogViews.CategoryView> updateCategory(
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CatalogCommands.CategoryInput in) {
        var view = service.updateCategory(id, ifMatch, in);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    // ---------- предложения ----------

    /**
     * @param popular  до 8 самых заказываемых услуг для главной портала, без постраничности
     * @param all      для ADM: включая выключенные и скрытые
     */
    @GetMapping("/items")
    CursorPage<CatalogViews.ItemCard> items(@RequestParam(required = false) UUID categoryId,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String team,
                                            @RequestParam(defaultValue = "false") boolean favorites,
                                            @RequestParam(defaultValue = "false") boolean popular,
                                            @RequestParam(defaultValue = "false") boolean all,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit) {
        if (popular) {
            return new CursorPage<>(service.popular(), null);
        }
        return service.list(categoryId, q, team, favorites, all, PageParams.of(cursor, limit));
    }

    @GetMapping("/teams")
    List<String> teams() {
        return service.teams();
    }

    @GetMapping("/items/{id}")
    ResponseEntity<CatalogViews.ItemDetail> item(@PathVariable UUID id) {
        var view = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping("/items")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<CatalogViews.ItemDetail> createItem(@Valid @RequestBody CatalogCommands.ItemFields in) {
        var view = service.createItem(in);
        return ResponseEntity.created(URI.create("/api/v1/catalog/items/" + view.id()))
                .eTag(ETags.of(view.version())).body(view);
    }

    @PutMapping("/items/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<CatalogViews.ItemDetail> updateItem(
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CatalogCommands.ItemFields in) {
        var view = service.updateItem(id, ifMatch, in);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PutMapping("/items/{id}/favorite")
    ResponseEntity<Void> addFavorite(@PathVariable UUID id) {
        service.favorite(id, true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/items/{id}/favorite")
    ResponseEntity<Void> removeFavorite(@PathVariable UUID id) {
        service.favorite(id, false);
        return ResponseEntity.noContent().build();
    }

    /** Заказ услуги: заявка через модуль tickets и, если нужно, маршрут согласования. Idempotency-Key обязателен клиенту. */
    @PostMapping("/items/{id}/order")
    ResponseEntity<CatalogViews.OrderResult> order(@PathVariable UUID id,
                                                   @Valid @RequestBody CatalogCommands.Order cmd) {
        var result = service.order(id, cmd);
        return ResponseEntity.created(URI.create("/api/v1/tickets/" + result.ticketId())).body(result);
    }

    /** Ошибки по полям формы показываются у полей (UI-06): {@code errors: {key: code}}. */
    @ExceptionHandler(FormValidationException.class)
    ResponseEntity<ProblemDetail> formInvalid(FormValidationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create("https://easyitsm.ru/problems/form_invalid"));
        pd.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        pd.setProperty("code", "form_invalid");
        pd.setProperty("errors", ex.errors());
        pd.setProperty("trace_id", TraceIdFilter.current());
        return ResponseEntity.badRequest().body(pd);
    }
}
