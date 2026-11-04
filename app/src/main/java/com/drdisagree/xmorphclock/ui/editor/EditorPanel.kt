package com.drdisagree.xmorphclock.ui.editor

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drdisagree.xmorphclock.R
import com.drdisagree.xmorphclock.domain.editor.FaceEditor
import com.drdisagree.xmorphclock.domain.editor.ItemGroups
import com.drdisagree.xmorphclock.domain.model.EditScope
import com.drdisagree.xmorphclock.domain.model.FaceAlign
import com.drdisagree.xmorphclock.domain.model.FaceChangeStyle
import com.drdisagree.xmorphclock.domain.model.FaceGradient
import com.drdisagree.xmorphclock.domain.model.FaceLayer
import com.drdisagree.xmorphclock.domain.model.FaceLayerType
import com.drdisagree.xmorphclock.domain.model.FaceSleepStyle
import com.drdisagree.xmorphclock.domain.model.FaceTextEffect
import com.drdisagree.xmorphclock.domain.model.FaceTile
import com.drdisagree.xmorphclock.domain.model.GaugeSource
import com.drdisagree.xmorphclock.domain.model.OrderPosition
import com.drdisagree.xmorphclock.domain.model.StyleKey
import com.drdisagree.xmorphclock.engine.FaceColors
import com.drdisagree.xmorphclock.engine.FaceFont
import com.drdisagree.xmorphclock.engine.FaceImages
import com.drdisagree.xmorphclock.ui.components.AppIcon
import com.drdisagree.xmorphclock.ui.components.GlassSupport
import com.drdisagree.xmorphclock.ui.components.InputField
import com.drdisagree.xmorphclock.ui.components.fadingEdges
import com.drdisagree.xmorphclock.ui.theme.Accent
import com.drdisagree.xmorphclock.ui.theme.Negative
import com.drdisagree.xmorphclock.ui.theme.OnInk
import com.drdisagree.xmorphclock.ui.theme.OnInkMuted
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EditorPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPlayChange: () -> Unit,
    onPickColor: (ColorTarget) -> Unit,
    onPickIcon: () -> Unit,
    onPickFont: () -> Unit,
    modifier: Modifier = Modifier
) {
    val face = state.face ?: return
    val layer = state.primary?.let { FaceEditor.layer(face, it) }
    Column(modifier) {
        EditorScopeTabs(
            scope = state.scope,
            onScopeChange = viewModel::setScope,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
        )
        AnimatedContent(
            targetState = state.selectedId,
            transitionSpec = {
                (fadeIn(tween(260, delayMillis = 80)) + slideInVertically(tween(360)) { it / 8 }) togetherWith
                        fadeOut(tween(140))
            },
            modifier = Modifier.weight(1f),
            label = "target"
        ) { id ->
            val target = id?.let { FaceEditor.layer(face, it) }
            when {
                id != null && ItemGroups.isGroup(id) -> GroupSettings(state, id, viewModel)
                target == null -> EmptyHint()
                else -> ElementSettings(state, target, viewModel, onPlayChange, onPickColor, onPickIcon, onPickFont)
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SelectHintBadge()
        Text(
            stringResource(R.string.editor_hint_title),
            style = MaterialTheme.typography.titleLarge,
            color = OnInk,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            stringResource(R.string.editor_select_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = OnInkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun ElementSettings(
    state: EditorUiState,
    layer: FaceLayer,
    viewModel: EditorViewModel,
    onPlayChange: () -> Unit,
    onPickColor: (ColorTarget) -> Unit,
    onPickIcon: () -> Unit,
    onPickFont: () -> Unit
) {
    val list = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .fadingEdges(list, Orientation.Vertical)
            .verticalScroll(list)
            .padding(bottom = 16.dp)
    ) {
        PanelTitle(stringResource(R.string.panel_element), if (layer.isSymbolLayer && layer.type == FaceLayerType.TEXT) stringResource(R.string.element_icon) else layerLabel(layer))
        val itemKey = viewModel.standaloneKey(layer.id)
        if (!(layer.isImage && state.scope == EditScope.AOD)) {
            val shown = itemKey?.let { viewModel.groupVisible(it, state.scope) } ?: viewModel.elementVisible(layer.id)
            val setShown: (Boolean) -> Unit = { visible ->
                if (itemKey != null) viewModel.setGroupVisible(itemKey, state.scope, visible) else viewModel.setElementVisible(layer.id, visible)
            }
            EditorBlock(stringResource(R.string.label_visibility)) {
                TileRow {
                    item { TextTile(stringResource(R.string.visibility_visible), shown, { setShown(true) }) }
                    item { TextTile(stringResource(R.string.visibility_hidden), !shown, { setShown(false) }) }
                }
            }
        }
        if (layer.type == FaceLayerType.TEXT && state.scope != EditScope.AOD) {
            if (layer.isSymbolLayer) IconSection(layer, viewModel, onPickIcon) else TextSection(layer, viewModel)
        }
        if (layer.type == FaceLayerType.IMAGE && state.scope != EditScope.AOD) ImageSection(layer, viewModel)
        if (layer.type == FaceLayerType.DATE && state.scope != EditScope.AOD) FormatSection(layer, viewModel)
        if (layer.type == FaceLayerType.GAUGE && state.scope != EditScope.AOD) SourceSection(layer, viewModel)
        if (state.scope != EditScope.AOD) IdleSection(listOf(layer), viewModel)
        if (layer.type in HOURLY_TYPES && state.scope != EditScope.AOD) HourSlotSection(layer, viewModel)
        if (layer.type == FaceLayerType.TILE && state.scope != EditScope.AOD) TileSection(layer, viewModel)
        if ((layer.type == FaceLayerType.CARD || layer.isImage) && state.scope != EditScope.AOD) {
            ShapeSection(layer, viewModel)
            AspectSection(layer, viewModel)
        }
        if (layer.type == FaceLayerType.SPACER) {
            ClockSection(layer, viewModel.value(StyleKey.SIZE), viewModel, onPlayChange, false)
        } else {
            if (state.scope == EditScope.AOD) {
                if (layer.isImage) ImageAodSection(layer, viewModel) else AodSection(layer, viewModel)
            }
            ClockSection(layer, viewModel.value(StyleKey.SIZE), viewModel, onPlayChange, state.scope == EditScope.AOD)
            if (state.scope != EditScope.AOD) PositionSection(state, itemKey ?: layer.id, viewModel)
            if (!layer.isImage) FontSection(state, layer, viewModel, onPickColor, onPickFont)
            LayoutSection(state, layer, viewModel)
        }
        if (state.scope != EditScope.AOD) {
            val position = if (itemKey != null) viewModel.groupPosition(itemKey) else viewModel.elementPosition(layer.id)
            ElementActions(layer, itemKey, position, viewModel)
        }
    }
}

@Composable
private fun ElementActions(layer: FaceLayer, itemKey: String?, position: OrderPosition?, viewModel: EditorViewModel) {
    if (position != null) {
        OrderBlock(position) { steps -> if (itemKey != null) viewModel.moveGroup(itemKey, steps) else viewModel.moveElement(layer.id, steps) }
    }
    if (itemKey != null) DepthOrderBlock(viewModel.groupDepth(itemKey)) { viewModel.setGroupDepth(itemKey, it) }
    val grouped = viewModel.groupedMember(layer.id)
    if (itemKey != null || grouped) {
        val name = stringResource(R.string.group_name)
        EditorBlock(stringResource(R.string.label_group)) {
            TileRow {
                if (itemKey != null) item { TextTile(stringResource(R.string.action_group_items), false, { viewModel.startGrouping(itemKey) }) }
                if (grouped) item { TextTile(stringResource(R.string.action_remove_from_group), false, { viewModel.removeFromGroup(layer.id, name) }) }
            }
        }
    }
}

@Composable
private fun PanelTitle(kind: String, title: String) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp)) {
        Text(kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = Accent)
        Text(title, style = MaterialTheme.typography.titleLarge, color = OnInk)
    }
}

@Composable
private fun ImageSection(layer: FaceLayer, viewModel: EditorViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.replaceImage(layer.id, it.toString()) }
    }
    EditorBlock(stringResource(R.string.layer_image)) {
        TileRow {
            item {
                TextTile(stringResource(R.string.action_replace), false, {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
            }
        }
    }
}

@Composable
private fun AspectSection(layer: FaceLayer, viewModel: EditorViewModel) {
    val original = if (layer.type == FaceLayerType.IMAGE) FaceImages.get(layer.format)?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } else null
    val options = listOfNotNull(original?.let { stringResource(R.string.aspect_original) to it }) + AspectRatios.map { (label, ratio) -> label to ratio }
    EditorBlock(stringResource(R.string.label_aspect)) {
        TileRow {
            items(options) { (label, ratio) ->
                TextTile(label, abs(layer.aspect - ratio) < ASPECT_MATCH, {
                    viewModel.updateLayer(layer.id, "aspect") { it.copy(aspect = ratio) }
                })
            }
        }
    }
}

@Composable
private fun ImageAodSection(layer: FaceLayer, viewModel: EditorViewModel) {
    val opacity = layer.sleepOpacity ?: 1f
    EditorBlock(stringResource(R.string.label_aod_visibility)) {
        TileRow {
            items(AodOpacities) { (label, value) ->
                TextTile(stringResource(label), abs(opacity - value) < ASPECT_MATCH, {
                    viewModel.updateLayer(layer.id, "sleepOpacity") { it.copy(sleepOpacity = value) }
                })
            }
        }
    }
    EditorBlock(stringResource(R.string.label_aod_color)) {
        TileRow {
            item { TextTile(stringResource(R.string.aod_color_full), !layer.sleepMono, { viewModel.updateLayer(layer.id, "sleepMono") { it.copy(sleepMono = false) } }) }
            item { TextTile(stringResource(R.string.aod_color_mono), layer.sleepMono, { viewModel.updateLayer(layer.id, "sleepMono") { it.copy(sleepMono = true) } }) }
        }
    }
}

@Composable
private fun FormatSection(layer: FaceLayer, viewModel: EditorViewModel) {
    EditorBlock(stringResource(R.string.label_format)) {
        InputField(
            value = layer.format,
            hint = stringResource(R.string.date_format_hint),
            onValueChange = { value -> viewModel.updateLayer(layer.id, "format") { it.copy(format = value) } },
            modifier = Modifier.padding(horizontal = 16.dp),
            key = layer.id
        )
    }
}

@Composable
private fun SourceSection(layer: FaceLayer, viewModel: EditorViewModel) {
    EditorBlock(stringResource(R.string.label_source)) {
        TileRow {
            items(GaugeSource.entries) { source ->
                TextTile(stringResource(sourceLabel(source)), layer.format == source.key, {
                    viewModel.updateLayer(layer.id, "source") { it.copy(format = source.key) }
                })
            }
        }
    }
}

@Composable
private fun TileSection(layer: FaceLayer, viewModel: EditorViewModel) {
    EditorBlock(stringResource(R.string.layer_tile)) {
        TileRow {
            items(FaceTile.entries) { tile ->
                TextTile(stringResource(tileLabel(tile)), layer.tile == tile, {
                    viewModel.updateLayer(layer.id, "tile") { it.copy(tile = tile) }
                })
            }
        }
    }
}

@Composable
private fun elementName(layer: FaceLayer): String = when {
    layer.type == FaceLayerType.TEXT && layer.isSymbolLayer -> stringResource(R.string.element_icon)
    layer.type == FaceLayerType.TEXT && layer.format.isNotBlank() -> layer.format
    else -> layerLabel(layer)
}

private fun sourceLabel(source: GaugeSource): Int = when (source) {
    GaugeSource.BATTERY -> R.string.layer_battery_level
    GaugeSource.STEPS -> R.string.layer_steps
    GaugeSource.WEATHER -> R.string.layer_weather_temp
    GaugeSource.HUMIDITY -> R.string.layer_weather_humidity
    GaugeSource.UV -> R.string.layer_uv
}


@Composable
private fun GroupSettings(state: EditorUiState, key: String, viewModel: EditorViewModel) {
    val list = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .fadingEdges(list, Orientation.Vertical)
            .verticalScroll(list)
            .padding(bottom = 16.dp)
    ) {
        PanelTitle(stringResource(R.string.panel_item), viewModel.groupName(key))
        val layers = viewModel.groupLayers(key)
        if (state.scope != EditScope.AOD) {
            layers.filter { it.type == FaceLayerType.TEXT && !it.isSymbolLayer }.forEach { TextSection(it, viewModel) }
        }
        EditorBlock(stringResource(R.string.label_elements)) {
            TileRow {
                items(layers, key = { it.id }) { layer ->
                    TextTile(elementName(layer), false, { viewModel.select(layer.id) })
                }
            }
        }
        if (state.scope != EditScope.AOD) {
            val scale = viewModel.groupScale(key)
            EditorBlock(stringResource(R.string.label_size), "${(scale * 100).roundToInt()}%") {
                RangeSlider(scale, GROUP_SCALE_RANGE, { viewModel.setGroupScale(key, it) })
            }
            PositionSection(state, key, viewModel)
        }
        val shown = viewModel.groupVisible(key, state.scope)
        EditorBlock(stringResource(R.string.label_visibility)) {
            TileRow {
                item { TextTile(stringResource(R.string.visibility_visible), shown, { viewModel.setGroupVisible(key, state.scope, true) }) }
                item { TextTile(stringResource(R.string.visibility_hidden), !shown, { viewModel.setGroupVisible(key, state.scope, false) }) }
            }
        }
        if (state.scope != EditScope.AOD) DepthOrderBlock(viewModel.groupDepth(key)) { viewModel.setGroupDepth(key, it) }
        if (state.scope != EditScope.AOD) IdleSection(layers, viewModel)
        viewModel.groupPosition(key)?.let { position -> OrderBlock(position) { viewModel.moveGroup(key, it) } }
        if (state.scope != EditScope.AOD && ItemGroups.itemId(key) != null) {
            val group = viewModel.isGroupItem(key)
            EditorBlock(stringResource(R.string.label_group)) {
                TileRow {
                    item { TextTile(stringResource(if (group) R.string.action_add_items else R.string.action_group_items), false, { viewModel.startGrouping(key) }) }
                    if (group) item { TextTile(stringResource(R.string.action_ungroup), false, { viewModel.ungroup(key) }) }
                }
            }
        }
        AnimatedVisibility(
            visible = viewModel.groupChanged(key),
            enter = fadeIn(tween(240)) + expandVertically(tween(320)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(280))
        ) {
            ActionRow(stringResource(R.string.action_reset), Negative) { viewModel.resetGroup(key) }
        }
    }
}

