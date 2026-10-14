package com.shixiaoshi0417.lunexsu.data.repository

import com.shixiaoshi0417.lunexsu.data.model.RepoModuleDetail
import com.shixiaoshi0417.lunexsu.data.model.RepoModuleList
import com.shixiaoshi0417.lunexsu.data.registry.UpdateVerification

interface ModuleRepoRepository {
    /** Index + module list; falls back to the last complete cached copy on failure. */
    suspend fun fetchModules(): Result<RepoModuleList>

    /** Detail and README of a module listed in the current registry index. */
    suspend fun fetchDetail(moduleId: String): Result<RepoModuleDetail>

    /** Sanitized changelog HTML of a release, or null. */
    suspend fun fetchChangelogHtml(moduleId: String, tag: String?): String?

    /** Verification policy for an installed module's updateJson download. */
    suspend fun resolveUpdate(moduleId: String, updateJsonUrl: String, zipUrl: String, versionCode: Int): UpdateVerification

    /** Raw Markdown of a registry-hosted document, or null if not registry-hosted. */
    suspend fun fetchRegistryText(url: String): String?

    /** Whether the URL requires the restricted registry renderer. */
    fun isRegistryUrl(url: String): Boolean
}
