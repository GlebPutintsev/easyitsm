package ru.easyitsm.admin.internal;

import java.util.ArrayList;
import java.util.List;
import ru.easyitsm.platform.web.ApiException;

/**
 * Разбор CSV по RFC 4180: кавычки, переводы строк внутри кавычек, удвоенная кавычка. Разделитель определяется
 * по заголовку: «;» (так сохраняет Excel с русской локалью), «,» или табуляция. BOM в начале отбрасывается.
 */
final class Csv {

    private Csv() {
    }

    static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String header = end < 0 ? text : text.substring(0, end);
        int semi = count(header, ';');
        int comma = count(header, ',');
        int tab = count(header, '\t');
        if (tab > semi && tab > comma) {
            return '\t';
        }
        return semi >= comma && semi > 0 ? ';' : ',';
    }

    static List<List<String>> parse(String raw, int maxRows) {
        String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
        char sep = detectDelimiter(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"' && cell.isEmpty()) {
                quoted = true;
                any = true;
            } else if (c == sep) {
                row.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || !cell.isEmpty()) {
                    row.add(cell.toString());
                    rows.add(row);
                    if (rows.size() > maxRows + 1) {
                        throw ApiException.badRequest("too_many_rows", "В файле больше " + maxRows + " строк");
                    }
                }
                row = new ArrayList<>();
                cell.setLength(0);
                any = false;
            } else {
                cell.append(c);
                any = true;
            }
        }
        if (quoted) {
            throw ApiException.badRequest("invalid_csv", "Незакрытая кавычка в файле");
        }
        if (any || !cell.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        if (rows.size() > maxRows + 1) {
            throw ApiException.badRequest("too_many_rows", "В файле больше " + maxRows + " строк");
        }
        return rows;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