@Composable
private fun SelectHintBadge() {
    val pulse by rememberInfiniteTransition(label = "hint").animateFloat(
        initialValue = 0.8f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1_100), RepeatMode.Reverse),
        label = "pulse"
    )
    val accent = Accent
    Box(
        Modifier
            .size(64.dp)
            .drawBehind { drawCircle(accent.copy(alpha = 0.18f), size.minDimension / 2f * pulse) },
        contentAlignment = Alignment.Center
    ) {
        AppIcon(R.drawable.ic_tap, tint = accent, size = 28.dp)
    }
}

@Composable
private fun layerLabel(layer: FaceLayer): String = stringResource(
    when (layer.type) {
        FaceLayerType.HOURS -> R.string.layer_hours
        FaceLayerType.MINUTES -> R.string.layer_minutes
        FaceLayerType.COLON -> R.string.layer_colon
        FaceLayerType.TIME -> R.string.layer_time
        FaceLayerType.HOUR_TENS -> R.string.layer_hour_tens
        FaceLayerType.HOUR_ONES -> R.string.layer_hour_ones
        FaceLayerType.MINUTE_TENS -> R.string.layer_minute_tens
        FaceLayerType.MINUTE_ONES -> R.string.layer_minute_ones
        FaceLayerType.HAND_HOUR -> R.string.layer_hand_hour
        FaceLayerType.HAND_MINUTE -> R.string.layer_hand_minute
        FaceLayerType.DATE -> R.string.layer_date
        FaceLayerType.TEXT -> R.string.layer_text
        FaceLayerType.WEATHER_TEMP -> R.string.layer_weather_temp
        FaceLayerType.WEATHER_ICON -> R.string.layer_weather_icon
        FaceLayerType.WEATHER_CONDITION -> R.string.layer_weather_condition
        FaceLayerType.WEATHER_HIGH_LOW -> R.string.layer_weather_high_low
        FaceLayerType.WEATHER_HUMIDITY -> R.string.layer_weather_humidity
        FaceLayerType.WEATHER_WIND -> R.string.layer_weather_wind
        FaceLayerType.WEATHER_LOCATION -> R.string.layer_weather_location
        FaceLayerType.BATTERY_LEVEL -> R.string.layer_battery_level
        FaceLayerType.BATTERY_ICON -> R.string.layer_battery_icon
        FaceLayerType.STEPS -> R.string.layer_steps
        FaceLayerType.DATA_USAGE -> R.string.layer_data_usage
        FaceLayerType.TILE -> tileLabel(layer.tile)
        FaceLayerType.ALARM_TIME -> R.string.layer_alarm_time
        FaceLayerType.ALARM_ICON -> R.string.layer_alarm_icon
        FaceLayerType.CHARGE_STATUS -> R.string.layer_charge_status
        FaceLayerType.CHARGE_ICON -> R.string.layer_charge_icon
        FaceLayerType.SUN_TIME -> R.string.layer_sun_time
        FaceLayerType.SUN_LABEL -> R.string.layer_sun_label
        FaceLayerType.SUN_ICON -> R.string.layer_sun_icon
        FaceLayerType.UV_INDEX -> R.string.layer_uv
        FaceLayerType.HOURLY_TIME -> R.string.layer_hourly_time
        FaceLayerType.HOURLY_ICON -> R.string.layer_hourly_icon
        FaceLayerType.HOURLY_TEMP -> R.string.layer_hourly_temp
        FaceLayerType.MEDIA_TITLE -> R.string.layer_media_title
        FaceLayerType.MEDIA_ARTIST -> R.string.layer_media_artist
        FaceLayerType.MEDIA_ICON -> R.string.layer_media_icon
        FaceLayerType.NOTIFICATION_COUNT -> R.string.layer_notification_count
        FaceLayerType.NOTIFICATION_ICON -> R.string.layer_notification_icon
        FaceLayerType.EVENT_TITLE -> R.string.layer_event_title
        FaceLayerType.EVENT_TIME -> R.string.layer_event_time
        FaceLayerType.EVENT_ICON -> R.string.layer_event_icon
        FaceLayerType.CARD -> R.string.layer_card
        FaceLayerType.GAUGE -> R.string.layer_gauge
        FaceLayerType.IMAGE -> R.string.layer_image
        FaceLayerType.MEDIA_ART -> R.string.layer_media_art
        FaceLayerType.SPACER -> R.string.layer_spacer
    }
)

