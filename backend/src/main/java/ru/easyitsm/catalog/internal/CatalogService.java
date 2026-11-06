package ru.easyitsm.catalog.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.catalog.api.CatalogItemRef;
import ru.easyitsm.catalog.api.CatalogQueries;
import ru.easyitsm.catalog.events.CatalogItemOrdered;
import ru.easyitsm.catalog.internal.form.FormSchema;
import ru.easyitsm.catalog.internal.form.FormSchemaValidator;
import ru.easyitsm.catalog.internal.form.FormValues;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.platform.tenant.TenantContext;

/** Каталог: категории, предложения, избранное и заказ услуги. */
@Service
class CatalogService implements CatalogQueries {

    static final int POPULAR_LIMIT = 8;
    private static final UUID NO_CATEGORY = new UUID(0, 0);

    private final CategoryRepository categories;
    private final CatalogItemRepository items;
    private final FavoriteRepository favorites;
    private final ApprovalFlowRepository flows;
    private final ApprovalService approvals;
    private final TicketGateway tickets;
    private final JsonCodec json;
    private final ApplicationEventPublisher events;

    CatalogService(CategoryRepository categories, CatalogItemRepository items, FavoriteRepository favorites,
                   ApprovalFlowRepository flows, ApprovalService approvals, TicketGateway tickets, JsonCodec json,
                   ApplicationEventPublisher events) {
        this.categories = categories;
        this.items = items;
        this.favorites = favorites;
        this.flows = flows;
        this.approvals = approvals;
        this.tickets = tickets;
        this.json = json;
        this.events = events;
    }

    // ---------- категории ----------

    @Transactional(readOnly = true)
    public List<CatalogViews.CategoryView> categories(boolean includeInactive) {
        boolean all = includeInactive && Roles.admin();
        return categories.findAllByOrderBySortOrderAscNameAsc().stream()
                .filter(c -> all || c.active())
                .map(Category::toView)
                .toList();
    }

    @Transactional
    public CatalogViews.CategoryView createCategory(CatalogCommands.CategoryInput in) {
        requireParent(in.parentId());
        var c = new Category(in.parentId(), in.name());
        c.update(in.parentId(), in.name(), in.description(), in.icon(), orZero(in.sortOrder()),
                in.active() == null || in.active());
        return categories.save(c).toView();
    }

    @Transactional
    public CatalogViews.CategoryView updateCategory(UUID id, String ifMatch, CatalogCommands.CategoryInput in) {
        Category c = categories.findById(id).orElseThrow(() -> ApiException.notFound("Категория"));
        ETags.requireMatch(ifMatch, c.getVersion());
        requireParent(in.parentId());
        // Категория не может оказаться внутри своей ветки.
        for (UUID p = in.parentId(); p != null; p = categories.findById(p).map(Category::parentId).orElse(null)) {
            if (p.equals(id)) {
                throw ApiException.badRequest("category_cycle", "Категорию нельзя вложить в саму себя");
            }
        }
        c.update(in.parentId(), in.name(), in.description(), in.icon(), orZero(in.sortOrder()),
                in.active() == null || in.active());
        return categories.save(c).toView();
    }

    private void requireParent(UUID parentId) {
        if (parentId != null && !categories.existsById(parentId)) {
            throw ApiException.badRequest("unknown_category", "Родительская категория не найдена");
        }
    }

    // ---------- предложения ----------

    @Transactional(readOnly = true)
    public CursorPage<CatalogViews.ItemCard> list(UUID categoryId, String q, String team, boolean onlyFavorites,
                                                  boolean includeInactive, PageParams page) {
        UUID me = Roles.actor();
        boolean admin = includeInactive && Roles.admin();
        List<UUID> categoryIds = categoryId == null ? List.of(NO_CATEGORY) : List.copyOf(subtree(categoryId));
        KeysetCursor after = page.keyset();
        List<CatalogItem> rows = items.page(admin, roles(), categoryId == null, categoryIds, like(q), blank(team),
                onlyFavorites ? me : null, after == null ? null : after.sortValue(),
                after == null ? null : after.id(), page.limit() + 1);
        Set<UUID> fav = new HashSet<>(favorites.itemIds(me));
        return CursorPage.of(rows, page.limit(), i -> new KeysetCursor(i.name(), i.getId()))
                .map(i -> i.toCard(fav.contains(i.getId())));
    }

