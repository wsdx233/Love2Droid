package top.wsdx233.love2droid

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale

internal object OmpModelRepository {
    private const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024
    private const val KNOWN_MODELS_URL =
        "https://raw.githubusercontent.com/Wei-Shaw/sub2api/main/backend/resources/model-pricing/model_prices_and_context_window.json"
    private const val FALLBACK_MODELS_URL =
        "https://raw.githubusercontent.com/router-for-me/models/main/models.json"

    data class KnownModel(
        val id: String,
        val name: String?,
        val family: String,
        val fields: LinkedHashMap<String, Any?>,
    )

    data class RemoteModel(val id: String, val name: String?)

    fun configFile(context: android.content.Context): File = ProotRuntime.ompModelsFile(context)

    fun load(context: android.content.Context): LinkedHashMap<String, Any?> {
        val file = configFile(context)
        if (!file.isFile) return defaultRoot()
        val parsed = Yaml().load<Any?>(file.readText(Charsets.UTF_8))
        return asMap(parsed) ?: throw IOException("${file.name} 的根对象无效")
    }

    fun save(context: android.content.Context, root: Map<String, Any?>) {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            width = 120
            isPrettyFlow = true
        }
        val yaml = Yaml(options).dump(root)
        StorageUtils.writeTextAtomic(configFile(context), yaml)
    }

    fun providers(root: MutableMap<String, Any?>): LinkedHashMap<String, Any?> {
        val existing = asMap(root["providers"])
        if (existing != null) {
            root["providers"] = existing
            return existing
        }
        return LinkedHashMap<String, Any?>().also { root["providers"] = it }
    }

    fun models(provider: MutableMap<String, Any?>): MutableList<Any?> {
        val existing = provider["models"] as? MutableList<Any?>
        if (existing != null) return existing
        return ArrayList<Any?>().also { provider["models"] = it }
    }

    fun asMap(value: Any?): LinkedHashMap<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        if (value is LinkedHashMap<*, *>) return value as LinkedHashMap<String, Any?>
        val source = value as? Map<*, *> ?: return null
        return LinkedHashMap<String, Any?>().apply {
            source.forEach { (key, item) -> put(key.toString(), normalize(item)) }
        }
    }

    fun asModel(value: Any?): LinkedHashMap<String, Any?>? = asMap(value)

    fun string(map: Map<String, Any?>, key: String): String = map[key]?.toString().orEmpty()

    fun boolean(map: Map<String, Any?>, key: String): Boolean = when (val value = map[key]) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }

    fun number(map: Map<String, Any?>, key: String): String = when (val value = map[key]) {
        is Number -> value.toString()
        else -> value?.toString().orEmpty()
    }

    fun fetchKnownModels(): List<KnownModel> {
        val primary = runCatching { parseKnownCatalog(fetchText(KNOWN_MODELS_URL)) }.getOrNull().orEmpty()
        val fallback = runCatching { parseFallbackCatalog(fetchText(FALLBACK_MODELS_URL)) }.getOrNull().orEmpty()
        if (primary.isEmpty() && fallback.isEmpty()) throw IOException("已知模型目录为空或不可用")
        val seen = primary.mapTo(hashSetOf()) { it.id }
        return primary + fallback.filter { seen.add(it.id) }
    }

    fun fetchRemoteModels(
        context: android.content.Context,
        providerId: String,
        provider: Map<String, Any?>,
    ): List<RemoteModel> {
        val rawBaseUrl = string(provider, "baseUrl").trim()
        require(rawBaseUrl.isNotBlank()) { "Provider 未设置 Base URL" }
        val baseUrl = rawBaseUrl.substringBefore('?').trimEnd('/')
        val query = rawBaseUrl.substringAfter('?', "").takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        val api = string(provider, "api")
        require(api.isNotBlank()) { "Provider 未设置 API 类型" }
        val path = if (api == "anthropic-messages" && !baseUrl.endsWith("/v1")) {
            "$baseUrl/v1/models"
        } else {
            "$baseUrl/models"
        }
        val endpoint = path + query
        val key = resolveCredential(context, providerId, string(provider, "apiKey"))
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.requestMethod = "GET"
            val headers = provider["headers"] as? Map<*, *>
            val explicitHeaders = headers?.keys
                ?.filterNotNull()
                ?.map { it.toString().lowercase(Locale.ROOT) }
                ?.toHashSet()
                ?: emptySet()
            headers?.forEach { (name, rawValue) ->
                if (name != null && rawValue != null) {
                    connection.setRequestProperty(
                        name.toString(),
                        resolveCredential(context, providerId, rawValue.toString()),
                    )
                }
            }
            if (!key.isNullOrBlank()) {
                if (boolean(provider, "authHeader")) {
                    if ("authorization" !in explicitHeaders) {
                        connection.setRequestProperty("Authorization", "Bearer $key")
                    }
                } else {
                    when (api) {
                        "anthropic-messages" -> {
                            if ("x-api-key" !in explicitHeaders) connection.setRequestProperty("x-api-key", key)
                            if ("anthropic-version" !in explicitHeaders) connection.setRequestProperty("anthropic-version", "2023-06-01")
                        }
                        "google-generative-ai" -> if ("x-goog-api-key" !in explicitHeaders) connection.setRequestProperty("x-goog-api-key", key)
                        else -> if ("authorization" !in explicitHeaders) connection.setRequestProperty("Authorization", "Bearer $key")
                    }
                }
            }
            val responseBytes = readResponse(connection)
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}: ${safeMessage(responseBytes, key)}")
            }
            return parseRemoteCatalog(String(responseBytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }


    private fun defaultRoot() = linkedMapOf<String, Any?>("providers" to LinkedHashMap<String, Any?>())

    private fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> asMap(value)
        is List<*> -> value.map(::normalize).toMutableList()
        else -> value
    }

    private fun parseKnownCatalog(text: String): List<KnownModel> {
        val root = JSONObject(text)
        val result = ArrayList<KnownModel>()
        root.keys().forEach { id ->
            val source = root.optJSONObject(id) ?: return@forEach
            val mode = source.optString("mode", "chat")
            if (mode !in setOf("chat", "responses", "completion")) return@forEach
            val fields = LinkedHashMap<String, Any?>()
            fields["id"] = id
            val family = source.optString("litellm_provider", "unknown")
            copyLong(source, fields, listOf("max_input_tokens"), "contextWindow")
            copyLong(source, fields, listOf("max_output_tokens", "max_tokens"), "maxTokens")
            val reasoning = source.optBoolean("supports_reasoning") ||
                source.optBoolean("supports_max_reasoning_effort") ||
                source.optBoolean("supports_xhigh_reasoning_effort")
            if (reasoning) fields["reasoning"] = true
            fields["input"] = if (source.optBoolean("supports_vision") || containsString(source.optJSONArray("supported_modalities"), "image")) {
                mutableListOf("text", "image")
            } else {
                mutableListOf("text")
            }
            val cost = linkedMapOf<String, Any?>()
            copyPrice(source, cost, "input_cost_per_token", "input")
            copyPrice(source, cost, "output_cost_per_token", "output")
            copyPrice(source, cost, "cache_read_input_token_cost", "cacheRead")
            copyPrice(source, cost, "cache_creation_input_token_cost", "cacheWrite")
            if (cost.isNotEmpty()) {
                listOf("input", "output", "cacheRead", "cacheWrite").forEach { cost.putIfAbsent(it, 0.0) }
                fields["cost"] = cost
            }
            if (source.optBoolean("supports_max_reasoning_effort") || source.optBoolean("supports_xhigh_reasoning_effort")) {
                val level = if (id.lowercase(Locale.ROOT).contains("claude") && source.optBoolean("supports_max_reasoning_effort")) "max" else "xhigh"
                fields["thinkingLevelMap"] = linkedMapOf<String, Any?>("xhigh" to level)
            }
            result += KnownModel(id, null, family, fields)
        }
        return result
    }

    private fun parseFallbackCatalog(text: String): List<KnownModel> {
        val root = JSONObject(text)
        val result = ArrayList<KnownModel>()
        root.keys().forEach { family ->
            val entries = root.optJSONArray(family) ?: return@forEach
            for (index in 0 until entries.length()) {
                val source = entries.optJSONObject(index) ?: continue
                val id = source.optString("id").trim()
                if (id.isEmpty()) continue
                val fields = LinkedHashMap<String, Any?>()
                fields["id"] = id
                val displayName = source.optString("display_name", source.optString("displayName", ""))
                val name = displayName.takeIf { it.isNotBlank() && it != id }
                if (name != null) fields["name"] = name
                if (source.optJSONObject("thinking") != null) fields["reasoning"] = true
                fields["input"] = if (containsString(source.optJSONArray("supportedInputModalities"), "image")) {
                    mutableListOf("text", "image")
                } else {
                    mutableListOf("text")
                }
                copyLong(source, fields, listOf("context_length", "inputTokenLimit"), "contextWindow")
                copyLong(source, fields, listOf("max_completion_tokens", "outputTokenLimit"), "maxTokens")
                result += KnownModel(id, name, family, fields)
            }
        }
        return result
    }

    private fun parseRemoteCatalog(text: String): List<RemoteModel> {
        val value = JSONTokener(text).nextValue()
        val items = when (value) {
            is JSONObject -> value.optJSONArray("data") ?: value.optJSONArray("models")
            is JSONArray -> value
            else -> null
        } ?: throw IOException("模型列表格式不受支持")
        val seen = hashSetOf<String>()
        val result = ArrayList<RemoteModel>()
        for (index in 0 until items.length()) {
            val item = items.opt(index)
            val id: String
            var name: String? = null
            when (item) {
                is String -> id = item
                is JSONObject -> {
                    val rawId = item.optString("id").ifBlank { item.optString("name") }
                    id = rawId.removePrefix("models/")
                    val rawName = item.optString("display_name").ifBlank {
                        item.optString("displayName").ifBlank { item.optString("name") }
                    }
                    name = rawName.removePrefix("models/").takeIf { it.isNotBlank() && it != id }
                }
                else -> continue
            }
            if (id.isNotBlank() && seen.add(id)) result += RemoteModel(id, name)
        }
        if (result.isEmpty()) throw IOException("模型列表为空")
        return result
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            val bytes = readResponse(connection)
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): ByteArray {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_RESPONSE_BYTES) throw IOException("响应超过 10 MiB")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: ByteArray(0)
    }

    private fun resolveCredential(context: android.content.Context, providerId: String, raw: String): String {
        if (raw.startsWith("!")) throw IOException("不执行命令形式的认证值")
        val authFile = configFile(context).parentFile?.let { File(it, "auth.json") }
        val authEntry = if (authFile?.isFile == true) {
            runCatching { JSONObject(authFile.readText(Charsets.UTF_8)).optJSONObject(providerId) }.getOrNull()
        } else {
            null
        }
        val authKey = authEntry
            ?.takeIf { it.optString("type") == "api_key" }
            ?.optString("key")
            ?.takeIf { it.isNotBlank() }
        val value = authKey ?: raw
        val scopedEnv = authEntry?.optJSONObject("env")
        if (value.startsWith("${'$'}{") && value.endsWith("}")) {
            val name = value.substring(2, value.length - 1)
            return scopedEnv?.optString(name)?.takeIf { it.isNotBlank() }
                ?: System.getenv(name)
                ?: value
        }
        if (value.startsWith("${'$'}") && value.length > 1 && value.drop(1).all { it == '_' || it.isLetterOrDigit() }) {
            val name = value.substring(1)
            return scopedEnv?.optString(name)?.takeIf { it.isNotBlank() }
                ?: System.getenv(name)
                ?: value
        }
        return value.replace("${'$'}${'$'}", "${'$'}").replace("${'$'}!", "!")
    }

    private fun safeMessage(bytes: ByteArray, secret: String?): String {
        var message = String(bytes, Charsets.UTF_8).replace(Regex("[\\r\\n\\t]+"), " ")
        if (!secret.isNullOrBlank()) message = message.replace(secret, "<redacted>")
        return message.take(300).ifBlank { "请求失败" }
    }

    private fun copyLong(source: JSONObject, target: MutableMap<String, Any?>, keys: List<String>, targetKey: String) {
        keys.firstNotNullOfOrNull { key ->
            source.optLong(key).takeIf { source.has(key) && it > 0 }
        }?.let { target[targetKey] = it }
    }

    private fun copyPrice(source: JSONObject, target: MutableMap<String, Any?>, sourceKey: String, targetKey: String) {
        if (!source.has(sourceKey)) return
        val value = source.optDouble(sourceKey, Double.NaN)
        if (!value.isNaN() && value >= 0) target[targetKey] = (value * 1_000_000.0 * 1_000_000_000_000.0).toLong() / 1_000_000_000_000.0
    }

    private fun containsString(array: JSONArray?, expected: String): Boolean {
        if (array == null) return false
        for (index in 0 until array.length()) if (array.optString(index) == expected) return true
        return false
    }
}
