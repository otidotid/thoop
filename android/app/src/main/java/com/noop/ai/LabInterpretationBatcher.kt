package com.noop.ai

import com.noop.data.LabMarkerRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.LocalDate

/** Bounded requests, without a total observation/history cap or silent record omission. */
object LabInterpretationBatcher {
    data class Plan(
        val snapshotDay: String,
        val originals: List<LabMarkerRow>,
        val requests: List<LabInterpretation.Request>,
        val localFindings: List<LabInterpretation.Finding>,
    )

    fun plan(rows: List<LabMarkerRow>, snapshotDay: String): Plan {
        val originals = rows.sortedWith(compareBy<LabMarkerRow> { it.id }
            .thenBy { it.deviceId }.thenBy { it.day }.thenBy { it.takenAt }).toList()
        if (runCatching { LocalDate.parse(snapshotDay) }.isFailure) return Plan(
            snapshotDay, originals, emptyList(),
            LabInterpretation.unavailableRows(originals, snapshotDay, LabInterpretation.Failure.INVALID_REQUEST).findings,
        )
        val ambiguous = originals.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        val requests = ArrayList<LabInterpretation.Request>()
        val local = ArrayList<LabInterpretation.Finding>()
        var pending = ArrayList<LabMarkerRow>()
        fun failed(row: LabMarkerRow, reason: LabInterpretation.Failure) {
            local += LabInterpretation.unavailableRows(listOf(row), snapshotDay, reason).findings
        }
        fun flush() {
            if (pending.isNotEmpty()) {
                // A pending batch is added only after prepare accepted exactly these originals.
                requests += checkNotNull(LabInterpretation.prepare(pending, snapshotDay, true))
                pending = ArrayList()
            }
        }
        for (row in originals) {
            if (row.id.isBlank() || row.id in ambiguous) {
                failed(row, LabInterpretation.Failure.AMBIGUOUS_SOURCE)
                continue
            }
            if (LabInterpretation.prepare(listOf(row), snapshotDay, true) == null) {
                failed(row, LabInterpretation.Failure.OVERSIZED)
                continue
            }
            if (pending.size == LabInterpretation.MAX_ROWS ||
                LabInterpretation.prepare(pending + row, snapshotDay, true) == null) flush()
            pending += row
        }
        flush()
        return Plan(snapshotDay, originals, requests, local)
    }

    /**
     * Consent is read immediately before every network call and after it. Source snapshots are
     * revalidated after each response and after the final batch. Cancellation is never an AI failure.
     */
    suspend fun run(
        plan: Plan,
        consent: () -> Boolean,
        currentRows: suspend () -> List<LabMarkerRow>,
        transport: suspend (LabInterpretation.Request) -> String?,
    ): LabInterpretation.Result {
        fun unavailable(reason: LabInterpretation.Failure) =
            LabInterpretation.unavailableRows(plan.originals, plan.snapshotDay, reason)
        currentCoroutineContext().ensureActive()
        if (!consent()) return unavailable(LabInterpretation.Failure.CONSENT_REVOKED)
        val byId = HashMap<String, LabInterpretation.Finding>()
        plan.localFindings.forEach { byId[it.rowId] = it }
        for (request in plan.requests) {
            currentCoroutineContext().ensureActive()
            if (!consent()) return unavailable(LabInterpretation.Failure.CONSENT_REVOKED)
            val wire = try { transport(request) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            if (!consent()) return unavailable(LabInterpretation.Failure.CONSENT_REVOKED)
            val fresh = try { currentRows() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { return unavailable(LabInterpretation.Failure.UNAVAILABLE) }
            currentCoroutineContext().ensureActive()
            if (!consent()) return unavailable(LabInterpretation.Failure.CONSENT_REVOKED)
            LabInterpretation.validate(wire, request, fresh).findings.forEach { byId[it.rowId] = it }
        }
        val finalRows = try { currentRows() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return unavailable(LabInterpretation.Failure.UNAVAILABLE) }
        currentCoroutineContext().ensureActive()
        if (!consent()) return unavailable(LabInterpretation.Failure.CONSENT_REVOKED)
        val current = finalRows.groupBy { it.id }
        val originalCounts = plan.originals.groupingBy { it.id }.eachCount()
        return LabInterpretation.Result(findings = plan.originals.map { row ->
            fun missing(reason: LabInterpretation.Failure) =
                LabInterpretation.unavailableRows(listOf(row), plan.snapshotDay, reason).findings.single()
            val candidates = current[row.id].orEmpty()
            when {
                originalCounts[row.id] != 1 || row.id.isBlank() -> missing(LabInterpretation.Failure.AMBIGUOUS_SOURCE)
                candidates.size != 1 || LabInterpretation.fingerprint(candidates.single()) != LabInterpretation.fingerprint(row) ->
                    missing(LabInterpretation.Failure.SOURCE_CHANGED)
                else -> byId[row.id] ?: missing(LabInterpretation.Failure.MISSING)
            }
        })
    }
}
