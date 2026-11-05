package ru.easyitsm;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Границы модулей (ADR-02, NFR-17): модуль обращается к другому только через api и events. */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(EasyItsmApplication.class);

    @Test
    void modulesRespectBoundaries() {
        MODULES.verify();
    }

    @Test
    void writeModuleDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
