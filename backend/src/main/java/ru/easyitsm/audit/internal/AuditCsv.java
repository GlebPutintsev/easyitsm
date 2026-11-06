package ru.easyitsm.audit.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Выгрузка журнала в CSV (UTF-8 с BOM, разделитель «;» для Excel). Все ячейки в кавычках, а значения,
 * которые табличный редактор принял бы за формулу ({@code = + - @}, табуляция, перевод строки), начинаются
 * с апострофа (защита от CSV-инъекций, RPT-04).
 */
final class AuditCsv {

    static final List<String> HEADER = List.of("seq", "at", "category", "action", "result", "actor_id", "actor_login",
            "object_type", "object_id", "ip", "user_agent", "trace_id", "details", "prev_hash", "hash");

    private final Writer out;

    AuditCsv(Writer out) {
        this.out = out;
    }

    void header() {
        write("﻿");
        row(HEADER);
    }

    void event(AuditViews.Event e) {
        row(List.of(Long.toString(e.seq()), DateTimeFormatter.ISO_INSTANT.format(e.at()), e.category(), e.action(),
                e.result(), str(e.actorId()), str(e.actorLogin()), str(e.objectType()), str(e.objectId()), str(e.ip()),
                str(e.userAgent()), str(e.traceId()), details(e.details()), e.prevHash(), e.hash()));
    }

    private void row(List<String> cells) {
        write(cells.stream().map(AuditCsv::cell).collect(Collectors.joining(";")) + "\r\n");
    }

    static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r\n".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static String details(Map<String, Object> details) {
        return details.entrySet().stream().map(d -> d.getKey() + "=" + d.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private void write(String s) {
        try {
            out.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
