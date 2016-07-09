/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.analyzer

import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.context.ModuleContext
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.context.withModule
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentProvider
import org.jetbrains.kotlin.descriptors.PackagePartProvider
import org.jetbrains.kotlin.descriptors.impl.LazyModuleDependencies
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.TargetEnvironment
import org.jetbrains.kotlin.resolve.TargetPlatform
import org.jetbrains.kotlin.resolve.createModule
import org.jetbrains.kotlin.utils.keysToMap
import org.jetbrains.kotlin.utils.singletonOrEmptyList
import java.util.*

class ResolverForModule(
    val packageFragmentProvider: PackageFragmentProvider,
    val componentProvider: ComponentProvider
)

abstract class ResolverForProject<M : ModuleInfo> {
    fun resolverForModule(moduleInfo: M): ResolverForModule = resolverForModuleDescriptor(descriptorForModule(moduleInfo))
    abstract fun tryGetResolverForModule(moduleInfo: M): ResolverForModule?
    abstract fun descriptorForModule(moduleInfo: M): ModuleDescriptor
    abstract fun resolverForModuleDescriptor(descriptor: ModuleDescriptor): ResolverForModule

    abstract val name: String
    abstract val allModules: Collection<M>

    override fun toString() = "$name"
}

class EmptyResolverForProject<M : ModuleInfo> : ResolverForProject<M>() {
    override val name: String
        get() = "Empty resolver"

    override fun tryGetResolverForModule(moduleInfo: M): ResolverForModule? = null
    override fun resolverForModuleDescriptor(descriptor: ModuleDescriptor): ResolverForModule = throw IllegalStateException("$descriptor is not contained in this resolver")
    override fun descriptorForModule(moduleInfo: M) = throw IllegalStateException("Should not be called for $moduleInfo")
    override val allModules: Collection<M> = listOf()
}

val ResolverForModuleCapability = ModuleDescriptor.Capability<() -> ResolverForModule>("ResolverForModuleCapability")

class ResolverForProjectImpl<M : ModuleInfo>(
        private val debugName: String,
        private val delegateResolver: ResolverForProject<M> = EmptyResolverForProject()
) : ResolverForProject<M>() {
    private lateinit var descriptorByModule: Map<M, () -> ModuleDescriptorImpl>

    fun initialize(descriptorByModule: Map<M, () -> ModuleDescriptorImpl>) {
        this.descriptorByModule = descriptorByModule
    }

    override fun tryGetResolverForModule(moduleInfo: M): ResolverForModule? {
        if (!isCorrectModuleInfo(moduleInfo)) {
            return null
        }
        return resolverForModuleDescriptor(doGetDescriptorForModule(moduleInfo))
    }

    override val allModules: Collection<M> by lazy {
        (descriptorByModule.keys + delegateResolver.allModules).toSet()
    }

    override val name: String
        get() = "Resolver for '$debugName'"

    private fun isCorrectModuleInfo(moduleInfo: M) = moduleInfo in allModules

    override fun resolverForModuleDescriptor(descriptor: ModuleDescriptor): ResolverForModule {
        return descriptor.getCapability(ResolverForModuleCapability)?.invoke() ?: delegateResolver.resolverForModuleDescriptor(descriptor)
    }

    override fun descriptorForModule(moduleInfo: M): ModuleDescriptorImpl {
        if (!isCorrectModuleInfo(moduleInfo)) {
            throw AssertionError("$name does not know how to resolve $moduleInfo")
        }
        return doGetDescriptorForModule(moduleInfo)
    }

    private fun doGetDescriptorForModule(moduleInfo: M): ModuleDescriptorImpl {
        return descriptorByModule[moduleInfo]?.invoke() ?: delegateResolver.descriptorForModule(moduleInfo) as ModuleDescriptorImpl
    }
}

data class ModuleContent(
        val syntheticFiles: Collection<KtFile>,
        val moduleContentScope: GlobalSearchScope
)

interface PlatformAnalysisParameters

interface ModuleInfo {
    val isLibrary: Boolean
        get() = false
    val name: Name
    fun dependencies(): List<ModuleInfo>
    fun modulesWhoseInternalsAreVisible(): Collection<ModuleInfo> = listOf()
    fun dependencyOnBuiltIns(): DependencyOnBuiltIns = DependenciesOnBuiltIns.LAST
    val capabilities: Map<ModuleDescriptor.Capability<*>, Any?>
        get() = emptyMap()

