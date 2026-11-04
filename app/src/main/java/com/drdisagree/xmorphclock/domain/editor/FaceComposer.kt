package com.drdisagree.xmorphclock.domain.editor

import com.drdisagree.xmorphclock.domain.model.ClockFace
import com.drdisagree.xmorphclock.domain.model.ColumnNode
import com.drdisagree.xmorphclock.domain.model.ComposedFaces
import com.drdisagree.xmorphclock.domain.model.CompositionItem
import com.drdisagree.xmorphclock.domain.model.FaceNode
import com.drdisagree.xmorphclock.domain.model.FacePlacement
import com.drdisagree.xmorphclock.domain.model.ItemState
import com.drdisagree.xmorphclock.domain.model.LayerNode
import com.drdisagree.xmorphclock.domain.model.RowNode
import com.drdisagree.xmorphclock.domain.model.StackNode

object FaceComposer {

    private val ITEM = Regex("^i(\\d+)\\.")

    fun prefix(id: Int): String = "i$id."

    fun itemOf(layerId: String): Int? = ITEM.find(layerId)?.groupValues?.get(1)?.toInt()

    fun isClockLayer(layerId: String): Boolean = itemOf(layerId) == null

    fun compose(parts: ComposedFaces): ClockFace {
        val clock = parts.clock
        val items = parts.composition.items
        if (items.size == 1 && items[0].clock && items[0].isDefault) return clock
        val entries = items.mapNotNull { item ->
            val face = if (item.clock) clock else parts.widgets[item.style] ?: return@mapNotNull null
            item to face
        }
        fun rename(item: CompositionItem, node: FaceNode): FaceNode = if (item.clock) node else ids(node) { prefix(item.id) + it }
        fun tree(state: ItemState, pick: (ClockFace) -> FaceNode, place: (CompositionItem, FaceNode) -> FacePlacement): FaceNode = StackNode(
            children = entries.map { (item, face) ->
                val node = FaceEditor.scaleNode(pick(face), item.scale(state))
                val placement = place(item, node)
                placed(rename(item, node), if (item.visible(state)) placement else placement.copy(hidden = true))
            }
        )
        val aod = items.any { it.hasAod } || entries.any { (_, face) -> face.aodLarge != null || face.aodSmall != null }
        return clock.copy(
            layers = entries.flatMap { (item, face) ->
                if (item.clock) face.layers.map { it.copy(depthFront = item.depthFront) }
                else face.layers.map { it.copy(id = prefix(item.id) + it.id, depthFront = item.depthFront) }
            },
            large = tree(ItemState.LARGE, { it.large }) { item, node -> item.large ?: node.placement },
            small = tree(ItemState.SMALL, { it.small }) { item, node -> item.small ?: node.placement },
            aodLarge = if (aod) tree(ItemState.AOD_LARGE, { it.aodLarge ?: it.large }) { item, node -> item.aodLarge ?: item.large ?: node.placement } else null,
            aodSmall = if (aod) tree(ItemState.AOD_SMALL, { it.aodSmall ?: it.small }) { item, node -> item.aodSmall ?: item.small ?: node.placement } else null
        )
    }

