package com.wifi.toolbox.ui.items

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

interface NavPage {
    val name: String
    val selectedIcon: ImageVector
    val unselectedIcon: ImageVector
    val content: @Composable () -> Unit
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavContainer(
    pages: List<NavPage>,
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    defaultIndex: Int = 0,
    subtitle: String,
    onMenuClick: () -> Unit
) {
    var previousIndex by rememberSaveable { mutableIntStateOf(selectedIndex) }
    var localCurrentIndex by rememberSaveable { mutableIntStateOf(selectedIndex) }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex != localCurrentIndex) {
            previousIndex = localCurrentIndex
            localCurrentIndex = selectedIndex
        }
    }

    val view = LocalView.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    var navBarWidth by remember { mutableFloatStateOf(0f) }

    BackHandler(enabled = localCurrentIndex != defaultIndex) {
        onIndexChange(defaultIndex)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    Column(modifier = Modifier.padding(0.dp, 8.dp)) {
                        Text(
                            text = pages[localCurrentIndex].name,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .onGloballyPositioned { navBarWidth = it.size.width.toFloat() }
                    .pointerInput(pages.size) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val index = (offset.x / (navBarWidth / pages.size)).toInt()
                                    .coerceIn(0, pages.size - 1)
                                if (localCurrentIndex != index) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onIndexChange(index)
                                }
                            },
                            onDrag = { change, _ ->
                                val index = (change.position.x / (navBarWidth / pages.size)).toInt()
                                    .coerceIn(0, pages.size - 1)
                                if (localCurrentIndex != index) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onIndexChange(index)
                                }
                            }
                        )
                    }
                    .pointerInput(pages.size) {
                        detectTapGestures(
                            onPress = { offset ->
                                val index = (offset.x / (navBarWidth / pages.size)).toInt()
                                    .coerceIn(0, pages.size - 1)
                                if (localCurrentIndex != index) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onIndexChange(index)
                                }
                            }
                        )
                    }
            ) {
                pages.forEachIndexed { index, page ->
                    val selected = localCurrentIndex == index
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) page.selectedIcon else page.unselectedIcon,
                                contentDescription = page.name
                            )
                        },
                        label = { Text(page.name) },
                        selected = selected,
                        alwaysShowLabel = pages.size < 5,
                        onClick = {
                            if (localCurrentIndex != index) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onIndexChange(index)
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            pages.forEachIndexed { index, page ->
                val isVisible = index == localCurrentIndex
                val isForward = localCurrentIndex > previousIndex

                AnimatedVisibility(
                    visible = isVisible,
                    enter = slideInHorizontally(tween(300)) { if (isForward) it else -it },
                    exit = slideOutHorizontally(tween(300)) { if (isForward) -it else it }
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                clip = true
                                renderEffect = null
                            }
                    ) {
                        key(page.name) {
                            page.content()
                        }
                    }
                }
            }
        }
    }
}