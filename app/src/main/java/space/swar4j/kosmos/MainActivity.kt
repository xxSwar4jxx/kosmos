package space.swar4j.kosmos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.weight
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = WallpaperStorage(applicationContext)
        val applier = StaticWallpaperApplier(applicationContext)
        val liveLauncher = LiveWallpaperLauncher(applicationContext)
        val metadata = WallpaperMetadataStore(applicationContext)
        val catalogue = CatalogRepository(applicationContext)

        setContent {
            val themePreferences = remember {
                getSharedPreferences("kosmos_settings", MODE_PRIVATE)
            }
            var darkTheme by rememberSaveable {
                mutableStateOf(themePreferences.getBoolean("dark_theme", true))
            }
            KosmosTheme(darkTheme = darkTheme) {
                KosmosScreen(
                    storage = storage,
                    applier = applier,
                    liveLauncher = liveLauncher,
                    metadata = metadata,
                    catalogue = catalogue,
                    darkTheme = darkTheme,
                    onThemeChange = {
                        darkTheme = it
                        themePreferences.edit().putBoolean("dark_theme", it).apply()
                    }
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
    catalogue: CatalogRepository,
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var wallpapers by remember { mutableStateOf<List<StoredWallpaper>>(emptyList()) }
    var catalogItems by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var selectedWallpaper by remember { mutableStateOf<StoredWallpaper?>(null) }
    var showApplyDialog by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var isLoadingCatalogue by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var favoriteVersion by remember { mutableStateOf(0) }
    var tab by rememberSaveable { mutableStateOf("Library") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showCategoryMenu by remember { mutableStateOf(false) }

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

    LaunchedEffect(Unit) {
        wallpapers = withContext(Dispatchers.IO) {
            storage.ensurePresets()
            storage.listWallpapers()
        }
        isLoadingCatalogue = true
        catalogItems = catalogue.load()
        isLoadingCatalogue = false
    }

    val filteredWallpapers = remember(wallpapers, filter, searchQuery, favoriteVersion) { wallpapers.filter { wallpaper ->
        val matchesSearch = searchQuery.isBlank() ||
            wallpaper.title.contains(searchQuery, ignoreCase = true) ||
            metadata.category(wallpaper.file.name).contains(searchQuery, ignoreCase = true)
        val matchesFilter = when (filter) {
            "Favorites" -> metadata.isFavorite(wallpaper.file.name)
            "Photos" -> !wallpaper.isAnimated
            "Animated" -> wallpaper.isAnimated
            "Presets" -> wallpaper.isPreset
            in WallpaperMetadataStore.CATEGORIES -> metadata.category(wallpaper.file.name) == filter
            else -> true
        }
        matchesSearch && matchesFilter
    } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("KOSMOS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Wallpapers in motion.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onThemeChange(!darkTheme) }) {
                    Text(if (darkTheme) "Light mode" else "Dark mode")
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Library", "Discover").forEach { destination ->
                    FilterChip(
                        selected = tab == destination,
                        onClick = { tab = destination },
                        label = { Text(destination) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (tab == "Library") {
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
                        onImport = { importLauncher.launch(SUPPORTED_MIME_TYPES) },
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
                                }
                            )
                        }
                    }
                }
                Button(
                    onClick = { importLauncher.launch(SUPPORTED_MIME_TYPES) },
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (isImporting) "Importing…" else "Import wallpaper")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Discover", style = MaterialTheme.typography.titleLarge)
                        Text("A small, curated collection", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                isLoadingCatalogue = true
                                catalogItems = catalogue.load(forceRefresh = true)
                                isLoadingCatalogue = false
                            }
                        },
                        enabled = !isLoadingCatalogue
                    ) { Text("Refresh") }
                }
                Spacer(Modifier.height(8.dp))
                if (isLoadingCatalogue && catalogItems.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (catalogItems.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Catalogue unavailable. Your saved wallpapers still work offline.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(catalogItems, key = { it.id }) { item ->
                            CatalogueTile(
                                item = item,
                                downloading = downloadingId == item.id,
                                onDownload = {
                                    if (downloadingId != null) return@CatalogueTile
                                    scope.launch {
                                        downloadingId = item.id
                                        runCatching { storage.downloadCatalogueItem(item) }
                                            .onSuccess {
                                                refreshLibrary()
                                                snackbarHostState.showSnackbar("Saved to your library")
                                            }
                                            .onFailure {
                                                snackbarHostState.showSnackbar(it.message ?: "Download failed")
                                            }
                                        downloadingId = null
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    selectedWallpaper?.let { wallpaper ->
        Dialog(onDismissRequest = { selectedWallpaper = null }) {
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
                    TextButton(onClick = { selectedWallpaper = null }, modifier = Modifier.align(Alignment.End)) {
                        Text("Close")
                    }
                }
            }
        }
    }

    if (showApplyDialog && selectedWallpaper != null) {
        AlertDialog(
            onDismissRequest = { showApplyDialog = false },
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
                            }
                        }) { Text(label) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyDialog = false }) { Text("Cancel") }
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
                if (isFiltered) "Try another filter or search." else "Your presets are being prepared. Import something to add more.",
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
    onFavorite: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Box {
                AsyncImage(
                    model = wallpaper.thumbnail,
                    contentDescription = wallpaper.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.78f),
                    contentScale = ContentScale.Crop
                )
                TextButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd)) {
                    Text(if (favorite) "★" else "☆")
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(wallpaper.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    when {
                        wallpaper.isPreset -> "Preset"
                        wallpaper.isAnimated -> "Animated"
                        else -> "Photo"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = item.previewUrl,
                contentDescription = item.title,
                modifier = Modifier.size(width = 104.dp, height = 140.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
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
