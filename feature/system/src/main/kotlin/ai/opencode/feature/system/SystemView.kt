package ai.opencode.feature.system

import ai.opencode.core.store.StoreOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Phase 0 placeholder for this area's root composable. See [SystemFeature]. */
@Composable
fun SystemView(store: StoreOf<SystemFeature.State, SystemFeature.Action>, modifier: Modifier = Modifier) {
    LaunchedEffect(store) { store.send(SystemFeature.Action.Task) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("SystemFeature", style = MaterialTheme.typography.titleMedium)
    }
}
