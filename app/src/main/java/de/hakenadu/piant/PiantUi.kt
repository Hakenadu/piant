package de.hakenadu.piant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val PiaPink = Color(0xFFFF4F8B)
val PiaOrange = Color(0xFFFF9A4D)
val Paper = Color(0xFFFFF8F1)
val Ink = Color(0xFF2B2140)

private val PiaGradient = Brush.linearGradient(listOf(PiaPink, PiaOrange))

@Composable
fun PiantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PiaPink,
            onPrimary = Color.White,
            secondary = PiaOrange,
            background = Paper,
            surface = Paper,
            onSurface = Ink,
            onBackground = Ink,
        ),
        content = content,
    )
}

data class MenuState(
    val soundOn: Boolean,
    val hapticsOn: Boolean,
    val paletteOn: Boolean,
    val pinned: Boolean,
)

class MenuActions(
    val onClose: () -> Unit,
    val onNewPicture: () -> Unit,
    val onSave: () -> Unit,
    val onSoundChange: (Boolean) -> Unit,
    val onHapticsChange: (Boolean) -> Unit,
    val onPaletteChange: (Boolean) -> Unit,
    val onTogglePin: () -> Unit,
    val onExit: () -> Unit,
)

@Composable
fun PiantOverlay(
    introVisible: Boolean,
    menuOpen: Boolean,
    menu: MenuState,
    actions: MenuActions,
    onIntroDone: () -> Unit,
    onMenuHidden: () -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(1500)
        onIntroDone()
    }
    LaunchedEffect(menuOpen) {
        if (!menuOpen) {
            delay(400) // let the exit animation finish
            onMenuHidden()
        } else {
            delay(60_000) // never leave the menu lying around for small hands
            actions.onClose()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = introVisible,
            enter = fadeIn(),
            exit = fadeOut(tween(450)),
        ) {
            Intro()
        }
        AnimatedVisibility(
            visible = menuOpen,
            enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.94f),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.97f),
        ) {
            ParentMenu(menu, actions)
        }
    }
}

/** "Pia" in the brand gradient, "nt" quieter: the name is Pia + paint. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun Wordmark(fontSize: TextUnit, modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(brush = PiaGradient, fontWeight = FontWeight.Black)) { append("Pia") }
            withStyle(SpanStyle(color = Ink.copy(alpha = 0.45f), fontWeight = FontWeight.Light)) { append("nt") }
        },
        fontSize = fontSize,
        letterSpacing = (-1.5).sp,
        modifier = modifier,
    )
}

@Composable
fun LogoBadge(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(PiaGradient),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo_mark),
            contentDescription = null,
            modifier = Modifier.size(size * 0.82f),
        )
    }
}

@Composable
private fun Intro() {
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Paper),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoBadge(112.dp, Modifier.scale(pop.value))
            Spacer(Modifier.height(20.dp))
            Wordmark(64.sp)
        }
    }
}

@Composable
private fun ParentMenu(state: MenuState, actions: MenuActions) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99231A2E))
            // Swallow every touch outside the card: a stray hand must not paint or close.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = Paper,
            shadowElevation = 24.dp,
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LogoBadge(64.dp)
                Spacer(Modifier.height(10.dp))
                Wordmark(44.sp)
                Text(
                    stringResource(R.string.parents_area),
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink.copy(alpha = 0.5f),
                )

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = actions.onClose,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PiaPink),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.keep_painting), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    ActionTile(stringResource(R.string.new_picture), Icons.Rounded.Refresh, actions.onNewPicture, Modifier.weight(1f))
                    ActionTile(stringResource(R.string.save_picture), Icons.Rounded.Favorite, actions.onSave, Modifier.weight(1f))
                }

                Spacer(Modifier.height(18.dp))
                SettingRow(stringResource(R.string.setting_sound), state.soundOn, actions.onSoundChange)
                SettingRow(stringResource(R.string.setting_haptics), state.hapticsOn, actions.onHapticsChange)
                SettingRow(stringResource(R.string.setting_palette), state.paletteOn, actions.onPaletteChange)

                Spacer(Modifier.height(16.dp))
                ActionTile(
                    label = stringResource(if (state.pinned) R.string.unpin else R.string.pin),
                    icon = Icons.Rounded.Lock,
                    onClick = actions.onTogglePin,
                    modifier = Modifier.fillMaxWidth(),
                    horizontal = true,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = actions.onExit) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = Ink.copy(alpha = 0.6f))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.exit), color = Ink.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun ActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = PiaPink.copy(alpha = 0.10f),
        contentColor = Ink,
        modifier = modifier,
    ) {
        if (horizontal) {
            Row(
                Modifier.padding(vertical = 16.dp, horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(icon, contentDescription = null, tint = PiaPink)
                Spacer(Modifier.width(10.dp))
                Text(label, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Column(
                Modifier.padding(vertical = 18.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, contentDescription = null, tint = PiaPink, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(6.dp))
                Text(label, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = PiaPink),
        )
    }
}
