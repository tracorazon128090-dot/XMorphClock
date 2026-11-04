package com.drdisagree.xmorphclock.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.drdisagree.xmorphclock.R

@Composable
internal fun DepthOrderBlock(front: Boolean?, onChange: (Boolean?) -> Unit) {
    EditorBlock(stringResource(R.string.label_depth_order)) {
        TileRow {
            item { TextTile(stringResource(R.string.depth_order_auto), front == null, { onChange(null) }) }
            item { TextTile(stringResource(R.string.depth_order_front), front == true, { onChange(true) }) }
            item { TextTile(stringResource(R.string.depth_order_behind), front == false, { onChange(false) }) }
        }
    }
}
