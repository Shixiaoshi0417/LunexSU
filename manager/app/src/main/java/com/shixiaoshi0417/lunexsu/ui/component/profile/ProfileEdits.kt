package com.shixiaoshi0417.lunexsu.ui.component.profile

import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.data.model.TemplateInfo
import com.shixiaoshi0417.lunexsu.toRawFlags

/** Copy only root policy fields: applying a template must not renew an authorization. */
internal fun Natives.Profile.withRootTemplate(id: String, template: TemplateInfo): Natives.Profile = copy(
    rootTemplate = id,
    rootUseDefault = false,
    uid = template.uid,
    gid = template.gid,
    groups = template.groups,
    capabilities = template.capabilities,
    context = template.context,
    namespace = template.namespace,
    flags = Natives.Profile.RootProfileFlag.entries.filter { it.ordinal in template.flags }.toRawFlags(),
)

internal fun Natives.Profile.withSELinux(domain: String, rules: String): Natives.Profile = copy(
    context = domain,
    rules = rules,
    rootUseDefault = false,
)

/** Empty is an explicit persisted value too (it removes the rules on the next boot). */
internal fun persistProfileRules(profile: Natives.Profile, special: Boolean, write: (String, String) -> Boolean): Boolean =
    !profile.allowSu || profile.rootUseDefault || special || write(profile.name, profile.rules)
