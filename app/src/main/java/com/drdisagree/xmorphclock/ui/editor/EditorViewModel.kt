package com.drdisagree.xmorphclock.ui.editor

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drdisagree.xmorphclock.domain.editor.CustomFaces
import com.drdisagree.xmorphclock.domain.editor.FaceBuilder
import com.drdisagree.xmorphclock.domain.editor.FaceComposer
import com.drdisagree.xmorphclock.domain.editor.FaceEditor
import com.drdisagree.xmorphclock.domain.editor.ItemGrouping
import com.drdisagree.xmorphclock.domain.editor.ItemGroups
import com.drdisagree.xmorphclock.domain.model.ClockFace
import com.drdisagree.xmorphclock.domain.model.ComposedFaces
import com.drdisagree.xmorphclock.domain.model.CompositionDraft
import com.drdisagree.xmorphclock.domain.model.CompositionItem
import com.drdisagree.xmorphclock.domain.model.EditScope
import com.drdisagree.xmorphclock.domain.model.FaceAlign
import com.drdisagree.xmorphclock.domain.model.FaceLayer
import com.drdisagree.xmorphclock.domain.model.FaceLayerType
import com.drdisagree.xmorphclock.domain.model.FacePlacement
import com.drdisagree.xmorphclock.domain.model.FaceSleepStyle
import com.drdisagree.xmorphclock.domain.model.FaceTextEffect
import com.drdisagree.xmorphclock.domain.model.ItemState
import com.drdisagree.xmorphclock.domain.model.OrderPosition
import com.drdisagree.xmorphclock.domain.model.StyleKey
import com.drdisagree.xmorphclock.domain.model.UserFont
import com.drdisagree.xmorphclock.domain.usecase.GetCompositionDraftUseCase
import com.drdisagree.xmorphclock.domain.usecase.GroupItemsUseCase
import com.drdisagree.xmorphclock.domain.usecase.ImportFontUseCase
import com.drdisagree.xmorphclock.domain.usecase.ImportImageUseCase
import com.drdisagree.xmorphclock.domain.usecase.ObserveUserFontsUseCase
import com.drdisagree.xmorphclock.domain.usecase.RemoveFromGroupUseCase
import com.drdisagree.xmorphclock.domain.usecase.SaveCompositionUseCase
import com.drdisagree.xmorphclock.domain.usecase.UngroupItemUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EditorViewModel(
    private val getDraft: GetCompositionDraftUseCase,
    private val saveComposition: SaveCompositionUseCase,
    private val importImage: ImportImageUseCase,
    private val importFont: ImportFontUseCase,
    observeUserFonts: ObserveUserFontsUseCase,
    private val groupItems: GroupItemsUseCase,
    private val ungroupItem: UngroupItemUseCase,
    private val removeFromGroup: RemoveFromGroupUseCase
) : ViewModel() {

    val userFonts: StateFlow<List<UserFont>> = observeUserFonts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    fun setFont(family: String) = updateLayers("font") { it.copy(font = family) }

    fun addFont(uri: String, onFailed: () -> Unit) {
        viewModelScope.launch {
            val font = importFont(uri) ?: return@launch onFailed()
            setFont(font.family)
        }
    }

    private val state = MutableStateFlow(EditorUiState())
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private var lastEditKey: Any? = null
    private var lastEditAt = 0L

    val uiState: StateFlow<EditorUiState> = state.asStateFlow()

    fun open(focus: String?) {
        undoStack.clear()
        redoStack.clear()
        lastEditKey = null
        viewModelScope.launch {
            val draft = getDraft() ?: return@launch
            val selected = focus?.takeIf { id -> draft.face.layers.any { it.id == id || ItemGroups.belongs(id, it.id) } }
                ?.let { id -> if (ItemGroups.isGroup(id)) elementOf(draft, id) ?: id else id }
            state.value = EditorUiState(
                face = draft.face,
                style = draft.clockStyle,
                default = draft.default,
                selectedId = selected,
                draft = draft
            )
        }
    }

    fun select(id: String?) {
        val target = id?.takeIf(ItemGroups::isGroup)?.let { key -> state.value.draft?.let { elementOf(it, key) } } ?: id
        state.update { it.copy(selectedId = target, editingOn = false) }
    }

    fun standaloneKey(layerId: String): String? {
        val draft = state.value.draft ?: return null
        val key = ItemGroups.keyOf(layerId)
        return key.takeIf { elementOf(draft, it) == layerId }
    }

    private fun elementOf(draft: CompositionDraft, key: String): String? {
        val id = ItemGroups.itemId(key) ?: return null
        val item = draft.parts.composition.item(id) ?: return null
        val face = draft.parts.widgets[item.style] ?: return null
        if (face.category != CustomFaces.ELEMENT_CATEGORY || face.layers.size != 1) return null
        return FaceComposer.prefix(id) + face.layers.first().id
    }

    fun setEditingOn(on: Boolean) = state.update { it.copy(editingOn = on) }

    private fun onTarget(current: EditorUiState): Boolean =
        current.editingOn && current.scope != EditScope.AOD && current.face?.let { face -> current.primary?.let { FaceEditor.layer(face, it) } }?.tile != null

    fun setScope(scope: EditScope) = state.update {
        if (scope == EditScope.AOD) it.copy(scope = scope) else it.copy(scope = scope, aodLarge = scope == EditScope.LARGE)
    }

    fun value(key: StyleKey): Float {
        val current = state.value
        val face = current.face ?: return 0f
        val id = current.primary ?: return 0f
        if (key == StyleKey.EFFECT_TINT && onTarget(current)) {
            FaceEditor.layer(face, id)?.let { layer -> return layer.activeTint ?: FaceEditor.read(face, id, current.scope, key) }
        }
        return FaceEditor.read(face, id, current.scope, key)
    }

    fun axes(id: String): Pair<Float, Float> {
        val current = state.value
        val face = current.face ?: return 0f to 0f
        return FaceEditor.read(face, id, current.scope, StyleKey.WIDTH) to FaceEditor.read(face, id, current.scope, StyleKey.HEIGHT)
    }

    fun color(): String {
        val current = state.value
        val face = current.face ?: return ""
        val id = current.primary ?: return ""
        if (onTarget(current)) FaceEditor.layer(face, id)?.activeColor?.let { return it }
        return FaceEditor.readColor(face, id, current.scope)
    }

    fun setValue(key: StyleKey, value: Float) = edit(key) { face, current ->
        if (key == StyleKey.EFFECT_TINT && onTarget(current)) return@edit FaceEditor.updateLayers(face, current.targets) { it.copy(activeTint = value) }
        FaceEditor.write(face, current.targets, current.scope, key, value)
    }

    fun effect(): FaceTextEffect? {
        val current = state.value
        val face = current.face ?: return null
        val id = current.primary ?: return null
        return FaceEditor.readEffect(face, id, current.scope)
    }

    fun setDimensions(width: Float, height: Float) = edit("dimensions") { face, current ->
        val id = current.primary ?: return@edit face
        if (width <= 0f || height <= 0f) return@edit face
        val sized = FaceEditor.write(face, current.targets, current.scope, StyleKey.SIZE, width)
        FaceEditor.updateLayers(sized, listOf(id)) { it.copy(aspect = width / height) }
    }

    fun setEffect(effect: FaceTextEffect) = edit("effect") { face, current ->
        FaceEditor.writeEffect(face, current.targets, current.scope, effect)
    }

    fun borderColor(): String {
        val current = state.value
        val face = current.face ?: return ""
        val id = current.primary ?: return ""
        return FaceEditor.layer(face, id)?.stroke.orEmpty()
    }

    fun setBorder(color: String) = updateLayers("stroke") { it.copy(stroke = color) }

    fun setColor(color: String) = edit("color") { face, current ->
        if (onTarget(current)) return@edit FaceEditor.updateLayers(face, current.targets) { it.copy(activeColor = color) }
        FaceEditor.writeColor(face, current.targets, current.scope, color)
    }

    fun groupScale(key: String): Float {
        val current = state.value
        val face = current.face ?: return 1f
        val large = current.scope != EditScope.SMALL
        val belongs: (String) -> Boolean = { ItemGroups.belongs(key, it) }
        val base = current.default?.let { FaceEditor.groupSize(it, belongs, large) }?.takeIf { it > 0f } ?: return 1f
        return FaceEditor.groupSize(face, belongs, large) / base
    }

    fun setGroupScale(key: String, target: Float) = edit("groupScale:$key") { face, current ->
        scaleGroupTo(face, current, key, target)
    }

    fun resetGroup(key: String) = edit("groupReset:$key") { face, current ->
        val default = current.default ?: return@edit face
        FaceEditor.resetGroup(face, default, { ItemGroups.belongs(key, it) })
    }

    fun groupChanged(key: String): Boolean {
        val current = state.value
        val face = current.face ?: return false
        val default = current.default ?: return false
        if (isElementItem(key)) return false
        return FaceEditor.groupChanged(face, default) { ItemGroups.belongs(key, it) }
    }

    private fun isElementItem(key: String): Boolean {
        val draft = state.value.draft ?: return false
        val id = ItemGroups.itemId(key) ?: return false
        val style = draft.parts.composition.item(id)?.style ?: return false
        return draft.parts.widgets[style]?.category == CustomFaces.ELEMENT_CATEGORY
    }

    fun groupVisible(key: String, scope: EditScope): Boolean {
        val face = state.value.face ?: return true
        val belongs: (String) -> Boolean = { ItemGroups.belongs(key, it) }
        return when (scope) {
            EditScope.LARGE -> FaceEditor.groupIn(face.large, belongs)
            EditScope.SMALL -> FaceEditor.groupIn(face.small, belongs)
            EditScope.AOD -> FaceEditor.groupIn(face.aodLarge ?: face.large, belongs) || FaceEditor.groupIn(face.aodSmall ?: face.small, belongs)
        }
    }

    fun setGroupVisible(key: String, scope: EditScope, visible: Boolean) {
        val current = state.value
        val face = current.face ?: return
        val draft = current.draft ?: return
        val split = FaceComposer.decompose(face, draft.parts)
        val states = when (scope) {
            EditScope.LARGE -> setOf(ItemState.LARGE)
            EditScope.SMALL -> setOf(ItemState.SMALL)
            EditScope.AOD -> setOf(ItemState.AOD_LARGE, ItemState.AOD_SMALL)
        }
        val id = ItemGroups.itemId(key)
        val items = split.composition.items.map { item ->
            val matches = if (id == null) item.clock else item.id == id
            if (!matches) return@map item
            val hidden = if (visible) item.hidden - states else item.hidden + states
            if (hidden.size == ItemState.entries.size) item else item.copy(hidden = hidden)
        }
        commitComposition(face, draft, split.copy(composition = split.composition.copy(items = items)))
    }

    fun groupPosition(key: String): OrderPosition? {
        val items = state.value.draft?.parts?.composition?.items ?: return null
        if (items.size < 2) return null
        return OrderPosition(items.indexOfFirst { matchesGroup(it, key) }, items.size)
    }

    fun removeGroup(key: String) {
        val current = state.value
        val face = current.face ?: return
        val draft = current.draft ?: return
        val split = FaceComposer.decompose(face, draft.parts)
        val target = split.composition.items.firstOrNull { matchesGroup(it, key) } ?: return
        commitComposition(face, draft, split.copy(composition = split.composition.remove(target.id)))
        state.update { it.copy(selectedId = null, editingOn = false) }
    }

    fun isGroupItem(key: String): Boolean {
        val draft = state.value.draft ?: return false
        val id = ItemGroups.itemId(key) ?: return false
        val style = draft.parts.composition.item(id)?.style ?: return false
        return draft.parts.widgets[style]?.category == CustomFaces.GROUP_CATEGORY
    }

    fun groupedMember(layerId: String): Boolean = ItemGrouping.memberOf(layerId) != null && isGroupItem(ItemGroups.keyOf(layerId))

    fun startGrouping(key: String) {
        val id = ItemGroups.itemId(key) ?: return
        state.update { it.copy(grouping = setOf(id), selectedId = null, editingOn = false) }
    }

    fun toggleGrouping(key: String) {
        val id = ItemGroups.itemId(key) ?: return
        state.update { current -> current.copy(grouping = current.grouping?.let { if (id in it) it - id else it + id }) }
    }

    fun cancelGrouping() {
        state.update { it.copy(grouping = null) }
    }

    fun confirmGrouping(name: String) {
        val ids = state.value.grouping?.takeIf { it.size >= 2 } ?: return
        save {
            viewModelScope.launch {
                val created = groupItems(ids, name)
                state.update { it.copy(grouping = null) }
                open(created?.let { ItemGroups.keyOfItem(it, false) })
            }
        }
    }

    fun ungroup(key: String) {
        val id = ItemGroups.itemId(key) ?: return
        save {
            viewModelScope.launch {
                ungroupItem(id)
                open(null)
            }
        }
    }

    fun removeFromGroup(layerId: String, name: String) {
        val group = FaceComposer.itemOf(layerId) ?: return
        val member = ItemGrouping.memberOf(layerId) ?: return
        save {
            viewModelScope.launch {
                val removed = removeFromGroup(group, member, name)
                open(removed?.let { ItemGroups.keyOfItem(it, false) })
            }
        }
    }

    private fun alignKey(id: String): String? = when {
        state.value.scope == EditScope.AOD -> null
        ItemGroups.isGroup(id) && isGroupItem(id) -> null
        ItemGroups.isGroup(id) -> id
        else -> standaloneKey(id)
    }

    fun alignOf(id: String): FaceAlign? {
        val key = alignKey(id) ?: return null
        val face = state.value.face ?: return null
        return FaceEditor.groupAlign(face, { ItemGroups.belongs(key, it) }, state.value.scope != EditScope.SMALL)
    }

    fun alignTarget(id: String, align: FaceAlign) {
        val key = alignKey(id) ?: return
        edit("alignGroup:$key") { face, current ->
            FaceEditor.alignGroup(face, { ItemGroups.belongs(key, it) }, current.scope != EditScope.SMALL, align)
        }
    }

    fun canRemoveTarget(id: String): Boolean =
        state.value.scope != EditScope.AOD && (ItemGroups.isGroup(id) || canRemove(id) || standaloneKey(id) != null)

    fun removeTarget(id: String) {
        when {
            ItemGroups.isGroup(id) -> removeGroup(id)
            canRemove(id) -> removeElement(id)
            else -> standaloneKey(id)?.let(::removeGroup)
        }
    }

    fun moveGroup(key: String, steps: Int) {
        val current = state.value
        val face = current.face ?: return
        val draft = current.draft ?: return
        val split = FaceComposer.decompose(face, draft.parts)
        val target = split.composition.items.firstOrNull { matchesGroup(it, key) } ?: return
        commitComposition(face, draft, split.copy(composition = split.composition.move(target.id, steps)))
    }

    fun groupDepth(key: String): Boolean? =
        state.value.draft?.parts?.composition?.items?.firstOrNull { matchesGroup(it, key) }?.depthFront

    fun setGroupDepth(key: String, front: Boolean?) {
        val current = state.value
        val face = current.face ?: return
        val draft = current.draft ?: return
        val split = FaceComposer.decompose(face, draft.parts)
        val target = split.composition.items.firstOrNull { matchesGroup(it, key) } ?: return
        commitComposition(face, draft, split.copy(composition = split.composition.setDepthFront(target.id, front)))
    }

    fun groupLayers(key: String): List<FaceLayer> = state.value.face?.let { FaceBuilder.groupLayers(it, key) }.orEmpty()

    fun replaceImage(id: String, uri: String) {
        viewModelScope.launch {
            val image = importImage(uri) ?: return@launch
            edit(Any()) { face, _ -> FaceEditor.updateLayers(face, listOf(id)) { it.copy(format = image.id, aspect = image.aspect) } }
        }
    }

    fun elementVisible(id: String): Boolean {
        val current = state.value
        val face = current.face ?: return true
        return when (current.scope) {
            EditScope.LARGE -> (FaceEditor.node(face, true, id)?.alpha ?: 1f) > 0f
            EditScope.SMALL -> (FaceEditor.node(face, false, id)?.alpha ?: 1f) > 0f
            EditScope.AOD -> FaceEditor.layer(face, id)?.let { (it.sleepOpacity ?: 1f) > 0f && it.sleepStyle != FaceSleepStyle.HIDE } ?: true
        }
    }

    fun setElementVisible(id: String, visible: Boolean) = edit(Any()) { face, current ->
        when (current.scope) {
            EditScope.LARGE -> FaceEditor.setNodeAlpha(face, true, id, if (visible) 1f else 0f)
            EditScope.SMALL -> FaceEditor.setNodeAlpha(face, false, id, if (visible) 1f else 0f)
            EditScope.AOD -> FaceEditor.updateLayers(face, listOf(id)) { layer ->
                val style = if (visible && layer.sleepStyle == FaceSleepStyle.HIDE) FaceSleepStyle.THIN else layer.sleepStyle
                layer.copy(sleepOpacity = if (visible) null else 0f, sleepStyle = style)
            }
        }
    }

    fun canRemove(id: String): Boolean = groupLayers(ItemGroups.keyOf(id)).size > 1

    fun removeElement(id: String) {
        if (!canRemove(id)) return
        edit(Any()) { face, _ -> FaceBuilder.remove(face, id) }
        state.update { it.copy(selectedId = ItemGroups.keyOf(id), editingOn = false) }
    }

    fun elementPosition(id: String): OrderPosition? = state.value.face?.let { FaceBuilder.position(it, id) }

    fun moveElement(id: String, steps: Int) = edit(Any()) { face, _ -> FaceBuilder.move(face, id, steps) }

    private fun matchesGroup(item: CompositionItem, key: String): Boolean {
        val id = ItemGroups.itemId(key)
        return if (id == null) item.clock else item.id == id
    }

    private fun commitComposition(face: ClockFace, draft: CompositionDraft, parts: ComposedFaces) {
        val updated = FaceComposer.compose(parts)
        if (updated == face && parts.composition == draft.parts.composition) return
        undoStack.addLast(EditorSnapshot(face, draft))
        if (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        lastEditKey = null
        state.update { it.copy(face = updated, draft = draft.copy(parts = parts), canUndo = true, canRedo = false) }
    }

    fun groupName(key: String): String {
        val draft = state.value.draft ?: return ""
        val id = ItemGroups.itemId(key) ?: return draft.parts.clock.name
        val style = draft.parts.composition.item(id)?.style ?: return ""
        return draft.parts.widgets[style]?.name.orEmpty()
    }

    private fun scaleGroupTo(face: ClockFace, current: EditorUiState, key: String, target: Float): ClockFace {
        val large = current.scope != EditScope.SMALL
        val belongs: (String) -> Boolean = { ItemGroups.belongs(key, it) }
        val base = current.default?.let { FaceEditor.groupSize(it, belongs, large) }?.takeIf { it > 0f } ?: return face
        val size = FaceEditor.groupSize(face, belongs, large).takeIf { it > 0f } ?: return face
        return FaceEditor.scaleGroup(face, belongs, large, base * target.coerceIn(MIN_GROUP_SCALE, MAX_GROUP_SCALE) / size)
    }

    fun setHideIdle(ids: List<String>, hide: Boolean) = edit("hideIdle") { face, _ ->
        FaceEditor.updateLayers(face, ids) { it.copy(hideIdle = hide) }
    }

    fun updateLayer(id: String, key: Any, transform: (FaceLayer) -> FaceLayer) = edit(key) { face, _ ->
        FaceEditor.updateLayers(face, listOf(id), transform)
    }

    fun updateLayers(key: Any, transform: (FaceLayer) -> FaceLayer) = edit(key) { face, current ->
        FaceEditor.updateLayers(face, current.targets, transform)
    }

    fun setAlign(align: FaceAlign) = edit("align") { face, current ->
        val id = current.primary ?: return@edit face
        val layer = FaceEditor.layer(face, id)
        if (layer?.type == FaceLayerType.CARD) FaceEditor.setContentAlign(face, current.scope, id, align)
        else FaceEditor.setSelfAlign(face, current.scope, id, align)
    }

    fun move(id: String, dx: Float, dy: Float) = edit("move:$id") { face, current ->
        shift(face, current, id, dx, dy)
    }

    private fun shift(face: ClockFace, current: EditorUiState, id: String, dx: Float, dy: Float): ClockFace =
        if (ItemGroups.isGroup(id)) {
            FaceEditor.moveGroup(face, { ItemGroups.belongs(id, it) }, current.scope != EditScope.SMALL, dx, dy)
        } else {
            FaceEditor.move(face, id, current.scope, dx, dy)
        }

    fun placementOf(current: EditorUiState, id: String): FacePlacement? = current.face?.let { placementIn(it, current.scope, id) }

    fun placementIn(face: ClockFace, scope: EditScope, id: String): FacePlacement? {
        val large = scope != EditScope.SMALL
        return if (ItemGroups.isGroup(id)) {
            FaceEditor.groupPlacement(face, { ItemGroups.belongs(id, it) }, large)
        } else {
            FaceEditor.node(face, large, id)?.placement
        }
    }

    fun reportBounds(bounds: TargetBounds?) {
        if (state.value.bounds != bounds) state.update { it.copy(bounds = bounds) }
    }

    fun setPositionX(id: String, x: Float) {
        val placement = placementOf(state.value, id) ?: return
        move(id, x - placement.x, 0f)
    }

    fun setPositionY(id: String, y: Float) {
        val placement = placementOf(state.value, id) ?: return
        move(id, 0f, y - placement.y)
    }

    fun resize(id: String, width: Float, height: Float) = edit("resize:$id") { face, current ->
        val scoped = FaceEditor.write(face, listOf(id), current.scope, StyleKey.WIDTH, width.coerceIn(25f, 151f))
        FaceEditor.write(scoped, listOf(id), current.scope, StyleKey.HEIGHT, height.coerceIn(560f, 788f))
    }

    fun transformBase(id: String): Pair<Float, Float> {
        if (ItemGroups.isGroup(id)) return groupScale(id) to 0f
        val current = state.value
        val face = current.face ?: return 0f to 0f
        return FaceEditor.read(face, id, current.scope, StyleKey.SIZE) to FaceEditor.read(face, id, current.scope, StyleKey.ROTATION)
    }

    fun transform(id: String, size: Float, rotation: Float, dx: Float, dy: Float) = edit("transform:$id") { face, current ->
        if (ItemGroups.isGroup(id)) {
            val scaled = scaleGroupTo(face, current, id, size)
            return@edit if (dx == 0f && dy == 0f) scaled else shift(scaled, current, id, dx, dy)
        }
        val sized = FaceEditor.write(face, listOf(id), current.scope, StyleKey.SIZE, size.coerceIn(MIN_SIZE, MAX_SIZE))
        val turned = FaceEditor.write(sized, listOf(id), current.scope, StyleKey.ROTATION, rotation.coerceIn(-MAX_ROTATION, MAX_ROTATION))
        if (dx == 0f && dy == 0f) turned else shift(turned, current, id, dx, dy)
    }

    fun endGesture() {
        lastEditKey = null
    }

    fun undo() {
        val current = state.value
        val face = current.face ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(EditorSnapshot(face, current.draft))
        lastEditKey = null
        state.update { it.copy(face = previous.face, draft = previous.draft, canUndo = undoStack.isNotEmpty(), canRedo = true) }
    }

    fun redo() {
        val current = state.value
        val face = current.face ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(EditorSnapshot(face, current.draft))
        lastEditKey = null
        state.update { it.copy(face = next.face, draft = next.draft, canUndo = true, canRedo = redoStack.isNotEmpty()) }
    }

    fun save(then: () -> Unit) {
        val current = state.value
        val face = current.face ?: return
        val draft = current.draft ?: return
        if (current.saving) return
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            saveComposition(draft, face)
            state.update { it.copy(saving = false) }
            then()
        }
    }

    private fun edit(key: Any, transform: (ClockFace, EditorUiState) -> ClockFace) {
        val current = state.value
        val face = current.face ?: return
        val updated = transform(face, current)
        if (updated == face) return
        val now = SystemClock.uptimeMillis()
        if (key != lastEditKey || now - lastEditAt > COALESCE_MS) {
            undoStack.addLast(EditorSnapshot(face, current.draft))
            if (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
            redoStack.clear()
        }
        lastEditKey = key
        lastEditAt = now
        state.update { it.copy(face = updated, canUndo = undoStack.isNotEmpty(), canRedo = false) }
    }
}

private const val COALESCE_MS = 600L
private const val STOP_TIMEOUT = 5_000L
private const val HISTORY_LIMIT = 60
private const val MIN_SIZE = 0.02f
private const val MAX_SIZE = 1.5f
private const val MAX_ROTATION = 180f
private const val MIN_GROUP_SCALE = 0.3f
private const val MAX_GROUP_SCALE = 3f
