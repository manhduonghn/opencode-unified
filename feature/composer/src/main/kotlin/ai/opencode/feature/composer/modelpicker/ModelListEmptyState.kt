@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.ProviderIcon as CoreProviderIcon
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.composer.R
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * The search's empty state, else **Couldn’t Load Models** with the error and **Try Again** (when
 * [includesLoadError]), else **No Models**.
 */
@Composable
fun ModelListEmptyState(
    state: ModelPickerState,
    includesLoadError: Boolean = true,
    onTryAgain: () -> Unit,
) {
    val error = state.modelsLoadError
    when {
        state.isFilteringModels -> OpenCodeContentUnavailable(
            symbol = SFSymbol.Magnifyingglass,
            title = stringResource(R.string.composer_model_no_results_title, state.modelFilter),
            modifier = Modifier.fillMaxSize(),
            description = stringResource(R.string.composer_model_no_results_message),
        )
        includesLoadError && error != null -> OpenCodeContentUnavailable(
            symbol = SFSymbol.ExclamationmarkTriangle,
            title = stringResource(R.string.composer_model_load_failed_title),
            modifier = Modifier.fillMaxSize(),
            description = error,
            action = stringResource(CoreR.string.common_try_again),
            onAction = onTryAgain,
        )
        else -> OpenCodeContentUnavailable(
            symbol = SFSymbol.Cpu,
            title = stringResource(R.string.composer_model_empty_title),
            modifier = Modifier.fillMaxSize(),
            description = stringResource(R.string.composer_model_empty_message),
        )
    }
}
