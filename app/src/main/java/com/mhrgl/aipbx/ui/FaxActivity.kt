package com.mhrgl.aipbx.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mhrgl.aipbx.R
import com.mhrgl.aipbx.data.ApiClient
import com.mhrgl.aipbx.data.AppPreferences
import com.mhrgl.aipbx.data.ChatUploadPrep
import com.mhrgl.aipbx.databinding.ActivityFaxBinding
import com.mhrgl.aipbx.databinding.ItemFaxBinding
import com.mhrgl.aipbx.model.FaxMessage
import com.mhrgl.aipbx.model.FaxTemplate
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.launch

/**
 * Faxes: received (for the user's DIDs) and sent, each a list with a PDF viewer, plus a send screen
 * (PDF file or typed text, optional cover page). Mirrors VoicemailActivity: list, manual refresh,
 * AlertDialog confirm for delete.
 */
class FaxActivity : BaseActivity() {

    companion object {
        fun start(context: Context) =
            context.startActivity(Intent(context, FaxActivity::class.java))
    }

    private enum class Tab {
        RECEIVED,
        SENT,
    }

    private lateinit var binding: ActivityFaxBinding
    private lateinit var prefs: AppPreferences
    private val apiClient by lazy { ApiClient { prefs } }
    private val faxes = mutableListOf<FaxMessage>()
    private val adapter = Adapter()
    private var tab = Tab.RECEIVED

    // --- Send screen state ---
    private var configLoaded = false
    private var templates: List<FaxTemplate> = emptyList()
    private var selectedTemplateId: String? = null
    private var pdfPrepared: ChatUploadPrep.Prepared? = null
    private var sending = false

