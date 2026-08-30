package com.wifi.toolbox.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.wifi.toolbox.R


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LicensePage(
    libs: Libs,
    onBack: () -> Unit
) {
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current

    var selectedLibraryUniqueId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedLibrary = remember(selectedLibraryUniqueId, libs) {
        libs.libraries.find { it.uniqueId == selectedLibraryUniqueId }
    }

    val groupedLibraries = remember(searchQuery, libs) {
        val filtered = if (searchQuery.isEmpty()) libs.libraries
        else libs.libraries.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.uniqueId.contains(searchQuery, ignoreCase = true)
        }

        val groups = filtered.groupBy { library ->
            val developerNames = library.developers
                .mapNotNull { it.name }
                .filter { it.isNotBlank() }
                .joinToString()

            when {
                developerNames.isNotBlank() -> developerNames
                library.organization?.name?.isNotBlank() == true -> library.organization!!.name
                else -> context.getString(R.string.other)
            }
        }

        groups.toSortedMap(
            compareByDescending<String> { groups[it]?.size ?: 0 }
                .thenBy { it.lowercase() }
        )
    }

    var expandedGroups by rememberSaveable { mutableStateOf(setOf<String>()) }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    BackHandler(enabled = isSearching || searchQuery.isNotEmpty()) {
        if (isSearching) {
            isSearching = false; searchQuery = ""
        }
    }

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding(),
        topBar = {
            MediumTopAppBar(
                title = {
                    if (isSearching) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 20.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) Text(
                                    stringResource(R.string.search_tip),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp)
                                )
                                innerTextField()
                            }
                        )
                    } else {
                        Text(stringResource(R.string.license), fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSearching) {
                            isSearching = false; searchQuery = ""
                        } else onBack()
                    }) {
                        Icon(
                            imageVector = if (isSearching) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    if (!isSearching) {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        val listState = rememberLazyListState()
        if (groupedLibraries.isEmpty() && searchQuery.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.search_nothing_tip),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                groupedLibraries.forEach { (author, libsInGroup) ->
                    stickyHeader(key = author) {
                        val isAtTop by remember(listState) {
                            derivedStateOf {
                                val layoutInfo = listState.layoutInfo
                                val visibleItem =
                                    layoutInfo.visibleItemsInfo.find { it.key == author }
                                visibleItem != null && visibleItem.offset <= 0
                            }
                        }

                        val containerColor by animateColorAsState(
                            targetValue = if (isAtTop && scrollBehavior.state.collapsedFraction > 0.5f) {
                                MaterialTheme.colorScheme.surfaceContainer
                            } else {
                                MaterialTheme.colorScheme.background
                            },
                            label = "headerColor"
                        )

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = containerColor
                        ) {
                            Text(
                                text = author ?: stringResource(R.string.other),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    val topFive = libsInGroup.take(5)
                    itemsIndexed(topFive, key = { _, it -> it.uniqueId }) { index, library ->
                        LicenseItem(library) { selectedLibraryUniqueId = library.uniqueId }
                        if (index < topFive.lastIndex || libsInGroup.size > 5) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 24.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }

                    if (libsInGroup.size > 5) {
                        val isExpanded = expandedGroups.contains(author)
                        val moreLibs = libsInGroup.drop(5)

                        item(key = "${author}_more") {
                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = androidx.compose.animation.expandVertically(
                                    expandFrom = Alignment.Top
                                ),
                                exit = androidx.compose.animation.shrinkVertically(
                                    shrinkTowards = Alignment.Top
                                )
                            ) {
                                Column {
                                    moreLibs.forEachIndexed { index, library ->
                                        LicenseItem(library) {
                                            selectedLibraryUniqueId = library.uniqueId
                                        }
                                        if (index < moreLibs.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 24.dp),
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. 展开/收起按钮
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(
                                    onClick = {
                                        expandedGroups = if (isExpanded) {
                                            expandedGroups - author!!
                                        } else {
                                            expandedGroups + author!!
                                        }
                                    },
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Text(if (isExpanded) stringResource(R.string.un_expand) else stringResource(
                                        R.string.expand_list_number,
                                        libsInGroup.size
                                    ))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedLibrary?.let { library ->
        LibraryDetailDialog(
            library = library,
            onDismiss = { selectedLibraryUniqueId = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LicenseItem(library: Library, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(
            text = library.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        library.description?.let {
            if (it.isNotBlank()) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            library.licenses.forEach { license ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = license.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryDetailDialog(
    library: Library,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_ok), fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(
                text = library.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                HorizontalDivider(
                    thickness = 0.5.dp,
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {}

                    library.description?.let {
                        if (it.isNotBlank()) item {
                            DetailSection(stringResource(R.string.description), Icons.Default.Description) {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    item {
                        DetailSection(stringResource(R.string.detailed_information), Icons.Default.Info) {
                            InfoTag(label = stringResource(R.string.id), value = library.uniqueId)
                            library.artifactVersion?.let {
                                Spacer(Modifier.height(8.dp))
                                InfoTag(label = stringResource(R.string.version), value = it)
                            }
                        }
                    }

                    item {
                        DetailSection(stringResource(R.string.link), Icons.Default.Link) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                library.website?.let { url ->
                                    AssistChip(
                                        onClick = { uriHandler.openUri(url) },
                                        label = { Text(stringResource(R.string.website)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Public,
                                                null,
                                                Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                                library.scm?.url?.let { scmUrl ->
                                    AssistChip(
                                        onClick = { uriHandler.openUri(scmUrl) },
                                        label = { Text(stringResource(R.string.view_score_code)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Code,
                                                null,
                                                Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (library.licenses.isNotEmpty()) {
                        item {
                            DetailSection(stringResource(R.string.ope_source_license), Icons.Default.Gavel) {
                                library.licenses.forEach { license ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(
                                            alpha = 0.4f
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(
                                                start = 12.dp,
                                                end = 4.dp,
                                                top = 4.dp,
                                                bottom = 4.dp
                                            ),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                license.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            license.url?.let { licenseUrl ->
                                                TextButton(onClick = { uriHandler.openUri(licenseUrl) }) {
                                                    Text(stringResource(R.string.view_details))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val devInfo = library.developers.mapNotNull { it.name }.joinToString()

                    if (devInfo.isNotBlank()) {
                        item {
                            DetailSection(stringResource(R.string.contributor), Icons.Default.People) {
                                InfoTag(label = stringResource(R.string.developer), value = devInfo)
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun InfoTag(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun DetailSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}