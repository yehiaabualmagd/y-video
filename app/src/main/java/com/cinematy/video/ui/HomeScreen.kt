package com.cinematy.video.ui

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.graphics.drawable.ColorDrawable
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.cinematy.video.PlayerActivity
import com.cinematy.video.R
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import com.cinematy.video.data.HiddenStore
import com.cinematy.video.data.Prefs
import com.cinematy.video.data.Video
import com.cinematy.video.data.VideoRepository
import kotlinx.coroutines.delay
import com.cinematy.video.player.PlayerHolder
import com.cinematy.video.ui.theme.LocalBgGradient
import com.cinematy.video.ui.theme.LocalAccents
import com.cinematy.video.ui.theme.UiThemeState
import kotlinx.coroutines.launch

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )
}

private fun hasVideoPermission(context: Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

private sealed class RenameResult {
    object Done : RenameResult()
    data class NeedsApproval(val sender: android.content.IntentSender) : RenameResult()
    object Failed : RenameResult()
}

private fun shareVideo(context: Context, video: Video) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, video.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Throwable) {
    }
}

private fun tryRename(context: Context, video: Video, newName: String): RenameResult {
    val finalName = ensureExtension(newName, video.extension)
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
        }
        context.contentResolver.update(video.uri, values, null, null)
        RenameResult.Done
    } catch (e: RecoverableSecurityException) {
        RenameResult.NeedsApproval(e.userAction.actionIntent.intentSender)
    } catch (e: SecurityException) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pi = MediaStore.createWriteRequest(context.contentResolver, listOf(video.uri))
                RenameResult.NeedsApproval(pi.intentSender)
            } catch (_: Throwable) {
                RenameResult.Failed
            }
        } else RenameResult.Failed
    } catch (_: Throwable) {
        RenameResult.Failed
    }
}

private sealed class DeleteResult {
    object Done : DeleteResult()
    data class NeedsApproval(val sender: android.content.IntentSender) : DeleteResult()
    object Failed : DeleteResult()
}

private fun tryDelete(context: Context, videos: List<Video>): DeleteResult {
    return try {
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(
                context.contentResolver, videos.map { it.uri }
            )
            DeleteResult.NeedsApproval(pi.intentSender)
        } else {
            var rows = 0
            videos.forEach { rows += context.contentResolver.delete(it.uri, null, null) }
            if (rows > 0) DeleteResult.Done else DeleteResult.Failed
        }
    } catch (e: RecoverableSecurityException) {
        DeleteResult.NeedsApproval(e.userAction.actionIntent.intentSender)
    } catch (_: Throwable) {
        DeleteResult.Failed
    }
}

private val WordmarkFont = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(com.cinematy.video.R.font.greatvibes_regular)
)

