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

package com.merxury.blocker.core.data.respository.componentdetail

import com.merxury.blocker.core.data.respository.componentdetail.datasource.DbComponentDetailDataSource
import com.merxury.blocker.core.data.respository.componentdetail.datasource.LocalComponentDetailDataSource
import com.merxury.blocker.core.data.respository.componentdetail.datasource.NetworkComponentDetailDataSource
import com.merxury.blocker.core.model.data.ComponentDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class OfflineFirstComponentDetailRepository @Inject constructor(
    private val dbDataSource: DbComponentDetailDataSource,
    private val networkDataSource: NetworkComponentDetailDataSource,
    private val userGeneratedDataSource: LocalComponentDetailDataSource,
) : ComponentDetailRepository {
    override fun getComponentDetail(name: String): Flow<ComponentDetail?> = flow {
        // Priority: user generated > db > network
        val userGeneratedData = userGeneratedDataSource.getComponentDetail(name)
            .first()
        if (userGeneratedData != null) {
            emit(userGeneratedData)
            return@flow
        }
        val dbData = dbDataSource.getComponentDetail(name).first()
        if (dbData != null) {
            emit(dbData)
            return@flow
        }
        val networkData = networkDataSource.getComponentDetail(name).first()
        if (networkData != null) {
            emit(networkData)
            saveComponentDetail(networkData, userGenerated = false)
            return@flow
        }
        emit(null)
    }

    override suspend fun saveComponentDetail(
        componentDetail: ComponentDetail,
        userGenerated: Boolean,
    ): Boolean {
        return if (userGenerated) {
            userGeneratedDataSource.saveComponentData(componentDetail)
        } else {
            dbDataSource.saveComponentData(componentDetail)
        }
    }
}