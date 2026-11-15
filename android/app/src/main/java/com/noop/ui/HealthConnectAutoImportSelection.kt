package com.noop.ui

/** Main-thread selection tickets keep late permission checks from reviving a superseded schedule. */
internal class HealthConnectAutoImportSelection {
    class Generation internal constructor()
    data class Request internal constructor(val generation: Generation, val hours: Int)

    private var current = Generation()
    private var permissionRequest: Request? = null

    fun snapshot(): Generation = current

    fun select(hours: Int): Request {
        require(hours >= 0)
        invalidate()
        return Request(current, hours)
    }

    /** Category changes also supersede pending checks, without changing the stored interval. */
    fun invalidate() {
        current = Generation()
    }

    fun applyIfCurrent(generation: Generation, action: () -> Unit): Boolean {
        if (generation !== current) return false
        action()
        return true
    }

    fun beginPermissionRequest(request: Request): Boolean {
        if (request.generation !== current || permissionRequest != null) return false
        permissionRequest = request
        return true
    }

    /** One launcher result belongs to its original request, never to a newer pending choice. */
    fun finishPermissionRequest(): Request? {
        val completed = permissionRequest
        permissionRequest = null
        return completed?.takeIf { it.generation === current }
    }

    fun dispose() {
        invalidate()
        permissionRequest = null
    }
}
