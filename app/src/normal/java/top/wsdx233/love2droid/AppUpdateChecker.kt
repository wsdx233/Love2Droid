package top.wsdx233.love2droid

import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal sealed interface AppUpdateResult {
    data class Available(val tag: String, val releaseUrl: String) : AppUpdateResult
    data object UpToDate : AppUpdateResult
    data object NoRelease : AppUpdateResult
    data object Failed : AppUpdateResult
}

internal object AppUpdateChecker {
    private const val REPOSITORY = "https://github.com/wsdx233/Love2Droid"
    private const val LATEST_RELEASE = "https://api.github.com/repos/wsdx233/Love2Droid/releases/latest"
    private const val MAX_RESPONSE_CHARS = 1024 * 1024

    /** Blocking network call; callers must use Dispatchers.IO. */
    fun check(currentVersion: String): AppUpdateResult {
        val connection = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Love2Droid/$currentVersion")
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> AppUpdateResult.NoRelease
                HttpURLConnection.HTTP_OK -> {
                    val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val text = StringBuilder()
                        val buffer = CharArray(8192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            if (text.length + count > MAX_RESPONSE_CHARS) {
                                throw IOException("GitHub release response exceeds limit")
                            }
                            text.append(buffer, 0, count)
                        }
                        text.toString()
                    }
                    parseRelease(response, currentVersion)
                }
                else -> throw IOException("GitHub HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun parseRelease(response: String, currentVersion: String): AppUpdateResult {
        val release = JSONObject(response)
        if (release.getBoolean("draft") || release.getBoolean("prerelease")) return AppUpdateResult.NoRelease
        val tag = release.getString("tag_name")
        val latest = ReleaseVersion.parse(tag)
        val current = ReleaseVersion.parse(currentVersion)
        if (latest <= current) return AppUpdateResult.UpToDate
        // Construct the link on the trusted repository, rather than opening a URL from the response.
        val url = URI("$REPOSITORY/releases/tag/" + tag).toASCIIString()
        return AppUpdateResult.Available(tag, url)
    }
}

private class ReleaseVersion(
    private val numbers: List<BigInteger>,
    private val prerelease: List<String>,
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
            val comparison = (numbers.getOrNull(index) ?: BigInteger.ZERO)
                .compareTo(other.numbers.getOrNull(index) ?: BigInteger.ZERO)
            if (comparison != 0) return comparison
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumber = left.takeIf { it.all { char -> char in '0'..'9' } }?.toBigInteger()
            val rightNumber = right.takeIf { it.all { char -> char in '0'..'9' } }?.toBigInteger()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val PATTERN = Regex(
            "[vV]?([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?",
        )

        fun parse(value: String): ReleaseVersion {
            val match = PATTERN.matchEntire(value) ?: throw IOException("Unsupported version: $value")
            return ReleaseVersion(
                match.groupValues[1].split('.').map { it.toBigInteger() },
                match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList(),
            )
        }
    }
}