    //TODO: (module refactoring) provide dependency on builtins after runtime in IDEA
    interface DependencyOnBuiltIns {
        fun adjustDependencies(builtinsModule: ModuleDescriptorImpl, dependencies: MutableList<ModuleDescriptorImpl>)
    }

    enum class DependenciesOnBuiltIns : DependencyOnBuiltIns {
        NONE {
            override fun adjustDependencies(builtinsModule: ModuleDescriptorImpl, dependencies: MutableList<ModuleDescriptorImpl>) {
                //do nothing
            }
        },
        LAST {
            override fun adjustDependencies(builtinsModule: ModuleDescriptorImpl, dependencies: MutableList<ModuleDescriptorImpl>) {
                dependencies.add(builtinsModule)
            }
        };
    }
}

abstract class AnalyzerFacade<in P : PlatformAnalysisParameters> {
    fun <M : ModuleInfo> setupResolverForProject(
            debugName: String,
            projectContext: ProjectContext,
            modules: Collection<M>,
            modulesContent: (M) -> ModuleContent,
            platformParameters: P,
            targetEnvironment: TargetEnvironment,
            builtIns: KotlinBuiltIns,
            delegateResolver: ResolverForProject<M> = EmptyResolverForProject(),
            packagePartProviderFactory: (M, ModuleContent) -> PackagePartProvider = { module, content -> PackagePartProvider.EMPTY },
            firstDependency: M? = null
    ): ResolverForProject<M> {
        val storageManager = projectContext.storageManager
        val resolverForProject = ResolverForProjectImpl(debugName, delegateResolver)

        fun computeDependencyDescriptors(module: M): List<ModuleDescriptorImpl> {
            val orderedDependencies = firstDependency.singletonOrEmptyList() + module.dependencies()
            val dependenciesDescriptors = orderedDependencies.mapTo(ArrayList<ModuleDescriptorImpl>()) {
                dependencyInfo ->
                resolverForProject.descriptorForModule(dependencyInfo as M)
            }
            module.dependencyOnBuiltIns().adjustDependencies(
                    resolverForProject.descriptorForModule(module).builtIns.builtInsModule, dependenciesDescriptors)
            return dependenciesDescriptors
        }

        val cache = WeakReferenceSLRUCache<M, ModuleDescriptorImpl>(storageManager)
        val moduleInfoToDescriptorComputationMap: Map<M, () -> ModuleDescriptorImpl> = modules.keysToMap {
            module ->
            cache.prepareValueComputation(module) {
                val capabilities = HashMap(module.capabilities)
                val descriptor = targetPlatform.createModule(module.name, storageManager, builtIns, capabilities)
                val content = modulesContent(module)

                val computeResolverForModule = storageManager.createLazyValue {
                    createResolverForModule(
                        module, descriptor, projectContext.withModule(descriptor), modulesContent(module),
                        platformParameters, targetEnvironment, resolverForProject,
                        packagePartProviderFactory(module, content)
                    )
                }

                descriptor.initialize(DelegatingPackageFragmentProvider { computeResolverForModule().packageFragmentProvider } )

                descriptor.setDependencies(
                        LazyModuleDependencies(storageManager) { computeDependencyDescriptors(module) }
                )

                descriptor.setFriends(storageManager.createLazyValue {
                    module.modulesWhoseInternalsAreVisible().mapTo(mutableSetOf()) {
                        resolverForProject.descriptorForModule(it as M)
                    }
                })

                capabilities[ResolverForModuleCapability] = computeResolverForModule

                descriptor
            }
        }

        resolverForProject.initialize(moduleInfoToDescriptorComputationMap)

        return resolverForProject
    }

    protected abstract fun <M : ModuleInfo> createResolverForModule(
            moduleInfo: M,
            moduleDescriptor: ModuleDescriptorImpl,
            moduleContext: ModuleContext,
            moduleContent: ModuleContent,
            platformParameters: P,
            targetEnvironment: TargetEnvironment,
            resolverForProject: ResolverForProject<M>,
            packagePartProvider: PackagePartProvider
    ): ResolverForModule

    abstract val targetPlatform: TargetPlatform
}

//NOTE: relies on delegate to be lazily computed and cached
private class DelegatingPackageFragmentProvider(
        private val delegate: () -> PackageFragmentProvider
) : PackageFragmentProvider {

    override fun getPackageFragments(fqName: FqName): List<PackageFragmentDescriptor> {
        return delegate().getPackageFragments(fqName)
    }

    override fun getSubPackagesOf(fqName: FqName, nameFilter: (Name) -> Boolean): Collection<FqName> {
        return delegate().getSubPackagesOf(fqName, nameFilter)
    }
}
