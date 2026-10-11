package com.shixiaoshi0417.lunexsu.data.repository

import com.shixiaoshi0417.lunexsu.data.model.RepoModule

interface ModuleRepoRepository {
    suspend fun fetchModules(): Result<List<RepoModule>>
}
