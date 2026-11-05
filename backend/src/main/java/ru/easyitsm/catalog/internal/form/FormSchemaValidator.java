package ru.easyitsm.catalog.internal.form;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import ru.easyitsm.platform.web.ApiException;

/** Проверка схемы формы при сохранении в конструкторе. Ошибка в схеме не должна ломать портал. */
public final class FormSchemaValidator {

    static final int MAX_FIELDS = 100;
    static final int MAX_OPTIONS = 200;
    static final int MAX_PATTERN = 200;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

    private FormSchemaValidator() {
    }

    public static void check(FormSchema schema) {
        if (schema.fields().size() > MAX_FIELDS) {
            throw invalid("в форме больше " + MAX_FIELDS + " полей");
        }
        Set<String> declared = new HashSet<>();
        for (FormField f : schema.fields()) {
            if (f == null || f.key() == null || !KEY.matcher(f.key()).matches()) {
                throw invalid("код поля: латинские строчные буквы, цифры и _, до 40 символов");
            }
            String at = " (поле " + f.key() + ")";
            if (!declared.add(f.key())) {
                throw invalid("код поля повторяется" + at);
            }
            if (f.type() == null || !FormField.TYPES.contains(f.type())) {
                throw invalid("неизвестный тип поля" + at);
            }
            if (f.label() == null || f.label().isBlank() || f.label().length() > 200) {
                throw invalid("название поля обязательно, до 200 символов" + at);
            }
            if (f.hasOptions()) {
                if (f.options() == null || f.options().isEmpty() || f.options().size() > MAX_OPTIONS) {
                    throw invalid("у списка должно быть от 1 до " + MAX_OPTIONS + " вариантов" + at);
                }
                Set<String> values = new HashSet<>();
                for (FieldOption o : f.options()) {
                    if (o == null || o.value() == null || o.value().isBlank() || o.label() == null || o.label().isBlank()) {
                        throw invalid("у варианта нужны значение и подпись" + at);
                    }
                    if (!values.add(o.value())) {
                        throw invalid("значение варианта повторяется" + at);
                    }
                }
            } else if (f.options() != null && !f.options().isEmpty()) {
                throw invalid("варианты допустимы только у списка" + at);
            }
            if (f.min() != null && f.max() != null && f.min() > f.max()) {
                throw invalid("минимум больше максимума" + at);
            }
            if (f.pattern() != null && !f.pattern().isEmpty()) {
                if (f.pattern().length() > MAX_PATTERN) {
                    throw invalid("шаблон проверки длиннее " + MAX_PATTERN + " символов" + at);
                }
                try {
                    Pattern.compile(f.pattern());
                } catch (PatternSyntaxException e) {
                    throw invalid("некорректный шаблон проверки" + at);
                }
            }
            // Условие ссылается только на поля выше: так нет циклов, а форма читается сверху вниз.
            checkCondition(f.visibleWhen(), declared, f.key(), at);
            checkCondition(f.requiredWhen(), declared, f.key(), at);
        }
    }

    /** Условие шага согласования ссылается на любые поля формы. */
    public static void checkStepCondition(Condition c, String stepName) {
        if (c == null) {
            return;
        }
        String problem = c.problem(0);
        if (problem != null) {
            throw invalid(problem + " (шаг " + stepName + ")");
        }
    }

    private static void checkCondition(Condition c, Set<String> declared, String self, String at) {
        if (c == null) {
            return;
        }
        String problem = c.problem(0);
        if (problem != null) {
            throw invalid(problem + at);
        }
        List<String> refs = new ArrayList<>();
        c.collectFields(refs);
        for (String ref : refs) {
            if (ref.equals(self) || !declared.contains(ref)) {
                throw invalid("условие ссылается на поле, которого нет выше: " + ref + at);
            }
        }
    }

    private static ApiException invalid(String detail) {
        return ApiException.badRequest("invalid_form_schema", "Ошибка в форме: " + detail);
    }
}
