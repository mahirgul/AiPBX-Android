package com.mhrgl.aipbx.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mhrgl.aipbx.R
import com.mhrgl.aipbx.data.ApiClient
import com.mhrgl.aipbx.data.AppPreferences
import com.mhrgl.aipbx.data.ChatUploadPrep
import com.mhrgl.aipbx.data.SimpleImageLoader
import com.mhrgl.aipbx.databinding.ActivitySmsBinding
import com.mhrgl.aipbx.databinding.ItemSmsMessageBinding
import com.mhrgl.aipbx.databinding.ItemSmsThreadBinding
import com.mhrgl.aipbx.model.SmsGif
import com.mhrgl.aipbx.model.SmsMessage
import com.mhrgl.aipbx.model.SmsNumber
import com.mhrgl.aipbx.model.SmsThread
import java.time.OffsetDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SMS / MMS: conversations (one of our numbers with one peer), each a thread with a composer, plus
 * a new-message screen. Mirrors FaxActivity: list, manual refresh, send button with a `sending`
 * guard so a double tap never sends twice. MMS pictures go through the same server path as the
 * portal.
 */
class SmsActivity : BaseActivity() {

    companion object {
        fun start(context: Context) =
            context.startActivity(Intent(context, SmsActivity::class.java))
    }

    private enum class Screen {
        LIST,
        THREAD,
        NEW,
    }

    private lateinit var binding: ActivitySmsBinding
    private lateinit var prefs: AppPreferences
    private val apiClient by lazy { ApiClient { prefs } }
    private val threads = mutableListOf<SmsThread>()
    private val threadAdapter = ThreadAdapter()
    private val messages = mutableListOf<SmsMessage>()
    private val messageAdapter = MessageAdapter()
    private var screen = Screen.LIST

    // --- Thread state ---
    private var openNumberId = 0
    private var openPeer = ""
    private var openName = ""
    private var sending = false

    // --- New-message state ---
    private var numbers: List<SmsNumber> = emptyList()
    private var configLoaded = false
    private var imagePrepared: ChatUploadPrep.Prepared? = null

