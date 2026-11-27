package com.pickupcode.app.geocoder

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object GeocoderVerifier {

    private const val TAG = "GeocoderVerifier"
    private const val AMAP_URL = "https://restapi.amap.com/v3/geocode/geo"
    private const val TIMEOUT_CONNECT_MS = 5000
    private const val TIMEOUT_READ_MS = 5000
    private const val CONFIDENCE_HIGH = 0.95f
    private const val CONFIDENCE_MEDIUM = 0.85f
    private const val CONFIDENCE_LOW = 0.6f
    private const val CONFIDENCE_MINIMAL = 0.4f

    data class GeocodedResult(
        val address: String,
        val formattedAddress: String?,
        val latitude: Double?,
        val longitude: Double?,
        val verified: Boolean,
        val confidence: Float,       // 0.0 - 1.0
        val provider: String         // "android" | "amap" | "none"
    )

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    suspend fun verify(
        context: Context,
        address: String,
        amapApiKey: String? = null,
        city: String? = null
    ): GeocodedResult {
        // Try Android Geocoder first
        val androidResult = tryAndroidGeocoder(context, address)
        if (androidResult != null && androidResult.verified) {
            return androidResult
        }

        // Fall back to AMap if key is available
        var amapResult: GeocodedResult? = null
        if (!amapApiKey.isNullOrBlank()) {
            amapResult = tryAMapGeocoder(amapApiKey, address, city)
            if (amapResult != null && amapResult.verified) {
                return amapResult
            }
        }

        // Both failed or unavailable
        return GeocodedResult(
            address = address,
            formattedAddress = androidResult?.formattedAddress ?: amapResult?.formattedAddress,
            latitude = null,
            longitude = null,
            verified = false,
            confidence = 0f,
            provider = "none"
        )
    }

    // ---------------------------------------------------------------
    // Android built-in Geocoder
    // ---------------------------------------------------------------

    private suspend fun tryAndroidGeocoder(
        context: Context,
        address: String
    ): GeocodedResult? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) {
            Log.d(TAG, "Android Geocoder not available on this device")
            return@withContext null
        }

        try {
            val geocoder = Geocoder(context)
            val results: List<Address> = geocoder.getFromLocationName(address, 3)
                ?: return@withContext null

            if (results.isEmpty()) {
                Log.d(TAG, "Android Geocoder: no results for address")
                return@withContext null
            }

            val best = results[0]
            val hasLatLon = best.hasLatitude() && best.hasLongitude()
            val confidence = when {
                hasLatLon && results.size == 1 -> 0.9f
                hasLatLon -> 0.7f
                else -> CONFIDENCE_MINIMAL
            }

            val formatted = buildString {
                for (i in 0..best.maxAddressLineIndex.coerceAtMost(2)) {
                    if (i > 0) append(", ")
                    append(best.getAddressLine(i))
                }
            }.takeIf { it.isNotBlank() }

            GeocodedResult(
                address = address,
                formattedAddress = formatted ?: best.featureName ?: best.getAddressLine(0),
                latitude = if (hasLatLon) best.latitude else null,
                longitude = if (hasLatLon) best.longitude else null,
                verified = hasLatLon,
                confidence = confidence,
                provider = "android"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Android Geocoder error", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Android Geocoder unexpected", e)
            null
        }
    }

    // ---------------------------------------------------------------
    // AMap (Gaode) REST API
    // ---------------------------------------------------------------

    private suspend fun tryAMapGeocoder(
        apiKey: String,
        address: String,
        city: String?
    ): GeocodedResult? = withContext(Dispatchers.IO) {
        try {
            val params = buildString {
                append("key=").append(apiKey)
                append("&address=").append(java.net.URLEncoder.encode(address, "UTF-8"))
                if (!city.isNullOrBlank()) {
                    append("&city=").append(java.net.URLEncoder.encode(city, "UTF-8"))
                }
            }
            val url = URL("$AMAP_URL?$params")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_CONNECT_MS
                conn.readTimeout = TIMEOUT_READ_MS
                conn.requestMethod = "GET"

                val code = conn.responseCode
                if (code != 200) {
                    Log.w(TAG, "AMap API returned HTTP $code")
                    return@withContext null
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)

                if (json.optInt("status") != 1) {
                    Log.w(TAG, "AMap API status != 1: ${json.optString("info")}")
                    return@withContext null
                }

                val geocodes = json.optJSONArray("geocodes")
                if (geocodes == null || geocodes.length() == 0) {
                    Log.d(TAG, "AMap: no geocode results")
                    return@withContext null
                }

                val best = geocodes.getJSONObject(0)
                val location = best.optString("location", "")
                val parts = location.split(",")
                // 高德 location 格式为 "经度,纬度"（如 116.397428,39.90923）—— parts[0]=经度(longitude), parts[1]=纬度(latitude)
                // M8: 不能只判长度，必须数值可解析且在合法范围，否则坐标非法仍算 verified
                val lon = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
                val lat = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                val hasLatLon = lat != null && lon != null &&
                    lat in -90.0..90.0 && lon in -180.0..180.0

                val amapLevel = best.optString("level", "")
                val confidence = when {
                    amapLevel.contains("门牌号") || amapLevel.contains("兴趣点") -> CONFIDENCE_HIGH
                    amapLevel.contains("道路") || amapLevel.contains("村庄") -> CONFIDENCE_MEDIUM
                    amapLevel.contains("区县") || amapLevel.contains("乡镇") -> CONFIDENCE_LOW
                    amapLevel.contains("城市") -> CONFIDENCE_MINIMAL
                    hasLatLon -> 0.7f
                    else -> 0f
                }

                // B5: optString(key, null) 在 key 缺失时返回字面量 "null"，须用 isNull 判缺失
                fun optNullable(key: String): String? =
                    if (best.isNull(key)) null else best.optString(key)
                GeocodedResult(
                    address = address,
                    formattedAddress = optNullable("formatted_address") ?: optNullable("name"),
                    latitude = lat,
                    longitude = lon,
                    verified = hasLatLon,
                    confidence = confidence,
                    provider = "amap"
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AMap geocoder error", e)
            null
        }
    }
}