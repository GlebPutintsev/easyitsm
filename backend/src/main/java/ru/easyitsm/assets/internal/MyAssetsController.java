package ru.easyitsm.assets.internal;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;

/** «Моё оборудование и ПО» для любого вошедшего сотрудника: только то, что закреплено за ним самим. */
@RestController
class MyAssetsController {

    private final AssetService assets;
    private final SoftwareLicenseService licenses;

    MyAssetsController(AssetService assets, SoftwareLicenseService licenses) {
        this.assets = assets;
        this.licenses = licenses;
    }

    @GetMapping("/api/v1/me/assets")
    List<AssetDtos.AssetRow> assets() {
        return assets.mine();
    }

    @GetMapping("/api/v1/me/licenses")
    List<AssetDtos.AssignmentView> licenses() {
        return licenses.ofUser(CurrentActor.userId().orElseThrow(ApiException::forbidden));
    }
}
