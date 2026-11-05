package ru.easyitsm.tickets.api;

/** Эскалация (INC-04): функциональная — в другую группу, иерархическая — руководителю. */
public enum EscalationKind {
    FUNCTIONAL, HIERARCHICAL;

    public String code() {
        return Codes.code(this);
    }

    public static EscalationKind fromCode(String code) {
        return Codes.parse(EscalationKind.class, code, "вида эскалации");
    }
}