private fun tileLabel(tile: FaceTile?): Int = when (tile) {
    FaceTile.WIFI -> R.string.tile_wifi
    FaceTile.BLUETOOTH -> R.string.tile_bluetooth
    FaceTile.FLASHLIGHT -> R.string.tile_flashlight
    FaceTile.HOTSPOT -> R.string.tile_hotspot
    FaceTile.MOBILE_DATA -> R.string.tile_mobile_data
    FaceTile.RINGER -> R.string.tile_ringer
    FaceTile.DND -> R.string.tile_dnd
    FaceTile.MEDIA -> R.string.tile_media
    FaceTile.MEDIA_PREVIOUS -> R.string.tile_media_previous
    FaceTile.MEDIA_NEXT -> R.string.tile_media_next
    null -> R.string.layer_tile
}

@Composable
private fun AodSection(layer: FaceLayer, viewModel: EditorViewModel) {
    EditorBlock(stringResource(R.string.label_aod_style)) {
        TileRow {
            items(FaceSleepStyle.entries.filter { it != FaceSleepStyle.HIDE }) { style ->
                TextTile(stringResource(sleepStyleLabel(style)), layer.sleepStyle == style, {
                    viewModel.updateLayers("sleepStyle") { it.copy(sleepStyle = style) }
                })
            }
        }
    }
}

