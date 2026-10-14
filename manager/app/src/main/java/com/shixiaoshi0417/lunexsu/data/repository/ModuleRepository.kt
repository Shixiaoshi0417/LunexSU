package com.shixiaoshi0417.lunexsu.data.repository

import com.shixiaoshi0417.lunexsu.data.model.Module
import com.shixiaoshi0417.lunexsu.data.model.ModuleUpdateInfo

interface ModuleRepository {
    suspend fun getModules(): Result<List<Module>>
    suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo>
}
