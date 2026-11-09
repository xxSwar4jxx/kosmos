package space.swar4j.kosmos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun HomeActionCard(
    title: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.height(142.dp).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier.size(42.dp),
                contentAlignment = Alignment.Center
            ) { icon() }
            Text(title, style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MiniWallpaperTile(
    wallpaper: StoredWallpaper,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            AsyncImage(
                model = wallpaper.thumbnail,
                contentDescription = wallpaper.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.05f).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
                contentScale = ContentScale.Crop
            )
            Text(wallpaper.title, modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}

@Composable
fun PresetTile(
    wallpaper: StoredWallpaper,
    onApply: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            AsyncImage(
                model = wallpaper.thumbnail,
                contentDescription = wallpaper.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.78f).clickable(onClick = onApply),
                contentScale = ContentScale.Crop
            )
            Text(wallpaper.title, modifier = Modifier.padding(start = 12.dp, top = 10.dp),
                style = MaterialTheme.typography.titleSmall, maxLines = 1)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onApply) { Text("Apply") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

@Composable
fun GhostWallpaperGrid(modifier: Modifier = Modifier) {
    val placeholders = (0 until 6).toList()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 148.dp),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        items(placeholders) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.82f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth(0.8f).height(14.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.fillMaxWidth(0.5f).height(11.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(20.dp)))
                }
            }
        }
    }
}
