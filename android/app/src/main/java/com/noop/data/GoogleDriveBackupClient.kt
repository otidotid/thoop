package com.noop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Drive REST operations limited to THOOP's private appDataFolder. Access tokens are never stored. */
internal class GoogleDriveBackupClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    data class RemoteBackup(val id: String, val name: String, val createdTime: String, val sizeBytes: Long)

    suspend fun upload(accessToken: String, backup: File, ownerId: String): RemoteBackup =
        withContext(Dispatchers.IO) {
            require(accessToken.isNotBlank()) { "Google Drive access token is empty." }
            require(ownerId.isNotBlank()) { "THOOP profile owner is empty." }
            require(backup.isFile && backup.length() > 0L) { "THOOP backup file is empty." }

            val createdAt = System.currentTimeMillis()
            val metadata = JSONObject()
                .put("name", "thoop-backup-$createdAt.noopbak")
                .put("parents", JSONArray().put(APP_DATA_FOLDER))
                .put("appProperties", JSONObject()
                    .put("kind", BACKUP_KIND)
                    .put("ownerId", ownerId)
                    .put("createdAtMs", createdAt.toString()))

            val start = Request.Builder()
                .url(UPLOAD_ENDPOINT)
                .header("Authorization", "Bearer $accessToken")
                .header("X-Upload-Content-Type", BACKUP_MIME)
                .header("X-Upload-Content-Length", backup.length().toString())
                .post(metadata.toString().toRequestBody(JSON_MEDIA))
                .build()
            val uploadUrl = http.newCall(start).execute().use { response ->
                if (!response.isSuccessful) throw driveError("start upload", response.code, response.body?.string())
                response.header("Location") ?: throw IOException("Google Drive returned no upload location.")
            }
            val put = Request.Builder().url(uploadUrl)
                .header("Authorization", "Bearer $accessToken")
                .put(backup.asRequestBody(BACKUP_MEDIA)).build()
            val uploaded = http.newCall(put).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw driveError("upload backup", response.code, body)
                parseBackup(JSONObject(body))
            }
            prune(accessToken, ownerId)
            uploaded
        }

    suspend fun list(accessToken: String, ownerId: String): List<RemoteBackup> =
        withContext(Dispatchers.IO) {
            val query = "'$APP_DATA_FOLDER' in parents and trashed = false " +
                "and appProperties has { key='kind' and value='$BACKUP_KIND' } " +
                "and appProperties has { key='ownerId' and value='$ownerId' }"
            val url = HttpUrl.Builder().scheme("https").host("www.googleapis.com")
                .addPathSegments("drive/v3/files")
                .addQueryParameter("spaces", APP_DATA_FOLDER)
                .addQueryParameter("q", query)
                .addQueryParameter("orderBy", "createdTime desc")
                .addQueryParameter("pageSize", "10")
                .addQueryParameter("fields", "files(id,name,createdTime,size)").build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw driveError("list backups", response.code, body)
                val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
                buildList { for (index in 0 until files.length()) add(parseBackup(files.getJSONObject(index))) }
            }
        }

    suspend fun download(accessToken: String, backupId: String, destination: File) =
        withContext(Dispatchers.IO) {
            destination.parentFile?.mkdirs()
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$backupId?alt=media")
                .header("Authorization", "Bearer $accessToken").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw driveError("download backup", response.code, response.body?.string())
                val body = response.body ?: throw IOException("Google Drive returned an empty backup.")
                destination.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
        }

    private suspend fun prune(accessToken: String, ownerId: String) {
        list(accessToken, ownerId).drop(MAX_BACKUPS).forEach { old ->
            val request = Request.Builder().url("https://www.googleapis.com/drive/v3/files/${old.id}")
                .header("Authorization", "Bearer $accessToken").delete().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw driveError("remove old backup", response.code, response.body?.string())
            }
        }
    }

    private fun parseBackup(json: JSONObject) = RemoteBackup(
        json.getString("id"), json.optString("name", "THOOP backup"),
        json.optString("createdTime"), json.optString("size").toLongOrNull() ?: 0L,
    )

    private fun driveError(action: String, code: Int, body: String?) =
        IOException("Google Drive could not $action (HTTP $code). ${body.orEmpty().take(240)}".trim())

    companion object {
        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val BACKUP_KIND = "thoop_full_backup_v1"
        private const val BACKUP_MIME = "application/vnd.otidotid.thoop-backup"
        private const val MAX_BACKUPS = 3
        private const val UPLOAD_ENDPOINT =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,createdTime,size"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val BACKUP_MEDIA = BACKUP_MIME.toMediaType()
    }
}
