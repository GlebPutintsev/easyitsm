package ru.easyitsm.knowledge.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Поиск секретов в тексте статьи перед отправкой на проверку (10.12): пароли, ключи, токены.
 * Срабатывание не запрещает отправку, но требует явного подтверждения автора.
 */
final class SecretScanner {

    private record Rule(String kind, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("private_key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
            new Rule("aws_key", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Rule("github_token", Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})\\b")),
            new Rule("slack_token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}")),
            new Rule("jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")),
            new Rule("password", Pattern.compile(
                    "(?iu)(?:password|passwd|pwd|пароль)\\s*[:=]\\s*(?![<\\[{(*]|\\.\\.\\.|x{3,}|\\*{3,})\\S{4,}")),
            new Rule("api_key", Pattern.compile(
                    "(?iu)\\b(?:api[_-]?key|secret|token|access[_-]?key|client[_-]?secret)\\s*[:=]\\s*['\"]?[A-Za-z0-9_\\-./+=]{16,}")),
            new Rule("connection_string", Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s:/@]+:[^\\s:/@]{3,}@")));

    private SecretScanner() {
    }

    static List<KnowledgeDtos.SecretFinding> scan(String... texts) {
        List<KnowledgeDtos.SecretFinding> findings = new ArrayList<>();
        int offset = 0;
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                for (Rule rule : RULES) {
                    if (rule.pattern().matcher(lines[i]).find()) {
                        findings.add(new KnowledgeDtos.SecretFinding(rule.kind(), offset + i + 1));
                    }
                }
            }
            offset += lines.length;
        }
        return findings;
    }
}
