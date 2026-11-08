package com.musealgo.oj;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseMaintenanceRoleLayoutTest {
    @Test
    void allowsEmptyBuiltinRolesAndPartiallyInitializedModernRoles() {
        assertDoesNotThrow(() -> DatabaseMaintenance.validateReferenceRoleLayout(Map.of()));
        assertDoesNotThrow(() -> DatabaseMaintenance.validateReferenceRoleLayout(
                Map.of(2, "TEACHER", 3, "ADMIN")));
    }

    @Test
    void allowsOnlyTheRecognizedLegacyReversal() {
        assertDoesNotThrow(() -> DatabaseMaintenance.validateReferenceRoleLayout(
                Map.of(1, "ADMIN", 2, "TEACHER", 3, "STUDENT")));
        assertDoesNotThrow(() -> DatabaseMaintenance.validateReferenceRoleLayout(
                Map.of(1, "ADMIN", 3, "STUDENT")));
    }

    @Test
    void rejectsUnknownReservedIdsAndPartialLegacyReversal() {
        assertThrows(IllegalStateException.class, () -> DatabaseMaintenance.validateReferenceRoleLayout(
                Map.of(1, "OWNER")));
        assertThrows(IllegalStateException.class, () -> DatabaseMaintenance.validateReferenceRoleLayout(
                Map.of(1, "ADMIN")));
        assertThrows(IllegalStateException.class, () -> DatabaseMaintenance.validateReferenceRoleLayout(
                Map.of(1, "ADMIN", 2, "OWNER", 3, "STUDENT")));
    }

    @Test
    void rejectsBuiltinCodesAllocatedToCustomIds() {
        assertThrows(IllegalStateException.class, () -> DatabaseMaintenance.validateReferenceRoleLayout(
                Map.of(8, "STUDENT")));
        assertThrows(IllegalStateException.class, () -> DatabaseMaintenance.validateReferenceRoleLayout(
                Map.of(2, "TEACHER", 8, "ADMIN")));
    }

    @Test
    void requiresAnExistingSupportedFlywayBaselineBeforeMaintenance() {
        assertThrows(IllegalStateException.class, () -> DatabaseMaintenance.requireSupportedHistory(null));
        assertThrows(IllegalStateException.class, () -> DatabaseMaintenance.requireSupportedHistory(
                MigrationVersion.fromVersion("5")));
        assertDoesNotThrow(() -> DatabaseMaintenance.requireSupportedHistory(
                MigrationVersion.fromVersion("6")));
    }
}
