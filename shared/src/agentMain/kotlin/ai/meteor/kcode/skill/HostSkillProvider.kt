package ai.meteor.kcode.skill

import ai.meteor.kcode.AgentWorkspace

data class HostSkillRoot(
    val path: String,
    val scope: SkillScope,
)

class HostSkillProvider(
    private val workspace: AgentWorkspace,
    private val roots: List<HostSkillRoot>,
    authorityId: String,
) : SkillProvider {
    override val authority = SkillAuthority(SkillAuthorityKind.Host, authorityId)

    override suspend fun catalog(forceReload: Boolean): SkillCatalog {
        val warnings = mutableListOf<SkillWarning>()
        val discovered = mutableListOf<LoadedSkill>()
        val canonicalPaths = mutableSetOf<String>()
        var directoryCount = 0
        var entryCount = 0

        roots.forEach { root ->
            val pending = ArrayDeque<DirectoryToScan>()
            pending += DirectoryToScan(root.path.trimEnd('/'), 0)
            while (pending.isNotEmpty() && directoryCount < SkillLimits.MaxDirectories && entryCount < SkillLimits.MaxEntries) {
                val current = pending.removeFirst()
                directoryCount++
                val entries = runCatching { workspace.list(current.path) }
                    .getOrElse {
                        if (current.depth > 0) warnings += SkillWarning(current.path, it.message ?: "Could not list directory")
                        emptyList()
                    }
                entryCount += entries.size
                val skillFile = entries.firstOrNull { !it.directory && it.path.substringAfterLast('/') == "SKILL.md" }
                if (skillFile != null) {
                    loadSkill(skillFile.path, root.scope, warnings)?.let { loaded ->
                        if (canonicalPaths.add(loaded.descriptor.mainResource)) discovered += loaded
                    }
                }
                if (current.depth < SkillLimits.MaxDepth) {
                    entries.asSequence()
                        .filter { it.directory && !it.path.substringAfterLast('/').startsWith('.') }
                        .forEach { pending += DirectoryToScan(it.path, current.depth + 1) }
                }
                if (discovered.size >= SkillLimits.MaxSkills) break
            }
        }
        if (directoryCount >= SkillLimits.MaxDirectories || entryCount >= SkillLimits.MaxEntries) {
            warnings += SkillWarning("", "Skill discovery limit reached; the catalog was truncated")
        }
        val sorted = discovered.sortedWith(
            compareBy<LoadedSkill>({ it.descriptor.scope.ordinal }, { it.descriptor.name }, { it.descriptor.displayPath }),
        )
        val generation = sorted.fold(17) { hash, skill -> 31 * hash + skill.fingerprint }.toUInt().toString(16)
        return SkillCatalog(sorted.map(LoadedSkill::descriptor), warnings, generation)
    }

    override suspend fun read(request: SkillReadRequest): SkillReadResult {
        validateHandle(request.authority.id)
        validateHandle(request.packageId)
        validateHandle(request.resourceId)
        require(request.authority == authority) { "Skill authority does not match this provider" }
        val catalog = catalog(forceReload = false)
        val descriptor = catalog.entries.singleOrNull {
            it.enabled && it.packageId == request.packageId && it.authority == request.authority
        } ?: error("Skill package is unavailable")
        val packageDirectory = descriptor.mainResource.substringBeforeLast('/', missingDelimiterValue = "")
        require(isContained(packageDirectory, request.resourceId)) { "Skill resource is outside its package" }
        val canonicalResource = workspace.canonicalize(request.resourceId)
        require(isContained(packageDirectory, canonicalResource)) { "Skill resource escapes its package" }
        val contents = workspace.readText(canonicalResource, SkillLimits.MaxResourceBytes)
        return SkillReadResult(
            authority = authority,
            packageId = request.packageId,
            resourceId = request.resourceId,
            contents = contents,
            external = false,
        )
    }

    private suspend fun loadSkill(
        discoveryPath: String,
        scope: SkillScope,
        warnings: MutableList<SkillWarning>,
    ): LoadedSkill? = runCatching {
        val canonical = workspace.canonicalize(discoveryPath)
        val contents = workspace.readText(canonical, SkillLimits.MaxResourceBytes)
        val parsed = SkillFrontmatterParser.parse(contents, discoveryPath.substringBeforeLast('/').substringAfterLast('/'))
        LoadedSkill(
            descriptor = SkillDescriptor(
                authority = authority,
                packageId = "host:$canonical",
                mainResource = canonical,
                name = parsed.name,
                description = parsed.description,
                scope = scope,
                displayPath = discoveryPath,
            ),
            fingerprint = 31 * canonical.hashCode() + contents.hashCode(),
        )
    }.getOrElse {
        warnings += SkillWarning(discoveryPath, it.message ?: "Could not load skill")
        null
    }

    private fun validateHandle(value: String) {
        require(value.isNotBlank()) { "Skill handle must not be blank" }
        require(value.encodeToByteArray().size <= SkillLimits.MaxHandleBytes) { "Skill handle is too long" }
        require(value.none(Char::isISOControl)) { "Skill handle contains a control character" }
    }

    private fun isContained(packageDirectory: String, resource: String): Boolean {
        if (packageDirectory.isEmpty() || resource.isEmpty()) return false
        val packageSegments = strictSegments(packageDirectory)
        val resourceSegments = strictSegments(resource)
        return resourceSegments.size > packageSegments.size &&
            resourceSegments.take(packageSegments.size) == packageSegments
    }

    private fun strictSegments(path: String): List<String> {
        require('\\' !in path && '\u0000' !in path) { "Invalid skill resource path" }
        val segments = path.split('/')
        require(segments.none { it == "." || it == ".." }) { "Skill resource traversal is not allowed" }
        return segments.filter { it.isNotEmpty() }
    }

    private data class DirectoryToScan(val path: String, val depth: Int)
    private data class LoadedSkill(val descriptor: SkillDescriptor, val fingerprint: Int)
}
