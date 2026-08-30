package com.wifi.toolbox.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withContext
import com.wifi.toolbox.BuildConfig
import com.wifi.toolbox.R
import com.wifi.toolbox.ui.items.HyperOSBackground
import com.wifi.toolbox.ui.items.TagItem
import com.wifi.toolbox.ui.items.TagType
import com.wifi.toolbox.ui.pages.LicensePage
import kotlinx.parcelize.Parcelize
import androidx.compose.runtime.produceState
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.wifi.toolbox.utils.LocaleConfigs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val isPreview = LocalInspectionMode.current
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showLicenseDialog by rememberSaveable { mutableStateOf(false) }
    var changelogText by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var showCreditsDialog by rememberSaveable { mutableStateOf(false) }

    val clickTimestamps = remember { mutableListOf<Long>() }
    var hasAccessibilityClick by remember { mutableStateOf(false) }
    var currentToast by remember { mutableStateOf<Toast?>(null) }

    val creditProjects by produceState(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val jsonString =
                    context.assets.open("thanks.json").bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(jsonString)
                List(jsonArray.length()) { i ->
                    val obj = jsonArray.getJSONObject(i)
                    CreditProject(
                        name = obj.optString("name"),
                        description = obj.optString("description"),
                        link = obj.optString("link").takeIf { it.isNotEmpty() },
                        license = obj.optString("license").takeIf { it.isNotEmpty() }
                    )
                }
            }.getOrElse {
                emptyList()
            }
        }
    }

    LaunchedEffect(Unit) { visible = true }

    LaunchedEffect(showDialog) {
        if (showDialog && changelogText.isEmpty()) {
            changelogText = try {
                context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                context.getString(R.string.changelog_md_not_found)
            }
        }
    }

    val libs = remember {
        runCatching {
            Libs.Builder()
                .withContext(context)
                .build()
        }.getOrElse {
            Libs.Builder().withJson("{}").build()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.5f)
        ) {
            Image(
                painter = painterResource(id = R.drawable.about_bg),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            Box(modifier = Modifier.matchParentSize()) {
                HyperOSBackground()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onMenuClick) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                AnimatedVisibility(
                    visible = visible,
                    enter = if (isPreview) fadeIn(tween(0)) else fadeIn() + slideInVertically(
                        initialOffsetY = { 40 })
                ) {
                    Spacer(modifier = Modifier.height(48.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Press) {
                                            val isAccessibility = event.changes.any {
                                                it.type == PointerType.Unknown
                                            }
                                            if (isAccessibility) {
                                                hasAccessibilityClick = true
                                            }

                                            val currentTime = System.currentTimeMillis()
                                            clickTimestamps.add(currentTime)

                                            if (clickTimestamps.size > 5) {
                                                clickTimestamps.removeAt(0)
                                            }

                                            if (clickTimestamps.size == 5) {
                                                val totalTime = currentTime - clickTimestamps[0]

                                                val showToast: (String) -> Unit = { message ->
                                                    currentToast?.cancel()
                                                    currentToast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
                                                    currentToast?.show()
                                                }

                                                when {
                                                    hasAccessibilityClick -> {
                                                        showToast(context.getString(R.string.toast_no_cheating))
                                                    }

                                                    totalTime < 200 -> {
                                                        val tags = arrayOf("lzh", "en-CN")
                                                        val randomTag = tags.random()
                                                        val targetIndex = LocaleConfigs.list.indexOfFirst { it.tag == randomTag }
                                                        if (targetIndex != -1) {
                                                            val app = context.applicationContext as com.wifi.toolbox.ToolboxApp
                                                            app.settings.update { it.copy(language = targetIndex) }
                                                            val appLocale = LocaleConfigs.getLocaleListCompat(targetIndex)
                                                            AppCompatDelegate.setApplicationLocales(appLocale)
                                                            showToast(context.getString(R.string.toast_operation_success))
                                                        }
                                                    }

                                                    totalTime < 2000 -> {
                                                        showToast(context.getString(R.string.toast_too_slow))
                                                    }
                                                }

                                                clickTimestamps.clear()
                                                hasAccessibilityClick = false
                                            }
                                        }
                                    }
                                }
                            }
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val drawable = remember(R.mipmap.ic_launcher) {
                                context.packageManager.getDrawable(
                                    context.packageName,
                                    R.mipmap.ic_launcher,
                                    context.applicationInfo
                                )
                            }
                            Surface(
                                modifier = Modifier.size(110.dp),
                                shape = RoundedCornerShape(28.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shadowElevation = 12.dp
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(model = drawable),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.version_text,
                                        BuildConfig.VERSION_NAME,
                                        BuildConfig.VERSION_CODE
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                )
                                if (BuildConfig.DEBUG) {
                                    TagItem(text = "DEBUG", type = TagType.Primary)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(40.dp))
                }

                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AnimatedVisibility(
                        visible = visible,
                        enter = if (isPreview) fadeIn(tween(0)) else fadeIn(
                            animationSpec = tween(
                                800,
                                0
                            )
                        ) +
                                slideInVertically(animationSpec = tween(800, 0)) { 100 }
                    ) {
                        SettingsGroup {
                            ClickableInfoItem(
                                stringResource(R.string.changelog),
                                stringResource(R.string.changelog_tip)
                            ) {
                                showDialog = true
                            }
                            SettingsDivider()
                            ClickableInfoItem(
                                stringResource(R.string.score_code_location),
                                stringResource(R.string.score_code_location_tip)
                            ) {
                                uriHandler.openUri("https://github.com/bszapp/android-wifi-pojie")
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = visible,
                        enter = if (isPreview) fadeIn(tween(0)) else fadeIn(
                            animationSpec = tween(
                                800,
                                200
                            )
                        ) +
                                slideInVertically(animationSpec = tween(800, 200)) { 100 }
                    ) {
                        SettingsGroup {
                            ClickableInfoItem(
                                stringResource(R.string.thanks_project),
                                stringResource(R.string.thanks_project_tip)
                            ) { showCreditsDialog = true }
                            SettingsDivider()
                            ClickableInfoItem(
                                stringResource(R.string.license),
                                stringResource(R.string.license_tip)
                            ) { showLicenseDialog = true }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.height(32.dp))

                val buildYear = remember {
                    BuildConfig.BUILD_DATE.substring(0, 4)
                }

                Text(
                    text = stringResource(R.string.copyright, buildYear),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                confirmButton = {
                    TextButton(onClick = {
                        showDialog = false
                    }) { Text(stringResource(R.string.btn_disappear)) }
                },
                title = { Text(stringResource(R.string.changelog), fontWeight = FontWeight.Bold) },
                text = {
                    Box(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(text = changelogText, style = MaterialTheme.typography.bodyMedium)
                    }
                },
                shape = RoundedCornerShape(28.dp)
            )
        }

        if (showCreditsDialog) {
            AlertDialog(
                onDismissRequest = { showCreditsDialog = false },
                confirmButton = {
                    TextButton(onClick = { showCreditsDialog = false }) {
                        Text(stringResource(R.string.btn_close))
                    }
                },
                title = {
                    Text(stringResource(R.string.thanks_project), fontWeight = FontWeight.Bold)
                },
                text = {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {

                        items(creditProjects) { project ->
                            val expanded = rememberSaveable { mutableStateOf(false) }
                            CreditProjectCard(project, uriHandler, expanded)
                        }

                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        }

                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )

                                Spacer(Modifier.width(6.dp))

                                Text(
                                    stringResource(R.string.thank_project_dialog_tip),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                stringResource(R.string.thank_project_dialog_tip_content),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(28.dp)
            )


        }

        BackHandler(enabled = showLicenseDialog) {
            showLicenseDialog = false
        }

        AnimatedVisibility(
            visible = showLicenseDialog,
            enter = if (isPreview) fadeIn(tween(0)) else slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it })
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                LicensePage(libs = libs, onBack = { showLicenseDialog = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val rippleConfig = RippleConfiguration(
        color = MaterialTheme.colorScheme.onSurface
    )

    CompositionLocalProvider(LocalRippleConfiguration provides rippleConfig) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                content = content
            )
        }
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
fun ClickableInfoItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            painter = rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowRight),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Keep
@Parcelize
data class CreditProject(
    val name: String,
    val description: String,
    val link: String? = null,
    val license: String? = null
) : android.os.Parcelable

@Composable
fun CreditProjectCard(
    project: CreditProject,
    uriHandler: UriHandler,
    expanded: MutableState<Boolean>
) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        onClick = { expanded.value = !expanded.value }
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(14.dp)
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(2.dp)
                        )
                )

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            project.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        project.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
                if (project.link != null || project.license != null) {
                    val angle by animateFloatAsState(if (expanded.value) 180f else 0f)
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.rotate(angle)
                    )
                }
            }

            AnimatedVisibility(visible = expanded.value) {
                Column {
                    if (project.link != null) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        ProjectDetailItem(
                            icon = Icons.Default.Link,
                            text = project.link,
                            onClick = { uriHandler.openUri(project.link) }
                        )
                    }
                    if (project.license != null) {
                        ProjectDetailItem(
                            icon = Icons.Default.Gavel,
                            text = project.license
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectDetailItem(
    icon: ImageVector,
    text: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick ?: {})
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Preview(showSystemUi = true)
@Composable
fun AboutScreenPreview() {
    MaterialTheme {
        AboutScreen(onMenuClick = {})
    }
}