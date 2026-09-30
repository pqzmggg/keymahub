package app.keymahub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.keymahub.KeyLabels
import app.keymahub.R
import app.keymahub.core.HubStatus
import kotlinx.coroutines.launch

/**
 * The tutorial's version. It shows once after setup and again when this goes up (the saved
 * [app.keymahub.core.UiPrefs.tutorialSeen] is lower): raise it when how the app is used changes
 * enough that people who saw it should see it again.
 */
const val TUTORIAL_VERSION = 2

private const val PAGES = 4

/**
 * How to use the app, in four pages to read (nothing to operate here): connect a keyboard and
 * mouse to this device; add a PC or tablet; put devices on hotkeys in a profile; switch with the
 * hotkeys. Shown after setup, and from the home menu. [onDone] also when skipped.
 */
@Composable
fun TutorialScreen(status: HubStatus, onDone: () -> Unit) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    BackHandler { if (page > 0) scope.launch { pager.animateScrollToPage(page - 1) } else onDone() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.tutorial_title),
                        Modifier.padding(start = 12.dp).weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onDone) { Text(stringResource(R.string.tutorial_skip)) }
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { p ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (p) {
                            0 -> TextPage(1, R.string.tutorial_input_title, R.string.tutorial_input_body)
                            1 -> TextPage(2, R.string.tutorial_pair_title, R.string.tutorial_pair_body)
                            2 -> TextPage(3, R.string.tutorial_profile_title, R.string.tutorial_profile_body)
                            else -> SwitchPage(status)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(PAGES) { i ->
                            Box(
                                Modifier.size(8.dp).background(
                                    if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { if (page < PAGES - 1) scope.launch { pager.animateScrollToPage(page + 1) } else onDone() }) {
                        Text(stringResource(if (page < PAGES - 1) R.string.tutorial_next else R.string.tutorial_start))
                    }
                }
            }
        }
    }
}

@Composable
private fun PageTitle(step: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Badge("$step", highlighted = true)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TextPage(step: Int, title: Int, body: Int) {
    PageTitle(step, stringResource(title))
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
}

/** 4. Switching with the hotkeys (the modifiers set now). */
@Composable
private fun SwitchPage(status: HubStatus) {
    PageTitle(4, stringResource(R.string.tutorial_switch_title))
    Text(
        stringResource(R.string.hotkeys_mods_hint, KeyLabels.mods(status.settings.mods)),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(stringResource(R.string.tutorial_switch_body), style = MaterialTheme.typography.bodyMedium)
}
