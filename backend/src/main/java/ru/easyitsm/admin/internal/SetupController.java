package ru.easyitsm.admin.internal;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.web.ETags;

/** Мастер первичной настройки {@code /api/v1/admin/setup} (ADM-01, 10.16): только ADM. */
@RestController
@RequestMapping("/api/v1/admin/setup")
@PreAuthorize(Roles.ADMIN)
class SetupController {

    private final SetupService setup;
    private final SetupChecks checks;
    private final DemoDataService demo;

    SetupController(SetupService setup, SetupChecks checks, DemoDataService demo) {
        this.setup = setup;
        this.checks = checks;
        this.demo = demo;
    }

    record StepCommand(String status) {
    }

    @GetMapping
    ResponseEntity<SetupService.State> get() {
        var s = setup.get();
        return ResponseEntity.ok().eTag(ETags.of(s.version())).body(s);
    }

    @PutMapping("/organization")
    ResponseEntity<SetupService.State> organization(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody SetupService.Organization org) {
        var s = setup.saveOrganization(ifMatch, org);
        return ResponseEntity.ok().eTag(ETags.of(s.version())).body(s);
    }

    @PutMapping("/steps/{step}")
    ResponseEntity<SetupService.State> step(@PathVariable String step, @RequestBody StepCommand cmd) {
        var s = setup.setStep(step, cmd.status());
        return ResponseEntity.ok().eTag(ETags.of(s.version())).body(s);
    }

    @PostMapping("/complete")
    ResponseEntity<SetupService.State> complete() {
        var s = setup.complete();
        return ResponseEntity.ok().eTag(ETags.of(s.version())).body(s);
    }

    @PostMapping("/checks/mail")
    SetupChecks.Report checkMail() {
        return checks.mail();
    }

    @PostMapping("/checks/sso")
    SetupChecks.Report checkSso() {
        return checks.sso();
    }

    @PostMapping("/demo-data")
    DemoDataService.Loaded demoData() {
        return demo.load();
    }
}
