package ru.easyitsm.assets.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.assets.internal.AssetDtos.ContractView;
import ru.easyitsm.platform.web.ETags;

/** Договоры, поставщики и местоположения активов (AST-04). */
@RestController
class ContractController {

    private final ContractService service;

    ContractController(ContractService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/contracts")
    @PreAuthorize(AssetRoles.READ)
    List<ContractView> list(@RequestParam(required = false) String q,
                            @RequestParam(required = false) @Pattern(regexp = "active|expiring|expired|terminated") String status,
                            @RequestParam(required = false) UUID vendorId) {
        return service.list(q, status, vendorId);
    }

    @GetMapping("/api/v1/contracts/{id}")
    @PreAuthorize(AssetRoles.READ)
    ResponseEntity<ContractView> get(@PathVariable UUID id) {
        ContractView v = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @PostMapping("/api/v1/contracts")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<ContractView> create(@Valid @RequestBody AssetDtos.ContractFields cmd) {
        ContractView v = service.create(cmd);
        return ResponseEntity.created(URI.create("/api/v1/contracts/" + v.id())).eTag(ETags.of(v.version())).body(v);
    }

    @PatchMapping("/api/v1/contracts/{id}")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<ContractView> update(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                        @Valid @RequestBody AssetDtos.ContractFields cmd) {
        ContractView v = service.update(id, ifMatch, cmd);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @GetMapping("/api/v1/vendors")
    @PreAuthorize(AssetRoles.READ)
    List<AssetDtos.VendorView> vendors() {
        return service.vendors();
    }

    @PostMapping("/api/v1/vendors")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetDtos.VendorView> createVendor(@Valid @RequestBody AssetDtos.VendorFields cmd) {
        AssetDtos.VendorView v = service.createVendor(cmd);
        return ResponseEntity.created(URI.create("/api/v1/vendors/" + v.id())).eTag(ETags.of(v.version())).body(v);
    }

    @PatchMapping("/api/v1/vendors/{id}")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetDtos.VendorView> updateVendor(@PathVariable UUID id,
                                                      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                      @Valid @RequestBody AssetDtos.VendorFields cmd) {
        AssetDtos.VendorView v = service.updateVendor(id, ifMatch, cmd);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @GetMapping("/api/v1/asset-locations")
    @PreAuthorize(AssetRoles.READ)
    List<AssetDtos.LocationView> locations() {
        return service.locations();
    }

    @PostMapping("/api/v1/asset-locations")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetDtos.LocationView> createLocation(@Valid @RequestBody AssetDtos.LocationFields cmd) {
        AssetDtos.LocationView v = service.createLocation(cmd);
        return ResponseEntity.created(URI.create("/api/v1/asset-locations/" + v.id())).eTag(ETags.of(v.version()))
                .body(v);
    }

    @PatchMapping("/api/v1/asset-locations/{id}")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetDtos.LocationView> updateLocation(@PathVariable UUID id,
                                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                          @Valid @RequestBody AssetDtos.LocationFields cmd) {
        AssetDtos.LocationView v = service.updateLocation(id, ifMatch, cmd);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }
}
