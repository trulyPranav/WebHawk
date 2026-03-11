package com.webhawk.detector.engine

import android.util.Log
import com.webhawk.detector.data.model.RedirectChain
import com.webhawk.detector.data.model.UrlEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * RedirectResolver performs a lightweight network crawl starting from a single URL,
 * following HTTP 3xx redirects (up to [maxRedirects]) and building a [RedirectChain].
 *
 * This replaces the need for a real browser + AccessibilityService when the user
 * simply wants to type a URL and have it analyzed by the RiskEngine.
 */
class RedirectResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)        // we want to see each 3xx hop ourselves
        .followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "WebHawk.Resolver"
    }

    suspend fun resolveChain(
        initialUrl: String,
        maxRedirects: Int = 8
    ): RedirectChain = withContext(Dispatchers.IO) {
        val entries = mutableListOf<UrlEntry>()
        var currentUrl = initialUrl
        var startTime = System.currentTimeMillis()
        entries.add(UrlEntry(url = currentUrl, timestamp = startTime))

        Log.d(TAG, "Resolving redirects for: $initialUrl (max=$maxRedirects)")

        repeat(maxRedirects) { index ->
            val request = Request.Builder()
                .url(currentUrl)
                .get()
                .build()

            val beforeCall = System.currentTimeMillis()
            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                Log.w(TAG, "Network error on hop #$index for $currentUrl — ${e.message}")
                return@withContext buildChain(entries)
            }

            response.use { resp ->
                val code = resp.code
                val location = resp.header("Location")

                if (code in 300..399 && !location.isNullOrBlank()) {
                    val nextUrl = try {
                        // Resolve relative Location headers against the current URL
                        URL(URL(currentUrl), location).toString()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve Location='$location' against '$currentUrl' — ${e.message}")
                        location
                    }

                    val ts = System.currentTimeMillis()
                    entries.add(UrlEntry(url = nextUrl, timestamp = ts))

                    Log.i(TAG, "  redirect #${index + 1}: $currentUrl -> $nextUrl  (code=$code, dt=${ts - beforeCall}ms)")

                    currentUrl = nextUrl
                } else {
                    // Not a redirect — final landing page reached.
                    Log.d(TAG, "  final response: code=$code, url=$currentUrl")
                    return@withContext buildChain(entries)
                }
            }
        }

        Log.w(TAG, "Reached max redirects ($maxRedirects) for $currentUrl")
        buildChain(entries)
    }

    private fun buildChain(entries: List<UrlEntry>): RedirectChain {
        val snapshot = if (entries.isEmpty()) {
            val now = System.currentTimeMillis()
            listOf(UrlEntry(url = "", timestamp = now))
        } else {
            entries
        }
        val start = snapshot.first()
        val end = snapshot.last()
        val duration = end.timestamp - start.timestamp

        return RedirectChain(
            entries = snapshot,
            startUrl = start.url,
            finalUrl = end.url,
            durationMs = duration
        )
    }
}