    fun decompose(face: ClockFace, parts: ComposedFaces): ComposedFaces {
        val items = parts.composition.items
        if (items.size == 1 && items[0].clock && items[0].isDefault) return parts.copy(clock = face)
        val large = face.large as? StackNode ?: return parts
        val small = face.small as? StackNode ?: return parts
        fun child(root: FaceNode?, item: CompositionItem): FaceNode? = (root as? StackNode)?.children?.firstOrNull { node ->
            val first = firstLayer(node) ?: return@firstOrNull false
            if (item.clock) isClockLayer(first) else itemOf(first) == item.id
        }
        val clockItem = items.firstOrNull { it.clock }
        fun unscaled(node: FaceNode?, item: CompositionItem, state: ItemState): FaceNode? =
            node?.let { FaceEditor.scaleNode(it, 1f / item.scale(state)) }
        val clock = clockItem?.let { item ->
            val largeChild = unscaled(child(large, item), item, ItemState.LARGE)
            val smallChild = unscaled(child(small, item), item, ItemState.SMALL)
            if (largeChild == null && smallChild == null) return@let null
            face.copy(
                layers = face.layers.filter { isClockLayer(it.id) }.map { it.copy(depthFront = null) },
                large = largeChild?.let { placed(it, parts.clock.large.placement) } ?: parts.clock.large,
                small = smallChild?.let { placed(it, parts.clock.small.placement) } ?: parts.clock.small,
                aodLarge = parts.clock.aodLarge,
                aodSmall = parts.clock.aodSmall
            )
        } ?: parts.clock
        val widgets = parts.widgets.toMutableMap()
        val restored = HashSet<Int>()
        items.filterNot { it.clock }.forEach { item ->
            if (item.style in restored) return@forEach
            val original = parts.widgets[item.style] ?: return@forEach
            val prefix = prefix(item.id)
            val largeChild = unscaled(child(large, item), item, ItemState.LARGE)
            val smallChild = unscaled(child(small, item), item, ItemState.SMALL)
            if (largeChild == null && smallChild == null) return@forEach
            val rebuilt = original.copy(
                layers = face.layers.filter { it.id.startsWith(prefix) }.map { it.copy(id = it.id.removePrefix(prefix), depthFront = null) },
                large = largeChild?.let { placed(ids(it) { id -> id.removePrefix(prefix) }, original.large.placement) } ?: original.large,
                small = smallChild?.let { placed(ids(it) { id -> id.removePrefix(prefix) }, original.small.placement) } ?: original.small
            )
            widgets[item.style] = rebuilt.copy(
                aodLarge = ItemGrouping.syncAod(rebuilt.large, original.aodLarge),
                aodSmall = ItemGrouping.syncAod(rebuilt.small, original.aodSmall)
            )
            restored += item.style
        }
        val placed = items.map { item ->
            fun shown(root: FaceNode?): FacePlacement? = child(root, item)?.placement?.takeUnless { it.hidden }
            fun spot(root: FaceNode?): FacePlacement? = child(root, item)?.placement?.copy(hidden = false)
            val hidden = buildSet {
                if (shown(large) == null) add(ItemState.LARGE)
                if (shown(small) == null) add(ItemState.SMALL)
                if (face.aodLarge != null && shown(face.aodLarge) == null) add(ItemState.AOD_LARGE)
                if (face.aodSmall != null && shown(face.aodSmall) == null) add(ItemState.AOD_SMALL)
            }
            item.copy(
                hidden = hidden,
                large = spot(large) ?: item.large,
                small = spot(small) ?: item.small,
                aodLarge = if (item.aodLarge != null) spot(face.aodLarge) ?: item.aodLarge else null,
                aodSmall = if (item.aodSmall != null) spot(face.aodSmall) ?: item.aodSmall else null
            )
        }
        return ComposedFaces(clock, widgets, parts.composition.copy(items = placed))
    }

    private fun firstLayer(node: FaceNode): String? = when (node) {
        is LayerNode -> node.id
        is RowNode -> node.children.firstNotNullOfOrNull(::firstLayer)
        is ColumnNode -> node.children.firstNotNullOfOrNull(::firstLayer)
        is StackNode -> node.children.firstNotNullOfOrNull(::firstLayer)
    }

    private fun placed(node: FaceNode, placement: FacePlacement): FaceNode = when (node) {
        is LayerNode -> node.copy(placement = placement)
        is RowNode -> node.copy(placement = placement)
        is ColumnNode -> node.copy(placement = placement)
        is StackNode -> node.copy(placement = placement)
    }

    private fun ids(node: FaceNode, rename: (String) -> String): FaceNode = when (node) {
        is LayerNode -> node.copy(id = rename(node.id))
        is RowNode -> node.copy(children = node.children.map { ids(it, rename) })
        is ColumnNode -> node.copy(children = node.children.map { ids(it, rename) })
        is StackNode -> node.copy(children = node.children.map { ids(it, rename) })
    }
}
