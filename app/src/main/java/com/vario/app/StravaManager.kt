package com.vario.app

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

data class StravaUploadResult(
    val success: Boolean,
    val uploadId: Long = 0,
    val activityId: Long = 0,
    val error: String = "",
    val status: String = ""
)

object StravaManager {
    private const val TAG = "StravaManager"
    const val CLIENT_ID = "YOUR_STRAVA_CLIENT_ID"
    const val CLIENT_SECRET = "YOUR_STRAVA_CLIENT_SECRET"
    const val REDIRECT_URI = "varioappli://strava-auth"
    
    private const val BASE_URL = "https://www.strava.com"
    private const val OAUTH_URL = "$BASE_URL/oauth/authorize"
    private const val TOKEN_URL = "$BASE_URL/oauth/token"
    private const val UPLOAD_URL = "$BASE_URL/api/v3/uploads"

    private const val PREFS_NAME = "strava_auth"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"

    fun isAuthenticated(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACCESS_TOKEN, null) != null
    }

    fun getAuthorizationUrl(): String {
        return "$OAUTH_URL?client_id=$CLIENT_ID&response_type=code&redirect_uri=$REDIRECT_URI&approval_prompt=force&scope=activity:write,activity:read_all"
    }

    suspend fun handleAuthCallback(context: Context, uri: Uri) {
        val code = uri.getQueryParameter("code") ?: return
        
        try {
            val url = URL(TOKEN_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val params = "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&code=$code&grant_type=authorization_code"
            val outputStream = DataOutputStream(connection.outputStream)
            outputStream.writeBytes(params)
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val accessToken = json.getString("access_token")
                val refreshToken = json.getString("refresh_token")
                val expiresAt = json.getLong("expires_at")

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_ACCESS_TOKEN, accessToken)
                    .putString(KEY_REFRESH_TOKEN, refreshToken)
                    .putLong(KEY_EXPIRES_AT, expiresAt)
                    .apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auth callback failed", e)
        }
    }

    suspend fun refreshTokenIfNeeded(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)

        if (accessToken == null || refreshToken == null) return null

        val currentTime = System.currentTimeMillis() / 1000
        if (currentTime < expiresAt - 300) { // 5 minutes buffer
            return accessToken
        }

        try {
            val url = URL(TOKEN_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val params = "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&refresh_token=$refreshToken&grant_type=refresh_token"
            val outputStream = DataOutputStream(connection.outputStream)
            outputStream.writeBytes(params)
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val newAccessToken = json.getString("access_token")
                val newRefreshToken = json.getString("refresh_token")
                val newExpiresAt = json.getLong("expires_at")

                prefs.edit()
                    .putString(KEY_ACCESS_TOKEN, newAccessToken)
                    .putString(KEY_REFRESH_TOKEN, newRefreshToken)
                    .putLong(KEY_EXPIRES_AT, newExpiresAt)
                    .apply()

                return newAccessToken
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh token failed", e)
        }

        return null
    }

    suspend fun uploadGpxToStrava(
        context: Context,
        gpxFile: File,
        activityName: String,
        activityType: String,
        description: String
    ): StravaUploadResult {
        val token = refreshTokenIfNeeded(context) ?: return StravaUploadResult(false, error = "Not authenticated")

        try {
            val url = URL(UPLOAD_URL)
            val connection = url.openConnection() as HttpURLConnection
            
            val boundary = "===${System.currentTimeMillis()}==="
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val outputStream = DataOutputStream(connection.outputStream)
            
            // Text parts
            val addFormField = { name: String, value: String ->
                outputStream.writeBytes("--$boundary\r\n")
                outputStream.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                outputStream.writeBytes("$value\r\n")
            }

            addFormField("name", activityName)
            addFormField("description", description)
            addFormField("trainer", "0")
            addFormField("commute", "0")
            addFormField("data_type", "gpx")
            addFormField("external_id", gpxFile.name)
            if (activityType.isNotEmpty()) {
                addFormField("sport_type", activityType)
            }

            // File part
            outputStream.writeBytes("--$boundary\r\n")
            outputStream.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${gpxFile.name}\"\r\n")
            outputStream.writeBytes("Content-Type: application/gpx+xml\r\n\r\n")

            val fileInputStream = FileInputStream(gpxFile)
            val buffer = ByteArray(4096)
            var bytesRead = fileInputStream.read(buffer)
            while (bytesRead != -1) {
                outputStream.write(buffer, 0, bytesRead)
                bytesRead = fileInputStream.read(buffer)
            }
            fileInputStream.close()
            outputStream.writeBytes("\r\n")
            outputStream.writeBytes("--$boundary--\r\n")
            
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            
            val reader = BufferedReader(InputStreamReader(inputStream))
            val response = reader.readText()
            reader.close()

            if (responseCode in 200..299) {
                val json = JSONObject(response)
                val uploadId = if (json.has("id")) json.getLong("id") else 0L
                val status = if (json.has("status")) json.getString("status") else ""
                val error = if (json.has("error")) json.getString("error") else ""
                
                return StravaUploadResult(
                    success = error.isEmpty() || error == "null",
                    uploadId = uploadId,
                    status = status,
                    error = error
                )
            } else {
                return StravaUploadResult(false, error = "HTTP Error $responseCode: $response")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            return StravaUploadResult(false, error = e.message ?: "Unknown error")
        }
    }

    suspend fun pollUploadStatus(context: Context, uploadId: Long): StravaUploadResult {
        val token = refreshTokenIfNeeded(context) ?: return StravaUploadResult(false, error = "Not authenticated")

        try {
            val url = URL("$UPLOAD_URL/$uploadId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val status = if (json.has("status")) json.getString("status") else ""
                val error = if (json.has("error") && !json.isNull("error")) json.getString("error") else ""
                val activityId = if (json.has("activity_id") && !json.isNull("activity_id")) json.getLong("activity_id") else 0L

                return StravaUploadResult(
                    success = error.isEmpty() && activityId > 0,
                    uploadId = uploadId,
                    activityId = activityId,
                    error = error,
                    status = status
                )
            } else {
                return StravaUploadResult(false, error = "HTTP Error $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Poll status failed", e)
            return StravaUploadResult(false, error = e.message ?: "Unknown error")
        }
    }

    fun logout(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
