package top.wsdx233.love2droid

import org.json.JSONArray
import org.json.JSONObject

enum class GameScreenOrientation(val manifestValue: String) {
    LANDSCAPE("landscape"),
    PORTRAIT("portrait"),
    SENSOR_LANDSCAPE("sensorLandscape"),
    SENSOR_PORTRAIT("sensorPortrait"),
    UNSPECIFIED("unspecified");

    companion object {
        fun fromManifestValue(value: String?): GameScreenOrientation =
            entries.firstOrNull { it.manifestValue == value } ?: LANDSCAPE
    }
}

data class AndroidProjectProperties(
    val appName: String,
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val orientation: GameScreenOrientation,
    val permissions: Set<String>,
    val signingKeyId: String,
) {
    fun validated(): AndroidProjectProperties {
        val cleanName = appName.trim()
        val cleanApplicationId = applicationId.trim()
        val cleanVersionName = versionName.trim()
        require(cleanName.isNotEmpty()) { "Application name is required" }
        require(APPLICATION_ID.matches(cleanApplicationId)) { "Invalid Android application ID" }
        require(cleanVersionName.isNotEmpty() && cleanVersionName.length <= MAX_VERSION_NAME_CHARS) {
            "Invalid Android version name"
        }
        require(versionCode > 0) { "Android version code must be positive" }
        val cleanPermissions = permissions.map(String::trim).filter(String::isNotEmpty).toSortedSet()
        require(cleanPermissions.size <= MAX_PERMISSIONS) { "Too many Android permissions" }
        require(cleanPermissions.all(PERMISSION_NAME::matches)) { "Invalid Android permission name" }
        require(SIGNING_KEY_ID.matches(signingKeyId)) { "Invalid signing key ID" }
        return copy(
            appName = cleanName,
            applicationId = cleanApplicationId,
            versionName = cleanVersionName,
            permissions = cleanPermissions,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("appName", appName)
        .put("applicationId", applicationId)
        .put("versionName", versionName)
        .put("versionCode", versionCode)
        .put("orientation", orientation.manifestValue)
        .put("permissions", JSONArray(permissions.sorted()))
        .put("signingKeyId", signingKeyId)

    companion object {
        val RECOMMENDED_PERMISSIONS: Set<String> = setOf(
            "android.permission.INTERNET",
            "android.permission.VIBRATE",
            "android.permission.BLUETOOTH",
        )
        const val MAX_PERMISSIONS = 32
        val COMMON_PERMISSIONS: List<String> = listOf(
            "android.permission.INTERNET",
            "android.permission.VIBRATE",
            "android.permission.BLUETOOTH",
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WAKE_LOCK",
        )

        fun defaults(projectId: String, displayName: String): AndroidProjectProperties {
            val packageSegment = projectId.lowercase()
                .replace(Regex("[^a-z0-9_]+"), "_")
                .trim('_')
                .let { value ->
                    when {
                        value.isBlank() -> "game"
                        value.first().isDigit() -> "game_$value"
                        else -> value
                    }
                }
            return AndroidProjectProperties(
                appName = displayName,
                applicationId = "com.love2d.$packageSegment",
                versionName = "1.0.0",
                versionCode = 1,
                orientation = GameScreenOrientation.LANDSCAPE,
                permissions = RECOMMENDED_PERMISSIONS,
                signingKeyId = projectId,
            )
        }

        fun fromJson(value: JSONObject?, projectId: String, displayName: String): AndroidProjectProperties {
            val defaults = defaults(projectId, displayName)
            if (value == null) return defaults

            val permissionArray = value.optJSONArray("permissions")
            val permissions = if (permissionArray == null) {
                defaults.permissions
            } else {
                buildSet {
                    for (index in 0 until permissionArray.length()) {
                        permissionArray.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
            return AndroidProjectProperties(
                appName = value.optString("appName", defaults.appName),
                applicationId = value.optString("applicationId", defaults.applicationId),
                versionName = value.optString("versionName", defaults.versionName),
                versionCode = value.optInt("versionCode", defaults.versionCode),
                orientation = GameScreenOrientation.fromManifestValue(value.optString("orientation")),
                permissions = permissions,
                signingKeyId = value.optString("signingKeyId", defaults.signingKeyId),
            ).let { candidate -> runCatching { candidate.validated() }.getOrDefault(defaults) }
        }
        fun isValidApplicationId(value: String): Boolean = APPLICATION_ID.matches(value.trim())

        fun isValidPermissionName(value: String): Boolean = PERMISSION_NAME.matches(value.trim())

        val PERMISSION_NAME = Regex("(?:[A-Za-z_][A-Za-z0-9_]*\\.)+[A-Za-z_][A-Za-z0-9_]*")
        private val APPLICATION_ID = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val SIGNING_KEY_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private const val MAX_VERSION_NAME_CHARS = 100
    }
}