private var wordmarkPlayed = false

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val repo = remember { VideoRepository(context.applicationContext) }
    val prefs = remember { Prefs(context) }
    LaunchedEffect(Unit) { Haptics.enabled = prefs.haptics }

    var videos by remember { mutableStateOf(repo.cached()) }
    var loaded by remember { mutableStateOf(false) }
    var hasPerm by remember { mutableStateOf(hasVideoPermission(context)) }
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(com.cinematy.video.StreamIntake.pendingUrl) {
        if (com.cinematy.video.StreamIntake.pendingUrl != null) tab = 2
    }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(prefs.sortMode) }
    var folderFilter by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Video?>(null) }
    var detailsTarget by remember { mutableStateOf<Video?>(null) }
    var deleteTarget by remember { mutableStateOf<List<Video>?>(null) }
    var pendingRename by remember { mutableStateOf<Pair<Video, String>?>(null) }
    var pendingDelete by remember { mutableStateOf<List<Video>?>(null) }
    var colsVideos by remember { mutableStateOf(prefs.gridColsVideos) }
    var colsFolders by remember { mutableStateOf(prefs.gridColsFolders) }
    var colsStream by remember { mutableStateOf(prefs.gridColsStream) }
    val gridCols = when (tab) {
        1 -> colsFolders
        2 -> colsStream
        else -> colsVideos
    }

    var searchVisible by remember { mutableStateOf(false) }
    var topFilter by remember { mutableStateOf(0) } // 0 none, 1 continue, 2 favorites, 3 films, 4 shorts
    // selection mode (multi-select)
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val selectionMode = selected.isNotEmpty()
    // hidden folder
    var showHidden by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var nowTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick++
            kotlinx.coroutines.delay(1000)
        }
    }
    var hiddenTick by remember { mutableStateOf(0) }
    var pinForHidden by remember { mutableStateOf(false) }
    var moveConfirm by remember { mutableStateOf<List<Video>?>(null) }
    var pendingHiddenDelete by remember { mutableStateOf<List<Pair<Video, String>>?>(null) }

    fun shareApp(context: Context) {
        try {
            val src = java.io.File(context.applicationInfo.sourceDir)
            val dest = java.io.File(context.cacheDir, "share/Y-video.apk")
            dest.parentFile?.mkdirs()
            src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", dest,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Y video: " + com.cinematy.video.BuildConfig.SHARE_URL,
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
        } catch (_: Throwable) {
        }
    }

    fun shareVideos(context: Context, list: List<Video>) {
        if (list.isEmpty()) return
        if (list.size == 1) {
            shareVideo(context, list.first())
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(list.map { it.uri }),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
        } catch (_: Throwable) {
        }
    }
    var favorites by remember { mutableStateOf(prefs.getFavorites()) }
    var watched by remember { mutableStateOf(prefs.getWatched()) }
    var barsVisible by remember { mutableStateOf(true) }
    // bumped every time the screen resumes (e.g. back from the player) so
    // resume positions, recents and watched badges refresh live
    var homeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                homeTick++
                watched = prefs.getWatched()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val videosGridState = rememberLazyGridState()
    val foldersGridState = rememberLazyGridState()
    val streamGridState = rememberLazyGridState()
    val searchGridState = rememberLazyGridState()
    // back steps: close search -> leave folder -> back to Videos root -> exit
    fun navigate(newTab: Int, newFolder: String?, newSearch: Boolean) {
        tab = newTab
        folderFilter = newFolder
        searchVisible = newSearch
    }

    BackHandler(
        enabled = selectionMode || showHidden || showSettings || searchVisible ||
            topFilter != 0 || folderFilter != null || tab != 0
    ) {
        when {
            selectionMode -> selected = emptySet()
            showSettings -> showSettings = false
            showHidden -> showHidden = false
            searchVisible -> { searchVisible = false; query = "" }
            topFilter != 0 -> topFilter = 0
            folderFilter != null -> {
                folderFilter = null
                tab = 1
            }
            else -> tab = 0
        }
    }

    LaunchedEffect(tab) { barsVisible = true }
    val activeGridState = if (searchVisible) searchGridState else when (tab) {
        1 -> foldersGridState
        2 -> streamGridState
        else -> videosGridState
    }
    LaunchedEffect(activeGridState) {
        var prevIndex = 0
        var prevOffset = 0
        snapshotFlow {
            activeGridState.firstVisibleItemIndex to activeGridState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            val forward = index > prevIndex || (index == prevIndex && offset > prevOffset + 8)
            val backward = index < prevIndex || (index == prevIndex && offset < prevOffset - 8)
            if (forward && barsVisible) barsVisible = false
            else if (backward && !barsVisible) barsVisible = true
            prevIndex = index
            prevOffset = offset
        }
    }

    fun refresh(force: Boolean) {
        scope.launch {
            videos = repo.getVideos(force)
            loaded = true
            watched = prefs.getWatched()
        }
    }

    // live library updates: new/deleted files refresh once, 600ms debounce
    DisposableEffect(hasPerm) {
        var observer: android.database.ContentObserver? = null
        if (hasPerm) {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var pendingJob: kotlinx.coroutines.Job? = null
            val obs = object : android.database.ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    pendingJob?.cancel()
                    pendingJob = scope.launch {
                        delay(600)
                        refresh(true)
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs
            )
            observer = obs
        }
        onDispose {
            observer?.let { context.contentResolver.unregisterContentObserver(it) }
        }
    }

    val writeApprovalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingRename
        if (result.resultCode == Activity.RESULT_OK && pending != null) {
            when (tryRename(context, pending.first, pending.second)) {
                RenameResult.Done -> {
                    Toast.makeText(context, R.string.rename_done, Toast.LENGTH_SHORT).show()
                    refresh(true)
                }
                else -> Toast.makeText(context, R.string.rename_failed, Toast.LENGTH_SHORT).show()
            }
        }
        pendingRename = null
    }

    val deleteApprovalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val batch = pendingDelete
        val hiddenCopies = pendingHiddenDelete
        if (result.resultCode == Activity.RESULT_OK && batch != null) {
            batch.forEach { prefs.clearResumePos(it.uri.toString()) }
            Toast.makeText(context, R.string.delete_done, Toast.LENGTH_SHORT).show()
            hiddenTick++
            refresh(true)
        } else {
            // delete denied: roll back any hidden copies so nothing is stranded
            if (hiddenCopies != null) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    hiddenCopies.forEach { (video, stored) ->
                        try {
                            java.io.File(context.filesDir, "hidden/$stored").delete()
                            prefs.removeHidden(stored)
                        } catch (_: Throwable) {
                        }
                    }
                }
                Toast.makeText(context, R.string.move_failed, Toast.LENGTH_SHORT).show()
            } else if (batch != null) {
                Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
        pendingDelete = null
        pendingHiddenDelete = null
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPerm = hasVideoPermission(context)
        if (hasPerm) refresh(true)
    }

    LaunchedEffect(hasPerm) {
        if (hasPerm && !loaded) refresh(false)
    }

    val resumeMapTop = remember(videos, homeTick) { prefs.getResumeMap() }
    val recentsTop = remember(videos, homeTick) { prefs.getRecents() }
    val continueTop = remember(videos, resumeMapTop, recentsTop) {
        recentsTop.mapNotNull { key ->
            val v = videos.firstOrNull { it.uri.toString() == key }
            val p = resumeMapTop[key] ?: 0L
            if (v != null && p > 10_000L &&
                (v.durationMs <= 0 || p < v.durationMs - 15_000L)
            ) v else null
        }
    }
    val filmsCountTop = remember(videos) { videos.count { it.durationMs >= 1_800_000L } }
    val shortsCountTop = remember(videos) { videos.count { it.durationMs in 1L until 1_800_000L } }

    val filtered = remember(videos, query, sort, folderFilter, topFilter, favorites, watched, continueTop, resumeMapTop) {
        var list = videos
        folderFilter?.let { b -> list = list.filter { it.bucketId == b } }
        when (topFilter) {
            1 -> list = continueTop.filter { folderFilter == null || it.bucketId == folderFilter }
            2 -> list = list.filter { favorites.contains(it.uri.toString()) }
            3 -> list = list.filter { it.durationMs >= 1_800_000L }
            4 -> list = list.filter { it.durationMs in 1L until 1_800_000L }
        }
        if (query.isNotBlank()) {
            val q = query.trim()
            list = list.filter {
                it.cleanTitle.contains(q, true) || it.displayName.contains(q, true)
            }
        }
        list = if (topFilter == 1) {
            // Continue watching keeps watch order: most recently played first
            list
        } else when (sort) {
            0 -> list.sortedBy { it.cleanTitle.lowercase() }
            2 -> list.sortedByDescending { it.durationMs }
            3 -> list.sortedByDescending { it.sizeBytes }
            else -> list.sortedByDescending { it.dateAddedSec }
        }
        list
    }

    val folders = remember(videos) {
        videos.groupBy { it.bucketId }
            .map { (id, list) -> id to list }
            .sortedByDescending { it.second.size }
    }

    fun play(list: List<Video>, index: Int) {
        if (list.isEmpty()) return
        PlayerHolder.pending = PlayerHolder.Pending.Queue(list, index)
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    // static app-wide deep violet -> dark magenta/indigo backdrop (drawn once,
    // no per-frame work); scaffold itself stays transparent so every tab shows it
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    LocalBgGradient.current
                )
            ),
    ) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            AnimatedVisibility(
                visible = barsVisible,
                enter = expandVertically(tween(160)) + fadeIn(tween(140)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(120)),
            ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (selectionMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.selected_count, selected.size),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        SelectionMenu(
                            count = selected.size,
                            onPlay = {
                                val picked = filtered.filter { selected.contains(it.id) }
                                if (picked.isNotEmpty()) play(picked, 0)
                                selected = emptySet()
                            },
                            onRename = {
                                val v = filtered.firstOrNull { selected.contains(it.id) }
                                if (v != null) renameTarget = v
                                selected = emptySet()
                            },
                            onShare = {
                                shareVideos(context, filtered.filter { selected.contains(it.id) })
                                selected = emptySet()
                            },
                            onDetails = {
                                val v = filtered.firstOrNull { selected.contains(it.id) }
                                if (v != null) detailsTarget = v
                                selected = emptySet()
                            },
                            onDelete = {
                                deleteTarget = filtered.filter { selected.contains(it.id) }
                                selected = emptySet()
                            },
                            onSelectAll = { selected = filtered.map { it.id }.toSet() },
                            onMoveToHidden = {
                                moveConfirm = filtered.filter { selected.contains(it.id) }
                                selected = emptySet()
                            },
                        )
                    }
                } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // wordmark: signature font, drawn on once per app open:
                    // left-to-right reveal + soft scale/slide settle + a light sweep
                    val markIn = remember { androidx.compose.animation.core.Animatable(if (wordmarkPlayed) 1f else 0f) }
                    val sweep = remember { androidx.compose.animation.core.Animatable(if (wordmarkPlayed) 1f else 0f) }
                    LaunchedEffect(Unit) {
                        if (!wordmarkPlayed) {
                            wordmarkPlayed = true
                            markIn.animateTo(
                                1f,
                                androidx.compose.animation.core.tween(
                                    1100,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                ),
                            )
                            sweep.animateTo(
                                1f,
                                androidx.compose.animation.core.tween(
                                    700,
                                    easing = androidx.compose.animation.core.LinearEasing,
                                ),
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            brush = Brush.horizontalGradient(
                                LocalAccents.current
                            ),
                            fontFamily = WordmarkFont,
                            fontSize = 38.sp,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .graphicsLayer {
                                alpha = (markIn.value * 2f).coerceIn(0f, 1f)
                                translationY = (1f - markIn.value) * -10.dp.toPx()
                                val sc = 0.9f + 0.1f * markIn.value
                                scaleX = sc
                                scaleY = sc
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                compositingStrategy = if (sweep.value < 1f)
                                    androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                                else androidx.compose.ui.graphics.CompositingStrategy.Auto
                            }
                            .drawWithContent {
                                val reveal = markIn.value
                                clipRect(
                                    left = 0f,
                                    top = -size.height,
                                    right = size.width * reveal,
                                    bottom = size.height * 2f,
                                ) {
                                    this@drawWithContent.drawContent()
                                }
                                val sw = sweep.value
                                if (sw > 0f && sw < 1f) {
                                    val cx = size.width * (-0.2f + 1.4f * sw)
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                Color.White.copy(alpha = 0.55f),
                                                Color.Transparent,
                                            ),
                                            startX = cx - size.width * 0.18f,
                                            endX = cx + size.width * 0.18f,
                                        ),
                                        blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop,
                                    )
                                }
                            },
                    )
                    var spinTap by remember { mutableStateOf(0) }
                    val spin by animateFloatAsState(
                        targetValue = spinTap * 360f,
                        animationSpec = tween(600),
                        label = "refreshSpin",
                    )
                    IconButton(onClick = { tapHaptic(view); spinTap++; refresh(true) }) {
                        Icon(
                            Icons.Filled.Refresh, stringResource(R.string.refresh),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.graphicsLayer { rotationZ = spin },
                        )
                    }
                    OverflowMenu(
                        sort = sort,
                        onSort = { sort = it; prefs.sortMode = it },
                        cols = gridCols,
                        onCols = { n ->
                            when (tab) {
                                1 -> { colsFolders = n; prefs.gridColsFolders = n }
                                2 -> { colsStream = n; prefs.gridColsStream = n }
                                else -> { colsVideos = n; prefs.gridColsVideos = n }
                            }
                        },
                        onSettings = { showSettings = true },
                        onShareApp = { shareApp(context) },
                    )
                }
                }
            }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = barsVisible,
                enter = slideInVertically(tween(160)) { it } + fadeIn(tween(140)),
                exit = slideOutVertically(tween(140)) { it } + fadeOut(tween(120)),
            ) {
                Column {
                    NowPlayingBar(nowTick)
                    FloatingNavBar(
                    tab = tab,
                    searchActive = searchVisible,
                    onTab = {
                        navigate(it, null, false)
                        topFilter = 0
                        query = ""
                        if (it == 0) scope.launch { videosGridState.scrollToItem(0) }
                    },
                    onSearch = {
                        if (searchVisible) {
                            searchVisible = false
                            query = ""
                        } else {
                            navigate(tab.coerceIn(0, 1), folderFilter, true)
                        }
                    },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !hasPerm -> PermissionGate { permLauncher.launch(requiredPermissions()) }
                tab == 2 -> StreamTab(prefs, columns = gridCols, state = streamGridState)
                else -> {
                    val pagerState = rememberPagerState(
                        initialPage = if (searchVisible) 2 else if (tab == 1) 1 else 0
                    ) { 3 }
                    val pageFor = if (searchVisible) 2 else tab.coerceIn(0, 1)
                    LaunchedEffect(pageFor) {
                        if (pagerState.currentPage != pageFor) {
                            pagerState.animateScrollToPage(
                                pageFor,
                                animationSpec = tween(240, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                    LaunchedEffect(pagerState.settledPage) {
                        val p = pagerState.settledPage
                        if (p == 2) {
                            if (!searchVisible) searchVisible = true
                        } else if (searchVisible) {
                            searchVisible = false
                            query = ""
                            if (tab != p) navigate(p, null, false)
                        } else if (tab != p) {
                            navigate(p, null, false)
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !selectionMode,
                        flingBehavior = PagerDefaults.flingBehavior(
                            state = pagerState,
                            snapAnimationSpec = spring(
                                Spring.DampingRatioNoBouncy,
                                Spring.StiffnessMediumLow,
                            ),
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        if (page == 1 && folderFilter == null) {
                            FolderList(
                                folders,
                                columns = colsFolders,
                                state = foldersGridState,
                                onPullDown = {
                                    val act = context as? androidx.fragment.app.FragmentActivity
                                    if (act != null && HiddenAuth.canUseBiometric(context)) {
                                        HiddenAuth.authenticate(
                                            act,
                                            onSuccess = { showHidden = true },
                                            onPinFallback = { pinForHidden = true },
                                        )
                                    } else {
                                        pinForHidden = true
                                    }
                                },
                            ) {
                                navigate(0, it, false)
                            }
                        } else if (page == 2) {
                            VideoGrid(
                                videos = if (query.isBlank()) emptyList() else filtered,
                                prefs = prefs,
                                columns = colsVideos,
                                gridState = searchGridState,
                                barsVisible = barsVisible,
                                searchVisible = true,
                                searchActive = pagerState.currentPage == 2,
                                favorites = favorites,
                                watched = watched,
                                homeTick = homeTick,
                                selected = selected,
                                onToggleSelect = { v ->
                                    selected =
                                        if (selected.contains(v.id)) selected - v.id
                                        else selected + v.id
                                },
                                onClearSelection = { selected = emptySet() },
                                onToggleFavorite = { v ->
                                    prefs.toggleFavorite(v.uri.toString())
                                    favorites = prefs.getFavorites()
                                },
                                emptyText = if (query.isBlank()) stringResource(R.string.search_hint)
                                else stringResource(R.string.search_empty),
                                query = query,
                                onQuery = { query = it },
                                onPlay = { idx -> play(filtered, idx) },
                                onPlayVideo = { v -> play(videos, videos.indexOfFirst { it.id == v.id }.coerceAtLeast(0)) },
                                showTopCards = false,
                                topFilter = 0,
                                continueCount = 0,
                                favCount = 0,
                                filmsCount = 0,
                                shortsCount = 0,
                                onTopFilter = { },
                            )
                        } else {
                            VideoGrid(
                                videos = filtered,
                                prefs = prefs,
                                columns = gridCols,
                                gridState = videosGridState,
                                barsVisible = barsVisible,
                                searchVisible = false,
                                searchActive = false,
                                showTopCards = folderFilter == null,
                                topFilter = topFilter,
                                continueCount = continueTop.size,
                                favCount = favorites.size,
                                filmsCount = filmsCountTop,
                                shortsCount = shortsCountTop,
                                onTopFilter = { topFilter = it },
                                favorites = favorites,
                                watched = watched,
                                homeTick = homeTick,
                                selected = selected,
                                onToggleSelect = { v ->
                                    selected =
                                        if (selected.contains(v.id)) selected - v.id
                                        else selected + v.id
                                },
                                onClearSelection = { selected = emptySet() },
                                onToggleFavorite = { v ->
                                    prefs.toggleFavorite(v.uri.toString())
                                    favorites = prefs.getFavorites()
                                },
                                emptyText = when (topFilter) {
                                    1 -> stringResource(R.string.no_continue)
                                    2 -> stringResource(R.string.no_favorites)
                                    3 -> stringResource(R.string.no_films)
                                    4 -> stringResource(R.string.no_shorts)
                                    else -> if (query.isBlank()) stringResource(R.string.no_videos)
                                    else stringResource(R.string.search_empty)
                                },
                                query = query,
                                onQuery = { query = it },
                                onPlay = { idx -> play(filtered, idx) },
                                onPlayVideo = { v -> play(videos, videos.indexOfFirst { it.id == v.id }.coerceAtLeast(0)) },
                            )
                        }
                    }
                }
            }
        }
    }
    }

    val detailsVideo = detailsTarget
    if (detailsVideo != null) {
        DetailsDialog(video = detailsVideo, onDismiss = { detailsTarget = null })
    }

    val renameVideo = renameTarget
    if (renameVideo != null) {
        val target = renameVideo
        RenameDialog(
            initial = target.displayName.substringBeforeLast('.', target.displayName),
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                when (val r = tryRename(context, target, newName)) {
                    RenameResult.Done -> {
                        Toast.makeText(context, R.string.rename_done, Toast.LENGTH_SHORT).show()
                        refresh(true)
                    }
                    is RenameResult.NeedsApproval -> {
                        pendingRename = target to newName
                        try {
                            writeApprovalLauncher.launch(
                                IntentSenderRequest.Builder(r.sender).build()
                            )
                        } catch (_: Throwable) {
                            Toast.makeText(context, R.string.rename_failed, Toast.LENGTH_SHORT).show()
                            pendingRename = null
                        }
                    }
                    RenameResult.Failed ->
                        Toast.makeText(context, R.string.rename_failed, Toast.LENGTH_SHORT).show()
                }
                renameTarget = null
            },
        )
    }



    val delVideos = deleteTarget
    if (delVideos != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_video_title)) },
            text = {
                Text(
                    if (delVideos.size == 1)
                        stringResource(R.string.delete_video_body, delVideos.first().cleanTitle)
                    else stringResource(R.string.delete_videos_body, delVideos.size)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (val r = tryDelete(context, delVideos)) {
                        DeleteResult.Done -> {
                            delVideos.forEach { prefs.clearResumePos(it.uri.toString()) }
                            Toast.makeText(context, R.string.delete_done, Toast.LENGTH_SHORT).show()
                            refresh(true)
                        }
                        is DeleteResult.NeedsApproval -> {
                            pendingDelete = delVideos
                            try {
                                deleteApprovalLauncher.launch(
                                    IntentSenderRequest.Builder(r.sender).build()
                                )
                            } catch (_: Throwable) {
                                Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                                pendingDelete = null
                            }
                        }
                        DeleteResult.Failed ->
                            Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    }
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // move-to-hidden confirm (carries the uninstall warning)
    val moveList = moveConfirm
    if (moveList != null) {
        AlertDialog(
            onDismissRequest = { moveConfirm = null },
            title = { Text(stringResource(R.string.hidden)) },
            text = { Text(stringResource(R.string.hidden_warn)) },
            confirmButton = {
                TextButton(onClick = {
                    moveConfirm = null
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val copied = moveList.mapNotNull { v ->
                            val stored = HiddenStore.moveToHidden(context, v, v.displayName)
                            if (stored != null) v to stored else null
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (copied.isEmpty()) {
                                Toast.makeText(context, R.string.move_failed, Toast.LENGTH_SHORT).show()
                            } else {
                                try {
                                    val pi = MediaStore.createDeleteRequest(
                                        context.contentResolver,
                                        copied.map { it.first.uri },
                                    )
                                    pendingDelete = copied.map { it.first }
                                    pendingHiddenDelete = copied
                                    deleteApprovalLauncher.launch(
                                        IntentSenderRequest.Builder(pi.intentSender).build()
                                    )
                                } catch (_: Throwable) {
                                    Toast.makeText(context, R.string.move_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.move_to_hidden))
                }
            },
            dismissButton = {
                TextButton(onClick = { moveConfirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (pinForHidden) {
        PinDialog(
            prefs = prefs,
            onSuccess = {
                pinForHidden = false
                showHidden = true
            },
            onDismiss = { pinForHidden = false },
        )
    }

    // hidden vault overlay
    if (showHidden) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        LocalBgGradient.current
                    )
                ),
        ) {
            HiddenScreen(
                prefs = prefs,
                tick = hiddenTick,
                onClose = { showHidden = false },
                onChanged = {
                    hiddenTick++
                    refresh(true)
                },
            )
        }
    }

    // settings overlay
    if (showSettings) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        LocalBgGradient.current
                    )
                ),
        ) {
            SettingsScreen(
                prefs = prefs,
                onClose = { showSettings = false },
                onStream = { showSettings = false; navigate(2, null, false) },
            )
        }
    }
}

@Composable
private fun FloatingNavBar(
    tab: Int,
    searchActive: Boolean,
    onTab: (Int) -> Unit,
    onSearch: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.horizontalGradient(
                    LocalAccents.current.map { it.copy(alpha = 0.9f) }
                )
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(
                tab == 0,
                CineIcons.Movie,
                stringResource(R.string.tab_videos),
            ) { onTab(0) }
            NavItem(tab == 1, CineIcons.Folder, stringResource(R.string.tab_folders)) { onTab(1) }
            NavItem(searchActive, Icons.Filled.Search, stringResource(R.string.search)) { onSearch() }
        }
    }
}

@Composable
private fun NavItem(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        if (selected) Color.White else Color(0xB3FFFFFF),
        tween(180),
        label = "navColor",
    )
    val bg by animateColorAsState(
        if (selected) Color(0x33FFFFFF) else Color.Transparent,
        tween(180),
        label = "navBg",
    )
    val view = LocalView.current
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable { tapHaptic(view); onClick() }
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun TopCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    active: Boolean,
    colors: List<Color>,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val g1 by animateColorAsState(
        if (active) colors[0] else colors[0].copy(alpha = 0.45f),
        tween(220),
        label = "cardG1",
    )
    val g2 by animateColorAsState(
        if (active) colors[1] else colors[1].copy(alpha = 0.45f),
        tween(220),
        label = "cardG2",
    )
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(g1, g2)))
            .clickable { tapHaptic(view); onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xCCFFFFFF),
        )
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            CineIcons.Movie, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.permission_needed),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text(stringResource(R.string.grant_permission)) }
    }
}

