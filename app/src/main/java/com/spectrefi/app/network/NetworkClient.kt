package com.spectrefi.app.network

import android.content.Context
import com.spectrefi.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class NetworkClient(private val context: Context) {

    companion object {

        @Volatile
        private var INSTANCE: NetworkClient? = null

        fun getInstance(context: Context): NetworkClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkClient(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val okHttpClient by lazy(LazyThreadSafetyMode.NONE) {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        if (!NetworkUtils.hasActiveConnection(context)) {
            throw NetworkException("No active network connection")
        }

        val urlsToTry = buildList {
            add(url)
            githubRawFallback(url)?.let { add(it) }
        }

        var lastException: Exception? = null

        for ((index, attemptUrl) in urlsToTry.withIndex()) {
            try {
                val request = Request.Builder()
                    .url(attemptUrl)
                    .addHeader("User-Agent", "SpectreFi/${BuildConfig.VERSION_NAME}")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }

                return@withContext response.body?.string() ?: throw Exception("Empty response")

            } catch (e: Exception) {
                lastException = e
                if (index < urlsToTry.size - 1) {
                    delay(1000)
                }
            }
        }

        throw NetworkException("Network request failed", lastException)
    }

    /**
     * Fallback for GitHub's "raw" view, which is served as an HTML page with a redirect:
     * https://github.com/<owner>/<repo>/raw/<ref>/<path> -> https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>
     * Only https URLs are rewritten; the original URL is always tried first.
     */
    private fun githubRawFallback(url: String): String? {
        val match = Regex("^https://github\\.com/([^/]+)/([^/]+)/raw/(.+)$").find(url) ?: return null
        val (owner, repo, rest) = match.destructured
        return "https://raw.githubusercontent.com/$owner/$repo/$rest"
    }

    suspend fun postMultipart(
        url: String,
        parts: List<Pair<String, Any>>,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        if (!NetworkUtils.hasActiveConnection(context)) {
            throw NetworkException("No active network connection")
        }

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)

        for ((name, value) in parts) {
            when (value) {
                is File -> {
                    val mediaType = "application/octet-stream".toMediaTypeOrNull()
                    multipartBuilder.addFormDataPart(
                        name,
                        value.name,
                        value.asRequestBody(mediaType)
                    )
                }

                is String -> {
                    multipartBuilder.addFormDataPart(name, value)
                }
            }
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "SpectreFi/${BuildConfig.VERSION_NAME}")
            .post(multipartBuilder.build())

        for ((key, value) in headers) {
            requestBuilder.addHeader(key, value)
        }

        val response = okHttpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            throw NetworkException("HTTP ${response.code}: ${response.body?.string() ?: ""}")
        }

        response.body?.string() ?: throw NetworkException("Empty response")
    }
}

class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)
