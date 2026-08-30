package com.wifi.toolbox.ui.items

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 全局唯一气泡持有者：同一时刻整个应用只显示一个提示气泡
 * （长按新按钮自动取代旧气泡，避免多个气泡叠放）。
 */
private var tipOwner by mutableStateOf<Any?>(null)

/** 气泡自动消失时长（标准 tooltip 行为：显示片刻后自动收起） */
private const val TIP_DISMISS_MS = 2_500L

/**
 * 带长按提示气泡的图标按钮（标准 tooltip 交互，用于实时日志/事件历史卡片工具行）：
 * - 点击：与普通 IconButton 行为一致（涟漪 + onClick）
 * - 长按：按钮正下方弹出深色圆角气泡显示 [tip]（水平方向自动夹在屏幕内，
 *   底部空间不足时翻转到按钮上方），约 2.5 秒后自动消失；点击任意处立即消失
 * - 气泡全局互斥：长按其它按钮时旧气泡自动收起
 */
@Composable
fun TipIconButton(
    onClick: () -> Unit,
    tip: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 36.dp,
    iconSize: Dp = 17.dp,
    contentDesc: String? = null,
    iconModifier: Modifier = Modifier
) {
    val owner = remember { Any() }
    val visible = tipOwner === owner
    val density = androidx.compose.ui.platform.LocalDensity.current

    // 自动消失：显示后计时收起（计时期间被取代则无害）
    if (visible) {
        LaunchedEffect(owner) {
            delay(TIP_DISMISS_MS)
            if (tipOwner === owner) tipOwner = null
        }
    }

    Box(modifier = modifier.size(buttonSize)) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = {
                        tipOwner = null
                        onClick()
                    },
                    onLongClick = { tipOwner = owner }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDesc ?: tip,
                modifier.then(Modifier.size(iconSize))
            )
        }

        if (visible) {
            // 气泡定位：锚点正下方居中；水平夹在窗口内；底部放不下翻转到上方
            val positionProvider = remember(density) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize
                    ): IntOffset {
                        val gap = with(density) { 6.dp.roundToPx() }
                        val x = (anchorBounds.center.x - popupContentSize.width / 2)
                            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                        val belowY = anchorBounds.bottom + gap
                        val y = if (belowY + popupContentSize.height <= windowSize.height) belowY
                        else anchorBounds.top - gap - popupContentSize.height
                        return IntOffset(x, y)
                    }
                }
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { tipOwner = null },
                properties = PopupProperties(focusable = false)
            ) {
                Text(
                    text = tip,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .shadow(4.dp, RoundedCornerShape(8.dp))
                        .background(
                            MaterialTheme.colorScheme.inverseSurface,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}
