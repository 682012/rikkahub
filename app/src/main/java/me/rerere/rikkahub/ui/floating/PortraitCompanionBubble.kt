package me.rerere.rikkahub.ui.floating

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import java.util.concurrent.atomic.AtomicLong

/** Transparent illustration, no fabricated Canvas face, no full wallpaper rectangle. */
@Composable
fun PortraitCompanionBubble(
    state: FloatingChatUiState,
    companion: FloatingCompanionSettings,
    onClick: () -> Unit,
    onMinimizedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val settings=companion.normalized()
    val context=LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active=lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val animate=active && settings.animationEnabled
    val scope=rememberCoroutineScope()
    val latestClick by rememberUpdatedState(onClick)
    val nextTap=remember { AtomicLong(0) }
    val breath=remember { Animatable(0f) }
    val greeting=remember { Animatable(0f) }
    var speech by remember(settings.preset) { mutableStateOf<String?>(null) }
    var responding by remember { mutableStateOf(false) }
    val defaultLines=listOf(stringResource(R.string.portrait_line_1),stringResource(R.string.portrait_line_2))
    val description=stringResource(R.string.floating_companion_bubble_desc)
    val name=settings.displayName(portraitName(settings.effectivePreset))
    val base=portraitAsset(settings.effectivePreset)
    val idle=remember(settings.customImagePath,settings.effectivePreset) {
        if(settings.effectivePreset==FloatingCompanionPreset.CUSTOM) FloatingCompanionFiles.resolve(context,settings.customImagePath) ?: base else base
    }
    val tapImage=remember(settings.customTapImagePath) { FloatingCompanionFiles.resolve(context,settings.customTapImagePath) }
    var imageFailed by remember(idle) { mutableStateOf(false) }
    LaunchedEffect(animate) {
        if(!animate) {breath.snapTo(0f);greeting.snapTo(0f);return@LaunchedEffect}
        breath.animateTo(1f,infiniteRepeatable(tween(3000),RepeatMode.Reverse))
    }
    LaunchedEffect(speech,active) {
        if(speech==null || !active) return@LaunchedEffect
        delay(1800);speech=null
    }
    fun tap() {
        val now=System.nanoTime();val prior=nextTap.get()
        if(now<prior || !nextTap.compareAndSet(prior,now+1100_000_000L)) return
        responding=true
        speech=settings.tapLinesOr(defaultLines).firstOrNull() ?: defaultLines.first()
        if(animate) scope.launch {
            greeting.snapTo(0f);greeting.animateTo(1f,spring(.55f,700f));greeting.animateTo(0f,spring(.8f,350f))
        }
        scope.launch {delay(400);responding=false;latestClick()}
    }
    val tapAction by rememberUpdatedState<() -> Unit>({tap()})
    val minimized=settings.minimized && onMinimizedChange!=null
    val width=if(minimized) 48.dp else settings.safeSizeDp.dp
    val height=if(minimized) 56.dp else (settings.safeSizeDp*1.32f).dp
    Column(modifier, horizontalAlignment=Alignment.CenterHorizontally) {
        if(!minimized) Box(
            Modifier.widthIn(max=180.dp).height(42.dp).padding(horizontal=2.dp),contentAlignment=Alignment.BottomCenter,
        ) {
            val line=speech ?: when {state.pendingTools.isNotEmpty()->stringResource(R.string.portrait_approval);state.failed->stringResource(R.string.portrait_error);else->""}
            if(line.isNotEmpty()) Text(line,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurface,
                maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface).padding(horizontal=8.dp,vertical=4.dp))
        }
        Box(Modifier.size(width,height).testTag(if(minimized) "portrait-edge-handle" else "portrait-character")
            .semantics { role=Role.Button;contentDescription=if(minimized) "$name ${context.getString(R.string.portrait_expand)}" else description
                onClick(label=description) {if(minimized) onMinimizedChange?.invoke(false) else tap();true}}
            .pointerInput(minimized,settings.safeSizeDp) {
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false);var drift=0f;var consumed=false;var released=false
                    while(true) {val event=awaitPointerEvent();val change=event.changes.firstOrNull {it.id==down.id} ?: break
                        drift=maxOf(drift,(change.position-down.position).getDistance());consumed=consumed||change.isConsumed
                        if(!change.pressed) {released=true;break}}
                    if(released && !consumed && drift<=viewConfiguration.touchSlop) {
                        if(minimized) onMinimizedChange?.invoke(false) else tapAction()
                    }
                }
            },contentAlignment=Alignment.Center) {
            AsyncImage(model=if(imageFailed) base else if(responding&&tapImage!=null) tapImage else idle,
                contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer {
                    val phase=if(animate) breath.value else 0f
                    translationY=if(minimized) 0f else -phase*2.dp.toPx()-greeting.value*6.dp.toPx()
                    rotationZ=if(minimized) 0f else greeting.value*2f
                    scaleX=1f+if(minimized) 0f else phase*.006f;scaleY=1f+if(minimized) 0f else phase*.006f
                },onError={imageFailed=true})
            if(minimized) Icon(HugeIcons.ArrowLeft01,null,modifier=Modifier.align(Alignment.BottomEnd).size(14.dp),tint=MaterialTheme.colorScheme.primary)
        }
        if(!minimized && onMinimizedChange!=null) androidx.compose.material3.FilledTonalIconButton(
            onClick={onMinimizedChange(true)},modifier=Modifier.size(48.dp)
                .testTag("portrait-minimize"),
            colors=androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface),
        ) {Icon(HugeIcons.ArrowRight01,stringResource(R.string.portrait_minimize))}
    }
}
