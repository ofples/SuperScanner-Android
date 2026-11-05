package tools.superscanner.clipboardhelper

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.superscanner.clipboard.ClipboardPairInfo

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var error: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var qrImage: ImageView
    private lateinit var pairCode: TextView
    private lateinit var copyPairCodeButton: Button
    private lateinit var pendingPanel: LinearLayout
    private lateinit var pendingShortCode: TextView
    private lateinit var approvedPanel: LinearLayout
    private lateinit var approvedIdentity: TextView
    private lateinit var outgoingText: EditText
    private lateinit var snapshotStatus: TextView
    private lateinit var incomingPanel: LinearLayout
    private lateinit var incomingText: TextView
    private lateinit var copyIncomingButton: Button

    private var helper: ClipboardHelperService? = null
    private var bound = false
    private var displayedQrCode: String? = null
    private var qrJob: Job? = null
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            helper = (binder as? ClipboardHelperService.LocalBinder)?.service
            helper?.setObserver(::render)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            helper?.setObserver(null)
            helper = null
            render(HelperUiState())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        applyWindowStyle()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, ClipboardHelperService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) {
            helper?.setObserver(null)
            unbindService(connection)
            bound = false
            helper = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        qrJob?.cancel()
        activityScope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        root = column()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("Supernote Clipboard Helper", 26, bold = true))
        root.addView(label("Experimental text transfer. Not verified with Supernote Partner or native Notes clipboard.", 16))
        status = label("Session stopped", 18, bold = true).also(root::addView)

        val actions = row().also(root::addView)
        startButton = button("Start session") { startSessionFromUi() }.also(actions::addView)
        stopButton = button("Stop session") { stopSessionFromUi() }.also(actions::addView)

        root.addView(label("Pair a device", 22, bold = true))
        root.addView(label("Scan this code on the other device. It contains the local address and public server certificate pin only.", 16))
        qrImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Clipboard helper pairing QR code"
            setBackgroundColor(Color.WHITE)
        }
        root.addView(qrImage, LinearLayout.LayoutParams(dp(320), dp(320)).apply { gravity = Gravity.CENTER })
        pairCode = label("Start a session to show the manual pairing code.", 14).apply { setTextIsSelectable(true) }.also(root::addView)
        copyPairCodeButton = button("Copy pairing code") { copyPairCode() }.also(root::addView)

        root.addView(label("Confirm device pairing", 22, bold = true))
        pendingPanel = column().also(root::addView)
        pendingShortCode = label("", 22, bold = true).also(pendingPanel::addView)
        pendingPanel.addView(label("Compare this 12-character code with the other device before approving.", 16))
        val pairActions = row().also(pendingPanel::addView)
        pairActions.addView(button("Approve") { helper?.approvePairing() })
        pairActions.addView(button("Reject") { helper?.rejectPairing() })

        approvedPanel = column().also(root::addView)
        approvedIdentity = label("", 16).also(approvedPanel::addView)
        approvedPanel.addView(button("Unpair now") { helper?.unpair() })

        root.addView(label("Send text to the paired device", 22, bold = true))
        outgoingText = EditText(this).apply {
            hint = "Enter text, or read this device's clipboard with the button below"
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            setTextColor(Color.BLACK)
            setHintTextColor(0xff444444.toInt())
            textSize = 18f
            setBackgroundColor(0xfff0f0f0.toInt())
        }
        root.addView(outgoingText, fullWidth())
        root.addView(button("Read this device's clipboard") { readClipboardExplicitly() })
        val sendActions = row().also(root::addView)
        sendActions.addView(button("Publish snapshot") { publishSnapshot() })
        sendActions.addView(button("Clear snapshot") { helper?.clearSnapshot() })
        snapshotStatus = label("No outgoing text published.", 16).also(root::addView)

        root.addView(label("Received text", 22, bold = true))
        incomingPanel = column().also(root::addView)
        incomingText = label("", 18).apply { setTextIsSelectable(true) }.also(incomingPanel::addView)
        incomingText.maxLines = 10
        val receiveActions = row().also(incomingPanel::addView)
        copyIncomingButton = button("Copy received text") { copyIncomingExplicitly() }.also(receiveActions::addView)
        receiveActions.addView(button("Clear inbox") { helper?.clearIncoming() })
        error = label("", 16, bold = true).apply { setTextColor(0xff8b0000.toInt()) }.also(root::addView)

        render(HelperUiState())
    }

    private fun render(value: HelperUiState) {
        if (!::status.isInitialized) return
        status.text = when {
            value.starting -> "Starting a protected local session…"
            value.running -> "Session active${if (value.approvedFingerprint == null) " · waiting for pairing" else " · paired"}"
            else -> "Session stopped"
        }
        startButton.visibility = if (value.running || value.starting) View.GONE else View.VISIBLE
        stopButton.visibility = if (value.running || value.starting) View.VISIBLE else View.GONE
        qrImage.visibility = if (value.pairInfo == null) View.GONE else View.VISIBLE
        pairCode.visibility = if (value.pairInfo == null) View.GONE else View.VISIBLE
        copyPairCodeButton.visibility = if (value.pairInfo == null) View.GONE else View.VISIBLE
        value.pairInfo?.let(::showPairInfo)

        pendingPanel.visibility = if (value.pendingFingerprint != null) View.VISIBLE else View.GONE
        pendingShortCode.text = value.pendingShortCode ?: ""
        approvedPanel.visibility = if (value.approvedFingerprint != null) View.VISIBLE else View.GONE
        approvedIdentity.text = value.approvedFingerprint?.let { fingerprint ->
            value.pairInfo?.approvalCode(fingerprint)?.let { "Paired phone · comparison code: $it" }
                ?: "Paired phone"
        }.orEmpty()

        snapshotStatus.text = if (value.snapshotPublished) "Outgoing snapshot is available to the paired device." else "No outgoing text published."
        incomingPanel.visibility = if (value.incomingText != null) View.VISIBLE else View.GONE
        incomingText.text = value.incomingText?.let { if (it.length > MAX_PREVIEW_CHARS) it.take(MAX_PREVIEW_CHARS) + "\n… preview shortened" else it }.orEmpty()
        copyIncomingButton.isEnabled = value.incomingText != null
        error.text = value.error.orEmpty()
    }

    private fun showPairInfo(info: ClipboardPairInfo) {
        val code = PairQr.encode(info)
        pairCode.text = "Manual pairing code:\n$code\n\nAddress: ${info.host}:${info.port}\nServer SHA-256 pin: ${info.serverFingerprint.uppercase()}"
        if (displayedQrCode == code) return
        displayedQrCode = code
        qrJob?.cancel()
        qrJob = activityScope.launch {
            val bitmap = runCatching { withContext(Dispatchers.Default) { PairQr.render(info) } }.getOrNull()
            if (displayedQrCode == code && bitmap != null) qrImage.setImageBitmap(bitmap)
        }
    }

    private fun startSessionFromUi() {
        clearError()
        try {
            val intent = Intent(this, ClipboardHelperService::class.java).setAction(ClipboardHelperService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) {
            error.text = "Android could not start the session. Keep this app open and try again."
        }
    }

    private fun stopSessionFromUi() {
        helper?.stopSession()
        if (helper == null) stopService(Intent(this, ClipboardHelperService::class.java))
    }

    private fun publishSnapshot() {
        val text = outgoingText.text.toString()
        if (text.isBlank()) {
            error.text = "Enter or read text before publishing a snapshot."
            return
        }
        if (helper?.state()?.approvedFingerprint == null) {
            error.text = "Pair a device before publishing text."
            return
        }
        helper?.publishSnapshot(text) ?: run { error.text = "Start the local session first." }
    }

    /** Clipboard is read only from this foreground Activity after an explicit user tap. */
    private fun readClipboardExplicitly() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            error.text = "This device's clipboard has no text."
            return
        }
        outgoingText.setText(clip.getItemAt(0).coerceToText(this)?.toString().orEmpty())
        clearError()
    }

    /** Clipboard is written only from this foreground Activity after an explicit user tap. */
    private fun copyIncomingExplicitly() {
        val text = helper?.state()?.incomingText ?: return
        putClipboard(text)
        helper?.clearIncoming()
        clearError()
    }

    private fun copyPairCode() {
        val info = helper?.state()?.pairInfo ?: return
        putClipboard(PairQr.encode(info))
        clearError()
    }

    private fun putClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }

    private fun clearError() { error.text = "" }

    private fun applyWindowStyle() {
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(32))
        setBackgroundColor(Color.WHITE)
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun label(text: String, size: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(Color.BLACK)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(10))
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.BLACK)
        setOnClickListener { action() }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val CLIP_LABEL = "Supernote Clipboard Helper"
        private const val MAX_PREVIEW_CHARS = 12_000
    }
}
