package ru.easyitsm.problems.api;

import java.util.UUID;

/** Созданная проблема: id и номер заявки PRB-…. */
public record ProblemRef(UUID id, String number) {
}
