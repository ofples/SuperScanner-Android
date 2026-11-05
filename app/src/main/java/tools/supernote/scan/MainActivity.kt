package tools.supernote.scan

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    private val appViewModel by viewModels<AppViewModel>()
    private val nativeLinkViewModel by viewModels<NativeLinkViewModel> { NativeLinkViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScannerApp(
                appViewModel,
                nativeLinkVm = nativeLinkViewModel,
                openSample = { startActivity(Intent(this, SampleScanActivity::class.java)) },
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appViewModel.browser.collect { uri ->
                    try {
                        SignInBrowser.launch(this@MainActivity, uri) { appViewModel.ui.value.isSigningIn }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        appViewModel.failBrowserLaunch(error)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appViewModel.resumeMirror()
        if (appViewModel.ui.value.screen == AppScreen.CLIPBOARD) nativeLinkViewModel.enterScreen()
    }

    override fun onStop() {
        nativeLinkViewModel.onBackground()
        appViewModel.pauseMirror()
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScannerApp(
    vm: AppViewModel,
    clipboardVm: ClipboardViewModel? = null,
    nativeLinkVm: NativeLinkViewModel? = null,
    openSample: (() -> Unit)? = null,
    exitSample: (() -> Unit)? = null,
    nextSamplePage: (() -> Unit)? = null,
) {
    val state by vm.ui.collectAsState()
    val context = LocalContext.current
    val sampleMode = vm.isSample || exitSample != null
    val coroutineScope = rememberCoroutineScope()
    var showPrivacy by remember { mutableStateOf(false) }
    var pendingGalleryPageId by remember { mutableStateOf<String?>(null) }
    var sharingPageId by remember { mutableStateOf<String?>(null) }
    var pageShareError by remember { mutableStateOf("") }
    var pageShareJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pageShareGeneration by remember { mutableStateOf(0) }
    LaunchedEffect(state.screen, state.selectedPreviewId) {
        if (state.screen != AppScreen.PREVIEW || state.selectedPreviewId != sharingPageId) {
            pageShareGeneration++
            pageShareJob?.cancel()
            pageShareJob = null
            sharingPageId = null
        }
    }
    val shareOriginal: (String) -> Unit = { pageId ->
        val page = state.pages.firstOrNull { it.id == pageId }
        if (page != null && sharingPageId == null) {
            sharingPageId = pageId
            pageShareError = ""
            val requestGeneration = ++pageShareGeneration
            pageShareJob = coroutineScope.launch {
                try {
                    CaptureSharer.share(context, page, canLaunch = {
                        requestGeneration == pageShareGeneration &&
                            vm.ui.value.screen == AppScreen.PREVIEW && vm.ui.value.selectedPreviewId == pageId
                    })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (vm.ui.value.screen == AppScreen.PREVIEW && vm.ui.value.selectedPreviewId == pageId)
                        pageShareError = error.message ?: "Could not prepare the original image for sharing."
                } finally {
                    if (requestGeneration == pageShareGeneration) {
                        if (sharingPageId == pageId) sharingPageId = null
                        pageShareJob = null
                    }
                }
            }
        }
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pageId = pendingGalleryPageId
        pendingGalleryPageId = null
        if (pageId != null) {
            if (granted) vm.savePageToGallery(pageId) else vm.galleryPermissionDenied(pageId)
        }
    }
    val requestGallerySave: (String) -> Unit = { pageId ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            vm.savePageToGallery(pageId)
        } else {
            pendingGalleryPageId = pageId
            galleryPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    val leaveClipboard: () -> Unit = {
        clipboardVm?.cancelOperation()
        nativeLinkVm?.leaveScreen()
        vm.closeClipboard()
    }
    BackHandler(enabled = state.screen != AppScreen.SCAN) {
        when (state.screen) {
            AppScreen.RESULT -> vm.openScanFromResult()
            AppScreen.SETTINGS -> vm.cancelSettings()
            AppScreen.PREVIEW -> vm.closePreview()
            AppScreen.CLIPBOARD -> leaveClipboard()
            AppScreen.SCAN -> Unit
        }
    }
    BackHandler(enabled = sampleMode && state.screen == AppScreen.SCAN) { exitSample?.invoke() }

    SupernoteTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(when (state.screen) {
                        AppScreen.SCAN -> if (sampleMode) "Sample scan" else "SuperScanner"
                        AppScreen.RESULT -> "Recognized text"
                        AppScreen.SETTINGS -> "Settings"
                        AppScreen.PREVIEW -> "Page ${state.pages.indexOfFirst { it.id == state.selectedPreviewId } + 1}"
                        AppScreen.CLIPBOARD -> if (nativeLinkVm != null) "Supernote Linking" else "Clipboard"
                    }) },
                    actions = {
                        if (sampleMode) {
                            TextButton(onClick = { exitSample?.invoke() },
                                modifier = Modifier.semantics { contentDescription = "Exit sample scan" }.testTag("exit_sample_scan")) {
                                Text("Exit sample")
                            }
                        } else if (state.screen == AppScreen.SCAN) {
                            TextButton(onClick = vm::openSettings,
                                modifier = Modifier.semantics { contentDescription = "Open settings" }.testTag("open_settings")) {
                                Text("Settings")
                            }
                        }
                        if (!sampleMode && state.screen in setOf(AppScreen.SCAN, AppScreen.RESULT, AppScreen.SETTINGS)) {
                            TextButton(onClick = {
                                vm.openClipboard()
                                nativeLinkVm?.enterScreen()
                            },
                                modifier = Modifier.semantics { contentDescription = if (nativeLinkVm != null) "Open Supernote Linking" else "Open clipboard bridge" }
                                    .testTag(if (nativeLinkVm != null) "open_native_link" else "open_clipboard")) {
                                Text(if (nativeLinkVm != null) "Supernote Link" else "Clipboard")
                            }
                        }
                        if (state.screen == AppScreen.CLIPBOARD) {
                            TextButton(onClick = leaveClipboard,
                                modifier = Modifier.semantics { contentDescription = if (nativeLinkVm != null) "Back from Supernote Linking" else "Back from clipboard" }
                                    .testTag("clipboard_back")) {
                                Text("Back")
                            }
                        }
                    }
                )
            }
        ) { insets ->
            Surface(Modifier.fillMaxSize().padding(insets), color = MaterialTheme.colorScheme.background) {
                when (state.screen) {
                    AppScreen.SCAN -> ScanScreen(state, vm, openSample, sampleMode, nextSamplePage)
                    AppScreen.RESULT -> ResultScreen(state, vm, context)
                    AppScreen.SETTINGS -> SettingsScreen(state, vm, onPrivacyClick = { showPrivacy = true })
                    AppScreen.PREVIEW -> PagePreview(state, vm, requestGallerySave, shareOriginal,
                        sharingPageId != null, pageShareError)
                    AppScreen.CLIPBOARD -> if (nativeLinkVm != null) {
                        NativeLinkScreen(nativeLinkVm)
                    } else if (clipboardVm != null) {
                        ClipboardScreen(clipboardVm, context, state.resultText) {
                            vm.decodeClipboardPairingCode(clipboardVm::acceptScannedPairingCode)
                        }
                    } else {
                        Text("Clipboard bridge is unavailable.")
                    }
                }
            }
        }

    state.showDiscardDialog.let { show ->
        if (show) AlertDialog(
            onDismissRequest = { if (state.storageError.isNotBlank()) vm.cancelNewScan() else vm.cancelClearPages() },
            title = { Text(if (state.storageError.isNotBlank()) "Discard damaged scan?" else "Clear captured pages?") },
            text = { Text(if (state.storageError.isNotBlank())
                "The saved session could not be read. Starting fresh clears saved page files."
            else "This clears the captured images. Your saved recognized text, settings, and account will be kept.") },
            confirmButton = { TextButton(onClick = { if (state.storageError.isNotBlank()) vm.confirmNewScan() else vm.confirmClearPages() }) {
                Text(if (state.storageError.isNotBlank()) "Discard damaged scan and start fresh" else "Clear pages")
            } },
            dismissButton = { TextButton(onClick = { if (state.storageError.isNotBlank()) vm.cancelNewScan() else vm.cancelClearPages() }) { Text("Cancel") } },
        )
    }
    if (state.showRecognitionDisclosure) {
        val billingText = if (state.settings.provider == "chatgpt")
            "Processing may use your ChatGPT plan allowance."
        else "Processing is billed to your OpenAI API account."
        AlertDialog(
            onDismissRequest = vm::dismissRecognitionDisclosure,
            title = { Text("Send pages to OpenAI?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The selected page images and your recognition prompt, glossary, and additional guidance will be sent to OpenAI for transcription. $billingText You can cancel; capture, save, and share remain available.")
                    TextButton(onClick = { showPrivacy = true }, modifier = Modifier.testTag("privacy_from_disclosure")) {
                        Text("Privacy policy")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmRecognitionDisclosure,
                    modifier = Modifier.testTag("recognition_disclosure_continue")) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissRecognitionDisclosure,
                    modifier = Modifier.testTag("recognition_disclosure_cancel")) { Text("Cancel") }
            },
        )
    }
    if (showPrivacy) PrivacyPolicyDialog(onClose = { showPrivacy = false })
    if (state.showPlanWelcome) {
        AlertDialog(
            onDismissRequest = vm::dismissPlanWelcome,
            title = { Text("Using your ChatGPT plan") },
            text = { Text("Eligible recognition requests use your ChatGPT plan or available credits. Manage usage in ChatGPT settings.") },
            confirmButton = { TextButton(onClick = vm::dismissPlanWelcome, modifier = Modifier.testTag("plan_welcome_done")) { Text("Got it") } },
        )
    }
    state.showDeletePageId?.let { id ->
        AlertDialog(
            onDismissRequest = vm::cancelDeletePage,
            title = { Text("Delete page?") },
            text = { Text("Page ${state.pages.indexOfFirst { it.id == id } + 1} will be removed from this scan.") },
            confirmButton = { TextButton(onClick = vm::confirmDeletePage) { Text("Delete") } },
            dismissButton = { TextButton(onClick = vm::cancelDeletePage) { Text("Cancel") } },
        )
    }
    if (state.showDeviceChooser) AlertDialog(
        onDismissRequest = vm::dismissDeviceChooser,
        title = { Text("Choose a Supernote") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.discoveredHosts.forEachIndexed { index, host ->
                    OutlinedButton(onClick = { vm.chooseDiscoveredHost(host) }, modifier = Modifier.fillMaxWidth()
                        .testTag("device_choice_$index")) { Text(host) }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::dismissDeviceChooser) { Text("Cancel") } },
    )
    }
}

