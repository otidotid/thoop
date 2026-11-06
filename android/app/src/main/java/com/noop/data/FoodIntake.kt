package com.noop.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate
import java.util.UUID

/** Confirmed nutrition only. Coach drafts remain outside this store until the user saves them. */
@Entity(
    tableName = "foodIntake",
    indices = [Index(value = ["localDay", "consumedAt"]), Index(value = ["source"])],
)
data class FoodIntakeEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val localDay: String,
    val consumedAt: Long,
    val foodName: String,
    val mealType: String = "other",
    val servingDescription: String?,
    val servingGrams: Double?,
    val caloriesKcal: Double,
    val proteinGrams: Double?,
    val carbohydrateGrams: Double?,
    val fatGrams: Double?,
    val fiberGrams: Double?,
    val source: String = "coach-ai-text",
    val confidence: String?,
    val assumptions: String?,
    val referenceSummary: String?,
    val updatedAt: Long = System.currentTimeMillis() / 1000L,
)

@Dao
internal interface FoodIntakeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FoodIntakeEntry)

    @Query("SELECT * FROM foodIntake WHERE localDay = :day ORDER BY consumedAt DESC")
    suspend fun entriesForDay(day: String): List<FoodIntakeEntry>

    @Query("SELECT * FROM foodIntake ORDER BY localDay DESC, consumedAt DESC")
    suspend fun allEntries(): List<FoodIntakeEntry>

    @Query("DELETE FROM foodIntake WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [FoodIntakeEntry::class], version = 2, exportSchema = false)
internal abstract class FoodIntakeDatabase : RoomDatabase() {
    abstract fun dao(): FoodIntakeDao

    companion object {
        @Volatile private var instance: FoodIntakeDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE foodIntake ADD COLUMN mealType TEXT NOT NULL DEFAULT 'other'")
            }
        }
        fun get(context: Context): FoodIntakeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FoodIntakeDatabase::class.java,
                    "thoop_food_intake.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}

class FoodIntakeRepository private constructor(private val dao: FoodIntakeDao) {
    suspend fun save(entry: FoodIntakeEntry) {
        require(FoodLogEditWindow.isEditable(entry.localDay)) { FoodLogEditWindow.LOCKED_MESSAGE }
        dao.upsert(entry.copy(mealType = normalizeMealType(entry.mealType)))
    }
    suspend fun entriesForDay(day: String) = dao.entriesForDay(day)
    suspend fun allEntries() = dao.allEntries()
    suspend fun delete(id: String, localDay: String? = null) {
        if (localDay != null) require(FoodLogEditWindow.isEditable(localDay)) { FoodLogEditWindow.LOCKED_MESSAGE }
        dao.delete(id)
    }

    private fun normalizeMealType(value: String): String {
        val normalized = value.trim().lowercase().replace('-', '_').replace(' ', '_')
        return normalized.takeIf { it in MEAL_TYPES } ?: "other"
    }

    companion object {
        @Volatile private var instance: FoodIntakeRepository? = null
        fun get(context: Context): FoodIntakeRepository =
            instance ?: synchronized(this) {
                instance ?: FoodIntakeRepository(FoodIntakeDatabase.get(context).dao())
                    .also { instance = it }
            }
    }
}

object FoodLogEditWindow {
    const val LOCKED_MESSAGE = "Food Log can only be changed for today and the previous 6 days."

    fun isEditable(day: String, today: LocalDate = LocalDate.now()): Boolean {
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return false
        return !date.isBefore(today.minusDays(6)) && !date.isAfter(today)
    }
}

private val MEAL_TYPES = setOf(
    "breakfast", "brunch", "lunch", "snack", "afternoon_snack",
    "dinner", "evening_snack", "late_night_snack", "other",
)

data class NutritionTotals(
    val caloriesKcal: Double = 0.0,
    val proteinGrams: Double = 0.0,
    val carbohydrateGrams: Double = 0.0,
    val fatGrams: Double = 0.0,
    val fiberGrams: Double = 0.0,
    val entryCount: Int = 0,
) {
    companion object {
        fun from(entries: List<FoodIntakeEntry>) = NutritionTotals(
            caloriesKcal = entries.sumOf { it.caloriesKcal.coerceAtLeast(0.0) },
            proteinGrams = entries.sumOf { it.proteinGrams?.coerceAtLeast(0.0) ?: 0.0 },
            carbohydrateGrams = entries.sumOf { it.carbohydrateGrams?.coerceAtLeast(0.0) ?: 0.0 },
            fatGrams = entries.sumOf { it.fatGrams?.coerceAtLeast(0.0) ?: 0.0 },
            fiberGrams = entries.sumOf { it.fiberGrams?.coerceAtLeast(0.0) ?: 0.0 },
            entryCount = entries.size,
        )
    }
}

enum class NutritionMetric(val label: String, val unit: String) {
    CALORIES("Calories Consumed", "kcal"), PROTEIN("Protein", "g"),
    CARBOHYDRATES("Carbohydrates", "g"), FAT("Fat", "g"), FIBER("Fiber", "g");

    fun value(totals: NutritionTotals) = when (this) {
        CALORIES -> totals.caloriesKcal
        PROTEIN -> totals.proteinGrams
        CARBOHYDRATES -> totals.carbohydrateGrams
        FAT -> totals.fatGrams
        FIBER -> totals.fiberGrams
    }
    fun value(entry: FoodIntakeEntry) = when (this) {
        CALORIES -> entry.caloriesKcal
        PROTEIN -> entry.proteinGrams ?: 0.0
        CARBOHYDRATES -> entry.carbohydrateGrams ?: 0.0
        FAT -> entry.fatGrams ?: 0.0
        FIBER -> entry.fiberGrams ?: 0.0
    }
}