@Composable
private fun IconSection(layer: FaceLayer, viewModel: EditorViewModel, onPickIcon: () -> Unit) {
    val filled = !layer.font.contains(OUTLINED_QUERY)
    EditorBlock(stringResource(R.string.label_icon_style)) {
        TileRow {
            item { TextTile(stringResource(R.string.icon_filled), filled, { viewModel.updateLayer(layer.id, "iconStyle") { it.copy(font = FILLED_SYMBOLS) } }) }
            item { TextTile(stringResource(R.string.icon_outlined), !filled, { viewModel.updateLayer(layer.id, "iconStyle") { it.copy(font = OUTLINED_SYMBOLS) } }) }
        }
    }
    val family by produceState<FontFamily?>(null, layer.font) {
        value = withContext(Dispatchers.Default) { FontFamily(Typeface(FaceFont.typeface(layer.font, ICON_PREVIEW_WEIGHT, FONT_WIDTH, FONT_HEIGHT))) }
    }
    EditorBlock(stringResource(R.string.element_icon)) {
        TileRow {
            item {
                OptionTile(selected = true, onClick = onPickIcon, width = ICON_TILE, height = ICON_TILE) {
                    family?.let { Text(layer.format, fontFamily = it, fontSize = ICON_GLYPH, color = OnInk, modifier = Modifier.align(Alignment.Center)) }
                }
            }
            item { TextTile(stringResource(R.string.action_change), false, onPickIcon) }
        }
    }
}

@Composable
private fun TextSection(layer: FaceLayer, viewModel: EditorViewModel) {
    var revision by remember(layer.id) { mutableIntStateOf(0) }
    val clock = if (DateFormat.is24HourFormat(LocalContext.current)) CLOCK_24 else CLOCK_12
    val tokens = listOf(
        R.string.layer_time to clock,
        R.string.layer_date to DATE_TOKEN,
        R.string.token_day to DAY_TOKEN,
        R.string.token_words to WORDS_TOKEN,
        R.string.token_words_24 to WORDS_24_TOKEN,
        R.string.token_hour_word to HOUR_WORD_TOKEN,
        R.string.token_minute_word to MINUTE_WORD_TOKEN,
        R.string.token_fuzzy to FUZZY_TOKEN,
        R.string.token_fuzzy_lead to FUZZY_LEAD_TOKEN,
        R.string.token_fuzzy_hour to FUZZY_HOUR_TOKEN,
        R.string.layer_weather_temp to TEMP_TOKEN,
        R.string.layer_weather_condition to CONDITION_TOKEN,
        R.string.layer_battery_level to BATTERY_TOKEN,
        R.string.layer_steps to STEPS_TOKEN,
        R.string.token_alarm to ALARM_TOKEN,
        R.string.token_charging to CHARGING_TOKEN,
        R.string.token_sun to SUN_TOKEN,
        R.string.token_uv to UV_TOKEN,
        R.string.token_song to SONG_TOKEN,
        R.string.token_notifications to NOTIFICATIONS_TOKEN,
        R.string.token_event to EVENT_TOKEN
    )
    EditorBlock(stringResource(R.string.label_content)) {
        InputField(
            value = layer.format,
            hint = stringResource(R.string.text_hint),
            onValueChange = { value -> viewModel.updateLayer(layer.id, "text") { it.copy(format = value) } },
            modifier = Modifier.padding(horizontal = 16.dp),
            key = layer.id to revision
        )
        Spacer(Modifier.height(10.dp))
        TileRow {
            items(tokens) { (label, token) ->
                TextTile(stringResource(label), false, {
                    viewModel.updateLayer(layer.id, Any()) { current ->
                        val text = current.format
                        current.copy(format = if (text.isEmpty() || text.endsWith(' ')) text + token else "$text $token")
                    }
                    revision++
                })
            }
        }
        Text(
            stringResource(R.string.text_tokens_hint),
            style = MaterialTheme.typography.bodySmall,
            color = OnInkMuted,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp)
        )
    }
}

@Composable
private fun ShapeSection(layer: FaceLayer, viewModel: EditorViewModel) {
    EditorBlock(stringResource(R.string.label_shape)) {
        TileRow {
            items(CardShape.entries) { shape ->
                TextTile(
                    stringResource(shape.label),
                    selected = abs(layer.radius - shape.radius) < SHAPE_MATCH,
                    onClick = { viewModel.updateLayer(layer.id, "shape") { it.copy(radius = shape.radius) } }
                )
            }
        }
    }
    val radius = layer.radius.coerceIn(0f, MAX_RADIUS)
    EditorBlock(stringResource(R.string.label_corners), "${(radius / MAX_RADIUS * PERCENT).roundToInt()}%") {
        RangeSlider(radius, 0f..MAX_RADIUS, { value -> viewModel.updateLayer(layer.id, "radius") { it.copy(radius = value) } })
    }
}

