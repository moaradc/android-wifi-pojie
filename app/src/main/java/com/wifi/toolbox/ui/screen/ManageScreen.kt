package com.wifi.toolbox.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.res.stringResource
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.R

@Composable
fun ManageScreen(onMenuClick: () -> Unit) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        NavContainer(
            pages = listOf(
                object : NavPage {
                    override val name = stringResource(R.string.scan)
                    override val selectedIcon = Icons.Filled.Radar
                    override val unselectedIcon = Icons.Outlined.Radar
                    override val content = @Composable {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.tip_not_completed))
                        }
                    }
                },
                object : NavPage {
                    override val name = stringResource(R.string.local)
                    override val selectedIcon = Icons.Filled.Dns
                    override val unselectedIcon = Icons.Outlined.Dns
                    override val content = @Composable {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.tip_not_completed))
                        }
                    }
                },
                object : NavPage {
                    override val name = stringResource(R.string.settings)
                    override val selectedIcon = Icons.Filled.Settings
                    override val unselectedIcon = Icons.Outlined.Settings
                    override val content = @Composable {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.tip_not_completed))
                        }
                    }
                }
            ),
            selectedIndex = selectedIndex,
            onIndexChange = { selectedIndex = it },
            subtitle = stringResource(R.string.wifi_manager),
            onMenuClick = onMenuClick
        )
    }
}