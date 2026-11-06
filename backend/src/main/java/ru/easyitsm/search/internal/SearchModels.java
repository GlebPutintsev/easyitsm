package ru.easyitsm.search.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ru.easyitsm.tickets.api.TicketType;

/** Запрос и ответ глобального поиска. */
final class SearchModels {

    static final char MARK_START = '';
    static final char MARK_END = '';

    private SearchModels() {
    }

    /** Что искать: заявки, статьи базы знаний, услуги каталога. */
    enum ResultKind {
        TICKET, ARTICLE, SERVICE;

        String code() {
            return name().toLowerCase();
        }

        static ResultKind fromCode(String code) {
            return valueOf(code.strip().toUpperCase());
        }
    }

    /**
     * @param state       заявки: {@code active} или {@code closed}, null — все
     * @param updatedFrom заявки изменены не раньше
     */
    record Query(String text, Set<ResultKind> kinds, Set<TicketType> ticketTypes, String state, Instant updatedFrom,
                 int limit) {
    }

    /** Кусок текста: {@code hl} — совпадение, его подсвечивает интерфейс. */
    record Segment(String text, boolean hl) {
    }

    /**
     * Найденная запись. Поля заявки ({@code ticketType}, {@code status}…) заполнены только для заявок.
     *
     * @param ref номер заявки {@code INC-000123} или статьи {@code KB-42}; у услуги — null
     */
    record Hit(String kind, UUID id, String ref, List<Segment> title, List<Segment> snippet, String ticketType,
               String status, String statusCategory, String priority, Instant updatedAt) {
    }

    /** @param total сколько найдено всего, если движок это знает (OpenSearch), иначе null */
    record Group(String kind, List<Hit> items, Long total) {
    }

    /**
     * @param engine   {@code opensearch} или {@code postgres}
     * @param degraded OpenSearch настроен, но недоступен или индекс ещё строится: поиск хуже обычного (AT-08)
     */
    record Response(String engine, boolean degraded, List<Group> groups) {
    }

    /** Разбивает текст с метками совпадений на куски. Непарные метки отбрасываются. */
    static List<Segment> segments(String marked) {
        List<Segment> out = new ArrayList<>();
        if (marked == null || marked.isEmpty()) {
            return out;
        }
        StringBuilder sb = new StringBuilder();
        boolean hl = false;
        for (int i = 0; i < marked.length(); i++) {
            char c = marked.charAt(i);
            if (c == MARK_START || c == MARK_END) {
                boolean next = c == MARK_START;
                if (next != hl) {
                    flush(out, sb, hl);
                    hl = next;
                }
            } else {
                sb.append(c);
            }
        }
        flush(out, sb, hl);
        return out;
    }

    static List<Segment> plain(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(new Segment(text, false));
    }

    private static void flush(List<Segment> out, StringBuilder sb, boolean hl) {
        if (!sb.isEmpty()) {
            if (!out.isEmpty() && out.getLast().hl() == hl) {
                Segment last = out.removeLast();
                out.add(new Segment(last.text() + sb, hl));
            } else {
                out.add(new Segment(sb.toString(), hl));
            }
            sb.setLength(0);
        }
    }
}
