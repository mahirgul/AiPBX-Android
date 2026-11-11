package com.mhrgl.aipbx.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mhrgl.aipbx.R
import com.mhrgl.aipbx.data.ApiClient
import com.mhrgl.aipbx.data.AppPreferences
import com.mhrgl.aipbx.data.ChatUploadPrep
import com.mhrgl.aipbx.databinding.ActivitySmsBinding
import com.mhrgl.aipbx.databinding.ItemSmsMessageBinding
import com.mhrgl.aipbx.databinding.ItemSmsThreadBinding
import com.mhrgl.aipbx.model.SmsMessage
import com.mhrgl.aipbx.model.SmsNumber
import com.mhrgl.aipbx.model.SmsThread
import java.time.OffsetDateTime
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
        binding.btnSendSms.setOnClickListener { submitSend() }
        binding.btnSendNew.setOnClickListener { submitNew() }
        loadThreads()
    }

    // --- Screens ---

    private fun showList() {
        screen = Screen.LIST
        binding.frameThread.visibility = View.GONE
        binding.scrollNew.visibility = View.GONE
        binding.frameList.visibility = View.VISIBLE
        binding.btnNew.visibility = View.VISIBLE
        binding.btnRefresh.visibility = View.VISIBLE
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
        binding.tvTitle.text = displayName(thread)
        imagePrepared?.cleanup()
        imagePrepared = null
        binding.tvAttachName.visibility = View.GONE
        loadThread()
    }

    private fun showNew() {
        screen = Screen.NEW
        binding.frameList.visibility = View.GONE
        binding.frameThread.visibility = View.GONE
        binding.scrollNew.visibility = View.VISIBLE
        binding.btnNew.visibility = View.GONE
        binding.btnRefresh.visibility = View.GONE
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

    private fun loadConfig() {
        val url = prefs.serverUrl
        val token = prefs.token ?: return
        lifecycleScope.launch {
            apiClient
                .getSmsConfig(url, token)
                .onSuccess { res ->
                    numbers = res.numbers.orEmpty()
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
                    if (numbers.isEmpty()) {
                        Toast.makeText(
                                this@SmsActivity,
                                getString(R.string.sms_no_numbers),
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                }
                .onFailure {
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
        if (text.isEmpty() && image == null) {
            Toast.makeText(this, getString(R.string.sms_text_required), Toast.LENGTH_LONG).show()
            return
        }
        val url = prefs.serverUrl
        val token = prefs.token ?: return
        sending = true
        binding.btnSendSms.isEnabled = false
        lifecycleScope.launch {
            apiClient
                .sendSms(
                    url,
                    token,
                    openNumberId,
                    openPeer,
                    text,
                    if (image != null) listOf(image.file) else emptyList(),
                )
                .onSuccess {
                    binding.etMessage.text?.clear()
                    imagePrepared?.cleanup()
                    imagePrepared = null
                    binding.tvAttachName.visibility = View.GONE
                    loadThread()
                }
                .onFailure {
                    Toast.makeText(this@SmsActivity, it.message, Toast.LENGTH_LONG).show()
                }
            sending = false
            binding.btnSendSms.isEnabled = true
        }
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
            apiClient
                .sendSms(url, token, number.id, peer, text)
                .onSuccess {
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
                }
                .onFailure {
                    Toast.makeText(this@SmsActivity, it.message, Toast.LENGTH_LONG).show()
                }
            sending = false
            binding.btnSendNew.isEnabled = true
            binding.btnSendNew.setText(R.string.sms_send)
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
