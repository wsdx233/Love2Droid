package top.wsdx233.love2droid

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

class ModelSettingsActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var listContainer: LinearLayout
    private var config: LinkedHashMap<String, Any?> = linkedMapOf("providers" to LinkedHashMap<String, Any?>())
    private var currentProviderId: String? = null
    private var configLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (!ProotRuntime.isOmpReady(this)) {
            Toast.makeText(this, R.string.settings_omp_unavailable, Toast.LENGTH_LONG).show()
            SetupActivity.start(this, targetComponent = InstallRegistry.ID_OMP)
            finish()
            return
        }
        createContent()
        loadConfig()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentProviderId != null) {
                    currentProviderId = null
                    render()
                } else finish()
            }
        })
    }

    private fun createContent() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(this, android.R.attr.colorBackground))
        }
        val topInset = View(this).apply { setBackgroundColor(getColor(R.color.action_bar_background)) }
        root.addView(topInset, LinearLayout.LayoutParams.MATCH_PARENT, 0)
        toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(getColor(R.color.action_bar_background))
            setTitleTextColor(getColor(R.color.action_bar_foreground))
            setNavigationIconTint(getColor(R.color.action_bar_foreground))
            title = getString(R.string.settings_models)
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.back)
            setNavigationOnClickListener {
                if (currentProviderId != null) {
                    currentProviderId = null
                    render()
                } else finish()
            }
            inflateMenu(R.menu.model_settings_menu)
            overflowIcon?.mutate()?.setTint(getColor(R.color.action_bar_foreground))
            setOnMenuItemClickListener(::onMenuItemSelected)
        }
        root.addView(toolbar, LinearLayout.LayoutParams.MATCH_PARENT, dp(64))
        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        scroll.addView(listContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams.MATCH_PARENT, 0)
        (scroll.layoutParams as LinearLayout.LayoutParams).weight = 1f
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset.layoutParams = topInset.layoutParams.apply { height = bars.top }
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        render()
    }

    private fun loadConfig() {
        lifecycleScope.launch {
            try {
                config = withContext(Dispatchers.IO) { OmpModelRepository.load(this@ModelSettingsActivity) }
                configLoaded = true
                render()
            } catch (error: Exception) {
                toast(error.message ?: getString(R.string.models_load_failed))
            }
        }
    }

    private fun render() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val providerId = currentProviderId
        if (providerId == null) {
            toolbar.title = getString(R.string.settings_models)
            setMenuVisibility(provider = true)
            renderProviders()
        } else {
            val provider = providerMap(providerId)
            if (provider == null) {
                currentProviderId = null
                render()
                return
            }
            toolbar.title = providerId
            setMenuVisibility(provider = false)
            renderModels(provider)
        }
    }

    private fun setMenuVisibility(provider: Boolean) {
        toolbar.menu.findItem(R.id.action_add_provider).isVisible = provider
        toolbar.menu.findItem(R.id.action_add_model).isVisible = !provider
        toolbar.menu.findItem(R.id.action_discover_models).isVisible = !provider
        toolbar.menu.findItem(R.id.action_known_models).isVisible = !provider
        toolbar.menu.findItem(R.id.action_edit_provider).isVisible = !provider
    }

    private fun renderProviders() {
        val providers = OmpModelRepository.providers(config)
        if (providers.isEmpty()) addEmpty(R.string.models_no_providers)
        providers.forEach { (id, raw) ->
            val provider = OmpModelRepository.asMap(raw) ?: return@forEach
            val count = OmpModelRepository.models(provider).size
            addListRow(
                title = id,
                subtitle = getString(
                    R.string.provider_summary,
                    OmpModelRepository.string(provider, "baseUrl").ifBlank { getString(R.string.provider_no_url) },
                    count,
                ),
                onClick = {
                    currentProviderId = id
                    render()
                },
                onLongClick = { showProviderActions(id) },
            )
        }
    }

    private fun renderModels(provider: MutableMap<String, Any?>) {
        val models = OmpModelRepository.models(provider)
        if (models.isEmpty()) addEmpty(R.string.models_no_models)
        models.forEachIndexed { index, raw ->
            val model = OmpModelRepository.asModel(raw) ?: return@forEachIndexed
            val name = OmpModelRepository.string(model, "name").ifBlank { OmpModelRepository.string(model, "id") }
            val details = buildString {
                append(OmpModelRepository.string(model, "id"))
                if (OmpModelRepository.boolean(model, "reasoning")) append(getString(R.string.model_reasoning_suffix))
                if (hasImageInput(model)) append(getString(R.string.model_vision_suffix))
                OmpModelRepository.number(model, "contextWindow").takeIf { it.isNotBlank() }?.let {
                    append(getString(R.string.model_context_suffix, it))
                }
            }
            addListRow(name, details, onClick = { showModelDialog(index) }, onLongClick = { showModelActions(index) })
        }
    }

    private fun addListRow(
        title: String,
        subtitle: String,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setOnLongClickListener {
                onLongClick?.invoke()
                onLongClick != null
            }
        }
        row.addView(TextView(this).apply { text = title; textSize = 17f })
        row.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, 0)
        })
        listContainer.addView(row, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun addEmpty(message: Int) {
        listContainer.addView(TextView(this).apply {
            text = getString(message)
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(dp(16), dp(48), dp(16), dp(48))
        }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        if (!configLoaded) return true
        when (item.itemId) {
            R.id.action_add_provider -> showProviderDialog(null)
            R.id.action_add_model -> showModelDialog(null)
            R.id.action_edit_provider -> showProviderDialog(currentProviderId)
            R.id.action_known_models -> fetchKnownModels()
            R.id.action_discover_models -> discoverModels()
        }
        return true
    }

    private fun showProviderActions(id: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(id)
            .setItems(arrayOf(getString(R.string.provider_edit), getString(R.string.delete))) { _, which ->
                if (which == 0) showProviderDialog(id) else deleteProvider(id)
            }
            .show()
    }

    private fun deleteProvider(id: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(getString(R.string.delete_warning))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                OmpModelRepository.providers(config).remove(id)
                saveConfig()
                render()
            }
            .show()
    }

    private fun showModelActions(index: Int) {
        val provider = currentProviderId?.let(::providerMap) ?: return
        val model = OmpModelRepository.asModel(OmpModelRepository.models(provider).getOrNull(index)) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(OmpModelRepository.string(model, "id"))
            .setItems(arrayOf(getString(R.string.model_edit), getString(R.string.delete))) { _, which ->
                if (which == 0) showModelDialog(index) else confirmDeleteModel(index, OmpModelRepository.string(model, "id"))
            }
            .show()
    }

    private fun confirmDeleteModel(index: Int, modelId: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(modelId)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val provider = currentProviderId?.let(::providerMap) ?: return@setPositiveButton
                val models = OmpModelRepository.models(provider)
                if (index in models.indices) {
                    models.removeAt(index)
                    saveConfig()
                    render()
                }
            }
            .show()
    }
    private fun showProviderDialog(existingId: String?): Boolean {
        val existing = existingId?.let(::providerMap)
        val form = formContainer()
        val id = addTextField(form, R.string.provider_id, existingId.orEmpty())
        val baseUrl = addTextField(form, R.string.provider_base_url, OmpModelRepository.string(existing.orEmptyMap(), "baseUrl"))
        val api = addSpinner(form, R.string.provider_api, API_TYPES, OmpModelRepository.string(existing.orEmptyMap(), "api"))
        val key = addTextField(form, R.string.provider_api_key, "", secret = true).apply {
            hint = if (OmpModelRepository.string(existing.orEmptyMap(), "apiKey").isBlank()) getString(R.string.provider_api_key_hint) else getString(R.string.provider_api_key_unchanged)
        }
        val authHeader = addSwitch(form, R.string.provider_auth_header, OmpModelRepository.boolean(existing.orEmptyMap(), "authHeader"))
        val strictTools = addSwitch(form, R.string.provider_disable_strict_tools, OmpModelRepository.boolean(existing.orEmptyMap(), "disableStrictTools"))
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existingId == null) R.string.provider_add else R.string.provider_edit)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val newId = id.text.toString().trim()
                val providers = OmpModelRepository.providers(config)
                if (newId.isBlank() || (newId != existingId && providers.containsKey(newId))) {
                    toast(getString(R.string.provider_id_invalid))
                    return@setPositiveButton
                }
                val value = existing ?: LinkedHashMap()
                value["baseUrl"] = baseUrl.text.toString().trim()
                value["api"] = api.selectedItem?.toString().orEmpty()
                if (key.text.toString().isNotBlank()) value["apiKey"] = key.text.toString()
                value["authHeader"] = authHeader.isChecked
                value["disableStrictTools"] = strictTools.isChecked
                if (existingId != null && existingId != newId) providers.remove(existingId)
                providers[newId] = value
                if (currentProviderId == existingId) currentProviderId = newId
                saveConfig()
                render()
            }
            .show()
        return true
    }

    private fun showModelDialog(index: Int?): Boolean {
        val providerId = currentProviderId ?: return true
        val provider = providerMap(providerId) ?: return true
        val existing = index?.let { OmpModelRepository.asModel(OmpModelRepository.models(provider).getOrNull(it)) }
        val form = formContainer()
        val id = addTextField(form, R.string.model_id, OmpModelRepository.string(existing.orEmptyMap(), "id"))
        val name = addTextField(form, R.string.model_name, OmpModelRepository.string(existing.orEmptyMap(), "name"))
        val api = addSpinner(form, R.string.model_api, API_TYPES, OmpModelRepository.string(existing.orEmptyMap(), "api"))
        val contextWindow = addTextField(form, R.string.model_context, OmpModelRepository.number(existing.orEmptyMap(), "contextWindow"), numeric = true)
        val maxTokens = addTextField(form, R.string.model_max_tokens, OmpModelRepository.number(existing.orEmptyMap(), "maxTokens"), numeric = true)
        val costInput = addTextField(form, R.string.model_cost_input, cost(existing, "input"), numeric = true)
        val costOutput = addTextField(form, R.string.model_cost_output, cost(existing, "output"), numeric = true)
        val costRead = addTextField(form, R.string.model_cost_cache_read, cost(existing, "cacheRead"), numeric = true)
        val costWrite = addTextField(form, R.string.model_cost_cache_write, cost(existing, "cacheWrite"), numeric = true)
        val reasoning = addSwitch(form, R.string.model_reasoning, OmpModelRepository.boolean(existing.orEmptyMap(), "reasoning"))
        val vision = addSwitch(form, R.string.model_vision, hasImageInput(existing.orEmptyMap()))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (index == null) R.string.model_add else R.string.model_edit)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val modelId = id.text.toString().trim()
                val models = OmpModelRepository.models(provider)
                val duplicate = models.indices.any { it != index && OmpModelRepository.string(OmpModelRepository.asModel(models[it]).orEmptyMap(), "id") == modelId }
                if (modelId.isBlank() || duplicate) {
                    toast(getString(R.string.model_id_invalid))
                    return@setPositiveButton
                }
                val value = existing ?: LinkedHashMap()
                value["id"] = modelId
                putOptional(value, "name", name.text.toString())
                putOptional(value, "api", api.selectedItem?.toString().orEmpty())
                putPositiveLong(value, "contextWindow", contextWindow.text.toString())
                putPositiveLong(value, "maxTokens", maxTokens.text.toString())
                value["reasoning"] = reasoning.isChecked
                value["input"] = if (vision.isChecked) mutableListOf("text", "image") else mutableListOf("text")
                updateCost(value, costInput, "input")
                updateCost(value, costOutput, "output")
                updateCost(value, costRead, "cacheRead")
                updateCost(value, costWrite, "cacheWrite")
                if (index == null) models.add(value) else models[index] = value
                saveConfig()
                render()
            }
        if (index != null) {
            dialog.setNeutralButton(R.string.model_lookup_info) { _, _ ->
                lookupModelInfo(providerId, index, id.text.toString().trim())
            }
        }
        dialog.show()
        return true
    }

    private fun fetchKnownModels() {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.models_fetching_known)
            .setView(ProgressBar(this).apply { isIndeterminate = true })
            .setNegativeButton(R.string.cancel, null)
            .create()
        progress.show()
        lifecycleScope.launch {
            try {
                val models = withContext(Dispatchers.IO) { OmpModelRepository.fetchKnownModels() }
                progress.dismiss()
                showKnownModelPicker(models)
            } catch (error: Exception) {
                progress.dismiss()
                toast(error.message ?: getString(R.string.models_fetch_failed))
            }
        }
    }

    private fun showKnownModelPicker(models: List<OmpModelRepository.KnownModel>) {
        val dialogContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        val search = EditText(this).apply { hint = getString(R.string.models_search_hint); setSingleLine(true) }
        val list = ListView(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, models.map { modelLabel(it) })
        list.adapter = adapter
        search.addTextChangedListener(SimpleTextWatcher {
            val query = search.text.toString().trim().lowercase()
            adapter.clear()
            adapter.addAll(models.filter { query.isBlank() || it.id.lowercase().contains(query) || (it.name?.lowercase()?.contains(query) == true) }.map(::modelLabel))
            adapter.notifyDataSetChanged()
        })
        dialogContent.addView(search, LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        dialogContent.addView(list, LinearLayout.LayoutParams.MATCH_PARENT, dp(420))
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.models_known_title).setView(dialogContent).setNegativeButton(R.string.cancel, null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            val selectedLabel = adapter.getItem(position) ?: return@setOnItemClickListener
            val selected = models.firstOrNull { modelLabel(it) == selectedLabel } ?: return@setOnItemClickListener
            addKnownModel(selected)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun discoverModels() {
        val providerId = currentProviderId ?: return
        val provider = providerMap(providerId) ?: return
        lifecycleScope.launch {
            try {
                val remote = withContext(Dispatchers.IO) { OmpModelRepository.fetchRemoteModels(this@ModelSettingsActivity, providerId, provider) }
                val models = OmpModelRepository.models(provider)
                val ids = models.mapTo(hashSetOf()) { OmpModelRepository.string(OmpModelRepository.asModel(it).orEmptyMap(), "id") }
                val added = remote.filter { ids.add(it.id) }.map { model -> linkedMapOf<String, Any?>("id" to model.id).also { value -> model.name?.let { value["name"] = it } } }
                models.addAll(added)
                saveConfig()
                render()
                toast(getString(R.string.models_discovered, added.size, remote.size))
            } catch (error: Exception) {
                toast(error.message ?: getString(R.string.models_fetch_failed))
            }
        }
    }

    private fun addKnownModel(model: OmpModelRepository.KnownModel) {
        val provider = currentProviderId?.let(::providerMap) ?: return
        val models = OmpModelRepository.models(provider)
        if (models.any { OmpModelRepository.string(OmpModelRepository.asModel(it).orEmptyMap(), "id") == model.id }) {
            toast(getString(R.string.model_already_exists))
            return
        }
        models.add(LinkedHashMap(model.fields))
        saveConfig()
        render()
        toast(getString(R.string.model_added, model.id))
    }
    private fun lookupModelInfo(providerId: String, index: Int, modelId: String) {
        if (modelId.isBlank()) return
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.model_info_fetching)
            .setView(ProgressBar(this).apply { isIndeterminate = true })
            .setNegativeButton(R.string.cancel, null)
            .create()
        progress.show()
        lifecycleScope.launch {
            try {
                val known = withContext(Dispatchers.IO) {
                    OmpModelRepository.fetchKnownModels().firstOrNull { it.id == modelId }
                }
                progress.dismiss()
                if (known == null) {
                    toast(getString(R.string.model_info_not_found, modelId))
                    return@launch
                }
                val provider = providerMap(providerId) ?: return@launch
                val model = OmpModelRepository.asModel(OmpModelRepository.models(provider).getOrNull(index)) ?: return@launch
                known.fields.forEach { (key, value) -> if (key != "id") model[key] = value }
                OmpModelRepository.models(provider)[index] = model
                saveConfig()
                render()
                toast(getString(R.string.model_info_updated, modelId))
            } catch (error: Exception) {
                progress.dismiss()
                toast(error.message ?: getString(R.string.models_fetch_failed))
            }
        }
    }


    private fun saveConfig() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { OmpModelRepository.save(this@ModelSettingsActivity, config) }
            } catch (error: Exception) {
                toast(error.message ?: getString(R.string.models_save_failed))
            }
        }
    }

    private fun providerMap(id: String): LinkedHashMap<String, Any?>? {
        val providers = OmpModelRepository.providers(config)
        val current = providers[id]
        if (current is LinkedHashMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return current as LinkedHashMap<String, Any?>
        }
        return OmpModelRepository.asMap(current)?.also { providers[id] = it }
    }

    private fun formContainer() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }

    private fun addTextField(parent: LinearLayout, hint: Int, value: String, numeric: Boolean = false, secret: Boolean = false): EditText {
        return EditText(this).apply {
            this.hint = getString(hint)
            setText(value)
            setSingleLine(true)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> InputType.TYPE_CLASS_TEXT
            }
            parent.addView(this, LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
        }
    }

    private fun addSpinner(parent: LinearLayout, hint: Int, values: List<String>, selected: String): Spinner {
        parent.addView(TextView(this).apply { text = getString(hint); setTextColor(Color.GRAY); setPadding(0, dp(8), 0, 0) })
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@ModelSettingsActivity, android.R.layout.simple_spinner_item, values).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(values.indexOf(selected).coerceAtLeast(0))
            parent.addView(this, LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        }
    }

    private fun addSwitch(parent: LinearLayout, label: Int, checked: Boolean): MaterialSwitch {
        return MaterialSwitch(this).apply { text = getString(label); isChecked = checked; parent.addView(this, LinearLayout.LayoutParams.MATCH_PARENT, dp(52)) }
    }

    private fun modelLabel(model: OmpModelRepository.KnownModel): String = listOfNotNull(model.id, model.name?.takeIf { it != model.id }, model.family.takeIf { it.isNotBlank() }).joinToString(" · ")
    private fun cost(model: Map<String, Any?>?, key: String): String = OmpModelRepository.asMap(model?.get("cost")).orEmpty()[key]?.toString().orEmpty()
    private fun hasImageInput(model: Map<String, Any?>): Boolean = (model["input"] as? List<*>)?.any { it == "image" } == true
    private fun putOptional(map: MutableMap<String, Any?>, key: String, raw: String) { raw.trim().takeIf { it.isNotBlank() }?.let { map[key] = it } ?: map.remove(key) }
    private fun putPositiveLong(map: MutableMap<String, Any?>, key: String, raw: String) { raw.trim().toLongOrNull()?.takeIf { it > 0 }?.let { map[key] = it } ?: map.remove(key) }
    private fun updateCost(map: MutableMap<String, Any?>, field: EditText, key: String) {
        val value = field.text.toString().trim().toDoubleOrNull()
        val cost = OmpModelRepository.asMap(map["cost"]) ?: LinkedHashMap<String, Any?>()
        if (value != null && value >= 0) cost[key] = value else cost.remove(key)
        if (cost.isEmpty()) map.remove("cost") else map["cost"] = cost
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun Map<String, Any?>?.orEmptyMap(): Map<String, Any?> = this ?: emptyMap()

    private class SimpleTextWatcher(private val changed: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed()
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    companion object {
        private val API_TYPES = listOf("", "openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai", "bedrock-converse-stream")
    }
}
