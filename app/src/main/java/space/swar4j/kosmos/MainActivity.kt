package space.swar4j.kosmos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val permissionPrefs = getSharedPreferences("kosmos_settings", MODE_PRIVATE)
            val hasAskedBefore = permissionPrefs.getBoolean("notification_permission_requested", false)
            if (!hasAskedBefore || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                permissionPrefs.edit { putBoolean("notification_permission_requested", true) }
                window.decorView.post {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        val storage = WallpaperStorage(applicationContext)
        val applier = StaticWallpaperApplier(applicationContext)
        val liveLauncher = LiveWallpaperLauncher(applicationContext)
        val metadata = WallpaperMetadataStore(applicationContext)
        val catalogue = CatalogRepository(applicationContext)

        setContent {
            KosmosTheme {
                KosmosScreen(
                    storage = storage,
                    applier = applier,
                    liveLauncher = liveLauncher,
                    metadata = metadata,
                    catalogue = catalogue
                )
            }
        }
    }
}

@Composable
private fun KosmosScreen(
    storage: WallpaperStorage,
    applier: StaticWallpaperApplier,
    liveLauncher: LiveWallpaperLauncher,
    metadata: WallpaperMetadataStore,
    catalogue: CatalogRepository
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var hasInternet by remember { mutableStateOf(false) }
    val connectivityManager = remember {
        context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    fun updateInternetState() {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    DisposableEffect(connectivityManager) {
        updateInternetState()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { updateInternetState() }
            override fun onLost(network: Network) { updateInternetState() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updateInternetState()
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        onDispose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
    }

    LaunchedEffect(Unit) {
        val preferences = context.getSharedPreferences(LiveWallpaperLauncher.PREFERENCES, android.content.Context.MODE_PRIVATE)
        while (true) {
            delay(750)
            val failure = preferences.getString(LiveWallpaperLauncher.PLAYBACK_ERROR, null)
            if (!failure.isNullOrBlank()) {
                val code = preferences.getString(LiveWallpaperLauncher.PLAYBACK_ERROR_CODE, "KOS-UNKNOWN")
                val category = preferences.getString(LiveWallpaperLauncher.PLAYBACK_ERROR_CATEGORY, "Unknown")
                val detail = preferences.getString(LiveWallpaperLauncher.PLAYBACK_ERROR_DETAIL, null)
                preferences.edit {
                    remove(LiveWallpaperLauncher.PLAYBACK_ERROR)
                    remove(LiveWallpaperLauncher.PLAYBACK_ERROR_CODE)
                    remove(LiveWallpaperLauncher.PLAYBACK_ERROR_CATEGORY)
                    remove(LiveWallpaperLauncher.PLAYBACK_ERROR_DETAIL)
                }
                val diagnostic = detail?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
                snackbarHostState.showSnackbar("Wallpaper failed [$code] • $category: $failure$diagnostic")
            }
        }
    }
    var wallpapers by remember { mutableStateOf<List<StoredWallpaper>>(emptyList()) }
    var catalogItems by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var onlineResults by remember { mutableStateOf<List<OnlineWallpaperResult>>(emptyList()) }
    var onlinePage by rememberSaveable { mutableIntStateOf(1) }
    var onlineQuery by rememberSaveable { mutableStateOf("") }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var isDiscoverLoading by remember { mutableStateOf(false) }
    var selectedWallpaper by remember { mutableStateOf<StoredWallpaper?>(null) }
    var showApplyDialog by remember { mutableStateOf(false) }
    var deleteAfterApply by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var isLoadingCatalogue by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var favoriteVersion by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableStateOf("Home") }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var discoverResults by remember { mutableStateOf<List<OnlineWallpaperResult>>(emptyList()) }
    var discoverIndex by rememberSaveable { mutableIntStateOf(0) }
    val discoverQueries = remember { listOf("nature landscape", "space nebula", "abstract wallpaper", "anime illustration", "night city architecture", "ocean mountain") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showCategoryMenu by remember { mutableStateOf(false) }

    val performOnlineSearch: () -> Unit = {
        if (hasInternet && onlineQuery.isNotBlank() && !isSearchingOnline) {
            tab = "Search"
            isSearchingOnline = true
            onlinePage = 1
            onlineResults = emptyList()
            scope.launch {
                try {
                    val results = OnlineWallpaperSearch().search(onlineQuery, page = 1)
                    onlineResults = results
                    if (results.isEmpty()) snackbarHostState.showSnackbar("No matching images found. Try a broader search.")
                } catch (error: Exception) {
                    snackbarHostState.showSnackbar(error.message ?: "Image search failed")
                } finally {
                    isSearchingOnline = false
                }
            }
        }
    }

    fun refreshLibrary() {
        scope.launch {
            wallpapers = withContext(Dispatchers.IO) { storage.listWallpapers() }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            isImporting = true
            runCatching { storage.importMedia(uri) }
                .onSuccess {
                    refreshLibrary()
                    selectedWallpaper = it
                    snackbarHostState.showSnackbar("Added to your library")
                }
                .onFailure { snackbarHostState.showSnackbar(it.message ?: "Couldn't import this file") }
            isImporting = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            isImporting = true
            var imported = 0
            uris.forEach { uri ->
                runCatching { storage.importMedia(uri) }
                    .onSuccess { imported++ }
                    .onFailure { snackbarHostState.showSnackbar(it.message ?: "Couldn't import a selected item") }
            }
            refreshLibrary()
            snackbarHostState.showSnackbar("$imported item(s) added from gallery")
            isImporting = false
        }
    }

    LaunchedEffect(Unit) {
        wallpapers = withContext(Dispatchers.IO) {
            storage.ensurePresets()
            storage.listWallpapers()
        }
        isLoadingCatalogue = true
        catalogItems = catalogue.load()
        isLoadingCatalogue = false
    }
    LaunchedEffect(tab, hasInternet) {
        if (tab == "Discover" && hasInternet && discoverResults.isEmpty() && !isDiscoverLoading) {
            isDiscoverLoading = true
            try {
                val initialQueries = (0 until 3).map { offset ->
                    discoverQueries[(discoverIndex + offset) % discoverQueries.size]
                }
                val results = coroutineScope {
                    initialQueries.map { query ->
                        async { runCatching { OnlineWallpaperSearch().search(query, page = 1) }.getOrDefault(emptyList()) }
                    }.awaitAll().flatten()
                }.distinctBy { it.downloadUrl.substringBefore('?') }.take(30)
                discoverResults = results
                if (results.isEmpty()) snackbarHostState.showSnackbar("Discover couldn't find images right now. Try Refresh.")
            } finally {
                isDiscoverLoading = false
            }
        }
    }


    val filteredWallpapers = remember(wallpapers, filter, searchQuery, favoriteVersion) { wallpapers.filter { wallpaper ->
        val matchesSearch = searchQuery.isBlank() ||
            wallpaper.title.contains(searchQuery, ignoreCase = true) ||
            metadata.category(wallpaper.file.name).contains(searchQuery, ignoreCase = true)
        val matchesFilter = when (filter) {
            "Favorites" -> metadata.isFavorite(wallpaper.file.name)
            "Photos" -> !wallpaper.isAnimated
            "Animated" -> wallpaper.isAnimated
            "Presets" -> wallpaper.isPreset || metadata.isPreset(wallpaper.file.name)
            in WallpaperMetadataStore.CATEGORIES -> metadata.category(wallpaper.file.name) == filter
            else -> true
        }
        matchesSearch && matchesFilter
    } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF100B1B), Color(0xFF21101D), Color(0xFF39200F), Color(0xFF090A0D)))).padding(padding).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.arka_banner),
                    contentDescription = "Arka by ZeroTech",
                    modifier = Modifier.weight(1f).height(72.dp),
                    contentScale = ContentScale.Fit
                )
                OutlinedTextField(
                    value = onlineQuery,
                    onValueChange = { onlineQuery = it },
                    modifier = Modifier.width(140.dp).height(56.dp),
                    singleLine = true,
                    enabled = hasInternet && !isSearchingOnline,
                    placeholder = { Text("SEARCH") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { performOnlineSearch() }),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Home", "Library", "Discover", "Presets").forEach { destination ->
                    FilterChip(
                        selected = tab == destination,
                        onClick = { tab = destination },
                        label = { Text(destination) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (tab == "Home") {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HomeActionCard(
                                title = "Import from gallery",
                                icon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                                modifier = Modifier.weight(1f),
                                enabled = !isImporting,
                                onClick = {
                                    galleryLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                        )
                                    )
                                }
                            )
                            HomeActionCard(
                                title = "Import from files",
                                icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                                modifier = Modifier.weight(1f),
                                enabled = !isImporting,
                                onClick = { importLauncher.launch(SUPPORTED_MIME_TYPES) }
                            )
                            HomeActionCard(
                                title = "Presets",
                                icon = { Icon(Icons.Outlined.Wallpaper, contentDescription = null) },
                                modifier = Modifier.weight(1f),
                                enabled = true,
                                onClick = { tab = "Presets" }
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Favorites", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { tab = "Library"; filter = "Favorites" }) { Text("View all") }
                        }
                        val favorites = wallpapers.filter { metadata.isFavorite(it.file.name) }.take(2)
                        if (favorites.isEmpty()) {
                            Text("No favorites saved yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                favorites.forEach { wallpaper ->
                                    MiniWallpaperTile(
                                        wallpaper = wallpaper,
                                        modifier = Modifier.weight(1f),
                                        onClick = { selectedWallpaper = wallpaper }
                                    )
                                }
                                if (favorites.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Recently added", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { tab = "Library" }) { Text("Library") }
                        }
                        val recent = wallpapers.filterNot { it.file.name.startsWith("preset_") }.take(2)
                        if (recent.isEmpty()) {
                            Text("Import a wallpaper to get started.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                recent.forEach { wallpaper ->
                                    MiniWallpaperTile(
                                        wallpaper = wallpaper,
                                        modifier = Modifier.weight(1f),
                                        onClick = { selectedWallpaper = wallpaper }
                                    )
                                }
                                if (recent.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else if (tab == "Library") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Your library", style = MaterialTheme.typography.titleLarge)
                    Text("${wallpapers.size} saved", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search your wallpapers") }
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (listOf("All", "Favorites", "Photos", "Animated", "Presets") +
                        WallpaperMetadataStore.CATEGORIES).distinct().forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (filteredWallpapers.isEmpty()) {
                    EmptyLibrary(
                        isImporting = isImporting,
                        isFiltered = wallpapers.isNotEmpty(),
                        onImport = { galleryLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredWallpapers, key = { it.file.name }) { wallpaper ->
                            WallpaperTile(
                                wallpaper = wallpaper,
                                favorite = metadata.isFavorite(wallpaper.file.name),
                                onClick = { selectedWallpaper = wallpaper },
                                onFavorite = {
                                    metadata.setFavorite(wallpaper.file.name, !metadata.isFavorite(wallpaper.file.name))
                                    favoriteVersion++
                                },
                                onApply = { selectedWallpaper = wallpaper; showApplyDialog = true },
                                onSavePreset = {
                                    metadata.setPreset(wallpaper.file.name, true)
                                    favoriteVersion++
                                    scope.launch { snackbarHostState.showSnackbar("Added to Presets") }
                                },
                                onRename = { newTitle ->
                                    scope.launch {
                                        runCatching { storage.renameWallpaper(wallpaper.file, newTitle) }
                                            .onSuccess {
                                                metadata.rename(wallpaper.file.name, it.file.name)
                                                refreshLibrary()
                                                snackbarHostState.showSnackbar("Wallpaper renamed")
                                            }
                                            .onFailure { snackbarHostState.showSnackbar(it.message ?: "Couldn't rename wallpaper") }
                                    }
                                },
                                onRemove = {
                                    scope.launch {
                                        runCatching {
                                            storage.deleteWallpaper(wallpaper.file)
                                            metadata.remove(wallpaper.file.name)
                                        }.onSuccess { refreshLibrary(); snackbarHostState.showSnackbar("Wallpaper removed") }
                                            .onFailure { snackbarHostState.showSnackbar(it.message ?: "Couldn't remove wallpaper") }
                                    }
                                }
                            )
                        }
                    }
                }
                Button(
                    onClick = { galleryLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (isImporting) "Importing…" else "Import wallpaper")
                }
            } else if (tab == "Discover") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Discover", style = MaterialTheme.typography.titleLarge)
                        Text("Fresh wallpapers selected for you", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = {
                            if (hasInternet && !isDiscoverLoading) {
                                val nextIndex = (discoverIndex + 1) % discoverQueries.size
                                discoverIndex = nextIndex
                                scope.launch {
                                    isDiscoverLoading = true
                                    try {
                                        val refreshed = coroutineScope {
                                            (0 until 3).map { offset ->
                                                async {
                                                    val query = discoverQueries[(nextIndex + offset) % discoverQueries.size]
                                                    runCatching { OnlineWallpaperSearch().search(query, page = 1) }.getOrDefault(emptyList())
                                                }
                                            }.awaitAll().flatten()
                                        }
                                        val unique = refreshed.distinctBy { it.downloadUrl.substringBefore('?') }
                                        if (unique.isNotEmpty()) {
                                            discoverResults = unique.take(30)
                                        } else {
                                            snackbarHostState.showSnackbar("No fresh wallpapers were found. Your current results are still here.")
                                        }
                                    } catch (error: Exception) {
                                        snackbarHostState.showSnackbar(error.message ?: "Discover refresh failed. Your current results are still here.")
                                    } finally {
                                        isDiscoverLoading = false
                                    }
                                }
                            }
                        },
                        enabled = hasInternet && !isDiscoverLoading
                    ) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh Discover") }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    !hasInternet -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Connect to the internet to discover wallpapers", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    isDiscoverLoading && discoverResults.isEmpty() -> GhostWallpaperGrid(Modifier.weight(1f))
                    discoverResults.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No images available right now. Try Refresh.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(discoverResults, key = { it.id }) { result ->
                            OnlineSearchTile(
                                result = result,
                                downloading = downloadingId == "discover-${result.id}",
                                favorite = metadata.isOnlineFavorite(result.id),
                                onFavorite = {
                                    metadata.setOnlineFavorite(result.id, !metadata.isOnlineFavorite(result.id))
                                    favoriteVersion++
                                },
                                onDownload = {
                                    if (downloadingId == null) scope.launch {
                                        downloadingId = "discover-${result.id}"
                                        runCatching { storage.downloadSearchResult(result) }
                                            .onSuccess { refreshLibrary(); snackbarHostState.showSnackbar("Saved to your library") }
                                            .onFailure { snackbarHostState.showSnackbar(it.message ?: "Download failed") }
                                        downloadingId = null
                                    }
                                },
                                onUseAsWallpaper = {
                                    if (downloadingId == null) scope.launch {
                                        downloadingId = "discover-${result.id}"
                                        runCatching { storage.downloadSearchResult(result, allowDuplicate = false) }
                                            .onSuccess { downloaded ->
                                                refreshLibrary()
                                                deleteAfterApply = true
                                                selectedWallpaper = downloaded
                                                showApplyDialog = true
                                            }
                                            .onFailure { snackbarHostState.showSnackbar(it.message ?: "Couldn't download wallpaper") }
                                        downloadingId = null
                                    }
                                }
                            )
                        }
                    }
                }
            } else if (tab == "Presets") {
                val userPresets = wallpapers.filter { !it.isPreset && metadata.isPreset(it.file.name) }
                if (userPresets.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No Presets are currently saved", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(userPresets, key = { it.file.name }) { wallpaper ->
                            PresetTile(
                                wallpaper = wallpaper,
                                onApply = {
                                    if (wallpaper.isAnimated) {
                                        runCatching { liveLauncher.launch(wallpaper.file) }
                                            .onFailure { scope.launch { snackbarHostState.showSnackbar(it.message ?: "Couldn't open live wallpaper") } }
                                    } else {
                                        applyWallpaper(scope, applier, wallpaper, WallpaperDestination.BOTH, snackbarHostState) { }
                                    }
                                },
                                onRemove = {
                                    metadata.setPreset(wallpaper.file.name, false)
                                    favoriteVersion++
                                }
                            )
                        }
                    }
                }
                Button(
                    onClick = { showAddPresetDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Preset")
                }
            } else {
                Text("Search results", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                when {
                    !hasInternet -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No Internet Connection", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("connect to the internet to search for wallpapers",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    isSearchingOnline && onlineResults.isEmpty() -> GhostWallpaperGrid(Modifier.weight(1f))
                    onlineResults.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Search for something from the bar above.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        Text("${onlineResults.size} results for “${onlineQuery}”",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 148.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(onlineResults, key = { it.id }) { result ->
                                OnlineSearchTile(
                                    result = result,
                                    downloading = downloadingId == "search-${result.id}",
                                favorite = metadata.isOnlineFavorite(result.id),
                                onFavorite = {
                                    metadata.setOnlineFavorite(result.id, !metadata.isOnlineFavorite(result.id))
                                    favoriteVersion++
                                },
                                    onDownload = {
                                        if (downloadingId == null) scope.launch {
                                            downloadingId = "search-${result.id}"
                                            runCatching { storage.downloadSearchResult(result) }
                                                .onSuccess { refreshLibrary(); snackbarHostState.showSnackbar("Saved to your library") }
                                                .onFailure { snackbarHostState.showSnackbar(it.message ?: "Download failed") }
                                            downloadingId = null
                                        }
                                    },
                                    onUseAsWallpaper = {
                                        if (downloadingId == null) scope.launch {
                                            downloadingId = "search-${result.id}"
                                            runCatching { storage.downloadSearchResult(result, allowDuplicate = false) }
                                                .onSuccess { downloaded ->
                                                    refreshLibrary()
                                                    deleteAfterApply = true
                                                    selectedWallpaper = downloaded
                                                    showApplyDialog = true
                                                }
                                                .onFailure { snackbarHostState.showSnackbar(it.message ?: "Couldn't download wallpaper") }
                                            downloadingId = null
                                        }
                                    }
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                if (!isSearchingOnline) scope.launch {
                                    isSearchingOnline = true
                                    val nextPage = onlinePage + 1
                                    runCatching { OnlineWallpaperSearch().search(onlineQuery, page = nextPage) }
                                        .onSuccess { more ->
                                            if (more.isNotEmpty()) {
                                                onlineResults = (onlineResults + more).distinctBy { it.id }
                                                onlinePage = nextPage
                                            } else snackbarHostState.showSnackbar("No more results for this search")
                                        }
                                        .onFailure { snackbarHostState.showSnackbar(it.message ?: "Couldn't load more results") }
                                    isSearchingOnline = false
                                }
                            },
                            enabled = hasInternet && !isSearchingOnline
                        ) { Text(if (isSearchingOnline) "Loading…" else "Load more") }
                    }
                }
            }
        }
    }

    if (showAddPresetDialog) {
        AlertDialog(
            onDismissRequest = { showAddPresetDialog = false },
            title = { Text("Add a preset") },
            text = {
                if (wallpapers.isEmpty()) {
                    Text("Import a wallpaper first.")
                } else {
                    LazyColumn(Modifier.height(280.dp)) {
                        items(wallpapers.filterNot { it.isPreset || metadata.isPreset(it.file.name) }, key = { it.file.name }) { wallpaper ->
                            TextButton(
                                onClick = {
                                    metadata.setPreset(wallpaper.file.name, true)
                                    favoriteVersion++
                                    showAddPresetDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(wallpaper.title, maxLines = 1) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddPresetDialog = false }) { Text("Done") } }
        )
    }

    if (!showApplyDialog) selectedWallpaper?.let { wallpaper ->
        Dialog(onDismissRequest = { selectedWallpaper = null; showApplyDialog = false; deleteAfterApply = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp)) {
                    AsyncImage(
                        model = wallpaper.thumbnail,
                        contentDescription = wallpaper.title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(wallpaper.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (wallpaper.isAnimated) "Animated wallpaper" else "Static wallpaper",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            metadata.setFavorite(wallpaper.file.name, !metadata.isFavorite(wallpaper.file.name))
                            favoriteVersion++
                        }) {
                            Text(if (metadata.isFavorite(wallpaper.file.name)) "★ Saved" else "☆ Save")
                        }
                        Box {
                            TextButton(onClick = { showCategoryMenu = true }) {
                                Text(metadata.category(wallpaper.file.name))
                            }
                            DropdownMenu(
                                expanded = showCategoryMenu,
                                onDismissRequest = { showCategoryMenu = false }
                            ) {
                                WallpaperMetadataStore.CATEGORIES.forEach { category ->
                                    DropdownMenuItem(
                                        text = { Text(category) },
                                        onClick = {
                                            metadata.setCategory(wallpaper.file.name, category)
                                            favoriteVersion++
                                            showCategoryMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            if (wallpaper.isAnimated) {
                                runCatching { liveLauncher.launch(wallpaper.file) }
                                    .onSuccess { selectedWallpaper = null }
                                    .onFailure { scope.launch { snackbarHostState.showSnackbar(it.message ?: "Couldn't open live preview") } }
                            } else showApplyDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (wallpaper.isAnimated) "Preview live wallpaper" else "Apply wallpaper") }
                    TextButton(onClick = { selectedWallpaper = null; showApplyDialog = false; deleteAfterApply = false }, modifier = Modifier.align(Alignment.End)) {
                        Text("Close")
                    }
                }
            }
        }
    }

    if (showApplyDialog && selectedWallpaper != null) {
        AlertDialog(
            onDismissRequest = { showApplyDialog = false; deleteAfterApply = false },
            title = { Text("Apply wallpaper") },
            text = { Text("Where would you like to use this wallpaper?") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    listOf(
                        "Home screen" to WallpaperDestination.HOME,
                        "Lock screen" to WallpaperDestination.LOCK,
                        "Both" to WallpaperDestination.BOTH
                    ).forEach { (label, destination) ->
                        TextButton(onClick = {
                            val wallpaper = selectedWallpaper ?: return@TextButton
                            applyWallpaper(scope, applier, wallpaper, destination, snackbarHostState) {
                                showApplyDialog = false
                                selectedWallpaper = null
                                if (deleteAfterApply) {
                                    deleteAfterApply = false
                                    scope.launch {
                                        runCatching {
                                            storage.deleteWallpaper(wallpaper.file)
                                            metadata.remove(wallpaper.file.name)
                                            refreshLibrary()
                                        }.onFailure {
                                            snackbarHostState.showSnackbar("Wallpaper applied, but its temporary download couldn't be removed")
                                        }
                                    }
                                }
                            }
                        }) { Text(label) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyDialog = false; deleteAfterApply = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EmptyLibrary(
    isImporting: Boolean,
    isFiltered: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { Text("✳", style = MaterialTheme.typography.headlineLarge) }
            Spacer(Modifier.height(18.dp))
            Text(if (isFiltered) "Nothing found" else "A blank canvas", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                if (isFiltered) "Try another filter or search." else "Import a wallpaper to start building your library.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            if (!isFiltered) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onImport, enabled = !isImporting) {
                    Text(if (isImporting) "Importing…" else "Choose a wallpaper")
                }
            }
        }
    }
}

@Composable
private fun WallpaperTile(
    wallpaper: StoredWallpaper,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onApply: () -> Unit,
    onSavePreset: () -> Unit,
    onRename: (String) -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember(wallpaper.file.name) { mutableStateOf(wallpaper.title) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Box {
                AsyncImage(
                    model = wallpaper.thumbnail,
                    contentDescription = wallpaper.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.78f).clickable(onClick = onClick),
                    contentScale = ContentScale.Crop
                )
                TextButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                    Text(
                        "★",
                        color = if (favorite) Color(0xFFFFC928) else Color(0xFFB8B8B8),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(wallpaper.title, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Wallpaper options")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Apply") }, onClick = { menuExpanded = false; onApply() })
                            DropdownMenuItem(text = { Text("Rename") }, enabled = !wallpaper.isPreset,
                                onClick = { menuExpanded = false; showRename = true })
                            DropdownMenuItem(text = { Text("Save to Presets") }, onClick = { menuExpanded = false; onSavePreset() })
                            DropdownMenuItem(text = { Text(if (favorite) "Remove from Favorites" else "Favourite") },
                                onClick = { menuExpanded = false; onFavorite() })
                            if (!wallpaper.isPreset) {
                                DropdownMenuItem(text = { Text("Remove") }, onClick = { menuExpanded = false; onRemove() })
                            }
                        }
                    }
                }
                Text(
                    when {
                        wallpaper.isPreset -> "Preinstalled"
                        wallpaper.isAnimated -> "Animated"
                        else -> "Photo"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename wallpaper") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Wallpaper name") }
                )
            },
            confirmButton = {
                TextButton(onClick = { showRename = false; onRename(renameText) }, enabled = renameText.isNotBlank()) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun OnlineSearchTile(
    result: OnlineWallpaperResult,
    downloading: Boolean,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onDownload: () -> Unit,
    onUseAsWallpaper: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val imageRequest = remember(result.previewUrl) {
        ImageRequest.Builder(context)
            .data(result.previewUrl)
            .crossfade(true)
            .setHeader("User-Agent", "ArkaAndroid/0.8 (wallpaper previews; https://github.com/xxSwar4jxx/kosmos)")
            .setHeader("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            .build()
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(9.dp)) {
            Box {
                SubcomposeAsyncImage(
                    model = imageRequest,
                    contentDescription = result.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.82f).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                    loading = {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Spacer(Modifier.fillMaxWidth(0.76f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                            Spacer(Modifier.height(8.dp))
                            Spacer(Modifier.fillMaxWidth(0.48f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
                        }
                    },
                    error = {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Preview unavailable",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    success = { SubcomposeAsyncImageContent() }
                )
                TextButton(
                    onClick = onFavorite,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(if (favorite) "★" else "☆", color = if (favorite) Color(0xFFFFC928) else Color(0xFF30251A),
                        style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(result.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Text(result.creator, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(result.license, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = onDownload,
                    enabled = !downloading,
                    modifier = Modifier.weight(1f)
                ) {
                    if (downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.FileDownload, contentDescription = "Download wallpaper")
                }
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = onUseAsWallpaper,
                    enabled = !downloading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Wallpaper, contentDescription = "Use as wallpaper")
                }
            }
        }
    }
}

@Composable
private fun CatalogueTile(
    item: CatalogItem,
    downloading: Boolean,
    onDownload: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val previewRequest = remember(item.previewUrl) {
        ImageRequest.Builder(context)
            .data(item.previewUrl)
            .crossfade(true)
            .setHeader("User-Agent", "ArkaAndroid/0.8 (catalogue previews; https://github.com/xxSwar4jxx/kosmos)")
            .setHeader("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            .build()
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SubcomposeAsyncImage(
                model = previewRequest,
                contentDescription = item.title,
                modifier = Modifier.size(width = 104.dp, height = 140.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                loading = {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                },
                error = {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Preview unavailable", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                success = { SubcomposeAsyncImageContent() }
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(item.category, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(5.dp))
                    Text(item.creator, style = MaterialTheme.typography.bodySmall)
                    Text(item.license, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onDownload, enabled = !downloading) {
                    if (downloading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Download")
                }
            }
        }
    }
}

private fun applyWallpaper(
    scope: kotlinx.coroutines.CoroutineScope,
    applier: StaticWallpaperApplier,
    wallpaper: StoredWallpaper,
    destination: WallpaperDestination,
    snackbarHostState: SnackbarHostState,
    onApplied: () -> Unit
) {
    scope.launch {
        runCatching { applier.apply(wallpaper.file, destination) }
            .onSuccess {
                onApplied()
                snackbarHostState.showSnackbar("Wallpaper applied")
            }
            .onFailure { snackbarHostState.showSnackbar(it.message ?: "Couldn't apply wallpaper") }
    }
}

private val SUPPORTED_MIME_TYPES = arrayOf(
    "image/jpeg", "image/png", "image/webp", "image/gif", "video/mp4"
)
