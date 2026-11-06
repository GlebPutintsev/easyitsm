package ru.easyitsm.assets.internal;

import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface LicenseRepository extends ListCrudRepository<SoftwareLicense, UUID> {
}