@Composable
private fun OverflowMenu(
    sort: Int,
    onSort: (Int) -> Unit,
    cols: Int,
    onCols: (Int) -> Unit,
    onSettings: () -> Unit,
    onShareApp: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf(0) } // 0 = main, 1 = sort, 2 = columns
    val view = LocalView.current
    BackHandler(enabled = open) {
        if (sub != 0) sub = 0 else open = false
    }
    Box {
        IconButton(onClick = { tapHaptic(view); open = true }) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.sort), tint = MaterialTheme.colorScheme.onBackground)
        }
        DropdownMenu(expanded = open && sub == 0, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.sort), color = MaterialTheme.colorScheme.onSurface)
                },
                onClick = { sub = 1 },
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.columns), color = MaterialTheme.colorScheme.onSurface)
                },
                onClick = { sub = 2 },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.settings),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                leadingIcon = { Icon(CineIcons.Tune, null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { open = false; onSettings() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.share_app),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                leadingIcon = {
                    Icon(Icons.Filled.Share, null, tint = MaterialTheme.colorScheme.primary)
                },
                onClick = { open = false; onShareApp() },
            )
        }
        DropdownMenu(expanded = sub == 1, onDismissRequest = { sub = 0; open = false }) {
            val labels = listOf(
                stringResource(R.string.sort_name),
                stringResource(R.string.sort_date),
                stringResource(R.string.sort_duration),
                stringResource(R.string.sort_size),
            )
            labels.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (i == sort) "• " else "") + label,
                            color = if (i == sort) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { onSort(i); sub = 0; open = false },
                )
            }
        }
        DropdownMenu(expanded = sub == 2, onDismissRequest = { sub = 0; open = false }) {
            (1..4).forEach { n ->
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (n == cols) "• " else "") + n.toString(),
                            color = if (n == cols) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { onCols(n); sub = 0; open = false },
                )
            }
        }
    }
}