@Composable
private fun ClockSection(layer: FaceLayer, size: Float, viewModel: EditorViewModel, onPlayChange: () -> Unit, aod: Boolean) {
    if (!aod && (layer.isImage || layer.type == FaceLayerType.CARD || layer.type == FaceLayerType.SPACER)) {
        val width = size
        val height = width / layer.aspect.coerceAtLeast(MIN_ASPECT)
        EditorBlock(stringResource(R.string.label_width), sliderLabel(StyleKey.SIZE, width)) {
            RangeSlider(width, minOf(DIMENSION_RANGE.start, width)..maxOf(DIMENSION_RANGE.endInclusive, width), { viewModel.setDimensions(it, height) })
        }
        EditorBlock(stringResource(R.string.label_height), sliderLabel(StyleKey.SIZE, height)) {
            RangeSlider(height, minOf(DIMENSION_RANGE.start, height)..maxOf(DIMENSION_RANGE.endInclusive, height), { viewModel.setDimensions(width, it) })
        }
    } else {
        EditorBlock(stringResource(R.string.label_size), sliderLabel(StyleKey.SIZE, size)) {
            val range = sizeRange(layer)
            RangeSlider(size, minOf(range.start, size)..maxOf(range.endInclusive, size), { viewModel.setValue(StyleKey.SIZE, it) })
        }
    }
    if (layer.isShapeLayer || aod) return
    if (layer.type == FaceLayerType.HOURS || layer.type == FaceLayerType.TIME || layer.type == FaceLayerType.HOUR_TENS) {
        EditorBlock(stringResource(R.string.label_leading_zero)) {
            TileRow {
                item { TextTile(stringResource(R.string.toggle_on), layer.hourZero, { viewModel.updateLayers("hourZero") { it.copy(hourZero = true) } }) }
                item { TextTile(stringResource(R.string.toggle_off), !layer.hourZero, { viewModel.updateLayers("hourZero") { it.copy(hourZero = false) } }) }
            }
        }
    }
    EditorBlock(stringResource(R.string.label_change)) {
        TileRow {
            items(FaceChangeStyle.entries) { change ->
                TextTile(stringResource(changeLabel(change)), layer.change == change, {
                    viewModel.updateLayers("change") { it.copy(change = change) }
                    onPlayChange()
                })
            }
        }
    }
}

@Composable
private fun HourSlotSection(layer: FaceLayer, viewModel: EditorViewModel) {
    val slot = layer.format.trim().toIntOrNull() ?: 1
    EditorBlock(stringResource(R.string.label_hours_ahead)) {
        TileRow {
            items(HOUR_SLOTS) { value ->
                TextTile(stringResource(R.string.hours_ahead, value), slot == value, {
                    viewModel.updateLayers("hourSlot") { it.copy(format = value.toString()) }
                })
            }
        }
    }
}

@Composable
private fun IdleSection(layers: List<FaceLayer>, viewModel: EditorViewModel) {
    val idle = layers.filter { it.type in IDLE_TYPES }
    if (idle.isEmpty()) return
    val label = when {
        idle.all { it.type in ALARM_TYPES } -> R.string.label_when_no_alarm
        idle.all { it.type in CHARGE_TYPES } -> R.string.label_when_unplugged
        idle.all { it.type in MEDIA_TYPES } -> R.string.label_when_no_media
        idle.all { it.type in NOTIFICATION_TYPES } -> R.string.label_when_no_notifications
        idle.all { it.type in EVENT_TYPES } -> R.string.label_when_no_events
        else -> R.string.label_when_idle
    }
    val hidden = idle.all { it.hideIdle }
    val ids = idle.map { it.id }
    EditorBlock(stringResource(label)) {
        TileRow {
            item { TextTile(stringResource(R.string.idle_keep), !hidden, { viewModel.setHideIdle(ids, false) }) }
            item { TextTile(stringResource(R.string.idle_hide), hidden, { viewModel.setHideIdle(ids, true) }) }
        }
    }
}

@Composable
private fun FontSelector(font: String, name: String, onClick: () -> Unit) {
    val family by produceState<FontFamily?>(null, font) {
        value = withContext(Dispatchers.Default) {
            FontFamily(Typeface(FaceFont.typeface(font, FONT_WEIGHT, FONT_WIDTH, FONT_HEIGHT)))
        }
    }
    val shown by animateFloatAsState(if (family != null) 1f else 0f, tween(240), label = "fontSelector")
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(FontSelectorShape)
            .background(OnInk.copy(alpha = FONT_SELECTOR_FILL))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.font_sample),
                fontFamily = family,
                fontSize = 24.sp,
                color = OnInk,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = shown }
            )
            Text(name, style = MaterialTheme.typography.bodySmall, color = OnInkMuted, maxLines = 1)
        }
        AppIcon(R.drawable.ic_chevron, tint = OnInkMuted, size = 16.dp)
    }
}

