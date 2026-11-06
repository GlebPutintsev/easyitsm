package ru.easyitsm.assets.internal;

import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface ContractRepository extends ListCrudRepository<Contract, UUID> {
}
