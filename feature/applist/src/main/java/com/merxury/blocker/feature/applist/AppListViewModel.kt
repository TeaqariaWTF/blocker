/*
 * Copyright 2023 Blocker
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

package com.merxury.blocker.feature.applist

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.merxury.blocker.core.data.respository.app.AppRepository
import com.merxury.blocker.core.data.respository.userdata.UserDataRepository
import com.merxury.blocker.core.dispatchers.BlockerDispatchers.DEFAULT
import com.merxury.blocker.core.dispatchers.BlockerDispatchers.IO
import com.merxury.blocker.core.dispatchers.Dispatcher
import com.merxury.blocker.core.extension.exec
import com.merxury.blocker.core.extension.getPackageInfoCompat
import com.merxury.blocker.core.model.data.InstalledApp
import com.merxury.blocker.core.model.preference.AppSorting
import com.merxury.blocker.core.model.preference.AppSorting.FIRST_INSTALL_TIME_ASCENDING
import com.merxury.blocker.core.model.preference.AppSorting.FIRST_INSTALL_TIME_DESCENDING
import com.merxury.blocker.core.model.preference.AppSorting.LAST_UPDATE_TIME_ASCENDING
import com.merxury.blocker.core.model.preference.AppSorting.LAST_UPDATE_TIME_DESCENDING
import com.merxury.blocker.core.model.preference.AppSorting.NAME_ASCENDING
import com.merxury.blocker.core.model.preference.AppSorting.NAME_DESCENDING
import com.merxury.blocker.core.result.Result
import com.merxury.blocker.core.ui.data.ErrorMessage
import com.merxury.blocker.core.ui.data.toErrorMessage
import com.merxury.blocker.core.utils.ApplicationUtil
import com.merxury.blocker.core.utils.FileUtils
import com.merxury.blocker.feature.applist.state.AppStateCache
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import timber.log.Timber

@HiltViewModel
class AppListViewModel @Inject constructor(
    app: android.app.Application,
    private val pm: PackageManager,
    private val userDataRepository: UserDataRepository,
    private val appRepository: AppRepository,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
    @Dispatcher(DEFAULT) private val cpuDispatcher: CoroutineDispatcher,
) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow<AppListUiState>(AppListUiState.Loading)
    val uiState = _uiState.asStateFlow()
    private val _errorState = MutableStateFlow<ErrorMessage?>(null)
    val errorState = _errorState.asStateFlow()
    private val appStateList = mutableStateListOf<AppItem>()
    private val appListMutex = Mutex()
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable)
        _errorState.tryEmit(throwable.toErrorMessage())
    }

    init {
        loadData()
        updateInstalledAppList()
        listenSortingChanges()
        listenShowSystemAppsChanges()
    }

    fun loadData() = viewModelScope.launch {
        appRepository.getApplicationList()
            .onStart {
                _uiState.emit(AppListUiState.Loading)
            }
            .distinctUntilChanged()
            .collect { list ->
                Timber.v("App list changed, size ${list.size}")
                val preference = userDataRepository.userData.first()
                val sortType = preference.appSorting
                val filteredList = if (preference.showSystemApps) {
                    list
                } else {
                    list.filterNot { it.isSystem }
                }.toMutableList()
                sortList(filteredList, sortType)
                updateAppStateList(filteredList)
                _uiState.emit(AppListUiState.Success(appStateList))
            }
    }

    private fun updateInstalledAppList() = viewModelScope.launch {
        appRepository.updateApplicationList().collect {
            if (it is Result.Error) {
                _errorState.emit(it.exception?.toErrorMessage())
            }
        }
    }

    private fun listenSortingChanges() = viewModelScope.launch {
        userDataRepository.userData
            .map { it.appSorting }
            .distinctUntilChanged()
            .drop(1)
            .collect {
                val uiState = _uiState.value
                if (uiState is AppListUiState.Success) {
                    sortList(uiState.appList, it)
                }
            }
    }

    private fun listenShowSystemAppsChanges() = viewModelScope.launch {
        userDataRepository.userData
            .map { it.showSystemApps }
            .distinctUntilChanged()
            .drop(1)
            .collect { loadData() }
    }

    fun updateSorting(sorting: AppSorting) = viewModelScope.launch {
        userDataRepository.setAppSorting(sorting)
    }

    fun updateServiceStatus(packageName: String) = viewModelScope.launch(
        context = ioDispatcher + exceptionHandler,
    ) {
        val userData = userDataRepository.userData.first()
        if (!userData.showServiceInfo) {
            return@launch
        }
        appListMutex.withLock {
            // Avoid ConcurrentModificationException
            val itemIndex = appStateList.indexOfFirst { it.packageName == packageName }
            val oldItem = appStateList.getOrNull(itemIndex) ?: return@launch
            if (oldItem.appServiceStatus != null) {
                // Don't get service info again
                return@launch
            }
            Timber.d("Get service status for $packageName")
            val status = AppStateCache.get(getApplication(), packageName)
            val serviceStatus = AppServiceStatus(
                packageName = status.packageName,
                running = status.running,
                blocked = status.blocked,
                total = status.total,
            )
            val newItem = oldItem.copy(appServiceStatus = serviceStatus)
            appStateList[itemIndex] = newItem
        }
    }

    fun dismissDialog() = viewModelScope.launch {
        _errorState.emit(null)
    }

    fun clearData(packageName: String) = viewModelScope.launch(ioDispatcher + exceptionHandler) {
        "pm clear $packageName".exec(ioDispatcher)
    }

    fun clearCache(packageName: String) = viewModelScope.launch(ioDispatcher + exceptionHandler) {
        val context: Context = getApplication()
        val cacheFolder = context.filesDir
            ?.parentFile
            ?.parentFile
            ?.resolve(packageName)
            ?.resolve("cache")
            ?: run {
                Timber.e("Can't resolve cache path for $packageName")
                return@launch
            }
        Timber.d("Delete cache folder: $cacheFolder")
        FileUtils.delete(cacheFolder.absolutePath, recursively = true, ioDispatcher)
    }

    fun uninstall(packageName: String) = viewModelScope.launch(ioDispatcher + exceptionHandler) {
        "pm uninstall $packageName".exec(ioDispatcher)
        notifyAppUpdated(packageName)
    }

    fun forceStop(packageName: String) = viewModelScope.launch(ioDispatcher + exceptionHandler) {
        "am force-stop $packageName".exec(ioDispatcher)
        notifyAppUpdated(packageName)
    }

    fun enable(packageName: String) = viewModelScope.launch(ioDispatcher + exceptionHandler) {
        "pm enable $packageName".exec(ioDispatcher)
        notifyAppUpdated(packageName)
    }

    fun disable(packageName: String) = viewModelScope.launch(ioDispatcher + exceptionHandler) {
        "pm disable $packageName".exec(ioDispatcher)
        notifyAppUpdated(packageName)
    }

    private suspend fun notifyAppUpdated(packageName: String) {
        appRepository.updateApplication(packageName).collect {
            if (it is Result.Error) {
                _errorState.emit(it.exception?.toErrorMessage())
            }
        }
    }

    private suspend fun sortList(
        list: SnapshotStateList<AppItem>,
        sorting: AppSorting,
    ) = withContext(cpuDispatcher) {
        appListMutex.withLock {
            when (sorting) {
                NAME_ASCENDING -> list.sortBy { it.label.lowercase() }
                NAME_DESCENDING -> list.sortByDescending { it.label.lowercase() }
                FIRST_INSTALL_TIME_ASCENDING -> list.sortBy { it.firstInstallTime }
                FIRST_INSTALL_TIME_DESCENDING -> list.sortByDescending { it.firstInstallTime }
                LAST_UPDATE_TIME_ASCENDING -> list.sortBy { it.lastUpdateTime }
                LAST_UPDATE_TIME_DESCENDING -> list.sortByDescending { it.lastUpdateTime }
            }
        }
    }

    private suspend fun sortList(
        list: MutableList<InstalledApp>,
        sorting: AppSorting,
    ) = withContext(cpuDispatcher) {
        when (sorting) {
            NAME_ASCENDING -> list.sortBy { it.label.lowercase() }
            NAME_DESCENDING -> list.sortByDescending { it.label.lowercase() }
            FIRST_INSTALL_TIME_ASCENDING -> list.sortBy { it.firstInstallTime }
            FIRST_INSTALL_TIME_DESCENDING -> list.sortByDescending { it.firstInstallTime }
            LAST_UPDATE_TIME_ASCENDING -> list.sortBy { it.lastUpdateTime }
            LAST_UPDATE_TIME_DESCENDING -> list.sortByDescending { it.lastUpdateTime }
        }
    }

    private suspend fun updateAppStateList(
        newList: List<InstalledApp>,
    ) = withContext(cpuDispatcher) {
        if (newList.size < appStateList.size) {
            appListMutex.withLock {
                appStateList.filter { origItem ->
                    // Size different, find the changed one
                    newList.none { newItem -> origItem.packageName == newItem.packageName }
                }.forEach { changedItem ->
                    // Then remove from the state list
                    appStateList.remove(changedItem)
                }
            }
        }
        // After modification, the size of the app list should be smaller than the new list
        check(newList.size >= appStateList.size) {
            "New list size == ${newList.size}, original list size = ${appStateList.size}"
        }
        newList.forEachIndexed { index, installedApp ->
            appListMutex.withLock {
                val origApp = appStateList.getOrNull(index)
                val newItem = AppItem(
                    label = installedApp.label,
                    packageName = installedApp.packageName,
                    versionName = installedApp.versionName,
                    versionCode = installedApp.versionCode,
                    isSystem = ApplicationUtil.isSystemApp(pm, installedApp.packageName),
                    // TODO detect if an app is running or not
                    isRunning = false,
                    enabled = installedApp.isEnabled,
                    firstInstallTime = installedApp.firstInstallTime,
                    lastUpdateTime = installedApp.lastUpdateTime,
                    appServiceStatus = null,
                    packageInfo = pm.getPackageInfoCompat(installedApp.packageName, 0),
                )
                if (origApp == null) {
                    // Fill in apps in the empty slot
                    appStateList.add(newItem)
                    return@withLock
                }
                if (!origApp.equalsToInstalledApp(installedApp)) {
                    appStateList[index] = newItem
                }
            }
        }
    }

    private fun AppItem.equalsToInstalledApp(app: InstalledApp): Boolean {
        return label == app.label &&
            packageName == app.packageName &&
            versionName == app.versionName &&
            versionCode == app.versionCode &&
            isSystem == app.isSystem &&
            enabled == app.isEnabled &&
            firstInstallTime == app.firstInstallTime &&
            lastUpdateTime == app.lastUpdateTime
    }
}

data class AppServiceStatus(
    val packageName: String,
    val running: Int = 0,
    val blocked: Int = 0,
    val total: Int = 0,
)

/**
 * Data representation for the installed application.
 * App icon will be loaded by PackageName.
 */
data class AppItem(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val isRunning: Boolean,
    val enabled: Boolean,
    val firstInstallTime: Instant?,
    val lastUpdateTime: Instant?,
    val appServiceStatus: AppServiceStatus?,
    val packageInfo: PackageInfo?,
)

sealed interface AppListUiState {
    object Loading : AppListUiState
    class Error(val error: ErrorMessage) : AppListUiState
    data class Success(
        val appList: SnapshotStateList<AppItem>,
    ) : AppListUiState
}
