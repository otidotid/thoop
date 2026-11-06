package com.noop.ui
import org.junit.Assert.assertTrue
import org.junit.Test
class WorkoutMetricResolverTest {
 @Test fun missingMetricsAlwaysResolve() {
  assertTrue(WorkoutMetricResolver.estimateAverageHr("Treadmill Run",60,190) > 60)
  assertTrue(WorkoutMetricResolver.estimateCalories("Treadmill Run",3600,70.0) > 0.0)
 }
}