@Composable
private fun FontSection(state: EditorUiState, layer: FaceLayer, viewModel: EditorViewModel, onPickColor: (ColorTarget) -> Unit, onPickFont: () -> Unit) {
    val context = LocalContext.current
    val aod = state.scope == EditScope.AOD
    val textual = !layer.isSymbolLayer && !layer.isShapeLayer
    if (textual && !aod) EditorBlock(stringResource(R.string.section_font)) {
        val userFonts by viewModel.userFonts.collectAsStateWithLifecycle()
        val name = FontOptions.firstOrNull { it.family == layer.font }?.let { stringResource(it.label) }
            ?: userFonts.firstOrNull { it.family == layer.font }?.name
            ?: stringResource(R.string.font_custom_name)
        FontSelector(layer.font, name, onPickFont)
    }
    val weighted = layer.type != FaceLayerType.CARD && (!textual || FaceFont.supportsWeight(layer.font))
    AxisSlider(weighted) {
        val weightLabel = if (layer.type == FaceLayerType.GAUGE) R.string.label_thickness else R.string.label_weight
        val weightRange = if (layer.isSymbolLayer) SYMBOL_WEIGHT_RANGE else TEXT_WEIGHT_RANGE
        EditorBlock(stringResource(weightLabel), sliderLabel(StyleKey.WEIGHT, viewModel.value(StyleKey.WEIGHT))) {
            RangeSlider(viewModel.value(StyleKey.WEIGHT), weightRange, { viewModel.setValue(StyleKey.WEIGHT, it) })
        }
    }
    AxisSlider(textual && FaceFont.hasWidthAxis(layer.font)) {
        EditorBlock(stringResource(R.string.label_width), sliderLabel(StyleKey.WIDTH, viewModel.value(StyleKey.WIDTH))) {
            RangeSlider(viewModel.value(StyleKey.WIDTH), 25f..151f, { viewModel.setValue(StyleKey.WIDTH, it) })
        }
    }
    AxisSlider(textual && FaceFont.hasHeightAxis(layer.font)) {
        EditorBlock(stringResource(R.string.label_height), sliderLabel(StyleKey.HEIGHT, viewModel.value(StyleKey.HEIGHT))) {
            RangeSlider(viewModel.value(StyleKey.HEIGHT), 560f..788f, { viewModel.setValue(StyleKey.HEIGHT, it) })
        }
    }
    if (!aod) EditorBlock(stringResource(R.string.label_effect)) {
        val selectedEffect = viewModel.effect()
        TileRow {
            items(FaceTextEffect.entries.filter { it != FaceTextEffect.GLASS || GlassSupport.full }) { effect ->
                TextTile(stringResource(effectLabel(effect)), selectedEffect == effect, {
                    viewModel.setEffect(effect)
                })
            }
        }
    }
    val tinted = !aod && viewModel.effect().let { it == FaceTextEffect.BLEND || it == FaceTextEffect.GLASS }
    AnimatedVisibility(
        visible = tinted,
        enter = fadeIn(tween(240)) + expandVertically(tween(320)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(280))
    ) {
        EditorBlock(stringResource(R.string.label_effect_tint), sliderLabel(StyleKey.EFFECT_TINT, viewModel.value(StyleKey.EFFECT_TINT))) {
            RangeSlider(viewModel.value(StyleKey.EFFECT_TINT), 0f..1f, { viewModel.setValue(StyleKey.EFFECT_TINT, it) })
        }
    }
    val currentColor = viewModel.color()
    val stateful = layer.tile != null && !aod
    EditorBlock(
        stringResource(R.string.section_color),
        trailing = if (stateful) {
            { StateSwitch(state.editingOn, viewModel::setEditingOn) }
        } else null
    ) {
        TileRow {
            items(DynamicColorOptions) { option ->
                Swatch(Color(FaceColors.resolve(context, option)), currentColor == option, dynamic = true) { viewModel.setColor(option) }
            }
            items(ColorOptions) { option ->
                Swatch(Color(FaceColors.resolve(context, option)), currentColor == option) { viewModel.setColor(option) }
            }
            item {
                val custom = currentColor.takeIf { it.isNotEmpty() && it !in ColorOptions && !FaceColors.isDynamic(it) }
                CustomSwatch(custom?.let { value ->
                    FaceGradient.parse(value)?.stops?.map { Color(FaceColors.resolve(context, it)) } ?: listOf(Color(FaceColors.resolve(context, value)))
                }, { onPickColor(ColorTarget.FILL) })
            }
        }
    }
    AnimatedVisibility(
        visible = state.scope != EditScope.AOD,
        enter = fadeIn(tween(240)) + expandVertically(tween(320)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(280))
    ) {
        EditorBlock(stringResource(R.string.label_outline_style)) {
            TileRow {
                item {
                    TextTile(stringResource(R.string.style_solid), !layer.outline, {
                        viewModel.updateLayers("outline") { it.copy(outline = false) }
                    })
                }
                item {
                    TextTile(stringResource(R.string.style_outline), layer.outline, {
                        viewModel.updateLayers("outline") { it.copy(outline = true) }
                    })
                }
            }
        }
    }
    AnimatedVisibility(
        visible = layer.outline || layer.sleepStyle.outline,
        enter = fadeIn(tween(240)) + expandVertically(tween(320)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(280))
    ) {
        EditorBlock(stringResource(R.string.label_outline_width), sliderLabel(StyleKey.OUTLINE_WIDTH, viewModel.value(StyleKey.OUTLINE_WIDTH))) {
            RangeSlider(viewModel.value(StyleKey.OUTLINE_WIDTH), 0.5f..6f, { viewModel.setValue(StyleKey.OUTLINE_WIDTH, it) })
        }
    }
    if (!aod && !layer.isShapeLayer) {
        AnimatedVisibility(
            visible = !layer.outline,
            enter = fadeIn(tween(240)) + expandVertically(tween(320)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(280))
        ) {
            Column { BorderSection(layer, viewModel) { onPickColor(ColorTarget.BORDER) } }
        }
    }
    if (!aod && !layer.isShapeLayer) ShadowSection(layer, viewModel)
}

@Composable
private fun BorderSection(layer: FaceLayer, viewModel: EditorViewModel, onPickColor: () -> Unit) {
    val context = LocalContext.current
    val stroke = layer.stroke
    EditorBlock(stringResource(R.string.label_border)) {
        TileRow {
            item { TextTile(stringResource(R.string.style_none), stroke == null, { viewModel.updateLayers("stroke") { it.copy(stroke = null) } }, width = BORDER_NONE_WIDTH) }
            items(DynamicColorOptions) { option ->
                Swatch(Color(FaceColors.resolve(context, option)), stroke == option, dynamic = true) { viewModel.updateLayers("stroke") { it.copy(stroke = option) } }
            }
            items(BorderColors) { option ->
                Swatch(Color(FaceColors.resolve(context, option)), stroke == option) { viewModel.updateLayers("stroke") { it.copy(stroke = option) } }
            }
            item {
                val custom = stroke?.takeIf { it !in BorderColors && !FaceColors.isDynamic(it) }
                CustomSwatch(custom?.let { value ->
                    FaceGradient.parse(value)?.stops?.map { Color(FaceColors.resolve(context, it)) } ?: listOf(Color(FaceColors.resolve(context, value)))
                }, onPickColor)
            }
        }
    }
    AnimatedVisibility(
        visible = stroke != null,
        enter = fadeIn(tween(240)) + expandVertically(tween(320)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(280))
    ) {
        EditorBlock(stringResource(R.string.label_border_width), "${(layer.strokeWidth * PERCENT).roundToInt()}%") {
            RangeSlider(layer.strokeWidth, BORDER_RANGE, { value -> viewModel.updateLayers("strokeWidth") { it.copy(strokeWidth = value) } })
        }
    }
}

@Composable
private fun AxisSlider(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(240)) + expandVertically(tween(320)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(280))
    ) {
        Column { content() }
    }
}

