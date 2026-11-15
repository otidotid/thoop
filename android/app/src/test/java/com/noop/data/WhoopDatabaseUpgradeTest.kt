package com.noop.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Opens the last released Room schema through the production migration chain (#1803).
 *
 * The structural tests can prove that migration numbers are consecutive, but they cannot prove the
 * SQL produces the entity shape Room expects. That gap once shipped a staging build which crashed
 * every existing install on launch while fresh installs and the whole JVM suite stayed green.
 *
 * Exported schemas under `src/test/resources/roomSchemas` are release inputs, not generated test
 * answers. Starting from `SCHEMA_VERSION - 1` makes an entity edit under an unchanged version fail
 * Room's own identity check; after a legitimate version bump, the new previous-version export becomes
 * the next fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WhoopDatabaseUpgradeTest {

    private val databaseName = "noop-room-upgrade-${WhoopDatabase.SCHEMA_VERSION}.db"
    private val previousVersion = WhoopDatabase.SCHEMA_VERSION - 1
    private val schemaArchive = createSchemaArchive()

    private val instrumentation = InstrumentationRegistry.getInstrumentation().also { instrumentation ->
        listOf(instrumentation.context.assets, instrumentation.targetContext.assets).forEach { assets ->
            val cookie = ReflectionHelpers.callInstanceMethod<Int>(
                assets,
                "addAssetPath",
                ClassParameter.from(String::class.java, schemaArchive.toString()),
            )
            check(cookie != 0) { "Robolectric could not mount the Room schema test assets" }
        }
    }

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        WhoopDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun previousReleasedSchemaOpensThroughTheProductionMigrationChain() {
        helper.createDatabase(databaseName, previousVersion).apply {
            execSQL("INSERT INTO labMarker (id,deviceId,markerKey,category,day,takenAt,value,unit,source,note,referenceText," +
                "displayName,sampleCondition,hoursSinceMeal,evidenceProfileId,evidenceVersion) VALUES " +
                "('source-lab','my-whoop','custom_glucose','other','2026-11-12',1794480123,175.25,'mg/dL','manual'," +
                "'original note','report range','Random glucose','post_meal',2.0,'glycemic_non_fasting','recorded-policy')")
            execSQL("INSERT INTO metricSeries (deviceId,day,`key`,value) VALUES " +
                "('my-whoop-noop','2026-11-12','fitness_age',42.25)")
            execSQL("INSERT INTO scoreInputProvenance (deviceId,day,`key`,sourceId) VALUES " +
                "('my-whoop-noop','2026-11-12','fitness_age','original-source')")
        }.close()

        val migrated = Room.databaseBuilder(
            instrumentation.targetContext,
            WhoopDatabase::class.java,
            databaseName,
        )
            .addMigrations(*WhoopDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        try {
            val db = migrated.openHelper.writableDatabase
            val cursor = db.query("PRAGMA user_version")
            cursor.use {
                check(it.moveToFirst()) { "PRAGMA user_version returned no row" }
                assertEquals(WhoopDatabase.SCHEMA_VERSION, it.getInt(0))
            }
            db.query("SELECT value,unit,source,note,referenceText,displayName,sampleCondition,hoursSinceMeal," +
                "evidenceProfileId,evidenceVersion FROM labMarker WHERE id='source-lab'").use {
                assertTrue(it.moveToFirst())
                assertEquals(175.25, it.getDouble(0), 0.0)
                assertEquals(listOf("mg/dL", "manual", "original note", "report range", "Random glucose", "post_meal"),
                    (1..6).map(it::getString))
                assertEquals(2.0, it.getDouble(7), 0.0)
                assertEquals("glycemic_non_fasting", it.getString(8))
                assertEquals("recorded-policy", it.getString(9))
            }
            db.query("SELECT value FROM metricSeries WHERE deviceId='my-whoop-noop' AND `key`='fitness_age'").use {
                assertTrue(it.moveToFirst())
                assertEquals(42.25, it.getDouble(0), 0.0)
            }
            db.query("SELECT sourceId,algorithmVersion,observedInputs FROM scoreInputProvenance " +
                "WHERE deviceId='my-whoop-noop' AND `key`='fitness_age'").use {
                assertTrue(it.moveToFirst())
                assertEquals("original-source", it.getString(0))
                assertTrue("legacy method version remains unknown", it.isNull(1))
                assertTrue("legacy evidence remains unknown", it.isNull(2))
            }
        } finally {
            migrated.close()
        }
    }

    /** Both additive metadata migrations must preserve original numeric and qualitative measurements. */
    @Test
    fun preMetadataSchemaPreservesNumericAndQualitativeOriginalLabRows() {
        val oldName = "noop-room-pre-metadata.db"
        helper.createDatabase(oldName, 41).apply {
            execSQL("INSERT INTO labMarker (id,deviceId,markerKey,category,day,takenAt,value,unit,source,note) VALUES " +
                "('numeric','my-whoop','waist','bodyMeasurement','2026-11-12',1794480123,36.0,'in','csv','source note')")
            execSQL("INSERT INTO labMarker (id,deviceId,markerKey,category,day,takenAt,valueText,unit,source) VALUES " +
                "('qualitative','my-whoop','custom_screen','other','2026-11-12',1794480124,'negative','qualitative','manual')")
        }.close()
        val migrated = Room.databaseBuilder(instrumentation.targetContext, WhoopDatabase::class.java, oldName)
            .addMigrations(*WhoopDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            migrated.openHelper.writableDatabase.query(
                "SELECT id,value,valueText,unit,source,note,displayName,sampleCondition,hoursSinceMeal," +
                    "evidenceProfileId,evidenceVersion FROM labMarker ORDER BY id",
            ).use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("numeric", cursor.getString(0))
                assertEquals(36.0, cursor.getDouble(1), 0.0)
                assertEquals("in", cursor.getString(3))
                assertEquals("csv", cursor.getString(4))
                assertEquals("source note", cursor.getString(5))
                for (column in 6..10) assertTrue(cursor.isNull(column))
                assertTrue(cursor.moveToNext())
                assertEquals("qualitative", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals("negative", cursor.getString(2))
                assertEquals("qualitative", cursor.getString(3))
                assertEquals("manual", cursor.getString(4))
                for (column in 6..10) assertTrue(cursor.isNull(column))
            }
        } finally { migrated.close() }
    }

    /** A version bump is not complete until its exact Room export is preserved for the next upgrade. */
    @Test
    fun currentGeneratedSchemaIsCommitted() {
        val asset = "${WhoopDatabase::class.java.canonicalName}/${WhoopDatabase.SCHEMA_VERSION}.json"
        val committed = checkNotNull(javaClass.classLoader?.getResourceAsStream("roomSchemas/$asset")) {
            "committed Room schema missing: roomSchemas/$asset"
        }.use { it.readBytes() }
        val generatedRoot = checkNotNull(System.getProperty("room.schemaLocation")) {
            "room.schemaLocation is not configured by app/build.gradle.kts"
        }
        val generated = Files.readAllBytes(Path.of(generatedRoot).resolve(asset))

        assertArrayEquals(
            "The committed v${WhoopDatabase.SCHEMA_VERSION} schema must exactly match Room's export",
            generated,
            committed,
        )
    }

    /** Robolectric's AssetManager mounts archives, while Gradle keeps these fixtures as JVM resources. */
    private fun createSchemaArchive(): Path =
        Files.createTempFile("noop-room-schemas-", ".zip").also { archive ->
            // AssetManager owns the mounted handle beyond JUnit's @After on Windows. Defer cleanup
            // until JVM shutdown; deleting here masks the outcome of the actual migration checks.
            archive.toFile().deleteOnExit()
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                for (version in (previousVersion - 1)..WhoopDatabase.SCHEMA_VERSION) {
                    val asset = "${WhoopDatabase::class.java.canonicalName}/$version.json"
                    val resource = "roomSchemas/$asset"
                    val input = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
                        "committed Room schema missing: $resource"
                    }
                    input.use {
                        // addAssetPath treats the archive like an APK, whose visible asset root is
                        // the `assets/` directory rather than the archive root.
                        zip.putNextEntry(ZipEntry("assets/$asset"))
                        it.copyTo(zip)
                        zip.closeEntry()
                    }
                }
            }
        }
}