    // --- Reply / GIF state ---
    private var replyToId = 0
    private var gifUrl: String? = null
    /** The server has a GIPHY key: the GIF button searches; otherwise it picks a GIF file. */
    private var gifSearch = false

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val prepared = ChatUploadPrep.prepare(this, uri, "image")
            if (prepared == null) {
                Toast.makeText(this, getString(R.string.err_file_read), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            imagePrepared?.cleanup()
            imagePrepared = prepared
            binding.tvAttachName.text = prepared.file.name
            binding.tvAttachName.visibility = View.VISIBLE
        }

    private val backCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen != Screen.LIST) showList() else finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences.getInstance(this)
        binding = ActivitySmsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets(binding.root, consumeIme = true)
        onBackPressedDispatcher.addCallback(this, backCallback)
        binding.btnBack.setOnClickListener {
            if (screen != Screen.LIST) showList() else finish()
        }
        binding.btnRefresh.setOnClickListener { loadThreads() }
        binding.btnNew.setOnClickListener { showNew() }
        binding.rvThreads.layoutManager = LinearLayoutManager(this)
        binding.rvThreads.adapter = threadAdapter
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = messageAdapter
        binding.btnAttach.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnEmoji.setOnClickListener { showEmojiPicker() }
        binding.btnGif.setOnClickListener {
            if (gifSearch) showGifPicker() else pickImageLauncher.launch("image/gif")
        }
        binding.btnReplyCancel.setOnClickListener { clearReply() }
        binding.btnGifRemove.setOnClickListener { clearGif() }
        binding.btnDeleteThread.setOnClickListener { confirmDeleteThread(openNumberId, openPeer) }
        binding.btnSendSms.setOnClickListener { submitSend() }
        binding.btnSendNew.setOnClickListener { submitNew() }
        loadThreads()
        loadConfig(quiet = true)
    }

    // --- Screens ---

    private fun showList() {
        screen = Screen.LIST
        binding.frameThread.visibility = View.GONE
        binding.scrollNew.visibility = View.GONE
        binding.frameList.visibility = View.VISIBLE
        binding.btnNew.visibility = View.VISIBLE
        binding.btnRefresh.visibility = View.VISIBLE
        binding.btnDeleteThread.visibility = View.GONE
        binding.tvTitle.setText(R.string.sms_title)
        loadThreads()
    }

    private fun showThread(thread: SmsThread) {
        screen = Screen.THREAD
        openNumberId = thread.numberId
        openPeer = thread.peer.orEmpty()
        openName = thread.name.orEmpty()
        binding.frameList.visibility = View.GONE
        binding.scrollNew.visibility = View.GONE
        binding.frameThread.visibility = View.VISIBLE
        binding.btnNew.visibility = View.GONE
        binding.btnRefresh.visibility = View.VISIBLE
        binding.btnDeleteThread.visibility = View.VISIBLE
        binding.tvTitle.text = displayName(thread)
        imagePrepared?.cleanup()
        imagePrepared = null
        binding.tvAttachName.visibility = View.GONE
        clearReply()
        clearGif()
        loadThread()
    }

    private fun showNew() {
        screen = Screen.NEW
        binding.frameList.visibility = View.GONE
        binding.frameThread.visibility = View.GONE
        binding.scrollNew.visibility = View.VISIBLE
        binding.btnNew.visibility = View.GONE
        binding.btnRefresh.visibility = View.GONE
        binding.btnDeleteThread.visibility = View.GONE
        binding.tvTitle.setText(R.string.sms_new)
        if (!configLoaded) loadConfig()
    }

    // --- Loading ---

    private fun loadThreads() {
        val url = prefs.serverUrl
        val token = prefs.token ?: return
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            apiClient
                .getSmsThreads(url, token)
                .onSuccess { res ->
                    threads.clear()
                    threads.addAll(res.threads.orEmpty())
                    threadAdapter.notifyDataSetChanged()
                }
                .onFailure {
                    Toast.makeText(this@SmsActivity, it.message, Toast.LENGTH_LONG).show()
                }
            binding.progress.visibility = View.GONE
            binding.tvEmpty.visibility = if (threads.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun loadThread() {
        val url = prefs.serverUrl
        val token = prefs.token ?: return
        lifecycleScope.launch {
            apiClient
                .getSmsThread(url, token, openNumberId, openPeer)
                .onSuccess { res ->
                    messages.clear()
                    messages.addAll(res.messages.orEmpty())
                    messageAdapter.notifyDataSetChanged()
                    if (messages.isNotEmpty()) {
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                }
                .onFailure {
                    Toast.makeText(this@SmsActivity, it.message, Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun loadConfig(quiet: Boolean = false) {
        val url = prefs.serverUrl
        val token = prefs.token ?: return
        lifecycleScope.launch {
            apiClient
                .getSmsConfig(url, token)
                .onSuccess { res ->
                    numbers = res.numbers.orEmpty()
                    gifSearch = res.gifSearch
                    configLoaded = true
                    val labels = numbers.map { n ->
                        if (n.label.isNullOrBlank()) n.number.orEmpty()
                        else "${n.label} (${n.number.orEmpty()})"
                    }
                    val adapter =
                        ArrayAdapter(
                            this@SmsActivity,
                            android.R.layout.simple_spinner_item,
                            labels,
                        )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spinnerSender.adapter = adapter
                    if (numbers.isEmpty() && !quiet) {
                        Toast.makeText(
                                this@SmsActivity,
                                getString(R.string.sms_no_numbers),
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                }
                .onFailure {
                    if (quiet) return@onFailure
                    Toast.makeText(
                            this@SmsActivity,
                            getString(R.string.sms_config_failed),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
        }
    }

    // --- Sending ---

    private fun submitSend() {
        if (sending) return
        val text = binding.etMessage.text.toString().trim()
        val image = imagePrepared
        val gif = gifUrl
        if (text.isEmpty() && image == null && gif == null) {
            Toast.makeText(this, getString(R.string.sms_text_required), Toast.LENGTH_LONG).show()
            return
        }
        val url = prefs.serverUrl
        val token = prefs.token ?: return
        sending = true
        binding.btnSendSms.isEnabled = false
        lifecycleScope.launch {
            val result =
                apiClient.sendSms(
                    url,
                    token,
                    openNumberId,
                    openPeer,
                    text,
                    if (image != null) listOf(image.file) else emptyList(),
                    replyTo = replyToId,
                    gifUrl = gif,
                )
            if (result.isSuccess) {
                clearComposer()
                loadThread()
            } else {
                val e = result.exceptionOrNull()
                // The send call can time out while the server still completes the
                // send (slow provider call, MMS upload). Re-fetch the thread: if
                // our message is now there, it went through — do not report it
                // as failed.
                if (e != null && isTimeout(e) && confirmSentAfterTimeout(text)) {
                    clearComposer()
                    Toast.makeText(this@SmsActivity, getString(R.string.sms_sent_ok), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@SmsActivity, e?.message, Toast.LENGTH_LONG).show()
                }
            }
            sending = false
            binding.btnSendSms.isEnabled = true
        }
    }

    private fun clearComposer() {
        binding.etMessage.text?.clear()
        imagePrepared?.cleanup()
        imagePrepared = null
        binding.tvAttachName.visibility = View.GONE
        clearReply()
        clearGif()
    }

    // --- Reply ---

    private fun startReply(message: SmsMessage) {
        replyToId = message.id
        val who = if (message.direction == "out") getString(R.string.sms_you) else openName.ifBlank { openPeer }
        val snippet = message.body.orEmpty().ifBlank {
            if (!message.media.isNullOrEmpty()) getString(R.string.sms_quote_picture) else ""
        }
        binding.tvReplyPreview.text = getString(R.string.sms_replying_to, who) + "\n" + snippet
        binding.replyBar.visibility = View.VISIBLE
        binding.etMessage.requestFocus()
    }

    private fun clearReply() {
        replyToId = 0
        binding.replyBar.visibility = View.GONE
    }

    // --- Message actions (long press): reply, copy, delete ---

    private fun showMessageActions(message: SmsMessage) {
        val labels = mutableListOf(getString(R.string.sms_reply))
        val hasText = !message.body.isNullOrBlank()
        if (hasText) labels.add(getString(R.string.sms_copy))
        labels.add(getString(R.string.sms_delete))
        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    getString(R.string.sms_reply) -> startReply(message)
                    getString(R.string.sms_copy) -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("sms", message.body.orEmpty()))
                        Toast.makeText(this, getString(R.string.sms_copied), Toast.LENGTH_SHORT).show()
                    }
                    getString(R.string.sms_delete) -> confirmDeleteMessage(message)
                }
            }
            .show()
    }

    private fun confirmDeleteMessage(message: SmsMessage) {
        AlertDialog.Builder(this)
            .setTitle(R.string.sms_delete_message_title)
            .setMessage(R.string.sms_delete_message_body)
            .setNegativeButton(R.string.sms_cancel, null)
            .setPositiveButton(R.string.sms_delete) { _, _ ->
                val token = prefs.token ?: return@setPositiveButton
                lifecycleScope.launch {
                    apiClient
                        .deleteSmsMessage(prefs.serverUrl, token, openNumberId, message.id)
                        .onSuccess {
                            if (replyToId == message.id) clearReply()
                            loadThread()
                        }
                        .onFailure {
                            Toast.makeText(this@SmsActivity, getString(R.string.sms_delete_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                }
            }
            .show()
    }

    private fun confirmDeleteThread(numberId: Int, peer: String) {
        if (numberId <= 0 || peer.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.sms_delete_thread_title)
            .setMessage(R.string.sms_delete_thread_body)
            .setNegativeButton(R.string.sms_cancel, null)
            .setPositiveButton(R.string.sms_delete) { _, _ ->
                val token = prefs.token ?: return@setPositiveButton
                lifecycleScope.launch {
                    apiClient
                        .deleteSmsThread(prefs.serverUrl, token, numberId, peer)
                        .onSuccess {
                            Toast.makeText(this@SmsActivity, getString(R.string.sms_deleted), Toast.LENGTH_SHORT).show()
                            if (screen == Screen.THREAD) showList() else loadThreads()
                        }
                        .onFailure {
                            Toast.makeText(this@SmsActivity, getString(R.string.sms_delete_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                }
            }
            .show()
    }

    // --- Emoji ---

    private val emojis =
        ("😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 😉 😍 🥰 😘 😋 😜 🤪 😎 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😫 " +
            "😩 🥺 😢 😭 😤 😠 😡 🤯 😳 😱 😨 😰 🤗 🤔 🤭 🤫 😶 😐 😑 😬 🙄 😯 😴 🤤 😷 🤒 🤕 🤢 🤮 🥴 " +
            "😵 🤠 👍 👎 👌 ✌️ 🤞 🤟 🤘 👏 🙌 👐 🙏 💪 👋 👀 ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💯 🔥 ✨ ⭐ 🎉 " +
            "🎊 🎁 🎂 ☕ 🍕 🍔 🍺 🍷 ⚽ 🏀 🚗 ✈️ 🏠 📞 📱 💻 📷 🎵 ✅ ❌ ⚠️ ❓ ❗ 💤 🌞 🌙 🌧️ ❄️ 🐶 🐱")
            .split(" ")

    private fun showEmojiPicker() {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SmsActivity, 7)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val dialog = AlertDialog.Builder(this).setView(grid).create()
        grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, dp(6))
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            override fun getItemCount() = emojis.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val tv = holder.itemView as TextView
                tv.text = emojis[position]
                tv.setOnClickListener {
                    insertIntoMessage(emojis[position])
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun insertIntoMessage(str: String) {
        val et = binding.etMessage
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(0)
        et.text.replace(minOf(start, end), maxOf(start, end), str)
        et.requestFocus()
    }

    // --- GIF (search through the server's GIPHY key) ---

    private fun showGifPicker() {
        val token = prefs.token ?: return
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val gifs = mutableListOf<SmsGif>()
        val search = EditText(this).apply {
            hint = getString(R.string.sms_gif_search_hint)
            setSingleLine()
        }
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SmsActivity, 3)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
        }
        val powered = TextView(this).apply {
            text = getString(R.string.sms_gif_powered)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
            addView(search)
            addView(grid)
            addView(powered)
        }
        val dialog = AlertDialog.Builder(this).setView(box).setNegativeButton(R.string.sms_cancel, null).create()
        grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val iv = ImageView(parent.context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    }
                }
                return object : RecyclerView.ViewHolder(iv) {}
            }
            override fun getItemCount() = gifs.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val g = gifs[position]
                val iv = holder.itemView as ImageView
                SimpleImageLoader.load(g.preview.orEmpty(), iv, maxDim = 300)
                iv.setOnClickListener {
                    pickGif(g)
                    dialog.dismiss()
                }
            }
        }
        var searchJob: Job? = null
        fun searchGifs(query: String) {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(350)
                apiClient
                    .searchSmsGifs(prefs.serverUrl, token, query)
                    .onSuccess { res ->
                        gifs.clear()
                        gifs.addAll(res.gifs.orEmpty().filter { !it.url.isNullOrBlank() })
                        grid.adapter?.notifyDataSetChanged()
                    }
                    .onFailure {
                        Toast.makeText(this@SmsActivity, getString(R.string.sms_gif_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                    }
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = searchGifs(s?.toString().orEmpty())
        })
        dialog.setOnDismissListener { searchJob?.cancel() }
        dialog.show()
        searchGifs("")
    }

    private fun pickGif(g: SmsGif) {
        gifUrl = g.url
        SimpleImageLoader.load(g.preview.orEmpty(), binding.ivGifPreview, maxDim = 200)
        binding.gifChip.visibility = View.VISIBLE
    }

    private fun clearGif() {
        gifUrl = null
        binding.gifChip.visibility = View.GONE
    }

    /** True when the failure is a timeout — the send may have completed anyway. */
    private fun isTimeout(e: Throwable): Boolean =
        e is java.net.SocketTimeoutException ||
            e.message?.contains("timed out", ignoreCase = true) == true ||
            e.message?.contains("timeout", ignoreCase = true) == true

    /**
     * Re-fetch the open thread and check whether the just-sent message is now
     * there. Matches an outgoing message with the same text (or, for a
     * picture-only MMS, an outgoing message carrying media) that the server
     * did not mark failed.
     */
    private suspend fun confirmSentAfterTimeout(text: String): Boolean {
        val url = prefs.serverUrl
        val token = prefs.token ?: return false
        val res = apiClient.getSmsThread(url, token, openNumberId, openPeer).getOrNull()
            ?: return false
        val found =
            res.messages.orEmpty().any { msg ->
                msg.direction == "out" && msg.status != "failed" &&
                    if (text.isNotEmpty()) msg.body == text else !msg.media.isNullOrEmpty()
            }
        if (found) loadThread()
        return found
    }

    private fun submitNew() {
        if (sending) return
        val peer = binding.etPeer.text.toString().trim()
        if (peer.isEmpty()) {
            Toast.makeText(this, getString(R.string.sms_peer_required), Toast.LENGTH_LONG).show()
            return
        }
        val text = binding.etNewMessage.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, getString(R.string.sms_text_required), Toast.LENGTH_LONG).show()
            return
        }
        if (numbers.isEmpty()) {
            Toast.makeText(this, getString(R.string.sms_no_numbers), Toast.LENGTH_LONG).show()
            return
        }
        val number = numbers.getOrNull(binding.spinnerSender.selectedItemPosition) ?: return
        val url = prefs.serverUrl
        val token = prefs.token ?: return
        sending = true
        binding.btnSendNew.isEnabled = false
        binding.btnSendNew.setText(R.string.sms_sending)
        lifecycleScope.launch {
            val result = apiClient.sendSms(url, token, number.id, peer, text)
            if (result.isSuccess) {
                Toast.makeText(
                        this@SmsActivity,
                        getString(R.string.sms_sent_ok),
                        Toast.LENGTH_LONG,
                    )
                    .show()
                showThread(
                    SmsThread(
                        numberId = number.id,
                        number = number.number,
                        peer = peer,
                    )
                )
            } else {
                val e = result.exceptionOrNull()
                // Same timeout ambiguity as the thread composer: the send may
                // have completed server-side. Check the conversation list for
                // a matching outgoing message before reporting a failure.
                if (e != null && isTimeout(e) && confirmNewSentAfterTimeout(peer, number.id, text)) {
                    Toast.makeText(
                            this@SmsActivity,
                            getString(R.string.sms_sent_ok),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    showThread(
                        SmsThread(
                            numberId = number.id,
                            number = number.number,
                            peer = peer,
                        )
                    )
                } else {
                    Toast.makeText(this@SmsActivity, e?.message, Toast.LENGTH_LONG).show()
                }
            }
            sending = false
            binding.btnSendNew.isEnabled = true
            binding.btnSendNew.setText(R.string.sms_send)
        }
    }

    /**
     * Re-fetch the conversation list and check whether a thread with this peer
     * now ends in our outgoing message. The list preview is the newest message,
     * so a match means the timed-out send went through.
     */
    private suspend fun confirmNewSentAfterTimeout(peer: String, numberId: Int, text: String): Boolean {
        val url = prefs.serverUrl
        val token = prefs.token ?: return false
        val res = apiClient.getSmsThreads(url, token).getOrNull() ?: return false
        return res.threads.orEmpty().any { thread ->
            thread.peer == peer && thread.numberId == numberId &&
                thread.direction == "out" && thread.body == text
        }
    }

    // --- Helpers ---

    private fun displayName(thread: SmsThread): String {
        val name = thread.name.orEmpty().trim()
        return if (name.isNotEmpty()) name else thread.peer.orEmpty()
    }

    private fun formatTime(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val instant = OffsetDateTime.parse(iso).toInstant()
            DateUtils.getRelativeTimeSpanString(
                    instant.toEpochMilli(),
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                )
                .toString()
        } catch (e: Exception) {
            iso
        }
    }

    // --- Adapters ---

    private inner class ThreadAdapter : RecyclerView.Adapter<ThreadAdapter.Holder>() {
        inner class Holder(val b: ItemSmsThreadBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemSmsThreadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(b)
        }

        override fun getItemCount() = threads.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val thread = threads[position]
            val b = holder.b
            b.tvPeer.text = displayName(thread)
            b.tvTime.text = formatTime(thread.createdAt)
            val preview = thread.body.orEmpty()
            b.tvPreview.text = if (thread.direction == "out") "You: $preview" else preview
            if (thread.unread > 0) {
                b.tvUnread.text = thread.unread.toString()
                b.tvUnread.visibility = View.VISIBLE
            } else {
                b.tvUnread.visibility = View.GONE
            }
            b.root.setOnClickListener { showThread(thread) }
            b.root.setOnLongClickListener {
                confirmDeleteThread(thread.numberId, thread.peer.orEmpty())
                true
            }
        }
    }

    private inner class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {
        inner class Holder(val b: ItemSmsMessageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b =
                ItemSmsMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(b)
        }

        override fun getItemCount() = messages.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val message = messages[position]
            val b = holder.b
            val outgoing = message.direction == "out"
            val params = b.bubble.layoutParams as ViewGroup.MarginLayoutParams
            if (outgoing) {
                b.bubble.setBackgroundResource(R.drawable.bg_chat_bubble_me)
                params.marginStart = 64
                params.marginEnd = 0
                b.root.gravity = Gravity.END
            } else {
                b.bubble.setBackgroundResource(R.drawable.bg_chat_bubble_other)
                params.marginStart = 0
                params.marginEnd = 64
                b.root.gravity = Gravity.START
            }
            b.bubble.layoutParams = params
            b.tvBody.text = message.body.orEmpty()
            b.tvBody.visibility = if (message.body.isNullOrBlank()) View.GONE else View.VISIBLE
            val quote = message.replyTo
            if (quote != null) {
                b.tvQuote.text =
                    when {
                        quote.deleted -> getString(R.string.sms_quote_deleted)
                        else -> {
                            val body = quote.body.orEmpty().ifBlank {
                                if (quote.media > 0) getString(R.string.sms_quote_picture) else ""
                            }
                            if (quote.direction == "out") getString(R.string.sms_you) + ": " + body else body
                        }
                    }
                b.tvQuote.visibility = View.VISIBLE
            } else {
                b.tvQuote.visibility = View.GONE
            }
            b.bubble.setOnLongClickListener {
                showMessageActions(message)
                true
            }
            val media = message.media.orEmpty()
            if (media.isNotEmpty()) {
                b.tvMedia.text =
                    if (media.size == 1) {
                        getString(R.string.sms_picture, media[0].name.orEmpty())
                    } else {
                        getString(R.string.sms_media_count, media.size)
                    }
                b.tvMedia.visibility = View.VISIBLE
                b.tvMedia.setOnClickListener {
                    val url = media[0].url.orEmpty()
                    if (url.isNotEmpty()) {
                        val base = prefs.serverUrl.trim().trimEnd('/')
                        val full = if (url.startsWith("http")) url else base + url
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)))
                        } catch (e: Exception) {
                            Toast.makeText(
                                    this@SmsActivity,
                                    getString(R.string.fax_open_failed),
                                    Toast.LENGTH_LONG,
                                )
                                .show()
                        }
                    }
                }
            } else {
                b.tvMedia.visibility = View.GONE
            }
            val meta = StringBuilder(formatTime(message.createdAt))
            if (outgoing) {
                when (message.status) {
                    "failed" -> meta.append(" · ").append(getString(R.string.sms_status_failed))
                    "sending" -> meta.append(" · ").append(getString(R.string.sms_status_sending))
                }
            }
            b.tvMeta.text = meta.toString()
            val error = message.error.orEmpty()
            if (outgoing && message.status == "failed" && error.isNotEmpty()) {
                b.tvError.text = error
                b.tvError.visibility = View.VISIBLE
            } else {
                b.tvError.visibility = View.GONE
            }
        }
    }
}