    @Transactional(readOnly = true)
    public List<CatalogViews.ItemCard> popular() {
        Set<UUID> fav = new HashSet<>(favorites.itemIds(Roles.actor()));
        return items.popular(false, roles(), POPULAR_LIMIT).stream().map(i -> i.toCard(fav.contains(i.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<String> teams() {
        return items.teams(false, roles());
    }

    @Transactional(readOnly = true)
    public CatalogViews.ItemDetail get(UUID id) {
        CatalogItem item = visible(id);
        return item.toDetail(json, favorites.existsByUserIdAndItemId(Roles.actor(), id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CatalogItemRef> search(String query, int limit) {
        int l = Math.max(1, Math.min(limit, PageParams.MAX_LIMIT));
        return items.page(false, roles(), true, List.of(NO_CATEGORY), like(query), null, null, null, null, l)
                .stream().map(CatalogItem::toRef).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CatalogItemRef> activeItems() {
        return items.allActive().stream().map(CatalogItem::toRef).toList();
    }

    @Transactional
    public CatalogViews.ItemDetail createItem(CatalogCommands.ItemFields in) {
        var item = new CatalogItem(in.categoryId(), in.name());
        item.update(in, checkedForm(in));
        checkReferences(in);
        return items.save(item).toDetail(json, false);
    }

    @Transactional
    public CatalogViews.ItemDetail updateItem(UUID id, String ifMatch, CatalogCommands.ItemFields in) {
        CatalogItem item = items.findById(id).orElseThrow(() -> ApiException.notFound("Услуга"));
        ETags.requireMatch(ifMatch, item.getVersion());
        item.update(in, checkedForm(in));
        checkReferences(in);
        CatalogItem saved = items.save(item);
        return saved.toDetail(json, favorites.existsByUserIdAndItemId(Roles.actor(), id));
    }

    private String checkedForm(CatalogCommands.ItemFields in) {
        FormSchema schema = in.form() == null ? FormSchema.EMPTY
                : json.parseInput(in.form(), FormSchema.class, "invalid_form_schema");
        FormSchemaValidator.check(schema);
        return json.write(schema);
    }

    private void checkReferences(CatalogCommands.ItemFields in) {
        if (!categories.existsById(in.categoryId())) {
            throw ApiException.badRequest("unknown_category", "Категория не найдена");
        }
        if (in.approvalFlowId() != null && !flows.existsById(in.approvalFlowId())) {
            throw ApiException.badRequest("unknown_approval_flow", "Маршрут согласования не найден");
        }
    }

    // ---------- избранное ----------

    @Transactional
    public void favorite(UUID itemId, boolean on) {
        UUID me = Roles.actor();
        visible(itemId);
        boolean exists = favorites.existsByUserIdAndItemId(me, itemId);
        if (on && !exists) {
            favorites.save(new Favorite(me, itemId));
        } else if (!on && exists) {
            favorites.remove(me, itemId);
        }
    }

    // ---------- заказ ----------

    @Transactional
    public CatalogViews.OrderResult order(UUID itemId, CatalogCommands.Order cmd) {
        UUID me = Roles.actor();
        CatalogItem item = visible(itemId);
        if (!item.active()) {
            throw ApiException.notFound("Услуга");
        }
        UUID forUser = cmd.forUserId() == null || cmd.forUserId().equals(me) ? null : cmd.forUserId();
        if (forUser != null && !item.allowOnBehalf()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "on_behalf_not_allowed",
                    "Эту услугу нельзя заказать для другого сотрудника");
        }
        Map<String, Object> values = FormValues.validate(json.form(item.form()), cmd.values());
        String title = cmd.title() == null || cmd.title().isBlank() ? item.name() : cmd.title().strip();
        String description = cmd.description() == null ? "" : cmd.description().strip();
        if (item.kind().equals("incident") && description.isEmpty()) {
            throw ApiException.badRequest("description_required", "Опишите, что случилось");
        }

        List<StepDef> route = approvals.plan(item.approvalFlowId(), values, me);
        boolean awaiting = !route.isEmpty();
        var ticket = tickets.create(new TicketGateway.NewTicket(item.kind(), title, description, me, forUser,
                item.getId(), cmd.impact() == null ? "only_me" : cmd.impact(), values, awaiting));
        if (awaiting) {
            approvals.start(ticket, title, item.getId(), item.approvalFlowId(), route, me, forUser);
        }
        items.incrementOrderCount(item.getId());
        events.publishEvent(new CatalogItemOrdered(TenantContext.require(), item.getId(), item.name(), ticket.id(),
                ticket.number(), me, forUser, awaiting));

        Instant expectedBy = item.leadTimeHours() == null ? null
                : Instant.now().plus(Duration.ofHours(item.leadTimeHours()));
        return new CatalogViews.OrderResult(ticket.id(), ticket.number(), ticket.status(), awaiting, expectedBy);
    }

    /** Недоступная по ролям или выключенная услуга выглядит как отсутствующая (AT-01). */
    private CatalogItem visible(UUID id) {
        return items.findById(id)
                .filter(i -> i.visibleTo(Roles.current(), Roles.admin()))
                .orElseThrow(() -> ApiException.notFound("Услуга"));
    }

    private Set<UUID> subtree(UUID root) {
        Map<UUID, List<UUID>> children = new java.util.HashMap<>();
        for (Category c : categories.findAll()) {
            if (c.parentId() != null) {
                children.computeIfAbsent(c.parentId(), k -> new java.util.ArrayList<>()).add(c.getId());
            }
        }
        Set<UUID> out = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>(List.of(root));
        while (!queue.isEmpty()) {
            UUID next = queue.pop();
            if (out.add(next)) {
                queue.addAll(children.getOrDefault(next, List.of()));
            }
        }
        return out;
    }

    private static String[] roles() {
        String[] r = Roles.current().toArray(String[]::new);
        return r.length == 0 ? new String[] {"-"} : r;
    }

    private static String like(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String escaped = q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static int orZero(Integer i) {
        return i == null ? 0 : i;
    }
}
