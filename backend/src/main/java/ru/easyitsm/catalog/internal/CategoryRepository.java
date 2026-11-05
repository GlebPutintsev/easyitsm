package ru.easyitsm.catalog.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface CategoryRepository extends ListCrudRepository<Category, UUID> {

    List<Category> findAllByOrderBySortOrderAscNameAsc();
}