@Composable
private fun ShadowSection(layer: FaceLayer, viewModel: EditorViewModel) {
    val label = if (layer.shadow > 0f) "${(layer.shadow * PERCENT).roundToInt()}%" else stringResource(R.string.style_none)
    EditorBlock(stringResource(R.string.label_shadow), label) {
        RangeSlider(layer.shadow, SHADOW_RANGE, { value -> viewModel.updateLayers("shadow") { it.copy(shadow = value) } })
    }
}

@Composable
private fun LayoutSection(state: EditorUiState, layer: FaceLayer, viewModel: EditorViewModel) {
    val face = state.face ?: return
    val id = state.primary ?: return
    val large = state.scope != EditScope.SMALL
    val aod = state.scope == EditScope.AOD
    if (!layer.isShapeLayer && !layer.isSymbolLayer && !layer.isTime && !aod) {
        val limit = layer.maxEms ?: 0f
        EditorBlock(stringResource(R.string.label_max_width), if (limit <= 0f) stringResource(R.string.toggle_off) else "%.1f".format(limit)) {
            RangeSlider(limit, 0f..MAX_EMS, { value -> viewModel.updateLayers("maxEms") { it.copy(maxEms = value.takeIf { v -> v >= MIN_EMS }) } })
        }
    }
    if (!layer.isShapeLayer) {
        EditorBlock(stringResource(R.string.label_stretch), sliderLabel(StyleKey.STRETCH, viewModel.value(StyleKey.STRETCH))) {
            RangeSlider(viewModel.value(StyleKey.STRETCH), STRETCH_RANGE, { viewModel.setValue(StyleKey.STRETCH, it) })
        }
        EditorBlock(stringResource(R.string.label_stretch_x), sliderLabel(StyleKey.STRETCH_X, viewModel.value(StyleKey.STRETCH_X))) {
            RangeSlider(viewModel.value(StyleKey.STRETCH_X), STRETCH_RANGE, { viewModel.setValue(StyleKey.STRETCH_X, it) })
        }
    }
    if (!layer.isSymbolLayer && !layer.isShapeLayer && !aod) {
        EditorBlock(stringResource(R.string.label_tracking), sliderLabel(StyleKey.TRACKING, viewModel.value(StyleKey.TRACKING))) {
            RangeSlider(viewModel.value(StyleKey.TRACKING), -0.2f..0.4f, { viewModel.setValue(StyleKey.TRACKING, it) })
        }
        EditorBlock(stringResource(R.string.label_line_height), sliderLabel(StyleKey.LINE_HEIGHT, viewModel.value(StyleKey.LINE_HEIGHT))) {
            RangeSlider(viewModel.value(StyleKey.LINE_HEIGHT), 0.5f..2f, { viewModel.setValue(StyleKey.LINE_HEIGHT, it) })
        }
    }
    EditorBlock(stringResource(R.string.label_rotation), sliderLabel(StyleKey.ROTATION, viewModel.value(StyleKey.ROTATION))) {
        RangeSlider(viewModel.value(StyleKey.ROTATION), -45f..45f, { viewModel.setValue(StyleKey.ROTATION, it) })
    }
    if (!aod) {
        EditorBlock(stringResource(R.string.label_tilt_x), sliderLabel(StyleKey.TILT_X, viewModel.value(StyleKey.TILT_X))) {
            RangeSlider(viewModel.value(StyleKey.TILT_X), TILT_RANGE, { viewModel.setValue(StyleKey.TILT_X, it) })
        }
        EditorBlock(stringResource(R.string.label_tilt_y), sliderLabel(StyleKey.TILT_Y, viewModel.value(StyleKey.TILT_Y))) {
            RangeSlider(viewModel.value(StyleKey.TILT_Y), TILT_RANGE, { viewModel.setValue(StyleKey.TILT_Y, it) })
        }
    }
    EditorBlock(stringResource(R.string.label_opacity), sliderLabel(StyleKey.OPACITY, viewModel.value(StyleKey.OPACITY))) {
        RangeSlider(viewModel.value(StyleKey.OPACITY), 0.1f..1f, { viewModel.setValue(StyleKey.OPACITY, it) })
    }
    val card = layer.type == FaceLayerType.CARD
    val alignable = if (card) FaceEditor.contentAlign(face, large, id) != null else FaceEditor.canAlign(face, large, id)
    if (!aod && alignable) {
        EditorBlock(stringResource(if (card) R.string.label_align_content else R.string.label_align)) {
            TileRow {
                items(FaceAlign.entries) { align ->
                    val current = if (card) FaceEditor.contentAlign(face, large, id) else FaceEditor.selfAlign(face, large, id)
                    TextTile(stringResource(alignLabel(align)), current == align, {
                        viewModel.setAlign(align)
                    })
                }
            }
        }
    }
}


private fun sleepStyleLabel(style: FaceSleepStyle): Int = when (style) {
    FaceSleepStyle.NONE -> R.string.style_normal
    FaceSleepStyle.THIN -> R.string.style_thin
    FaceSleepStyle.OUTLINE -> R.string.style_outline
    FaceSleepStyle.THIN_OUTLINE -> R.string.style_thin_outline
    FaceSleepStyle.HIDE -> R.string.style_hide
}

private fun effectLabel(effect: FaceTextEffect): Int = when (effect) {
    FaceTextEffect.SOLID -> R.string.effect_solid
    FaceTextEffect.BLEND -> R.string.effect_blend
    FaceTextEffect.GLASS -> R.string.effect_glass
    FaceTextEffect.OVERLAP -> R.string.effect_overlap
}

private fun alignLabel(align: FaceAlign): Int = when (align) {
    FaceAlign.START -> R.string.align_start
    FaceAlign.CENTER -> R.string.align_center
    FaceAlign.END -> R.string.align_end
}

private fun changeLabel(change: FaceChangeStyle): Int = when (change) {
    FaceChangeStyle.NONE -> R.string.change_none
    FaceChangeStyle.FADE -> R.string.change_fade
    FaceChangeStyle.SLIDE_UP -> R.string.change_slide_up
    FaceChangeStyle.SLIDE_DOWN -> R.string.change_slide_down
    FaceChangeStyle.ROLL -> R.string.change_roll
    FaceChangeStyle.FLIP -> R.string.change_flip
    FaceChangeStyle.SCALE -> R.string.change_scale
    FaceChangeStyle.WEIGHT -> R.string.change_weight
}

private const val FONT_WEIGHT = 600
private const val FONT_WIDTH = 100
private const val FONT_HEIGHT = 700

