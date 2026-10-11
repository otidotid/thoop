package com.noop.ingest

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import com.noop.ingest.HealthConnectImporter.ImportCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for THOOP's manual Health Connect import contract.
 *
 * Health Connect is limited to activity and exercise records. Existing Android grants never broaden
 * the selected scope, and opening Today must not trigger an automatic Health Connect read.
 */
class HealthConnectPermissionCategoryTest {
    private val activityOnly = setOf(ImportCategory.ACTIVITY_ONLY)
    private val allActivity = setOf(ImportCategory.ALL)

    @Test
    fun defaultScopeIsActivityOnly() {
        assertEquals(activityOnly, HealthConnectImporter.DEFAULT_CATEGORIES)
    }

    @Test
    fun activityOnlyRequestsActivityButNotExerciseRecoveryOrBodyComposition() {
        val permissions = HealthConnectImporter.permissionsFor(activityOnly)

        assertTrue(HealthPermission.getReadPermission(StepsRecord::class) in permissions)
        assertFalse(HealthPermission.getReadPermission(ExerciseSessionRecord::class) in permissions)
        assertFalse(HealthPermission.getReadPermission(DistanceRecord::class) in permissions)
        assertFalse(HealthPermission.getReadPermission(HeartRateRecord::class) in permissions)
        assertFalse(HealthPermission.getReadPermission(WeightRecord::class) in permissions)
    }

    @Test
    fun allScopeAddsExerciseButNeverRecoveryOrBodyComposition() {
        val permissions = HealthConnectImporter.permissionsFor(allActivity)

        assertTrue(HealthPermission.getReadPermission(StepsRecord::class) in permissions)
        assertTrue(HealthPermission.getReadPermission(ExerciseSessionRecord::class) in permissions)
        assertTrue(HealthPermission.getReadPermission(DistanceRecord::class) in permissions)
        assertFalse(HealthPermission.getReadPermission(HeartRateRecord::class) in permissions)
        assertFalse(HealthPermission.getReadPermission(WeightRecord::class) in permissions)
    }

    @Test
    fun supportedPermissionsMatchTheActivityAndExerciseContract() {
        assertEquals(
            HealthConnectImporter.permissionsFor(HealthConnectImporter.ALL_CATEGORIES),
            HealthConnectImporter.PERMISSIONS,
        )
        assertFalse(
            HealthPermission.getReadPermission(HeartRateRecord::class) in
                HealthConnectImporter.PERMISSIONS,
        )
        assertFalse(
            HealthPermission.getReadPermission(WeightRecord::class) in
                HealthConnectImporter.PERMISSIONS,
        )
    }

    @Test
    fun newAndLegacyInstallsDefaultToActivityOnly() {
        assertEquals(
            activityOnly,
            HealthConnectImporter.categoriesFromStoredKeys(
                storedKeys = null,
                hadLegacyPermissionSignature = false,
            ),
        )
        assertEquals(
            activityOnly,
            HealthConnectImporter.categoriesFromStoredKeys(
                storedKeys = null,
                hadLegacyPermissionSignature = true,
            ),
        )
    }

    @Test
    fun storedAllSelectionIsRestored() {
        assertEquals(
            allActivity,
            HealthConnectImporter.categoriesFromStoredKeys(
                storedKeys = setOf(ImportCategory.ALL.storageKey),
                hadLegacyPermissionSignature = true,
            ),
        )
    }

    @Test
    fun unknownOrLegacyStoredKeysCannotBroadenTheScope() {
        assertEquals(
            activityOnly,
            HealthConnectImporter.categoriesFromStoredKeys(
                storedKeys = setOf("recovery", "activity", "body-composition"),
                hadLegacyPermissionSignature = true,
            ),
        )
    }

    @Test
    fun legacyAndroidGrantsNeverBroadenManualImport() {
        val oldGrants = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
        )

        assertEquals(
            activityOnly,
            HealthConnectImporter.categoriesFromGrantedPermissions(oldGrants),
        )
    }

    @Test
    fun readableTypesRequireBothSelectionAndGrant() {
        val granted = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
        )

        assertEquals(
            setOf(StepsRecord::class),
            HealthConnectImporter.readableRecordTypes(
                categories = activityOnly,
                grantedPermissions = granted,
            ),
        )

        val allReadable = HealthConnectImporter.readableRecordTypes(
            categories = allActivity,
            grantedPermissions = granted,
        )
        assertTrue(StepsRecord::class in allReadable)
        assertTrue(ExerciseSessionRecord::class in allReadable)
        assertFalse(HeartRateRecord::class in allReadable)
    }

    @Test
    fun unaskedPermissionsOnlyContainTheSelectedManualScope() {
        val activityPermissions = HealthConnectImporter.permissionsFor(activityOnly)

        assertTrue(
            HealthConnectImporter.unaskedPermissions(
                asked = activityPermissions,
                categories = activityOnly,
            ).isEmpty(),
        )

        val newlyNeeded = HealthConnectImporter.unaskedPermissions(
            asked = activityPermissions,
            categories = allActivity,
        )
        assertTrue(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class) in newlyNeeded,
        )
        assertFalse(
            HealthPermission.getReadPermission(HeartRateRecord::class) in newlyNeeded,
        )
        assertFalse(
            HealthPermission.getReadPermission(WeightRecord::class) in newlyNeeded,
        )
    }
}
