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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalDensity

/**
 * The tutorial's version. It shows once after setup and again when this goes up (the saved
 * [app.keymahub.core.UiPrefs.tutorialSeen] is lower): raise it when how the app is used changes
 * enough that people who saw it should see it again.
 */
const val TUTORIAL_VERSION = 4

private const val PAGES = 5

/**
 * How to use the app, in five pages to read (nothing to operate here): connect a keyboard and
 * mouse to this device; add a PC or tablet; put devices on hotkeys in a profile; how a profile is
 * picked by itself; switch with the hotkeys. Shown after setup, and from the home menu. [onDone] also when skipped.
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
                            0 -> {
                                Head(1, R.string.tutorial_input_title, R.string.tutorial_input_lead)
                                Steps(R.string.tutorial_input_step1, R.string.tutorial_input_step2)
                                Tip(stringResource(R.string.tutorial_input_tip))
                            }
                            1 -> {
                                Head(2, R.string.tutorial_pair_title, R.string.tutorial_pair_lead)
                                Steps(R.string.tutorial_pair_step1, R.string.tutorial_pair_step2, R.string.tutorial_pair_step3)
                                Tip(stringResource(R.string.tutorial_pair_tip)) { WindowsAddDevice() }
                            }
                            2 -> {
                                Head(3, R.string.tutorial_profile_title, R.string.tutorial_profile_lead)
                                Steps(R.string.tutorial_profile_step1, R.string.tutorial_profile_step2, R.string.tutorial_profile_step3)
                            }
                            3 -> AutoPage()
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

/** A page's number and title, and what it is about in one line. */
@Composable
private fun Head(step: Int, title: Int, lead: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Badge("$step", highlighted = true)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
    Text(stringResource(lead), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What to do, one short line per step, numbered. */
@Composable
private fun Steps(vararg steps: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { i, step ->
            Row {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(step), Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** A side note, set apart; with a picture of what it points at. */
@Composable
private fun Tip(text: String, picture: (@Composable () -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = MaterialTheme.typography.bodyMedium
            Row {
                // As tall as one line of the text, so it sits level with the first line.
                val line = with(LocalDensity.current) { style.lineHeight.toDp() }
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(line))
                Spacer(Modifier.width(8.dp))
                Text(text, style = style)
            }
            picture?.invoke()
        }
    }
}

/** 4. The ways a profile is picked by itself, and Galaxy routines as another way. */
@Composable
private fun AutoPage() {
    Head(4, R.string.tutorial_auto_title, R.string.tutorial_auto_lead)
    Text(stringResource(R.string.tutorial_auto_where), style = MaterialTheme.typography.bodyLarge)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((_, label, hint) in MODES) {
            Column {
                Text(stringResource(label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Tip(stringResource(R.string.tutorial_auto_routine_tip))
}

/** 5. Switching with the hotkeys (the modifiers set now), shown as keys. */
@Composable
private fun SwitchPage(status: HubStatus) {
    Head(5, R.string.tutorial_switch_title, R.string.tutorial_switch_lead)
    val mods = KeyLabels.mods(status.settings.mods)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HotkeyLine("$mods + 1", stringResource(R.string.tutorial_switch_phone))
        HotkeyLine("$mods + 2–9, 0", stringResource(R.string.tutorial_switch_target))
    }
    Tip(stringResource(R.string.tutorial_switch_body))
}

@Composable
private fun HotkeyLine(keys: String, what: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(keys, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text("→ $what", style = MaterialTheme.typography.bodyLarge)
    }
}