private fun sizeRange(layer: FaceLayer): ClosedFloatingPointRange<Float> = when {
    layer.isImage -> IMAGE_SIZE_RANGE
    layer.type == FaceLayerType.CARD -> CARD_SIZE_RANGE
    layer.type == FaceLayerType.GAUGE -> GAUGE_SIZE_RANGE
    layer.isTime || layer.isHand -> DIGIT_SIZE_RANGE
    else -> TEXT_SIZE_RANGE
}

private val FaceLayer.isSymbolLayer: Boolean
    get() = isIcon || font.startsWith(SYMBOL_FONT)

private val FaceLayer.isShapeLayer: Boolean
    get() = type == FaceLayerType.CARD || type == FaceLayerType.GAUGE || isImage || type == FaceLayerType.SPACER

private val TEXT_SIZE_RANGE = 0.015f..0.8f
private val SHADOW_RANGE = 0f..1f
private val CARD_SIZE_RANGE = 0.1f..1.2f
private val IMAGE_SIZE_RANGE = 0.05f..1.2f
private val GAUGE_SIZE_RANGE = 0.05f..1.2f
private val SYMBOL_WEIGHT_RANGE = 100f..700f
private val TEXT_WEIGHT_RANGE = 100f..1000f
private const val SYMBOL_FONT = "res:symbols"
private val DIGIT_SIZE_RANGE = 0.05f..1.5f

private const val SHAPE_MATCH = 0.01f
private const val MAX_RADIUS = 0.5f

private val GROUP_SCALE_RANGE = 0.5f..2f

private val AspectRatios = listOf("1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f, "2:1" to 2f)
private val AodOpacities = listOf(R.string.visibility_visible to 1f, R.string.aod_dim to 0.4f, R.string.visibility_hidden to 0f)
private const val ASPECT_MATCH = 0.01f
private val DIMENSION_RANGE = 0.03f..1.2f
private const val MIN_ASPECT = 0.05f
private const val CLOCK_24 = "{HH:mm}"
private const val CLOCK_12 = "{h:mm}"
private const val DATE_TOKEN = "{EEE d MMM}"
private const val DAY_TOKEN = "{EEEE}"
private const val WORDS_TOKEN = "{time_words}"
private const val FUZZY_TOKEN = "{fuzzy_time}"
private const val WORDS_24_TOKEN = "{time_words_24}"
private const val HOUR_WORD_TOKEN = "{hour_word}"
private const val MINUTE_WORD_TOKEN = "{minute_word}"
private const val FUZZY_LEAD_TOKEN = "{fuzzy_lead}"
private const val FUZZY_HOUR_TOKEN = "{fuzzy_hour}"
private const val ALARM_TOKEN = "{alarm}"
private const val CHARGING_TOKEN = "{charging}"
private const val SUN_TOKEN = "{sun}"
private const val UV_TOKEN = "{uv}"
private const val SONG_TOKEN = "{song}"
private const val NOTIFICATIONS_TOKEN = "{notifications}"
private const val EVENT_TOKEN = "{event}"
private const val TEMP_TOKEN = "{temp}"
private const val CONDITION_TOKEN = "{condition}"
private const val BATTERY_TOKEN = "{battery}"
private const val STEPS_TOKEN = "{steps}"
private const val FILLED_SYMBOLS = "res:symbols?FILL=1"
private const val OUTLINED_SYMBOLS = "res:symbols?FILL=0"
private const val OUTLINED_QUERY = "FILL=0"
private const val ICON_PREVIEW_WEIGHT = 400
private val ICON_TILE = 52.dp
private val ICON_GLYPH = 26.sp
private val STRETCH_RANGE = 0.5f..3f
private val TILT_RANGE = -60f..60f
private val BorderColors = listOf("#FF000000", "#FF1C1C1E", "#FFFFFFFF") + ColorOptions.filterNot { it == FaceColors.TEXT_PRIMARY || it == "#FF000000" }
private val BORDER_RANGE = 0.02f..0.2f
private val BORDER_NONE_WIDTH = 76.dp
private const val PERCENT = 100f

private val FontSelectorShape = RoundedCornerShape(18.dp)
private const val FONT_SELECTOR_FILL = 0.06f

private val IDLE_TYPES = setOf(
    FaceLayerType.ALARM_TIME, FaceLayerType.ALARM_ICON, FaceLayerType.CHARGE_STATUS, FaceLayerType.CHARGE_ICON,
    FaceLayerType.SUN_TIME, FaceLayerType.SUN_LABEL, FaceLayerType.SUN_ICON, FaceLayerType.UV_INDEX,
    FaceLayerType.HOURLY_TIME, FaceLayerType.HOURLY_ICON, FaceLayerType.HOURLY_TEMP,
    FaceLayerType.MEDIA_TITLE, FaceLayerType.MEDIA_ARTIST, FaceLayerType.MEDIA_ICON, FaceLayerType.MEDIA_ART,
    FaceLayerType.NOTIFICATION_COUNT, FaceLayerType.NOTIFICATION_ICON,
    FaceLayerType.EVENT_TITLE, FaceLayerType.EVENT_TIME, FaceLayerType.EVENT_ICON
)
private val EVENT_TYPES = setOf(FaceLayerType.EVENT_TITLE, FaceLayerType.EVENT_TIME, FaceLayerType.EVENT_ICON)
private val MEDIA_TYPES = setOf(FaceLayerType.MEDIA_TITLE, FaceLayerType.MEDIA_ARTIST, FaceLayerType.MEDIA_ICON, FaceLayerType.MEDIA_ART)
private val NOTIFICATION_TYPES = setOf(FaceLayerType.NOTIFICATION_COUNT, FaceLayerType.NOTIFICATION_ICON)
private val CHARGE_TYPES = setOf(FaceLayerType.CHARGE_STATUS, FaceLayerType.CHARGE_ICON)
private val HOURLY_TYPES = setOf(FaceLayerType.HOURLY_TIME, FaceLayerType.HOURLY_ICON, FaceLayerType.HOURLY_TEMP)
private val ALARM_TYPES = setOf(FaceLayerType.ALARM_TIME, FaceLayerType.ALARM_ICON)
private val HOUR_SLOTS = listOf(1, 2, 3, 4, 5, 6, 9, 12)
private const val MAX_EMS = 30f
private const val MIN_EMS = 1f
