package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NutritionTotalsTest {
    @Test
    fun totalsTreatMissingMacrosAsZeroWithoutChangingCalories() {
        val entries = listOf(
            FoodIntakeEntry(
                id = "gado", localDay = "2026-11-04", consumedAt = 1,
                foodName = "Gado-gado", servingDescription = "1 portion", servingGrams = 300.0,
                caloriesKcal = 410.0, proteinGrams = 18.0, carbohydrateGrams = 63.0,
                fatGrams = 10.0, fiberGrams = 3.0, confidence = "medium",
                assumptions = null, referenceSummary = null,
            ),
            FoodIntakeEntry(
                id = "coffee", localDay = "2026-11-04", consumedAt = 2,
                foodName = "Coffee", servingDescription = null, servingGrams = null,
                caloriesKcal = 80.0, proteinGrams = null, carbohydrateGrams = 12.0,
                fatGrams = null, fiberGrams = null, confidence = "high",
                assumptions = null, referenceSummary = null,
            ),
        )
        val totals = NutritionTotals.from(entries)
        assertEquals(490.0, totals.caloriesKcal, 0.001)
        assertEquals(18.0, totals.proteinGrams, 0.001)
        assertEquals(75.0, totals.carbohydrateGrams, 0.001)
        assertEquals(10.0, totals.fatGrams, 0.001)
        assertEquals(3.0, totals.fiberGrams, 0.001)
        assertEquals(2, totals.entryCount)
    }
}