@Composable
private fun ScanScreen(
    state: AppUiState,
    vm: AppViewModel,
    openSample: (() -> Unit)?,
    sampleMode: Boolean,
    nextSamplePage: (() -> Unit)?,
) {
    val pageListState = rememberLazyListState()
    val lastSeenCaptureToken = remember { mutableStateOf(state.captureFeedbackToken) }
    val captureFlash = remember { Animatable(0f) }
    var captureFeedback by remember { mutableStateOf("") }
    var draggedPageId by remember { mutableStateOf<String?>(null) }
    var dragPointerX by remember { mutableStateOf(0f) }
    var dragPointerY by remember { mutableStateOf(0f) }
    var dragGrabOffsetY by remember { mutableStateOf(0f) }
    var pendingPageOrder by remember { mutableStateOf<List<String>?>(null) }
    val pageIds = state.pages.map { it.id }
    LaunchedEffect(state.captureFeedbackToken) {
        val token = state.captureFeedbackToken
        if (token <= lastSeenCaptureToken.value) return@LaunchedEffect
        lastSeenCaptureToken.value = token
        captureFeedback = "Page ${state.currentPageCount} saved."
        if (ValueAnimator.areAnimatorsEnabled()) {
            captureFlash.snapTo(0.12f)
            captureFlash.animateTo(0f, animationSpec = tween(durationMillis = 180))
        } else {
            captureFlash.snapTo(0f)
        }
        delay(2_400)
        if (state.captureFeedbackToken == token) captureFeedback = ""
    }
    LaunchedEffect(pendingPageOrder, pageIds, state.isSavingCapture, state.storageError) {
        val pending = pendingPageOrder ?: return@LaunchedEffect
        if (pageIds.toSet() != pending.toSet() || pageIds == pending ||
            (!state.isSavingCapture && !vm.ui.value.isSavingCapture)) {
            pendingPageOrder = null
        }
    }
    val pagesById = state.pages.associateBy { it.id }
    val displayedPages = pendingPageOrder?.mapNotNull(pagesById::get) ?: state.pages
    val density = LocalDensity.current
    val reorderEnabled = !state.isSavingCapture && !state.isSavingGallery && !state.isRecognizing && state.storageError.isBlank()

    LaunchedEffect(draggedPageId, reorderEnabled) {
        if (draggedPageId == null || !reorderEnabled) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        while (draggedPageId != null) {
            val layout = pageListState.layoutInfo
            val direction = when {
                dragPointerX < layout.viewportStartOffset + edge -> -1f
                dragPointerX > layout.viewportEndOffset - edge -> 1f
                else -> 0f
            }
            if (direction != 0f) pageListState.scrollBy(direction * 18f)
            delay(16)
        }
    }

    fun finishPageDrag(pageId: String) {
        if (draggedPageId != pageId) return
        val pages = vm.ui.value.pages
        val currentIndex = pages.indexOfFirst { it.id == pageId }
        if (currentIndex >= 0 && reorderEnabled) {
            val others = pageListState.layoutInfo.visibleItemsInfo
                .filter { item -> item.index in pages.indices && pages[item.index].id != pageId }
                .sortedBy { it.index }
            val before = others.lastOrNull { item -> item.offset + item.size / 2f < dragPointerX }
            fun normalizedIndex(index: Int) = index - if (index > currentIndex) 1 else 0
            val after = others.firstOrNull { item -> item.offset + item.size / 2f >= dragPointerX }
            val targetIndex = when {
                after != null -> normalizedIndex(after.index)
                before != null -> normalizedIndex(before.index) + 1
                else -> currentIndex
            }.coerceIn(0, pages.lastIndex)
            if (targetIndex != currentIndex) {
                val orderedIds = pages.map { it.id }.toMutableList()
                orderedIds.removeAt(currentIndex)
                orderedIds.add(targetIndex.coerceAtMost(orderedIds.size), pageId)
                if (vm.reorderPages(orderedIds)) pendingPageOrder = orderedIds
            }
        }
        draggedPageId = null
        dragPointerX = 0f
        dragPointerY = 0f
        dragGrabOffsetY = 0f
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = if (state.canSnap)
            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(state.connectionMessage, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { contentDescription = "Mirror connection status: ${state.connectionMessage}" })
                    Text(if (state.canSnap) "Ready to capture" else "Snap is available when the mirror is live.",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = vm::reconnect, enabled = !state.isDiscovering,
                    modifier = Modifier.semantics { contentDescription = "Reconnect to Supernote" }) {
                    if (state.isDiscovering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Reconnect")
                }
            }
        }

        Box(Modifier.fillMaxWidth().heightIn(min = 250.dp, max = 430.dp)
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = "Live Supernote screen preview" }, contentAlignment = Alignment.Center) {
            state.livePreview?.let { bitmap ->
                Image(bitmap.asImageBitmap(), contentDescription = "Latest mirror frame",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp), contentScale = ContentScale.Fit)
            } ?: Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Waiting for Supernote", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Keep Screen Mirroring open, then reconnect.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (captureFlash.value > 0f) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = captureFlash.value))
                    .clearAndSetSemantics { })
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::snap, enabled = state.canSnap,
                modifier = Modifier.weight(1f).height(58.dp).semantics { contentDescription = "Snap current page" }
                    .testTag("snap_button")) {
                Text(if (state.isSavingCapture) "Saving…" else "Snap", style = MaterialTheme.typography.titleMedium)
            }
            val emptyScan = state.pages.isEmpty()
            Button(onClick = { if (emptyScan) vm.snapAndRecognize() else vm.openResult() },
                enabled = (if (emptyScan) state.canSnap else state.pages.isNotEmpty()) &&
                    !state.isSavingCapture && !state.isRecognizing && !state.isSavingGallery && state.storageError.isBlank(),
                modifier = Modifier.weight(1f).height(58.dp).semantics {
                    contentDescription = if (emptyScan) "Snap and recognize current page" else "Recognize captured pages"
                }
                    .testTag("recognize_button")) {
                Text(if (emptyScan) "Snap & recognize" else "Recognize pages", style = MaterialTheme.typography.titleMedium)
            }
        }

        if (sampleMode) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Offline sample", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold)
                        Text("No account or network needed.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (nextSamplePage != null) TextButton(onClick = nextSamplePage, enabled = state.canSnap,
                        modifier = Modifier.testTag("next_sample_page")) { Text("Next sample page") }
                }
            }
        } else if (state.pages.isEmpty() && openSample != null) {
            OutlinedButton(onClick = openSample, modifier = Modifier.fillMaxWidth().height(50.dp)
                .testTag("try_sample_scan")) {
                Text("Try a sample scan")
            }
        }

        if (captureFeedback.isNotBlank()) {
            Text(captureFeedback, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics {
                    contentDescription = captureFeedback
                    liveRegion = LiveRegionMode.Polite
                }.testTag("capture_feedback"))
        }

        if (state.isRecognizing) {
            OutlinedButton(onClick = vm::showExistingResult,
                modifier = Modifier.fillMaxWidth().height(48.dp)
                    .semantics { contentDescription = "View recognition progress" }
                    .testTag("view_recognition_progress")) {
                Text("View progress")
            }
        }

        if (state.resultText.isNotBlank()) {
            OutlinedButton(onClick = vm::showExistingResult, modifier = Modifier.fillMaxWidth()
                .height(50.dp).testTag("view_existing_text")) {
                Text("View saved text")
            }
        }

        if (state.pages.isNotEmpty()) {
            Text("Captured pages  ·  ${state.pages.size}/${SessionStore.MAX_PAGES}", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().height(170.dp)
                .pointerInput(reorderEnabled) {
                    if (reorderEnabled) {
                        awaitEachGesture {
                            // Observe before LazyRow and thumbnail click handlers so they cannot cancel the held drag.
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val pointerId = down.id
                            val start = down.position
                            val longPressReached = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                    if (change == null || !change.pressed) {
                                        return@withTimeoutOrNull false
                                    }
                                    if ((change.position - start).getDistance() > viewConfiguration.touchSlop) {
                                        return@withTimeoutOrNull false
                                    }
                                }
                                false
                            } == null

                            if (!longPressReached) return@awaitEachGesture

                            val item = pageListState.layoutInfo.visibleItemsInfo.firstOrNull {
                                start.x >= it.offset && start.x < it.offset + it.size
                            }
                            val id = item?.key as? String
                            if (id == null || vm.ui.value.pages.none { it.id == id }) {
                                return@awaitEachGesture
                            }

                            draggedPageId = id
                            dragPointerX = start.x
                            dragPointerY = start.y
                            dragGrabOffsetY = start.y
                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                    if (change == null) {
                                        draggedPageId = null
                                        dragPointerX = 0f
                                        dragPointerY = 0f
                                        dragGrabOffsetY = 0f
                                        break
                                    }
                                    if (!change.pressed) {
                                        change.consume()
                                        dragPointerX = change.position.x
                                        dragPointerY = change.position.y
                                        finishPageDrag(id)
                                        break
                                    }
                                    val position = change.position
                                    dragPointerX = position.x
                                    dragPointerY = position.y
                                    change.consume()
                                }
                            } finally {
                                if (draggedPageId == id) {
                                    draggedPageId = null
                                    dragPointerX = 0f
                                    dragPointerY = 0f
                                    dragGrabOffsetY = 0f
                                }
                            }
                        }
                    }
                }
                .semantics {
                    contentDescription = "Captured pages. Long press and drag a thumbnail to reorder."
                }
                .testTag("page_thumbnail_row")) {
                LazyRow(state = pageListState, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(displayedPages, key = { _, page -> page.id }) { index, page ->
                        PageThumbnail(page = page, number = index + 1, bitmap = state.thumbnails[page.id],
                            index = index, allPages = state.pages, vm = vm, canReorder = reorderEnabled,
                            isDragged = draggedPageId == page.id)
                    }
                }
                val ghostPage = draggedPageId?.let { id -> state.pages.firstOrNull { it.id == id } }
                if (ghostPage != null) {
                    val ghostIndex = state.pages.indexOfFirst { it.id == ghostPage.id }
                    val ghostBitmap = state.thumbnails[ghostPage.id]
                    Box(Modifier.offset(
                        x = with(density) { (dragPointerX - 64.dp.toPx()).toDp() },
                        y = with(density) { (dragPointerY - dragGrabOffsetY).toDp() },
                    ).width(128.dp).height(160.dp).zIndex(2f)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(8.dp).clearAndSetSemantics { }) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                ghostBitmap?.let { Image(it.asImageBitmap(), contentDescription = null,
                                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                                    ?: Text("Page ${ghostIndex + 1}")
                            }
                            Text("Page ${ghostIndex + 1}", style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            OutlinedButton(onClick = vm::requestClearPages,
                enabled = !state.isSavingCapture && !state.isRecognizing && !state.isSavingGallery && state.storageError.isBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("clear_pages")) {
                Text("Clear pages")
            }
        } else if (!sampleMode) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text("No pages saved. Snap a live screen to start a scan.",
                    Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.notice.isNotBlank()) {
            Text(state.notice, color = if (state.storageError.isNotBlank()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { contentDescription = state.notice })
        }
        if (state.storageError.isNotBlank()) {
            OutlinedButton(onClick = vm::requestNewScan, modifier = Modifier.fillMaxWidth()) {
                Text("Discard damaged saved scan and start fresh")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PageThumbnail(
    page: CapturedPage,
    number: Int,
    bitmap: android.graphics.Bitmap?,
    index: Int,
    allPages: List<CapturedPage>,
    vm: AppViewModel,
    canReorder: Boolean,
    isDragged: Boolean,
) {
    val id = page.id
    Card(
        Modifier.width(128.dp)
            .graphicsLayer {
                alpha = if (isDragged) 0.24f else 1f
            }
            .semantics {
                contentDescription = "Page $number. Long press and drag to reorder."
                customActions = buildList {
                    if (canReorder && index > 0) add(CustomAccessibilityAction("Move page $number earlier") {
                        vm.movePage(id, -1); true
                    })
                    if (canReorder && index < allPages.lastIndex) add(CustomAccessibilityAction("Move page $number later") {
                        vm.movePage(id, 1); true
                    })
                }
            }
            .testTag("scan_thumbnail_$id"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { vm.openPage(id) }.semantics { contentDescription = "Open page $number preview" },
                contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Page $number thumbnail",
                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: Text("Page $number")
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp)
                        .clickable { vm.askDeletePage(id) }
                        .semantics { contentDescription = "Remove page $number from scan" }
                        .testTag("remove_page_$id"),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("×", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text("Page $number", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ResultScreen(state: AppUiState, vm: AppViewModel, context: Context) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.pages.isNotEmpty()) {
            Text("${state.pages.size} page${if (state.pages.size == 1) "" else "s"} in this scan",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = vm::recognize, enabled = !state.isRecognizing && !state.isSavingCapture && state.storageError.isBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp).testTag("recognize_retry")) {
                Text(if (state.resultText.isNotBlank()) "Recognize again" else if (state.recognitionError.isNotBlank()) "Try recognition again" else "Recognize pages")
            }
            if (state.resultText.isNotBlank()) Text("Recognizing again sends these pages to the selected provider.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (state.isRecognizing) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.testTag("recognition_progress")) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(state.recognitionMessage.ifBlank { "Recognizing pages…" }, Modifier.weight(1f))
                        TextButton(onClick = vm::cancelRecognition,
                            modifier = Modifier.semantics { contentDescription = "Cancel recognition" }) { Text("Cancel") }
                    }
                }
            }
        }
        if (vm.isSample) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text("Sample transcript · no AI request",
                    modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("sample_transcript_label"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        if (state.recognitionError.isNotBlank())
            Text(state.recognitionError, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "Recognition error: ${state.recognitionError}" })
        if (state.settings.provider == "chatgpt" &&
            Regex("usage|quota|credit|allowance|plan limit", RegexOption.IGNORE_CASE)
                .containsMatchIn(state.recognitionError)) {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHATGPT_USAGE_URL))) }
            }, modifier = Modifier.testTag("recognition_manage_usage")) { Text("Manage ChatGPT usage") }
        }
        OutlinedTextField(value = state.resultText, onValueChange = vm::updateResultText,
            modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp)
                .semantics { contentDescription = "Editable recognized text" }.testTag("recognition_result_text"),
            enabled = !state.isRecognizing && state.storageError.isBlank(),
            label = { Text("Recognized text") }, placeholder = { Text("A completed transcription will appear here.") },
            minLines = 12, maxLines = 24)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { copyText(context, state.resultText) }, enabled = state.resultText.isNotEmpty(),
                modifier = Modifier.weight(1f).height(54.dp).semantics { contentDescription = "Copy recognized text" }) { Text("Copy") }
            OutlinedButton(onClick = { shareText(context, state.resultText) }, enabled = state.resultText.isNotEmpty(),
                modifier = Modifier.weight(1f).height(54.dp).semantics { contentDescription = "Share recognized text" }) { Text("Share") }
        }
        OutlinedButton(onClick = vm::openScanFromResult, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("back_to_scan")) {
            Text("Back to scan")
        }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, vm: AppViewModel, onPrivacyClick: () -> Unit) {
    val context = LocalContext.current
    var usageLinkError by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Recognition provider", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Recognition sends selected pages and instructions to OpenAI. ChatGPT uses eligible plan allowance; API usage may be billed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onPrivacyClick, modifier = Modifier.testTag("privacy_policy")) {
            Text("Privacy policy")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.settingsDraft.provider == "api") Button(onClick = {}, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("OpenAI API") }
            else OutlinedButton(onClick = { vm.updateSettingsDraft { it.copy(provider = "api") } }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("OpenAI API") }
            if (state.settingsDraft.provider == "chatgpt") Button(onClick = {}, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("ChatGPT plan") }
            else OutlinedButton(onClick = { vm.updateSettingsDraft { it.copy(provider = "chatgpt") } }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("ChatGPT plan") }
        }

        if (state.settingsDraft.provider == "api") {
            Text(if (state.hasStoredApiKey) "An API key is saved. Leave blank to keep it." else "Add an API key to use the OpenAI API.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = state.apiKeyDraft, onValueChange = vm::updateApiKeyDraft,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "OpenAI API key" },
                label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true)
            if (state.hasStoredApiKey && !state.clearApiKey)
                TextButton(onClick = vm::requestClearApiKey, modifier = Modifier.semantics { contentDescription = "Clear stored API key" }) { Text("Clear saved API key") }
            if (state.clearApiKey)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("The saved API key will be cleared.", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::cancelClearApiKey) { Text("Undo") }
                }
        } else {
            Column {
                Text(if (state.chatGptConnected) "Connected · ${state.subscription.ifBlank { "ChatGPT account" }}" else "Sign in to use your ChatGPT plan.",
                    style = MaterialTheme.typography.bodyMedium)
                if (state.chatGptConnected) TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHATGPT_USAGE_URL)))
                        usageLinkError = ""
                    } catch (_: Throwable) {
                        usageLinkError = "Could not open ChatGPT usage settings."
                    }
                }, modifier = Modifier.testTag("manage_chatgpt_usage")) { Text("Manage usage") }
                if (usageLinkError.isNotBlank()) Text(usageLinkError, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::signInToChatGpt, enabled = !state.isSigningIn,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("chatgpt_sign_in")) {
                    Text(if (state.isSigningIn) "Signing in…" else if (state.chatGptConnected) "Reconnect" else "Continue with ChatGPT")
                }
                if (state.isSigningIn) OutlinedButton(onClick = vm::cancelChatGptSignIn,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("cancel_sign_in")) { Text("Cancel sign-in") }
                if (state.chatGptConnected) OutlinedButton(onClick = vm::disconnectChatGpt,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Disconnect") }
            }
        }

        Text("Model", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = state.settingsDraft.model,
            onValueChange = { value -> vm.updateSettingsDraft { it.copy(model = value) } },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Model ID" },
            label = { Text("Model ID") }, singleLine = true)
        Text("The selected model must accept image input.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = vm::loadModels, enabled = !state.isLoadingModels &&
                !(state.settingsDraft.provider == "api" && state.apiKeyDraft.isNotBlank()),
                modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { contentDescription = "Load models from account" }.testTag("load_models")) {
                if (state.isLoadingModels) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Load account models")
            }
            Text("Tap a loaded model to use it.", style = MaterialTheme.typography.bodySmall)
        }
        state.modelChoices.take(30).forEach { choice ->
            OutlinedButton(onClick = { vm.updateSettingsDraft { it.copy(model = choice.id) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("${choice.name}  ·  ${choice.id}", textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
            }
        }
        Text("Supernote connection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Used for local Wi-Fi mirroring. This is separate from your recognition provider.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = state.settingsDraft.manualIp,
            onValueChange = { value -> vm.updateSettingsDraft { it.copy(manualIp = value) } },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Manual Supernote IP address" },
            label = { Text("Manual Supernote IP (optional)") }, singleLine = true)
        Text("Transcription", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = state.settingsDraft.glossary,
            onValueChange = { value -> vm.updateSettingsDraft { it.copy(glossary = value) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).semantics { contentDescription = "Optional glossary" },
            label = { Text("Glossary (optional)") }, minLines = 3)
        Text("Recognition prompt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("This is the base prompt sent with every scan. Glossary and additional guidance stay separate. Maximum $MAX_RECOGNITION_PROMPT_CHARS characters.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = state.settingsDraft.prompt,
            onValueChange = vm::updateRecognitionPromptDraft,
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 320.dp)
                .semantics { contentDescription = "Editable recognition prompt" }.testTag("recognition_prompt"),
            label = { Text("Base recognition prompt") }, minLines = 8, maxLines = 12)
        Text("${state.settingsDraft.prompt.length} / $MAX_RECOGNITION_PROMPT_CHARS characters",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = vm::restoreDefaultRecognitionPrompt, enabled = !state.isSavingSettings,
            modifier = Modifier.semantics { contentDescription = "Restore default recognition prompt" }
                .testTag("restore_default_prompt")) {
            Text("Restore default prompt")
        }
        OutlinedTextField(value = state.settingsDraft.instructions,
            onValueChange = { value -> vm.updateSettingsDraft { it.copy(instructions = value) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 132.dp).semantics { contentDescription = "Additional recognition guidance" },
            label = { Text("Additional guidance (optional)") }, minLines = 5)
        if (state.settingsMessage.isNotBlank()) Text(state.settingsMessage, color = MaterialTheme.colorScheme.primary)
        if (state.settingsError.isNotBlank()) Text(state.settingsError, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = "Settings error: ${state.settingsError}" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = vm::cancelSettings, enabled = !state.isSavingSettings,
                modifier = Modifier.weight(1f).height(54.dp)) { Text("Cancel") }
            Button(onClick = vm::saveSettings, enabled = !state.isSavingSettings,
                modifier = Modifier.weight(1f).height(54.dp)) { Text(if (state.isSavingSettings) "Saving…" else "Save settings") }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun PagePreview(
    state: AppUiState,
    vm: AppViewModel,
    onSaveToGallery: (String) -> Unit,
    onShareOriginal: (String) -> Unit,
    isSharingOriginal: Boolean,
    shareError: String,
) {
    val id = state.selectedPreviewId
    var previewAttempt by remember(id) { mutableIntStateOf(0) }
    var previewFailed by remember(id) { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, id, previewAttempt) {
        previewFailed = false
        try {
            value = if (id == null) null else vm.loadPreview(id)
            previewFailed = value == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            value = null
            previewFailed = true
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (previewFailed) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("This page preview could not be loaded.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { previewAttempt++ }, modifier = Modifier.testTag("retry_page_preview")) {
                    Text("Try again")
                }
            }
        } else {
            ZoomablePageImage(bitmap, Modifier.weight(1f).fillMaxWidth().testTag("page_zoom_viewer"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { id?.let(onSaveToGallery) }, enabled = id != null && !state.isSavingGallery && !isSharingOriginal,
                modifier = Modifier.weight(1f).height(54.dp).testTag("save_page_to_gallery")) {
                if (state.isSavingGallery) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Saving…")
                } else Text("Save to Gallery")
            }
            OutlinedButton(onClick = { id?.let(onShareOriginal) }, enabled = id != null && !state.isSavingGallery && !isSharingOriginal,
                modifier = Modifier.weight(1f).height(54.dp).semantics { contentDescription = "Share original page image" }
                    .testTag("share_page_original")) {
                if (isSharingOriginal) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Preparing…")
                } else Text("Share image")
            }
        }
        if (shareError.isNotBlank()) Text(shareError, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = "Image share error: $shareError" })
        if (state.isSavingGallery) {
            TextButton(onClick = vm::cancelGallerySave, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "Cancel gallery save" }.testTag("cancel_gallery_save")) {
                Text("Cancel save")
            }
        }
        if (state.gallerySaveMessage.isNotBlank()) Text(state.gallerySaveMessage,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { contentDescription = state.gallerySaveMessage })
        if (state.gallerySaveError.isNotBlank()) Text(state.gallerySaveError,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = "Gallery save error: ${state.gallerySaveError}" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = vm::closePreview, enabled = !isSharingOriginal,
                modifier = Modifier.weight(1f).height(54.dp)
                    .semantics { contentDescription = "Back to scan" }) { Text("Back to scan") }
            OutlinedButton(onClick = { id?.let(vm::askDeletePage) }, enabled = !state.isSavingGallery && !isSharingOriginal,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ), modifier = Modifier.weight(1f).height(54.dp)
                    .semantics { contentDescription = "Delete this scan page" }) { Text("Delete page") }
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Supernote transcription", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share recognized text"))
}