    private val pickPdfLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            // PDFs go through the raw copy: the image re-encode branch must never see them.
            val prepared = ChatUploadPrep.prepare(this, uri, "document")
            if (prepared == null) {
                Toast.makeText(this, getString(R.string.err_file_read), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            pdfPrepared?.cleanup()
            pdfPrepared = prepared
            binding.tvPdfName.text = prepared.file.name
        }

    private val backCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.scrollSend.visibility == View.VISIBLE) showList() else finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences.getInstance(this)
        binding = ActivityFaxBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root, consumeIme = true)
        onBackPressedDispatcher.addCallback(this, backCallback)
        binding.btnBack.setOnClickListener {
            if (binding.scrollSend.visibility == View.VISIBLE) showList() else finish()
        }
        binding.btnRefresh.setOnClickListener { load() }
        binding.btnSend.setOnClickListener { showSend() }
        binding.btnTabReceived.setOnClickListener { switchTab(Tab.RECEIVED) }
        binding.btnTabSent.setOnClickListener { switchTab(Tab.SENT) }
        binding.rvFax.layoutManager = LinearLayoutManager(this)
        binding.rvFax.adapter = adapter
        binding.rgCompose.setOnCheckedChangeListener { _, _ -> updateComposeVisibility() }
        binding.cbCover.setOnCheckedChangeListener { _, checked ->
            binding.coverFields.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.btnPickPdf.setOnClickListener { pickPdfLauncher.launch("application/pdf") }
        binding.btnSendFax.setOnClickListener { submitSend() }
        updateTabAppearance()
        load()
    }

    private fun switchTab(newTab: Tab) {
        if (tab == newTab) return
        tab = newTab
        updateTabAppearance()
        load()
    }

    private fun updateTabAppearance() {
        val selected = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
        val selectedText = ContextCompat.getColor(this, android.R.color.white)
        val unselectedText = ContextCompat.getColor(this, R.color.text_primary)
        binding.btnTabReceived.backgroundTintList = if (tab == Tab.RECEIVED) selected else null
        binding.btnTabReceived.setTextColor(
            if (tab == Tab.RECEIVED) selectedText else unselectedText
        )
        binding.btnTabSent.backgroundTintList = if (tab == Tab.SENT) selected else null
        binding.btnTabSent.setTextColor(if (tab == Tab.SENT) selectedText else unselectedText)
        binding.tvEmpty.setText(
            if (tab == Tab.RECEIVED) R.string.fax_empty_received else R.string.fax_empty_sent
        )
    }

    private fun load() {
        val url = prefs.serverUrl
        val token = prefs.token ?: return
        val type = if (tab == Tab.RECEIVED) "received" else "sent"
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            apiClient
                .getFaxList(url, token, type)
                .onSuccess { res ->
                    faxes.clear()
                    faxes.addAll(res.faxes.orEmpty())
                    adapter.notifyDataSetChanged()
                }
                .onFailure {
                    Toast.makeText(this@FaxActivity, it.message, Toast.LENGTH_LONG).show()
                }
            binding.progress.visibility = View.GONE
            binding.tvEmpty.visibility = if (faxes.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun kind() = if (tab == Tab.RECEIVED) "received" else "sent"

    private fun openFax(fax: FaxMessage) {
        val url = prefs.serverUrl
        val token = prefs.token ?: return
        val kind = kind()
        lifecycleScope.launch {
            val file = File(File(cacheDir, "fax").apply { mkdirs() }, "fax-${fax.id}.pdf")
            val res = apiClient.downloadFax(url, token, fax.id, kind, file)
            if (res.isFailure) {
                Toast.makeText(
                        this@FaxActivity,
                        getString(R.string.fax_open_failed),
                        Toast.LENGTH_LONG,
                    )
                    .show()
                return@launch
            }
            if (tab == Tab.RECEIVED && !fax.isRead) markRead(fax)
            try {
                val uri =
                    FileProvider.getUriForFile(
                        this@FaxActivity,
                        "${packageName}.fileprovider",
                        file,
                    )
                val intent =
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                        this@FaxActivity,
                        getString(R.string.fax_open_failed),
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    }

    private fun markRead(fax: FaxMessage) {
        val token = prefs.token ?: return
        lifecycleScope.launch {
            apiClient.faxAction(prefs.serverUrl, token, "mark_read", fax.id).onSuccess {
                val i = faxes.indexOfFirst { it.id == fax.id }
                if (i >= 0) {
                    faxes[i] = fax.copy(isRead = true)
                    adapter.notifyItemChanged(i)
                }
            }
        }
    }

    private fun confirmDelete(fax: FaxMessage) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.fax_delete_confirm))
            .setPositiveButton(getString(R.string.ui_delete)) { _, _ ->
                val token = prefs.token ?: return@setPositiveButton
                lifecycleScope.launch {
                    apiClient
                        .faxAction(prefs.serverUrl, token, "delete", fax.id, kind())
                        .onSuccess {
                            val i = faxes.indexOfFirst { it.id == fax.id }
                            if (i >= 0) {
                                faxes.removeAt(i)
                                adapter.notifyItemRemoved(i)
                            }
                            binding.tvEmpty.visibility =
                                if (faxes.isEmpty()) View.VISIBLE else View.GONE
                        }
                        .onFailure {
                            Toast.makeText(this@FaxActivity, it.message, Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton(getString(R.string.btn_dismiss), null)
            .show()
    }

    private fun confirmResend(fax: FaxMessage) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.fax_resend_confirm))
            .setPositiveButton(getString(R.string.fax_resend)) { _, _ ->
                val token = prefs.token ?: return@setPositiveButton
                lifecycleScope.launch {
                    apiClient
                        .faxAction(prefs.serverUrl, token, "resend", fax.id)
                        .onSuccess {
                            Toast.makeText(
                                    this@FaxActivity,
                                    getString(R.string.fax_resent),
                                    Toast.LENGTH_LONG,
                                )
                                .show()
                            load()
                        }
                        .onFailure {
                            Toast.makeText(this@FaxActivity, it.message, Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton(getString(R.string.btn_dismiss), null)
            .show()
    }

    // --- Send screen ---

    private fun showSend() {
        binding.frameList.visibility = View.GONE
        binding.tabRow.visibility = View.GONE
        binding.btnSend.visibility = View.GONE
        binding.btnRefresh.visibility = View.GONE
        binding.scrollSend.visibility = View.VISIBLE
        updateComposeVisibility()
        if (!configLoaded) loadConfig()
    }

    private fun showList() {
        binding.scrollSend.visibility = View.GONE
        binding.frameList.visibility = View.VISIBLE
        binding.tabRow.visibility = View.VISIBLE
        binding.btnSend.visibility = View.VISIBLE
        binding.btnRefresh.visibility = View.VISIBLE
    }

    private fun updateComposeVisibility() {
        val pdfMode = binding.rbPdf.isChecked
        binding.rowPdf.visibility = if (pdfMode) View.VISIBLE else View.GONE
        binding.etFaxText.visibility = if (pdfMode) View.GONE else View.VISIBLE
    }

    private fun loadConfig() {
        val token = prefs.token ?: return
        lifecycleScope.launch {
            apiClient
                .getFaxConfig(prefs.serverUrl, token)
                .onSuccess { config ->
                    configLoaded = true
                    val numbers = config.sendableNumbers.orEmpty()
                    val senderItems = numbers.ifEmpty { listOfNotNull(prefs.extension) }
                    binding.spinnerSender.adapter =
                        ArrayAdapter(
                            this@FaxActivity,
                            android.R.layout.simple_spinner_dropdown_item,
                            senderItems,
                        )
                    val ownExt = prefs.extension
                    val ownIndex = senderItems.indexOf(ownExt)
                    if (ownIndex >= 0) binding.spinnerSender.setSelection(ownIndex)
                    // Cover: server sends off|optional|required — "off" hides it,
                    // "required" forces it on, anything else leaves the choice.
                    when (config.coverMode) {
                        "off" -> {
                            binding.cbCover.isChecked = false
                            binding.cbCover.visibility = View.GONE
                            binding.coverFields.visibility = View.GONE
                        }
                        "required" -> {
                            binding.cbCover.isChecked = true
                            binding.cbCover.isEnabled = false
                            binding.coverFields.visibility = View.VISIBLE
                        }
                        else -> {
                            binding.cbCover.isChecked = true
                            binding.coverFields.visibility = View.VISIBLE
                        }
                    }
                    if (binding.etCoverMessage.text.isNullOrBlank()) {
                        binding.etCoverMessage.setText(config.coverNotice.orEmpty())
                    }
                    templates = config.templates.orEmpty()
                    val templateNames =
                        listOf(getString(R.string.fax_template_none)) +
                            templates.map { it.name ?: getString(R.string.fax_template_none) }
                    binding.spinnerTemplate.adapter =
                        ArrayAdapter(
                            this@FaxActivity,
                            android.R.layout.simple_spinner_dropdown_item,
                            templateNames,
                        )
                    binding.spinnerTemplate.onItemSelectedListener =
                        object : AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(
                                parent: AdapterView<*>,
                                view: View?,
                                position: Int,
                                id: Long,
                            ) {
                                if (position == 0) {
                                    selectedTemplateId = null
                                    return
                                }
                                val template = templates.getOrNull(position - 1) ?: return
                                selectedTemplateId = template.id
                                if (
                                    binding.etCoverMessage.text.isNullOrBlank() &&
                                        !template.notice.isNullOrBlank()
                                ) {
                                    binding.etCoverMessage.setText(template.notice)
                                }
                            }

                            override fun onNothingSelected(parent: AdapterView<*>) = Unit
                        }
                }
                .onFailure {
                    Toast.makeText(
                            this@FaxActivity,
                            getString(R.string.fax_config_failed),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
        }
    }

    private fun submitSend() {
        if (sending) return
        val dest = binding.etDest.text.toString().trim()
        if (dest.isEmpty()) {
            Toast.makeText(this, getString(R.string.fax_dest_required), Toast.LENGTH_LONG).show()
            return
        }
        val pdfMode = binding.rbPdf.isChecked
        if (pdfMode && pdfPrepared == null) {
            Toast.makeText(this, getString(R.string.fax_pdf_required), Toast.LENGTH_LONG).show()
            return
        }
        val text = binding.etFaxText.text.toString()
        if (!pdfMode && text.isBlank()) {
            Toast.makeText(this, getString(R.string.fax_text_required), Toast.LENGTH_LONG).show()
            return
        }
        val token = prefs.token ?: return
        val senderDid = (binding.spinnerSender.selectedItem as? String) ?: prefs.extension.orEmpty()
        sending = true
        binding.btnSendFax.isEnabled = false
        binding.btnSendFax.setText(R.string.fax_sending)
        lifecycleScope.launch {
            val res =
                apiClient.sendFax(
                    baseUrl = prefs.serverUrl,
                    token = token,
                    destNumber = dest,
                    composeMode = if (pdfMode) "pdf" else "text",
                    senderDid = senderDid,
                    coverEnabled = binding.cbCover.isChecked,
                    coverTo = binding.etCoverTo.text.toString(),
                    coverSubject = binding.etCoverSubject.text.toString(),
                    coverMessage = binding.etCoverMessage.text.toString(),
                    templateId = selectedTemplateId,
                    pdfFile = pdfPrepared?.file,
                    faxText = if (pdfMode) null else text,
                )
            sending = false
            binding.btnSendFax.isEnabled = true
            binding.btnSendFax.setText(R.string.ui_send)
            res.onSuccess { sendRes ->
                    Toast.makeText(
                            this@FaxActivity,
                            getString(R.string.fax_sent_ok, sendRes.pages ?: 0),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    pdfPrepared?.cleanup()
                    pdfPrepared = null
                    binding.etDest.text?.clear()
                    binding.etFaxText.text?.clear()
                    binding.tvPdfName.setText(R.string.fax_no_pdf)
                    showList()
                    switchTab(Tab.SENT)
                }
                .onFailure {
                    // Keep the picked PDF so the user can retry without re-picking it.
                    Toast.makeText(this@FaxActivity, it.message, Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun formatWhen(whenText: String?): String {
        if (whenText.isNullOrBlank()) return ""
        return runCatching {
            val ms = OffsetDateTime.parse(whenText).toInstant().toEpochMilli()
            DateUtils.formatDateTime(
                this,
                ms,
                DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_SHOW_TIME or
                    DateUtils.FORMAT_ABBREV_MONTH,
            )
        }
            .getOrNull()
            .orEmpty()
    }

    private fun formatSize(bytes: Long): String =
        when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
            bytes >= 1024 -> "%d KB".format(bytes / 1024)
            bytes > 0 -> "%d B".format(bytes)
            else -> ""
        }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val b: ItemFaxBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemFaxBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = faxes.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val m = faxes[position]
            val b = holder.b
            val received = tab == Tab.RECEIVED
            b.tvTitle.text =
                if (received) {
                    val caller = m.callerId.orEmpty()
                    if (caller.isNotEmpty() && !caller.equals("Unknown", true)) caller
                    else getString(R.string.vm_unknown_caller)
                } else {
                    getString(R.string.fax_to, m.destinationNumber.orEmpty())
                }
            b.tvNew.visibility = if (received && !m.isRead) View.VISIBLE else View.GONE
            val whenText = formatWhen(if (received) m.receivedAt else m.createdAt)
            val pages = getString(R.string.fax_pages, m.pages)
            val size = formatSize(m.fileSize)
            val via = if (received) m.didExtension else m.senderExtension
            val details = buildList {
                if (via != null) add(getString(R.string.fax_via, via))
                if (whenText.isNotEmpty()) add(whenText)
                add(pages)
                if (size.isNotEmpty()) add(size)
                if (!received && !m.status.isNullOrBlank()) add(m.status)
            }
                .joinToString(" · ")
            b.tvDetails.text = details
            val error = m.errorMessage.orEmpty()
            b.tvError.visibility = if (error.isNotBlank()) View.VISIBLE else View.GONE
            b.tvError.text = error
            val failed = !received && m.status?.equals("failed", ignoreCase = true) == true
            b.btnResend.visibility = if (failed) View.VISIBLE else View.GONE
            b.btnResend.setOnClickListener { confirmResend(m) }
            b.btnOpen.setOnClickListener { openFax(m) }
            b.root.setOnClickListener { openFax(m) }
            b.btnDelete.setOnClickListener { confirmDelete(m) }
        }
    }
}
