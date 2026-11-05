package ru.easyitsm.surveys.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Опрос удовлетворённости (PRT-06). */
@RestController
@RequestMapping("/api/v1/surveys")
class SurveyController {

    private final SurveyService service;

    SurveyController(SurveyService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<SurveyResponse.SurveyView> submit(@Valid @RequestBody SurveyCommands.Submit cmd) {
        var view = service.submit(cmd);
        return ResponseEntity.created(URI.create("/api/v1/surveys/" + view.ticketId())).body(view);
    }

    /** Оценка заявки: 404, если её ещё нет или она недоступна. */
    @GetMapping("/{ticketId}")
    SurveyResponse.SurveyView get(@PathVariable UUID ticketId) {
        return service.get(ticketId);
    }
}
