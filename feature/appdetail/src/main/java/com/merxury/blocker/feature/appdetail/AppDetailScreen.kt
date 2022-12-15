/*
 * Copyright 2022 Blocker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.merxury.blocker.feature.appdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.ExperimentalLifecycleComposeApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.merxury.blocker.core.designsystem.component.BlockerLoadingWheel
import com.merxury.blocker.core.designsystem.component.BlockerTab
import com.merxury.blocker.core.designsystem.component.BlockerTabRow
import com.merxury.blocker.core.designsystem.component.BlockerTopAppBar
import com.merxury.blocker.core.designsystem.icon.BlockerIcons
import com.merxury.blocker.core.ui.TabState
import com.merxury.blocker.feature.appdetail.AppDetailUiState.Success
import com.merxury.blocker.feature.appdetail.component.AppInfoTabContent
import com.merxury.blocker.feature.appdetail.component.ComponentTabContent

@OptIn(ExperimentalLifecycleComposeApi::class)
@Composable
fun AppDetailRoute(
    viewModel: AppDetailViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
) {
    val tabState by viewModel.tabState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AppDetailScreen(
        uiState = uiState,
        tabState = tabState,
        isRefreshing = uiState is AppDetailUiState.Loading,
        onRefresh = { viewModel.onRefresh() },
        switchTab = viewModel::switchTab,
        onSwitch = viewModel::onSwitch,
        onBackClick = onBackClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    uiState: AppDetailUiState,
    tabState: TabState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    switchTab: (Int) -> Unit,
    onSwitchClick: (String, String, Boolean) -> Boolean,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        when (uiState) {
            AppDetailUiState.Loading -> {
                Column(
                    modifier = modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    BlockerLoadingWheel(
                        modifier = modifier,
                        contentDesc = stringResource(id = R.string.loading),
                    )
                }
            }

            is Success -> {
                BlockerTopAppBar(
                    title = uiState.appInfo.appName,
                    navigationIcon = BlockerIcons.Back,
                    navigationIconContentDescription = null,
                    actionIconFirst = BlockerIcons.Search,
                    actionIconContentDescriptionFirst = null,
                    actionIconSecond = BlockerIcons.MoreVert,
                    actionIconContentDescriptionSecond = null,
                    onNavigationClick = onBackClick
                )
                AppDetailContent(
                    uiState = uiState,
                    tabState = tabState,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    switchTab = switchTab,
                    onSwitchClick = onSwitchClick,
                    modifier = modifier
                )
            }

            is AppDetailUiState.Error -> ErrorAppDetailScreen(uiState.error.message)
        }
    }
}

@Composable
fun AppDetailContent(
    uiState: Success,
    tabState: TabState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    switchTab: (Int) -> Unit,
    onSwitchClick: (String, String, Boolean) -> Boolean,
    modifier: Modifier = Modifier
) {
    BlockerTabRow(selectedTabIndex = tabState.currentIndex) {
        tabState.titles.forEachIndexed { index, titleRes ->
            BlockerTab(
                selected = index == tabState.currentIndex,
                onClick = { switchTab(index) },
                text = { Text(text = stringResource(id = titleRes)) }
            )
        }
    }
    when (tabState.currentIndex) {
        0 -> {
            AppInfoTabContent(appDetailInfo = uiState.appInfo)
        }

        1 -> {
            ComponentTabContent(
                components = uiState.service,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                onSwitchClick = onSwitchClick,
                modifier = modifier
            )
        }

        2 -> {
            ComponentTabContent(
                components = uiState.receiver,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                onSwitchClick = onSwitchClick,
                modifier = modifier
            )
        }

        3 -> {
            ComponentTabContent(
                components = uiState.activity,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                onSwitchClick = onSwitchClick,
                modifier = modifier
            )
        }

        4 -> {
            ComponentTabContent(
                components = uiState.contentProvider,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                onSwitchClick = onSwitchClick,
                modifier = modifier
            )
        }
    }
}

@Composable
fun ErrorAppDetailScreen(message: String) {
    Text(text = message)
}
