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

@Database(entities = [FoodIntakeEntry::class], version = 1, exportSchema = false)
internal abstract class FoodIntakeDatabase : RoomDatabase() {
    abstract fun dao(): FoodIntakeDao

    companion object {
        @Volatile private var instance: FoodIntakeDatabase? = null
        fun get(context: Context): FoodIntakeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FoodIntakeDatabase::class.java,
                    "thoop_food_intake.db",
                ).build().also { instance = it }
            }
    }
}

class FoodIntakeRepository private constructor(private val dao: FoodIntakeDao) {
    suspend fun save(entry: FoodIntakeEntry) = dao.upsert(entry)
    suspend fun entriesForDay(day: String) = dao.entriesForDay(day)
    suspend fun allEntries() = dao.allEntries()
    suspend fun delete(id: String) = dao.delete(id)

    companion object {
        @Volatile private var instance: FoodIntakeRepository? = null
        fun get(context: Context): FoodIntakeRepository =
            instance ?: synchronized(this) {
                instance ?: FoodIntakeRepository(FoodIntakeDatabase.get(context).dao())
                    .also { instance = it }
            }
    }
}

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
