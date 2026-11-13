package com.noop.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Plain-JVM tests for the new two-database recovery ZIP's staging contract. */
class FullRecoveryPackageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fullPackageStagesHealthFoodAndSettings() {
        val zipFile = temporary.newFile("complete.noopbak")
        val health = "SQLite format 3\u0000health records".toByteArray()
        val food = "SQLite format 3\u0000food records".toByteArray()
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            listOf(
                "noop-backup.sqlite" to health,
                "settings.json" to "{\"profile.age\":30}".toByteArray(),
                DataBackup.FOOD_ENTRY_NAME to food,
                "manifest.json" to "{}".toByteArray(),
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val healthOut = File(temporary.root, "health-staged.sqlite")
        val foodOut = File(temporary.root, "food-staged.sqlite")
        val settingsOut = File(temporary.root, "settings-staged.json")
        val result = DataBackup.stageBackupSqlite(
            zipFile.inputStream(), DataBackup.peekHeader(zipFile), healthOut,
            settingsOut, foodDest = foodOut,
        )
        assertEquals(DataBackup.StageResult.OK, result)
        assertArrayEquals(health, healthOut.readBytes())
        assertArrayEquals(food, foodOut.readBytes())
        assertTrue(settingsOut.readText().contains("profile.age"))
        assertTrue(DataBackup.containsFoodHistory(zipFile))
    }

    @Test fun legacyPackageStillRestoresHealthWithoutInventingFoodHistory() {
        val zipFile = temporary.newFile("legacy.noopbak")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("noop-backup.sqlite"))
            zip.write("SQLite format 3\u0000health only".toByteArray())
            zip.closeEntry()
        }
        val healthOut = File(temporary.root, "legacy-health.sqlite")
        val foodOut = File(temporary.root, "legacy-food.sqlite")
        val result = DataBackup.stageBackupSqlite(
            zipFile.inputStream(), DataBackup.peekHeader(zipFile), healthOut,
            foodDest = foodOut,
        )
        assertEquals(DataBackup.StageResult.OK, result)
        assertTrue(healthOut.exists())
        assertFalse(foodOut.exists())
        assertFalse(DataBackup.containsFoodHistory(zipFile))
    }
}
