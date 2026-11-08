package space.swar4j.kosmos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var wallpapers by remember { mutableStateOf<List<StoredWallpaper>>(emptyList()) }
    var selectedWallpaper by remember { mutableStateOf<StoredWallpaper?>(null) }
    var showApplyDialog by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                runCatching { storage.importMedia(uri) }
                    .onSuccess { imported ->
                        wallpapers = withContext(Dispatchers.IO) { storage.listWallpapers() }
                        selectedWallpaper = imported
                        snackbarHostState.showSnackbar("Added to your library")
                    }
                    .onFailure { error ->
                        snackbarHostState.showSnackbar(
                            error.message ?: "Couldn't import this file"
                        )
                    }
                isImporting = false
            }
        }
    }

    LaunchedEffect(Unit) {
        wallpapers = withContext(Dispatchers.IO) { storage.listWallpapers() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "KOSMOS",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Wallpapers in motion.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onThemeChange(!darkTheme) }) {
                    Text(if (darkTheme) "Light mode" else "Dark mode")
                }
            }

            Spacer(Modifier.height(28.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Your library", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${wallpapers.size} saved",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(14.dp))

            if (wallpapers.isEmpty()) {
                EmptyLibrary(
                    isImporting = isImporting,
                    onImport = {
                        importLauncher.launch(arrayOf(
                            "image/jpeg", "image/png", "image/webp", "image/gif", "video/mp4"
                        ))
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(wallpapers, key = { it.file.absolutePath }) { wallpaper ->
                        WallpaperTile(
                            wallpaper = wallpaper,
                            onClick = { selectedWallpaper = wallpaper }
                        )
                    }
                }
            }

            Button(
                onClick = {
                    importLauncher.launch(arrayOf(
                        "image/jpeg", "image/png", "image/webp", "image/gif", "video/mp4"
                    ))
                },
                enabled = !isImporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(if (isImporting) "Importing…" else "Import wallpaper")
            }
        }
    }

    selectedWallpaper?.let { wallpaper ->
        Dialog(onDismissRequest = { selectedWallpaper = null }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(16.dp)) {
                    AsyncImage(
                        model = wallpaper.thumbnail,
                        contentDescription = wallpaper.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.72f)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(wallpaper.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (wallpaper.isAnimated) {
                                runCatching { liveLauncher.launch(wallpaper.file) }
                                    .onSuccess {
                                        selectedWallpaper = null
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Continue in Android's wallpaper preview"
                                            )
                                        }
                                    }
                                    .onFailure { error ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                error.message ?: "Couldn't open live wallpaper preview"
                                            )
                                        }
                                    }
                            } else {
                                showApplyDialog = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (wallpaper.isAnimated) "Preview live wallpaper" else "Apply wallpaper")
                    }
                    TextButton(
                        onClick = { selectedWallpaper = null },
                        modifier = Modifier.align(Alignment.End)
                    ) {
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
                    TextButton(onClick = {
                        applyWallpaper(
                            scope, applier, selectedWallpaper!!,
                            WallpaperDestination.HOME, snackbarHostState
                        ) { showApplyDialog = false; selectedWallpaper = null }
                    }) { Text("Home screen") }
                    TextButton(onClick = {
                        applyWallpaper(
                            scope, applier, selectedWallpaper!!,
                            WallpaperDestination.LOCK, snackbarHostState
                        ) { showApplyDialog = false; selectedWallpaper = null }
                    }) { Text("Lock screen") }
                    TextButton(onClick = {
                        applyWallpaper(
                            scope, applier, selectedWallpaper!!,
                            WallpaperDestination.BOTH, snackbarHostState
                        ) { showApplyDialog = false; selectedWallpaper = null }
                    }) { Text("Both") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyDialog = false }) { Text("Cancel") }
            }
        )
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
            .onFailure { error ->
                snackbarHostState.showSnackbar(
                    error.message ?: "Couldn't apply wallpaper"
                )
            }
    }
}

@Composable
private fun EmptyLibrary(
    isImporting: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("✳", style = MaterialTheme.typography.headlineLarge)
            }
            Spacer(Modifier.height(20.dp))
            Text("A blank canvas", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Import an image, GIF, or video to start your collection.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onImport, enabled = !isImporting) {
                Text("Choose a wallpaper")
            }
        }
    }
}

@Composable
private fun WallpaperTile(
    wallpaper: StoredWallpaper,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            AsyncImage(
                model = wallpaper.thumbnail,
                contentDescription = wallpaper.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f),
                contentScale = ContentScale.Crop
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = wallpaper.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (wallpaper.isAnimated) {
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