@Composable
private fun SelectionMenu(
    count: Int,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onMoveToHidden: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    BackHandler(enabled = open) { open = false }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, null, tint = MaterialTheme.colorScheme.onBackground)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.play)) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                onClick = { open = false; onPlay() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.rename),
                        color = if (count == 1) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = { Icon(CineIcons.Tune, null) },
                onClick = { open = false; if (count == 1) onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share)) },
                leadingIcon = { Icon(Icons.Filled.Share, null) },
                onClick = { open = false; onShare() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.details),
                        color = if (count == 1) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = { Icon(CineIcons.Movie, null) },
                onClick = { open = false; if (count == 1) onDetails() },
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                },
                leadingIcon = {
                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { open = false; onDelete() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.select_all)) },
                leadingIcon = { Icon(Icons.Filled.Check, null) },
                onClick = { open = false; onSelectAll() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_to_hidden)) },
                leadingIcon = { Icon(Icons.Filled.Lock, null) },
                onClick = { open = false; onMoveToHidden() },
            )
        }
    }
}

private fun thumbSizePx(columns: Int): Int = when (columns) {
    1 -> 640
    2 -> 512
    3 -> 360
    else -> 300
}

private fun thumbRequest(context: Context, video: Video, columns: Int, placeholder: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(video.uri)
        .videoFrameMillis(500)
        .size(thumbSizePx(columns))
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .placeholder(ColorDrawable(placeholder))
        .error(ColorDrawable(placeholder))
        .build()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGrid(
    videos: List<Video>,
    prefs: Prefs,
    columns: Int,
    gridState: LazyGridState,
    barsVisible: Boolean,
    searchVisible: Boolean,
    searchActive: Boolean,
    showTopCards: Boolean,
    topFilter: Int,
    continueCount: Int,
    favCount: Int,
    filmsCount: Int,
    shortsCount: Int,
    onTopFilter: (Int) -> Unit,
    favorites: Set<String>,
    watched: Set<String>,
    homeTick: Int,
    selected: Set<Long>,
    onToggleSelect: (Video) -> Unit,
    onClearSelection: () -> Unit,
    onToggleFavorite: (Video) -> Unit,
    emptyText: String,
    query: String,
    onQuery: (String) -> Unit,
    onPlay: (Int) -> Unit,
    onPlayVideo: (Video) -> Unit,
) {
    val context = LocalContext.current
    val thumbPhColor = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.22f)
    val thumbPh = thumbPhColor.toArgb()
    val imageLoader = context.imageLoader
    LaunchedEffect(videos, columns) {
        videos.take(12).forEach { v ->
            imageLoader.enqueue(thumbRequest(context, v, columns, thumbPh))
        }
    }
    LaunchedEffect(gridState, videos, columns) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                videos.drop(last + 1).take(6).forEach { v ->
                    imageLoader.enqueue(thumbRequest(context, v, columns, thumbPh))
                }
            }
    }
    Column(Modifier.fillMaxSize()) {
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(searchActive) {
            try {
                if (searchActive) {
                    delay(120)
                    focusRequester.requestFocus()
                    keyboard?.show()
                } else {
                    keyboard?.hide()
                }
            } catch (_: Throwable) {
            }
        }
        AnimatedVisibility(
            visible = searchVisible,
            enter = expandVertically(tween(160)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(120)),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        if (videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(36.dp))
                            .background(
                                Brush.horizontalGradient(
                                    LocalAccents.current
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            CineIcons.Movie, null,
                            tint = Color.White,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
        val resumeMap = remember(videos, homeTick) { prefs.getResumeMap() }
        if (showTopCards && barsVisible) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TopCard(
                    Modifier.weight(1f),
                    Icons.Filled.PlayArrow,
                    stringResource(R.string.continue_watching),
                    continueCount,
                    topFilter == 1,
                    listOf(Color(0xFF7C4DFF), Color(0xFFE040FB)),
                ) { onTopFilter(if (topFilter == 1) 0 else 1) }
                TopCard(
                    Modifier.weight(1f),
                    Icons.Filled.Star,
                    stringResource(R.string.favorites),
                    favCount,
                    topFilter == 2,
                    listOf(Color(0xFFE040FB), Color(0xFFFF7043)),
                ) { onTopFilter(if (topFilter == 2) 0 else 2) }
                TopCard(
                    Modifier.weight(1f),
                    CineIcons.Movie,
                    stringResource(R.string.films),
                    filmsCount,
                    topFilter == 3,
                    listOf(Color(0xFF536DFE), Color(0xFF7C4DFF)),
                ) { onTopFilter(if (topFilter == 3) 0 else 3) }
                TopCard(
                    Modifier.weight(1f),
                    CineIcons.Bolt,
                    stringResource(R.string.shorts),
                    shortsCount,
                    topFilter == 4,
                    listOf(Color(0xFFFF7043), Color(0xFFFFB300)),
                ) { onTopFilter(if (topFilter == 4) 0 else 4) }
            }
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(videos, key = { it.id }, contentType = { "video" }) { video ->
                val resume = resumeMap[video.uri.toString()] ?: 0L
                VideoCard(
                    video = video,
                    resumeMs = resume,
                    columns = columns,
                    isFavorite = favorites.contains(video.uri.toString()),
                    isNew = resume <= 0L && !watched.contains(video.uri.toString()),
                    selectionMode = selected.isNotEmpty(),
                    isSelected = selected.contains(video.id),
                    onToggleFavorite = { onToggleFavorite(video) },
                    onClick = {
                        // tap always opens the player, leaving selection mode first
                        if (selected.isNotEmpty()) onClearSelection()
                        onPlay(videos.indexOfFirst { v -> v.id == video.id })
                    },
                    onLongClick = { onToggleSelect(video) },
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoCard(
    video: Video,
    resumeMs: Long,
    columns: Int,
    modifier: Modifier = Modifier,
    isFavorite: Boolean,
    isNew: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val thumbPhColor = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.22f)
    val thumbPh = thumbPhColor.toArgb()
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(
                onClick = { tapHaptic(view); onClick() },
                onLongClick = { longHaptic(view); onLongClick() },
            ),
    ) {
        Box {
            AsyncImage(
                model = thumbRequest(context, video, columns, thumbPh),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(thumbPhColor),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xAA000000))
                        )
                    ),
            )
            Text(
                formatTime(video.durationMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x55000000))
                    .clickable { tapHaptic(view); onToggleFavorite() }
                    .padding(4.dp),
            ) {
                Icon(
                    Icons.Filled.Star,
                    stringResource(
                        if (isFavorite) R.string.remove_favorite else R.string.add_favorite
                    ),
                    tint = if (isFavorite) MaterialTheme.colorScheme.tertiary
                    else Color(0x99FFFFFF),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (isNew && !selectionMode) {
                Text(
                    stringResource(R.string.new_badge),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (selectionMode) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (isSelected) Brush.horizontalGradient(
                                listOf(Color(0xFF7C4DFF), Color(0xFFE040FB))
                            ) else Brush.linearGradient(
                                listOf(Color(0x66000000), Color(0x66000000))
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isSelected,
                        enter = scaleIn(tween(150)) + fadeIn(tween(150)),
                        exit = fadeOut(tween(100)),
                    ) {
                        Icon(
                            Icons.Filled.Check, null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            if (resumeMs > 10_000L && video.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (resumeMs.toFloat() / video.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = Color(0x33FFFFFF),
                )
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                video.cleanTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatSize(video.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun folderDisplayName(name: String): String =
    if (name.isBlank()) stringResource(R.string.unknown) else name

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderList(
    folders: List<Pair<String, List<Video>>>,
    columns: Int,
    state: LazyGridState,
    onPullDown: () -> Unit,
    onOpen: (String) -> Unit,
) {
    // pull down at the top of the folder list -> open the hidden vault
    val view = LocalView.current
    var pull by remember { mutableStateOf(0f) }
    val nested = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (available.y > 0 && state.firstVisibleItemIndex == 0 &&
                    state.firstVisibleItemScrollOffset == 0
                ) {
                    pull += available.y
                } else if (available.y < 0) {
                    pull = 0f
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    LaunchedEffect(pull) {
        if (pull > 320f) {
            pull = 0f
            onPullDown()
        } else if (pull > 0f) {
            delay(700)
            pull = 0f
        }
    }
    if (folders.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(nested),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.no_videos), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Fixed(columns),
        modifier = Modifier.nestedScroll(nested),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 100.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(folders, key = { it.first }) { (id, list) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { tapHaptic(view); onOpen(id) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CineIcons.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        folderDisplayName(list.first().bucketName),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.videos_count, list.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamTab(prefs: Prefs, columns: Int, state: LazyGridState) {
    val context = LocalContext.current
    val view = LocalView.current
    var url by remember { mutableStateOf("") }
    var streams by remember { mutableStateOf(prefs.getStreams()) }
    LaunchedEffect(com.cinematy.video.StreamIntake.pendingUrl) {
        com.cinematy.video.StreamIntake.pendingUrl?.let { u ->
            url = u
            com.cinematy.video.StreamIntake.pendingUrl = null
        }
    }

    fun open(u: String) {
        prefs.addStream(u)
        streams = prefs.getStreams()
        PlayerHolder.pending = PlayerHolder.Pending.Stream(u)
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.stream_hint)) },
                leadingIcon = { Icon(CineIcons.Link, null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { if (url.isNotBlank()) open(url.trim()) },
            ) { Icon(Icons.Filled.Add, stringResource(R.string.add)) }
        }
        Spacer(Modifier.height(16.dp))
        if (streams.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_streams), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                state = state,
                columns = GridCells.Fixed(columns),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(streams, key = { it }) { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { tapHaptic(view); open(s) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(CineIcons.History, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            s,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { prefs.removeStream(s); streams = prefs.getStreams() }) {
                            Icon(
                                Icons.Filled.Delete, stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(video: Video, resumeMs: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(150.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(video.uri)
                    .videoFrameMillis(resumeMs.coerceAtLeast(1000))
                    .size(360)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            // circular gradient play button
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF7C4DFF), Color(0xFFE040FB))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow, null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                video.cleanTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatTime(resumeMs) + " / " + formatTime(video.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (video.durationMs > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (resumeMs.toFloat() / video.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = Color(0x33FFFFFF),
                )
            }
        }
    }
}

@Composable
private fun DetailsDialog(video: Video, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details)) },
        text = {
            Column {
                Text(
                    video.cleanTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                DetailRow(stringResource(R.string.sort_duration), formatTime(video.durationMs))
                DetailRow(stringResource(R.string.sort_size), formatSize(video.sizeBytes))
                if (video.width > 0 && video.height > 0) {
                    DetailRow(stringResource(R.string.resolution), "${video.width} x ${video.height}")
                }
                DetailRow(
                    stringResource(R.string.folder),
                    video.bucketName.ifBlank { "-" },
                )
                DetailRow(
                    stringResource(R.string.date_added),
                    java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
                    ).format(java.util.Date(video.dateAddedSec * 1000L)),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.name_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HiddenScreen(
    prefs: Prefs,
    tick: Int,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val items = remember(tick) { HiddenStore.list(context) }
    var deleteItem by remember { mutableStateOf<HiddenStore.HiddenVideo?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                stringResource(R.string.hidden) + " (" + items.size + ")",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            stringResource(R.string.hidden_warn),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.hidden_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.storedName }, contentType = { "hidden" }) { item ->
                    var menuOpen by remember { mutableStateOf(false) }
                    BackHandler(enabled = menuOpen) { menuOpen = false }
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .combinedClickable(
                                onClick = {
                                    tapHaptic(view)
                                    com.cinematy.video.player.PlayerHolder.pending =
                                        com.cinematy.video.player.PlayerHolder.Pending.External(
                                            item.uri, item.originalName
                                        )
                                    context.startActivity(
                                        Intent(context, com.cinematy.video.PlayerActivity::class.java)
                                    )
                                },
                                onLongClick = { longHaptic(view); menuOpen = true },
                            ),
                    ) {
                        Box {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(item.file)
                                    .videoFrameMillis(500)
                                    .size(360)
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .placeholder(ColorDrawable(MaterialTheme.colorScheme.surfaceVariant.toArgb()))
                                    .error(ColorDrawable(MaterialTheme.colorScheme.surfaceVariant.toArgb()))
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.restore_hidden)) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            val ok = HiddenStore.unhide(context, item)
                                            kotlinx.coroutines.withContext(
                                                kotlinx.coroutines.Dispatchers.Main
                                            ) {
                                                Toast.makeText(
                                                    context,
                                                    if (ok) R.string.unhidden else R.string.move_failed,
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                if (ok) onChanged()
                                            }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = { menuOpen = false; deleteItem = item },
                                )
                            }
                        }
                        Text(
                            item.originalName.substringBeforeLast('.', item.originalName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
    val del = deleteItem
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text(stringResource(R.string.delete_video_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_video_body,
                        del.originalName.substringBeforeLast('.', del.originalName),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    HiddenStore.deleteHidden(context, del)
                    deleteItem = null
                    onChanged()
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteItem = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsScreen(
    prefs: Prefs,
    onClose: () -> Unit,
    onStream: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var auto by remember { mutableStateOf(prefs.autoNext) }
    var hapt by remember { mutableStateOf(prefs.haptics) }
    var lang by remember { mutableStateOf(prefs.language) }
    var themePref by remember { mutableStateOf(prefs.theme) }
    var langDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var palette by remember { mutableStateOf(prefs.palette) }
    var paletteDialog by remember { mutableStateOf(false) }
    val langLabels = listOf(
        stringResource(R.string.lang_auto),
        stringResource(R.string.lang_ar),
        stringResource(R.string.lang_en),
    )
    val themeLabels = listOf(
        stringResource(R.string.theme_auto),
        stringResource(R.string.theme_dark),
        stringResource(R.string.theme_light),
    )
    val paletteLabels = listOf(
        stringResource(R.string.palette_violet),
        stringResource(R.string.palette_ocean),
        stringResource(R.string.palette_sunset),
        stringResource(R.string.palette_rose),
    )
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                stringResource(R.string.settings),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Column(Modifier.padding(horizontal = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { tapHaptic(view); onStream() }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CineIcons.Link, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.tab_stream),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        auto = !auto
                        prefs.autoNext = auto
                        tapHaptic(view)
                    }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CineIcons.SkipNext, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.auto_play_next),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = auto,
                    onCheckedChange = { auto = it; prefs.autoNext = it },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        hapt = !hapt
                        Haptics.enabled = hapt
                        prefs.haptics = hapt
                        tapHaptic(view)
                    }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CineIcons.Movie, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.haptics),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = hapt,
                    onCheckedChange = {
                        hapt = it
                        Haptics.enabled = it
                        prefs.haptics = it
                    },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { tapHaptic(view); langDialog = true }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CineIcons.Translate, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.settings_language),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    langLabels[lang],
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { tapHaptic(view); themeDialog = true }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CineIcons.Moon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.settings_theme),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    themeLabels[themePref],
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { tapHaptic(view); paletteDialog = true }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.settings_colors),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    paletteLabels[palette],
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Y video " + com.cinematy.video.BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 24.dp),
            )
        }
    }
    if (langDialog) {
        AlertDialog(
            onDismissRequest = { langDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    langLabels.forEachIndexed { i, label ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    lang = i
                                    prefs.language = i
                                    langDialog = false
                                    (context as? android.app.Activity)?.recreate()
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = lang == i, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { langDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text(stringResource(R.string.settings_theme)) },
            text = {
                Column {
                    themeLabels.forEachIndexed { i, label ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    themePref = i
                                    prefs.theme = i
                                    UiThemeState.themePref = i
                                    themeDialog = false
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = themePref == i, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { themeDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
    if (paletteDialog) {
        AlertDialog(
            onDismissRequest = { paletteDialog = false },
            title = { Text(stringResource(R.string.settings_colors)) },
            text = {
                Column {
                    paletteLabels.forEachIndexed { i, label ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    palette = i
                                    prefs.palette = i
                                    UiThemeState.palettePref = i
                                    paletteDialog = false
                                    com.cinematy.video.IconSwitcher.apply(context, i)
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = palette == i, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { paletteDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun NowPlayingBar(tick: Int) {
    val context = LocalContext.current
    val view = LocalView.current
    val player = remember(tick) { PlayerHolder.peek() }
    val item = remember(tick) { player?.currentMediaItem }
    val playing = remember(tick) { player?.isPlaying == true }
    if (player != null && item != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                .clickable {
                    tapHaptic(view)
                    context.startActivity(Intent(context, PlayerActivity::class.java))
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = item.localConfiguration?.uri,
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                item.mediaMetadata.title?.toString() ?: item.mediaId,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                tapHaptic(view)
                if (playing) player.pause() else player.play()
            }) {
                Icon(
                    if (playing) CineIcons.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (playing) R.string.pause else R.string.play),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
