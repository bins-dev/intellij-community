// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins

import com.fasterxml.jackson.databind.type.TypeFactory
import com.intellij.DynamicBundle.LanguageBundleEP
import com.intellij.codeInsight.daemon.impl.InspectionVisitorOptimizer
import com.intellij.configurationStore.jdomSerializer
import com.intellij.configurationStore.runInAutoSaveDisabledMode
import com.intellij.configurationStore.saveProjectsAndApp
import com.intellij.diagnostic.MessagePool
import com.intellij.diagnostic.PerformanceWatcher
import com.intellij.diagnostic.hprof.action.SystemTempFilenameSupplier
import com.intellij.diagnostic.hprof.analysis.AnalyzeClassloaderReferencesGraph
import com.intellij.diagnostic.hprof.analysis.HProfAnalysis
import com.intellij.ide.DataManager
import com.intellij.ide.IdeBundle
import com.intellij.ide.IdeEventQueue
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.cancelAndJoinBlocking
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.ide.plugins.cl.PluginClassLoader
import com.intellij.ide.ui.TopHitCache
import com.intellij.ide.ui.UIThemeProvider
import com.intellij.idea.IdeaLogger
import com.intellij.lang.Language
import com.intellij.notification.NotificationType
import com.intellij.notification.impl.ApplicationNotificationsModel
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.actionSystem.impl.canUnloadActionGroup
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.ApplicationImpl
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.application.impl.inModalContext
import com.intellij.openapi.components.ComponentManagerEx
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.impl.BundledColorSchemeEPName
import com.intellij.openapi.extensions.ExtensionDescriptor
import com.intellij.openapi.extensions.ExtensionPointDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.extensions.impl.ExtensionsAreaImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.keymap.impl.BundledKeymapBean
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.PotemkinProgress
import com.intellij.openapi.progress.util.TitledIndicator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.project.getOpenedProjects
import com.intellij.openapi.project.impl.ProjectManagerImpl
import com.intellij.openapi.updateSettings.impl.UpdateChecker
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.objectTree.ThrowableInterner
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryManager
import com.intellij.openapi.vfs.newvfs.FileAttribute
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.plugins.parser.impl.elements.ActionElement.ActionElementName
import com.intellij.psi.util.CachedValuesManager
import com.intellij.serviceContainer.getComponentManagerImpl
import com.intellij.ui.IconDeferrer
import com.intellij.ui.mac.touchbar.TouchbarSupport
import com.intellij.util.*
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.containers.WeakList
import com.intellij.util.messages.impl.DynamicPluginUnloaderCompatibilityLayer
import com.intellij.util.messages.impl.MessageBusEx
import com.intellij.util.ref.GCWatcher
import com.intellij.util.xmlb.clearPropertyCollectorCache
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.*
import java.util.function.Predicate
import javax.swing.JComponent
import javax.swing.ToolTipManager

private val LOG = logger<DynamicPlugins>()
private val classloadersFromUnloadedPlugins = mutableMapOf<PluginId, WeakList<PluginClassLoader>>()

@ApiStatus.Internal
object DynamicPlugins {
  private val VETOER_EP_NAME: ExtensionPointName<DynamicPluginVetoer> = ExtensionPointName.create("com.intellij.ide.dynamicPluginVetoer");

  private var myProcessRun = 0
  private val myProcessCallbacks = mutableListOf<Runnable>()
  private val myLock = Any()

  @JvmStatic
  @JvmOverloads
  fun allowLoadUnloadWithoutRestart(descriptor: IdeaPluginDescriptorImpl,
                                    baseDescriptor: IdeaPluginDescriptorImpl? = null,
                                    context: List<IdeaPluginDescriptorImpl> = emptyList()): Boolean {
    val reason = checkCanUnloadWithoutRestart(module = descriptor, parentModule = baseDescriptor, context = context)
    if (reason != null) {
      LOG.info(reason)
    }
    return reason == null
  }

  /**
   * @return true if the requested enabled state was applied without restart, false if restart is required
   */
  fun loadPlugins(descriptors: Collection<IdeaPluginDescriptorImpl>, project: Project?): Boolean {
    if (descriptors.isEmpty()) {
      return true
    }
    return runProcess {
      @Suppress("UNCHECKED_CAST") (descriptors as Collection<PluginMainDescriptor>)
      val descriptors = getDescriptorsToUpdateWithoutRestart(descriptors, load = true)
      if (descriptors.isEmpty()) {
        return@runProcess false
      }
      withPotemkinProgress(project, IdeBundle.message("plugins.progress.loading.plugins.for.current.project.title")) { indicator ->
        publishingPluginsLoadedEvent {
          for (descriptor in descriptors) {
            indicator.title = IdeBundle.message("plugins.progress.loading.plugin.title", descriptor.name)
            descriptor.isMarkedForLoading = true
            if (!doLoadPlugin(descriptor)) {
              LOG.info("Failed to load: $descriptor, restart required")
              InstalledPluginsState.getInstance().isRestartRequired = true
              return@publishingPluginsLoadedEvent false
            }
          }
          return@publishingPluginsLoadedEvent true
        }
      }
    }
  }

  /**
   * @return true if the requested enabled state was applied without restart, false if restart is required
   */
  fun unloadPlugins(
    descriptors: Collection<IdeaPluginDescriptorImpl>,
    project: Project? = null,
    parentComponent: JComponent? = null,
    options: UnloadPluginOptions = UnloadPluginOptions(disable = true),
  ): Boolean {
    if (descriptors.isEmpty()) {
      return true
    }
    return runProcess {
      descriptors.forEach { check(it is PluginMainDescriptor) { it } }
      @Suppress("UNCHECKED_CAST") (descriptors as Collection<PluginMainDescriptor>)
      val descriptors = getDescriptorsToUpdateWithoutRestart(descriptors, load = false)
      if (descriptors.isEmpty()) {
        return@runProcess false
      }
      for (descriptor in descriptors) {
        descriptor.isMarkedForLoading = false
        if (!doUnloadPluginWithProgress(project, parentComponent, descriptor, options)) {
          LOG.info("Failed to unload: $descriptor, restart required")
          InstalledPluginsState.getInstance().isRestartRequired = true
          return@runProcess false
        }
      }
      return@runProcess true
    }
  }

  private fun runProcess(process: () -> Boolean): Boolean {
    try {
      synchronized(myLock) {
        myProcessRun++
      }
      return process.invoke()
    }
    finally {
      val callbacks = mutableListOf<Runnable>()
      synchronized(myLock) {
        myProcessRun--
        callbacks.addAll(myProcessCallbacks)
        myProcessCallbacks.clear()
      }
      callbacks.forEach { it.run() }
    }
  }

  fun runAfter(runAlways: Boolean, callback: Runnable) {
    synchronized(myLock) {
      if (myProcessRun > 0) {
        myProcessCallbacks.add(callback)
        return
      }
    }
    if (runAlways) {
      callback.run()
    }
  }

  private fun getDescriptorsToUpdateWithoutRestart(plugins: Collection<PluginMainDescriptor>, load: Boolean): List<PluginMainDescriptor> {
    val pluginSet = PluginManagerCore.getPluginSet()
    val descriptors = plugins
      .asSequence()
      .distinctBy { it.pluginId }
      .filter { pluginSet.isPluginEnabled(it.pluginId) != load }
      .toList()

    val operationText = if (load) "load" else "unload"
    val message = descriptors.joinToString(prefix = "Plugins to $operationText: [", postfix = "]")
    LOG.info(message)

    if (!descriptors.all { allowLoadUnloadWithoutRestart(it, context = descriptors) }) {
      return emptyList()
    }

    // todo plugin installation should be done not in this method
    var allPlugins = pluginSet.allPlugins
    for (descriptor in descriptors) {
      if (!allPlugins.contains(descriptor)) {
        allPlugins = allPlugins + descriptor
      }
    }

    // todo make internal:
    //  1) ModuleGraphBase;
    //  2) SortedModuleGraph;
    //  3) SortedModuleGraph.topologicalComparator;
    //  4) PluginSetBuilder.sortedModuleGraph.
    var comparator = PluginSetBuilder(allPlugins).topologicalComparator

    if (!load) {
      comparator = comparator.reversed()
    }

    return descriptors.sortedWith(comparator)
  }

  fun checkCanUnloadWithoutRestart(module: IdeaPluginDescriptorImpl): String? {
    return checkCanUnloadWithoutRestart(module, parentModule = null)
  }

  /**
   * @param context Plugins which are being loaded at the same time as [module]
   */
  private fun checkCanUnloadWithoutRestart(module: IdeaPluginDescriptorImpl,
                                           parentModule: IdeaPluginDescriptorImpl?,
                                           optionalDependencyPluginId: PluginId? = null,
                                           context: List<IdeaPluginDescriptorImpl> = emptyList(),
                                           checkImplementationDetailDependencies: Boolean = true): String? {
    if (parentModule == null) {
      if (module.isRequireRestart) {
        return "Plugin ${module.pluginId} is explicitly marked as requiring restart"
      }
      if (module.productCode != null && !module.isBundled && !PluginManagerCore.isDevelopedByJetBrains(module)) {
        return "Plugin ${module.pluginId} is a paid plugin"
      }
      if (InstalledPluginsState.getInstance().isRestartRequired) {
        return InstalledPluginsState.RESTART_REQUIRED_MESSAGE
      }
    }

    val pluginSet = PluginManagerCore.getPluginSet()

    if (classloadersFromUnloadedPlugins[module.pluginId]?.isEmpty() == false) {
      return "Not allowing load/unload of ${module.pluginId} because of incomplete previous unload operation for that plugin"
    }
    findMissingRequiredDependency(module, context, pluginSet)?.let { pluginDependency ->
      return "Required dependency ${pluginDependency} of plugin ${module.pluginId} is not currently loaded"
    }

    val app = ApplicationManager.getApplication()

    if (parentModule == null) {
      if (!RegistryManager.getInstance().`is`("ide.plugins.allow.unload")) {
        if (!allowLoadUnloadSynchronously(module)) {
          return "ide.plugins.allow.unload is disabled and synchronous load/unload is not possible for ${module.pluginId}"
        }
        return null
      }

      val vetoMessage = VETOER_EP_NAME.computeSafeIfAny {
        it.vetoPluginUnload(module)
      }
      if (vetoMessage != null) return vetoMessage
    }

    if (!Registry.`is`("ide.plugins.allow.unload.from.sources")) {
      if (pluginSet.findEnabledPlugin(module.pluginId) != null && module === parentModule && !module.useIdeaClassLoader) {
        val pluginClassLoader = module.pluginClassLoader
        if (pluginClassLoader != null && pluginClassLoader !is PluginClassLoader && !app.isUnitTestMode) {
          return "Plugin ${module.pluginId} is not unload-safe because of use of ${pluginClassLoader.javaClass.name} as the default class loader. " +
                 "For example, the IDE is started from the sources with the plugin."
        }
      }
    }

    val epNameToExtensions = module.extensions
    if (!epNameToExtensions.isEmpty()) {
      doCheckExtensionsCanUnloadWithoutRestart(
        extensions = epNameToExtensions,
        descriptor = module,
        baseDescriptor = parentModule,
        app = app,
        optionalDependencyPluginId = optionalDependencyPluginId,
        context = context,
        pluginSet = pluginSet,
      )?.let { return it }
    }

    checkNoComponentsOrServiceOverrides(module)?.let { return it }
    checkUnloadActions(module)?.let { return it }

    for (moduleRef in module.contentModules) {
      if (pluginSet.isModuleEnabled(moduleRef.moduleName)) {
        checkCanUnloadWithoutRestart(module = moduleRef,
                                     parentModule = module,
                                     optionalDependencyPluginId = null,
                                     context = context)?.let {
          return "$it in optional dependency on ${moduleRef.pluginId}"
        }
      }
    }

    for (dependency in module.dependencies) {
      if (pluginSet.isPluginEnabled(dependency.pluginId)) {
        checkCanUnloadWithoutRestart(dependency.subDescriptor ?: continue, parentModule ?: module, null, context)?.let {
          return "$it in optional dependency on ${dependency.pluginId}"
        }
      }
    }

    // if not a sub plugin descriptor, then check that any dependent plugin also reloadable
    if (parentModule != null && module !== parentModule) {
      return null
    }

    if (isPluginWhichDependsOnKotlinPluginAndItsIncompatibleWithIt(module)) {
      // force restarting the IDE in the case the dynamic plugin is incompatible with Kotlin Plugin K1/K2 modes KTIJ-24797
      val mode = if (isKotlinPluginK1Mode()) "K1" else "K2"
      return "Plugin ${module.pluginId} depends on the Kotlin plugin in $mode Mode, but the plugin does not support $mode Mode"
    }

    var dependencyMessage: String? = null
    processOptionalDependenciesOnPlugin(module, pluginSet, isLoaded = true) { mainDescriptor, subDescriptor ->
      if ((subDescriptor.pluginClassLoader === module.pluginClassLoader)
          || mainDescriptor.pluginId.idString == "org.jetbrains.kotlin" || mainDescriptor.pluginId == PluginManagerCore.JAVA_PLUGIN_ID) {
        dependencyMessage = "Plugin ${subDescriptor.pluginId} that optionally depends on ${module.pluginId}" +
                            " does not have a separate classloader for the dependency"
        return@processOptionalDependenciesOnPlugin false
      }

      dependencyMessage = checkCanUnloadWithoutRestart(subDescriptor, mainDescriptor, subDescriptor.pluginId, context)
      if (dependencyMessage == null) {
        true
      }
      else {
        dependencyMessage = "Plugin ${subDescriptor.pluginId} that optionally depends on ${module.pluginId} requires restart: $dependencyMessage"
        false
      }
    }

    if (dependencyMessage == null && checkImplementationDetailDependencies) {
      val contextWithImplementationDetails = context.toMutableList()
      contextWithImplementationDetails.add(module)
      processImplementationDetailDependenciesOnPlugin(module, pluginSet, contextWithImplementationDetails::add)

      processImplementationDetailDependenciesOnPlugin(module, pluginSet) { dependentDescriptor ->
        // don't check a plugin that is an implementation-detail dependency on the current plugin if it has other disabled dependencies
        // and won't be loaded anyway
        if (findMissingRequiredDependency(dependentDescriptor, contextWithImplementationDetails, pluginSet) == null) {
          dependencyMessage = checkCanUnloadWithoutRestart(module = dependentDescriptor,
                                                           parentModule = null,
                                                           context = contextWithImplementationDetails,
                                                           checkImplementationDetailDependencies = false)
          if (dependencyMessage != null) {
            dependencyMessage = "implementation-detail plugin ${dependentDescriptor.pluginId} which depends on ${module.pluginId}" +
                                " requires restart: $dependencyMessage"
          }
        }
        dependencyMessage == null
      }
    }
    return dependencyMessage
  }

  private fun findMissingRequiredDependency(descriptor: IdeaPluginDescriptorImpl,
                                            context: List<IdeaPluginDescriptorImpl>,
                                            pluginSet: PluginSet): PluginId? {
    for (dependency in descriptor.dependencies) {
      if (!dependency.isOptional &&
          !PluginManagerCore.looksLikePlatformPluginAlias(dependency.pluginId) &&
          !pluginSet.isPluginEnabled(dependency.pluginId) &&
          context.none { it.pluginId == dependency.pluginId }) {
        return dependency.pluginId
      }
    }
    return null
  }

  /**
   * Checks if a given plugin affects only the UI representation of the IDE.
   *
   * Acts as an allowlist condition to enable some features for "UI-only" plugins.
   */
  // TODO should we demand their "statelessness"?
  fun isUIOnlyDynamicPlugin(plugin: PluginMainDescriptor): Boolean {
    return plugin.contentModules.isEmpty() &&
           !plugin.isRequireRestart &&
           plugin.actions.isEmpty() &&
           checkNoComponentsOrServiceOverrides(plugin) == null &&
           plugin.extensions.all { isUIOnlyExtension(it.key) }
  }

  // TODO imprecise naming
  internal fun isUIOnlyExtension(extensionFqn: String): Boolean {
    return extensionFqn == UIThemeProvider.EP_NAME.name ||
           extensionFqn == BundledKeymapBean.EP_NAME.name ||
           extensionFqn == LanguageBundleEP.EP_NAME.name ||
           extensionFqn == BundledColorSchemeEPName.name
  }

  /**
   * Checks if the plugin can be loaded/unloaded immediately when the corresponding action is invoked in the
   * plugins settings, without pressing the Apply button.
   */
  // TODO migrate to isUIOnlyDynamicPlugin
  @JvmStatic
  fun allowLoadUnloadSynchronously(module: IdeaPluginDescriptorImpl): Boolean {
    val extensions = module.extensions.takeIf { it.isNotEmpty() } ?: emptyMap()
    if (!extensions.all { it.key == UIThemeProvider.EP_NAME.name || it.key == BundledKeymapBean.EP_NAME.name || it.key == LanguageBundleEP.EP_NAME.name}) {
      return false
    }
    return checkNoComponentsOrServiceOverrides(module) == null && module.actions.isEmpty()
  }

  private fun checkNoComponentsOrServiceOverrides(module: IdeaPluginDescriptorImpl): String? {
    val id = module.pluginId
    return checkNoComponentsOrServiceOverrides(id, module.appContainerDescriptor)
           ?: checkNoComponentsOrServiceOverrides(id, module.projectContainerDescriptor)
           ?: checkNoComponentsOrServiceOverrides(id, module.moduleContainerDescriptor)
  }

  private fun checkNoComponentsOrServiceOverrides(pluginId: PluginId?, containerDescriptor: ContainerDescriptor): String? {
    if (!containerDescriptor.components.isEmpty()) {
      return "Plugin $pluginId is not unload-safe because it declares components"
    }
    if (containerDescriptor.services.any { it.overrides }) {
      return "Plugin $pluginId is not unload-safe because it overrides services"
    }
    return null
  }

  fun unloadPluginWithProgress(project: Project? = null,
                               parentComponent: JComponent?,
                               pluginDescriptor: IdeaPluginDescriptorImpl,
                               options: UnloadPluginOptions): Boolean {
    return runProcess {
      doUnloadPluginWithProgress(project, parentComponent, pluginDescriptor, options)
    }
  }

  private fun doUnloadPluginWithProgress(project: Project? = null,
                                         parentComponent: JComponent?,
                                         pluginDescriptor: IdeaPluginDescriptorImpl,
                                         options: UnloadPluginOptions): Boolean {
    var result = false
    val modalOwner = project?.let { ModalTaskOwner.project(it) } ?: ModalTaskOwner.guess()
    if (options.save) {
      runInAutoSaveDisabledMode {
        FileDocumentManager.getInstance().saveAllDocuments()
        runWithModalProgressBlocking(modalOwner, "") {
          saveProjectsAndApp(true)
        }
      }
    }
    // We are doing a very bad thing here: we are using `PotemkinProgress` with a non-modal state.
    // We cannot properly replace it with the classic modal progress, because the function inside frequently uses write actions,
    // and we need to update the progress bar.
    // The problem here is that because of the non-modal state,
    // any dialog can appear on top of PotemkinProgress. But PotemkinProgress blocks input events if they are not related to the appeared dialog,
    // hence the user cannot interact with the dialog.
    // We are using an ad-hoc modality here to block any attempts to initiate a modal dialog stemming from a non-modal state.
    // A proper fix would be to transfer the inner write actions to background, and show normal running modal progress dialog instead of Potemkin; see IJPL-182690
    inModalContext(ObjectUtils.sentinel("Unloading of ${pluginDescriptor.name}")) {
      val indicator = PotemkinProgress(IdeBundle.message("plugins.progress.unloading.plugin.title", pluginDescriptor.name),
                                       project,
                                       parentComponent,
                                       null)
      indicator.runInSwingThread {
        result = unloadPluginWithoutProgress(pluginDescriptor, options.withSave(false))
      }
    }
    return result
  }

  data class UnloadPluginOptions(
    var disable: Boolean = true,
    var isUpdate: Boolean = false,
    var save: Boolean = true,
    var requireMemorySnapshot: Boolean = false,
    var waitForClassloaderUnload: Boolean = false,
    var checkImplementationDetailDependencies: Boolean = true,
    var unloadWaitTimeout: Int? = null,
  ) {
    fun withUpdate(isUpdate: Boolean): UnloadPluginOptions = also {
      this.isUpdate = isUpdate
    }

    fun withWaitForClassloaderUnload(waitForClassloaderUnload: Boolean): UnloadPluginOptions = also {
      this.waitForClassloaderUnload = waitForClassloaderUnload
    }

    fun withDisable(disable: Boolean): UnloadPluginOptions = also {
      this.disable = disable
    }

    fun withRequireMemorySnapshot(requireMemorySnapshot: Boolean): UnloadPluginOptions = also {
      this.requireMemorySnapshot = requireMemorySnapshot
    }

    fun withUnloadWaitTimeout(unloadWaitTimeout: Int): UnloadPluginOptions = also {
      this.unloadWaitTimeout = unloadWaitTimeout
    }

    fun withSave(save: Boolean): UnloadPluginOptions = also {
      this.save = save
    }

    fun withCheckImplementationDetailDependencies(checkImplementationDetailDependencies: Boolean): UnloadPluginOptions = also {
      this.checkImplementationDetailDependencies = checkImplementationDetailDependencies
    }
  }

  @JvmOverloads
  fun unloadPlugin(pluginDescriptor: IdeaPluginDescriptorImpl,
                   options: UnloadPluginOptions = UnloadPluginOptions(disable = true)): Boolean {
    return runProcess {
      doUnloadPluginWithProgress(project = null, parentComponent = null, pluginDescriptor, options)
    }
  }

  private fun unloadPluginWithoutProgress(pluginDescriptor: IdeaPluginDescriptorImpl,
                                          options: UnloadPluginOptions = UnloadPluginOptions(disable = true)): Boolean {
    pluginDescriptor as PluginMainDescriptor
    val app = ApplicationManager.getApplication() as ApplicationImpl
    val pluginId = pluginDescriptor.pluginId
    val pluginSet = PluginManagerCore.getPluginSet()

    if (options.checkImplementationDetailDependencies) {
      processImplementationDetailDependenciesOnPlugin(pluginDescriptor, pluginSet) { dependentDescriptor ->
        if (dependentDescriptor is PluginMainDescriptor) {
          dependentDescriptor.isMarkedForLoading = false
          unloadPluginWithoutProgress(dependentDescriptor, UnloadPluginOptions(waitForClassloaderUnload = false,
                                                                               checkImplementationDetailDependencies = false))
        }
        true
      }
    }

    try {
      app.messageBus.syncPublisher(DynamicPluginListener.TOPIC).beforePluginUnload(pluginDescriptor, options.isUpdate)
      IdeEventQueue.getInstance().flushQueue()
    }
    catch (e: Exception) {
      logger<DynamicPlugins>().error(e)
      DynamicPluginsUsagesCollector.logDescriptorUnload(pluginDescriptor, success = false)
      return false
    }

    var classLoaderUnloaded: Boolean
    val classLoaders = WeakList<PluginClassLoader>()
    try {
      app.runWriteAction {
        // must be after flushQueue (e.g. https://youtrack.jetbrains.com/issue/IDEA-252010)
        val forbidGettingServicesToken = app.forbidGettingServices("Plugin $pluginId being unloaded.")
        try {
          (pluginDescriptor.pluginClassLoader as? PluginClassLoader)?.let {
            classLoaders.add(it)
          }
          // https://youtrack.jetbrains.com/issue/IDEA-245031
          // mark plugin classloaders as being unloaded to ensure that new extension instances will be not created during unload
          setClassLoaderState(pluginDescriptor, PluginAwareClassLoader.UNLOAD_IN_PROGRESS)

          unloadLoadedOptionalDependenciesOnPlugin(pluginDescriptor, pluginSet = pluginSet, classLoaders = classLoaders)

          unloadDependencyDescriptors(pluginDescriptor, pluginSet, classLoaders)
          unloadModuleDescriptorNotRecursively(pluginDescriptor)

          app.extensionArea.clearUserCache()
          for (project in ProjectUtil.getOpenProjects()) {
            (project.extensionArea as ExtensionsAreaImpl).clearUserCache()
          }
          clearCachedValues()

          jdomSerializer.clearSerializationCaches()
          clearPropertyCollectorCache()
          InspectionVisitorOptimizer.clearCache()
          TypeFactory.defaultInstance().clearCache()
          TopHitCache.getInstance().clear()
          ActionToolbarImpl.resetAllToolbars()
          PresentationFactory.clearPresentationCaches()
          TouchbarSupport.reloadAllActions()
          ApplicationNotificationsModel.expireAll()
          MessagePool.getInstance().clearErrors()
          LaterInvocator.purgeExpiredItems()
          FileAttribute.resetRegisteredIds()
          resetFocusCycleRoot()
          clearNewFocusOwner()
          hideTooltip()
          PerformanceWatcher.getInstance().clearFreezeStacktraces()

          for (classLoader in classLoaders) {
            IconLoader.detachClassLoader(classLoader)
            Language.unregisterAllLanguagesIn(classLoader, pluginDescriptor)
          }
          serviceIfCreated<IconDeferrer>()?.clearCache()

          (ApplicationManager.getApplication().messageBus as MessageBusEx).clearPublisherCache()
          @Suppress("TestOnlyProblems")
          (ProjectManager.getInstanceIfCreated() as? ProjectManagerImpl)?.disposeDefaultProjectAndCleanupComponentsForDynamicPluginTests()

          // clear parents as much late as possible because allParents are a lazy list and calculated on demand
          // it may happen that too early invalidation may lead to unloaded loaders appear in allParents again (IJPL-171566)
          clearPluginClassLoaderParentListCache(pluginSet)
          val newPluginSet = pluginSet.withoutPlugin(
            plugin = pluginDescriptor,
            disable = options.disable,
          ).createPluginSetWithEnabledModulesMap()

          PluginManagerCore.setPluginSet(newPluginSet)
          // clear parents cache for newPluginSet just for a case
          clearPluginClassLoaderParentListCache(newPluginSet)
        }
        finally {
          try {
            forbidGettingServicesToken.finish()
          }
          finally {
            app.messageBus.syncPublisher(DynamicPluginListener.TOPIC).pluginUnloaded(pluginDescriptor, options.isUpdate)
          }
        }
      }
    }
    catch (e: Exception) {
      logger<DynamicPlugins>().error(e)
    }
    finally {
      IdeEventQueue.getInstance().flushQueue()
      cancelAndJoinPluginScopes(classLoaders)

      // do it after IdeEventQueue.flushQueue() to ensure that Disposer.isDisposed(...) works as expected in flushed tasks.
      Disposer.clearDisposalTraces()   // ensure we don't have references to plugin classes in disposal backtraces
      ThrowableInterner.clearInternedBacktraces()
      IdeaLogger.ourErrorsOccurred = null   // ensure we don't have references to plugin classes in exception stacktraces
      clearTemporaryLostComponent()

      if (app.isUnitTestMode && pluginDescriptor.pluginClassLoader !is PluginClassLoader) {
        classLoaderUnloaded = true
      }
      else {
        classloadersFromUnloadedPlugins[pluginId] = classLoaders
        ClassLoaderTreeChecker(pluginDescriptor, classLoaders).checkThatClassLoaderNotReferencedByPluginClassLoader()

        val checkClassLoaderUnload = options.waitForClassloaderUnload ||
                                     options.requireMemorySnapshot ||
                                     Registry.`is`("ide.plugins.snapshot.on.unload.fail")
        val timeout = if (checkClassLoaderUnload) {
          options.unloadWaitTimeout ?: Registry.intValue("ide.plugins.unload.timeout", 5000)
        }
        else {
          0
        }

        classLoaderUnloaded = unloadClassLoader(pluginDescriptor, timeout)
        if (classLoaderUnloaded) {
          LOG.info("Successfully unloaded plugin $pluginId (classloader unload checked=$checkClassLoaderUnload)")
          classloadersFromUnloadedPlugins.remove(pluginId)
        }
        else {
          if ((options.requireMemorySnapshot || (Registry.`is`("ide.plugins.snapshot.on.unload.fail") && !app.isUnitTestMode)) &&
              MemoryDumpHelper.memoryDumpAvailable()) {
            classLoaderUnloaded = saveMemorySnapshot(pluginId)
          }
          else {
            LOG.info("Plugin $pluginId is not unload-safe because class loader cannot be unloaded")
          }
        }
        if (!classLoaderUnloaded) {
          InstalledPluginsState.getInstance().isRestartRequired = true
        }

        DynamicPluginsUsagesCollector.logDescriptorUnload(pluginDescriptor, success = classLoaderUnloaded)
      }
    }

    if (!classLoaderUnloaded) {
      setClassLoaderState(pluginDescriptor, PluginAwareClassLoader.ACTIVE)
    }

    ActionToolbarImpl.updateAllToolbarsImmediately(true)

    return classLoaderUnloaded
  }

  private fun resetFocusCycleRoot() {
    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    var focusCycleRoot = focusManager.currentFocusCycleRoot
    if (focusCycleRoot != null) {
      while (focusCycleRoot != null && focusCycleRoot !is IdeFrameImpl) {
        focusCycleRoot = focusCycleRoot.parent
      }
      if (focusCycleRoot is IdeFrameImpl) {
        focusManager.setGlobalCurrentFocusCycleRoot(focusCycleRoot)
      }
      else {
        focusCycleRoot = focusManager.currentFocusCycleRoot
        val dataContext = DataManager.getInstance().getDataContext(focusCycleRoot)
        val project = CommonDataKeys.PROJECT.getData(dataContext)
        if (project != null) {
          val projectFrame = WindowManager.getInstance().getFrame(project)
          if (projectFrame != null) {
            focusManager.setGlobalCurrentFocusCycleRoot(projectFrame)
          }
        }
      }
    }
  }

  private fun unloadLoadedOptionalDependenciesOnPlugin(dependencyPlugin: IdeaPluginDescriptorImpl,
                                                       pluginSet: PluginSet,
                                                       classLoaders: WeakList<PluginClassLoader>) {
    val dependencyClassloader = dependencyPlugin.classLoader
    processOptionalDependenciesOnPlugin(dependencyPlugin, pluginSet, isLoaded = true) { mainDescriptor, subDescriptor ->
      val classLoader = subDescriptor.classLoader
      unloadModuleDescriptorNotRecursively(subDescriptor)

      // this additional code is required because in unit tests PluginClassLoader is not used
      if (mainDescriptor !== subDescriptor) {
        subDescriptor.pluginClassLoader = null
      }

      if (dependencyClassloader is PluginClassLoader && classLoader is PluginClassLoader) {
        LOG.info("Detach classloader $dependencyClassloader from $classLoader")
        if (mainDescriptor !== subDescriptor && classLoader.pluginDescriptor === subDescriptor) {
          classLoaders.add(classLoader)
          classLoader.state = PluginAwareClassLoader.UNLOAD_IN_PROGRESS
        }
      }
      true
    }
  }

  private fun unloadDependencyDescriptors(plugin: IdeaPluginDescriptorImpl,
                                          pluginSet: PluginSet,
                                          classLoaders: WeakList<PluginClassLoader>) {
    for (dependency in plugin.dependencies) {
      val subDescriptor = dependency.subDescriptor ?: continue
      val classLoader = subDescriptor.pluginClassLoader
      if (!pluginSet.isPluginEnabled(dependency.pluginId)) {
        LOG.assertTrue(classLoader == null,
                       "Expected not to have any sub descriptor classloader when dependency ${dependency.pluginId} is not loaded")
        continue
      }

      if (classLoader is PluginClassLoader && classLoader.pluginDescriptor === subDescriptor) {
        classLoaders.add(classLoader)
        classLoader.state = PluginAwareClassLoader.UNLOAD_IN_PROGRESS
      }

      unloadDependencyDescriptors(subDescriptor, pluginSet, classLoaders)
      unloadModuleDescriptorNotRecursively(subDescriptor)
      subDescriptor.pluginClassLoader = null
    }

    for (module in plugin.contentModules) {
      val classLoader = module.pluginClassLoader ?: continue
      if (classLoader is PluginClassLoader && classLoader.pluginDescriptor === module) {
        classLoaders.add(classLoader)
        classLoader.state = PluginAwareClassLoader.UNLOAD_IN_PROGRESS
      }
      unloadModuleDescriptorNotRecursively(module)
      module.pluginClassLoader = null
    }
  }

  internal fun notify(@NlsContexts.NotificationContent text: String, notificationType: NotificationType, vararg actions: AnAction) {
    val notification = UpdateChecker.getNotificationGroupForPluginUpdateResults().createNotification(text, notificationType)
    for (action in actions) {
      notification.addAction(action)
    }
    notification.notify(null)
  }

  // PluginId cannot be used to unload related resources because one plugin descriptor may consist of several sub descriptors,
  // each of them depends on presense of another plugin, here not the whole plugin is unloaded, but only one part.
  private fun unloadModuleDescriptorNotRecursively(module: IdeaPluginDescriptorImpl) {
    val app = ApplicationManager.getApplication() as ApplicationImpl
    (ActionManager.getInstance() as ActionManagerImpl).unloadActions(module)

    val openedProjects = ProjectUtil.getOpenProjects().toMutableList()
    @Suppress("TestOnlyProblems")
    if (ProjectManagerEx.getInstanceEx().isDefaultProjectInitialized) {
      openedProjects.add(ProjectManagerEx.getInstanceEx().defaultProject)
    }

    val appExtensionArea = app.extensionArea
    val priorityUnloadListeners = mutableListOf<Runnable>()
    val unloadListeners = mutableListOf<Runnable>()
    unregisterUnknownLevelExtensions(module.extensions, module, appExtensionArea, openedProjects,
                                     priorityUnloadListeners, unloadListeners)
    // note: here was a dead code for unregistering appContainer.extensions, but the map was always empty
    // note: here was a dead code for unregistering project level extensions, but it is already handled by a call above
    // note: here was a dead code for unregistering unknown level extensions with `moduleContainer.extensions` but the latter was always empty

    for (priorityUnloadListener in priorityUnloadListeners) {
      priorityUnloadListener.run()
    }
    for (unloadListener in unloadListeners) {
      unloadListener.run()
    }

    // first, reset all plugin extension points before unregistering, so that listeners don't see plugin in semi-torn-down state
    processExtensionPoints(module, openedProjects) { points, area ->
      area.resetExtensionPoints(points, module)
    }
    // unregister plugin extension points
    processExtensionPoints(module, openedProjects) { points, area ->
      area.unregisterExtensionPoints(points, module)
    }

    val appMessageBus = app.messageBus as MessageBusEx
    app.unloadServices(module, module.appContainerDescriptor.services)
    appMessageBus.unsubscribeLazyListeners(module, module.appContainerDescriptor.listeners)

    for (project in openedProjects) {
      (project as ComponentManagerEx).unloadServices(module, module.projectContainerDescriptor.services)
      (project.messageBus as MessageBusEx).unsubscribeLazyListeners(module, module.projectContainerDescriptor.listeners)

      val moduleServices = module.moduleContainerDescriptor.services
      for (ideaModule in ModuleManager.getInstance(project).modules) {
        (ideaModule as ComponentManagerEx).unloadServices(module, moduleServices)
        createDisposeTreePredicate(module)?.let { Disposer.disposeChildren(ideaModule, it) }
      }

      createDisposeTreePredicate(module)?.let { Disposer.disposeChildren(project, it) }
    }

    appMessageBus.disconnectPluginConnections(Predicate { aClass ->
      (aClass.classLoader as? PluginClassLoader)?.pluginDescriptor === module
    })

    createDisposeTreePredicate(module)?.let { Disposer.disposeChildren(ApplicationManager.getApplication(), it) }
  }

  private fun unregisterUnknownLevelExtensions(extensionMap: Map<String, List<ExtensionDescriptor>>,
                                               pluginDescriptor: IdeaPluginDescriptorImpl,
                                               appExtensionArea: ExtensionsAreaImpl,
                                               openedProjects: List<Project>,
                                               priorityUnloadListeners: MutableList<Runnable>,
                                               unloadListeners: MutableList<Runnable>) {
    for (epName in extensionMap.keys) {
      val isAppLevelEp = appExtensionArea.unregisterExtensions(epName, pluginDescriptor, priorityUnloadListeners,
                                                               unloadListeners)
      if (isAppLevelEp) {
        continue
      }

      for (project in openedProjects) {
        val isProjectLevelEp = (project.extensionArea as ExtensionsAreaImpl)
          .unregisterExtensions(epName, pluginDescriptor, priorityUnloadListeners, unloadListeners)
        if (!isProjectLevelEp) {
          for (module in ModuleManager.getInstance(project).modules) {
            (module.extensionArea as ExtensionsAreaImpl)
              .unregisterExtensions(epName, pluginDescriptor, priorityUnloadListeners, unloadListeners)
          }
        }
      }
    }
  }

  private inline fun processExtensionPoints(pluginDescriptor: IdeaPluginDescriptorImpl,
                                            projects: List<Project>,
                                            processor: (points: List<ExtensionPointDescriptor>, area: ExtensionsAreaImpl) -> Unit) {
    pluginDescriptor.appContainerDescriptor.extensionPoints.takeIf { it.isNotEmpty() }?.let {
      processor(it, ApplicationManager.getApplication().extensionArea as ExtensionsAreaImpl)
    }
    pluginDescriptor.projectContainerDescriptor.extensionPoints.takeIf { it.isNotEmpty() }?.let { extensionPoints ->
      for (project in projects) {
        processor(extensionPoints, project.extensionArea as ExtensionsAreaImpl)
      }
    }
    pluginDescriptor.moduleContainerDescriptor.extensionPoints.takeIf { it.isNotEmpty() }?.let { extensionPoints ->
      for (project in projects) {
        for (module in ModuleManager.getInstance(project).modules) {
          processor(extensionPoints, module.extensionArea as ExtensionsAreaImpl)
        }
      }
    }
  }

  @JvmOverloads
  fun loadPlugin(pluginDescriptor: IdeaPluginDescriptorImpl, project: Project? = null): Boolean {
    return runProcess {
      withPotemkinProgress(project, IdeBundle.message("plugins.progress.loading.plugin.title", pluginDescriptor.name)) {
        publishingPluginsLoadedEvent {
          doLoadPlugin(pluginDescriptor)
        }
      }
    }
  }

  @RequiresEdt
  private fun <T> publishingPluginsLoadedEvent(action: () -> T): T {
    val app = ApplicationManager.getApplication() as ApplicationImpl
    app.messageBus.syncPublisher(DynamicPluginListener.TOPIC).beforePluginsLoaded()
    try {
      return action()
    }
    finally {
      app.runWriteAction {
        app.messageBus.syncPublisher(DynamicPluginListener.TOPIC).pluginsLoaded()
      }
    }
  }

  private fun withPotemkinProgress(project: Project?, title: @NlsContexts.ModalProgressTitle String, action: (TitledIndicator) -> Boolean): Boolean {
    var result = false
    val indicator = PotemkinProgress(title, project, null, null)

    indicator.runInSwingThread {
      result = action(indicator)
      indicator.title = title
    }
    return result
  }

  @RequiresEdt
  private fun doLoadPlugin(pluginDescriptor: IdeaPluginDescriptorImpl): Boolean {
    val isVetoed = VETOER_EP_NAME.findFirstSafe {
      it.vetoPluginLoad(pluginDescriptor)
    } != null

    if (isVetoed) {
      return false
    }

    return loadPluginWithoutProgress(pluginDescriptor, checkImplementationDetailDependencies = true)
  }

  private fun loadPluginWithoutProgress(pluginDescriptor: IdeaPluginDescriptorImpl, checkImplementationDetailDependencies: Boolean = true): Boolean {
    pluginDescriptor as PluginMainDescriptor
    if (classloadersFromUnloadedPlugins[pluginDescriptor.pluginId]?.isEmpty() == false) {
      LOG.info("Requiring restart for loading plugin ${pluginDescriptor.pluginId}" +
               " because previous version of the plugin wasn't fully unloaded")
      return false
    }

    val loadStartTime = System.currentTimeMillis()

    val pluginSet = PluginManagerCore.getPluginSet()
      .withPlugin(pluginDescriptor)
      .createPluginSetWithEnabledModulesMap()

    val classLoaderConfigurator = ClassLoaderConfigurator(pluginSet)

    // todo loadPluginWithoutProgress should be called per each module, temporary solution
    val pluginWithContentModules = pluginSet.getEnabledModules()
      .filter { it.pluginId == pluginDescriptor.pluginId }
      .filter(classLoaderConfigurator::configureModule)
      .toList()

    val app = ApplicationManager.getApplication() as ApplicationImpl
    app.messageBus.syncPublisher(DynamicPluginListener.TOPIC).beforePluginLoaded(pluginDescriptor)
    app.runWriteAction {
      try {
        val listenerCallbacks = mutableListOf<Runnable>()

        // 4. load into service container
        loadModules(modules = pluginWithContentModules, app = app, listenerCallbacks = listenerCallbacks)
        loadModules(
          modules = optionalDependenciesOnPlugin(dependencyPlugin = pluginDescriptor,
                                                 classLoaderConfigurator = classLoaderConfigurator,
                                                 pluginSet = pluginSet).filter { descriptorImpl ->
            when (descriptorImpl) {
              is ContentModuleDescriptor if !pluginSet.isModuleEnabled(descriptorImpl.moduleName) -> false
              is PluginMainDescriptor if !pluginSet.isPluginEnabled(descriptorImpl.pluginId) -> false
              else -> true
            }
          }.toList(),
          app = app,
          listenerCallbacks = listenerCallbacks,
        )

        clearPluginClassLoaderParentListCache(pluginSet)
        clearCachedValues()

        PluginManagerCore.setPluginSet(pluginSet)

        listenerCallbacks.forEach(Runnable::run)

        DynamicPluginsUsagesCollector.logDescriptorLoad(pluginDescriptor)
        PluginManagerCore.clearLoadingErrorsFor(pluginDescriptor.pluginId)
        LOG.info("Plugin ${pluginDescriptor.pluginId} loaded without restart in ${System.currentTimeMillis() - loadStartTime} ms")
      }
      finally {
        app.messageBus.syncPublisher(DynamicPluginListener.TOPIC).pluginLoaded(pluginDescriptor)
      }
    }

    if (checkImplementationDetailDependencies) {
      var implementationDetailsLoadedWithoutRestart = true
      processImplementationDetailDependenciesOnPlugin(pluginDescriptor, pluginSet) { dependentDescriptor ->
        val dependencies = dependentDescriptor.dependencies
        if (dependencies.all { it.isOptional || PluginManagerCore.getPlugin(it.pluginId) != null }) {
          if (dependentDescriptor is PluginMainDescriptor &&
              !loadPluginWithoutProgress(dependentDescriptor, checkImplementationDetailDependencies = false)) {
            implementationDetailsLoadedWithoutRestart = false
          }
        }
        implementationDetailsLoadedWithoutRestart
      }
      return implementationDetailsLoadedWithoutRestart
    }
    return true
  }

  fun onPluginUnload(parentDisposable: Disposable, callback: Runnable) {
    ApplicationManager.getApplication().messageBus.connect(parentDisposable)
      .subscribe(DynamicPluginListener.TOPIC, object : DynamicPluginListener {
        override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
          callback.run()
        }
      })
  }
}

private fun clearTemporaryLostComponent() {
  try {
    val clearMethod = Window::class.java.declaredMethods.find { it.name == "setTemporaryLostComponent" }
    if (clearMethod == null) {
      LOG.info("setTemporaryLostComponent method not found")
      return
    }
    clearMethod.isAccessible = true
    loop@ for (frame in WindowManager.getInstance().allProjectFrames) {
      val window = when (frame) {
        is ProjectFrameHelper -> frame.frame
        is Window -> frame
        else -> continue@loop
      }
      clearMethod.invoke(window, null)
    }
  }
  catch (e: Throwable) {
    LOG.info("Failed to clear Window.temporaryLostComponent", e)
  }
}

private fun hideTooltip() {
  try {
    val showMethod = ToolTipManager::class.java.declaredMethods.find { it.name == "show" }
    if (showMethod == null) {
      LOG.info("ToolTipManager.show method not found")
      return
    }
    showMethod.isAccessible = true
    showMethod.invoke(ToolTipManager.sharedInstance(), null)
  }
  catch (e: Throwable) {
    LOG.info("Failed to hide tooltip", e)
  }
}

private fun clearNewFocusOwner() {
  val field = ReflectionUtil.getDeclaredField(KeyboardFocusManager::class.java, "newFocusOwner")
  if (field != null) {
    try {
      field.set(null, null)
    }
    catch (e: Throwable) {
      LOG.info(e)
    }
  }
}

private fun cancelAndJoinPluginScopes(classLoaders: WeakList<PluginClassLoader>) {
  for (classLoader in classLoaders) {
    cancelAndJoinBlocking(classLoader.pluginCoroutineScope, "Plugin ${classLoader.pluginId}") { job, _ ->
      while (job.isActive) {
        ProgressManager.checkCanceled()
        IdeEventQueue.getInstance().flushQueue()
      }
    }
  }
}

private fun saveMemorySnapshot(pluginId: PluginId): Boolean {
  val snapshotDate = SimpleDateFormat("dd.MM.yyyy_HH.mm.ss").format(Date())
  val snapshotFileName = "unload-$pluginId-$snapshotDate.hprof"
  val snapshotPath = System.getProperty("memory.snapshots.path", SystemProperties.getUserHome()) + "/" + snapshotFileName

  MemoryDumpHelper.captureMemoryDump(snapshotPath)

  if (classloadersFromUnloadedPlugins[pluginId]?.isEmpty() != false) {
    LOG.info("Successfully unloaded plugin $pluginId (classloader collected during memory snapshot generation)")
    return true
  }

  if (Registry.`is`("ide.plugins.analyze.snapshot")) {
    val analysisResult = analyzeSnapshot(snapshotPath, pluginId)
    @Suppress("ReplaceSizeZeroCheckWithIsEmpty")
    if (analysisResult.length == 0) {
      LOG.info("Successfully unloaded plugin $pluginId (no strong references to classloader in .hprof file)")
      classloadersFromUnloadedPlugins.remove(pluginId)
      return true
    }
    else {
      LOG.error("Snapshot analysis result: $analysisResult")
    }
  }

  DynamicPlugins.notify(
    IdeBundle.message("memory.snapshot.captured.text", snapshotPath),
    NotificationType.WARNING,
    object : AnAction(IdeBundle.message("ide.restart.action")), DumbAware {
      override fun actionPerformed(e: AnActionEvent) = ApplicationManager.getApplication().restart()
    },
    object : AnAction(
      IdeBundle.message("memory.snapshot.captured.action.text", snapshotFileName, RevealFileAction.getFileManagerName())), DumbAware {
      override fun actionPerformed(e: AnActionEvent) = RevealFileAction.openFile(Paths.get(snapshotPath))
    }
  )

  LOG.info("Plugin $pluginId is not unload-safe because class loader cannot be unloaded. Memory snapshot created at $snapshotPath")
  return false
}

private fun processImplementationDetailDependenciesOnPlugin(pluginDescriptor: IdeaPluginDescriptorImpl,
                                                            pluginSet: PluginSet,
                                                            processor: (descriptor: IdeaPluginDescriptorImpl) -> Boolean) {
  processDependenciesOnPlugin(dependencyTarget = pluginDescriptor,
                              pluginSet = pluginSet,
                              loadStateFilter = LoadStateFilter.ANY,
                              onlyOptional = false) { _, module ->
    if (module.isImplementationDetail) {
      processor(module)
    }
    else {
      true
    }
  }
}

/**
 * @return a Set of modules that depend on [dependencyPlugin]
 */
private fun optionalDependenciesOnPlugin(
  dependencyPlugin: IdeaPluginDescriptorImpl,
  classLoaderConfigurator: ClassLoaderConfigurator,
  pluginSet: PluginSet,
): Set<IdeaPluginDescriptorImpl> {
  val dependentDescriptors = ArrayList<IdeaPluginDescriptorImpl>()
  processOptionalDependenciesOnPlugin(dependencyPlugin, pluginSet, isLoaded = false) { _, module ->
    dependentDescriptors.add(module)
    true
  }
  if (dependentDescriptors.isEmpty()) {
    return emptySet()
  }
  val topologicalComparator = PluginSetBuilder(dependentDescriptors.map { it.getMainDescriptor() }.toSet()).topologicalComparator
  dependentDescriptors.sortWith(Comparator { o1, o2 -> topologicalComparator.compare(o1.getMainDescriptor(), o2.getMainDescriptor()) })
  return dependentDescriptors
    .distinct()
    .filter {
      classLoaderConfigurator.configureDescriptorDynamic(it)
    }
    .toSet()
}

private fun loadModules(modules: List<IdeaPluginDescriptorImpl>, app: ApplicationImpl, listenerCallbacks: MutableList<in Runnable>) {
  app.registerComponents(modules = modules, app = app, listenerCallbacks = listenerCallbacks)
  for (openProject in getOpenedProjects()) {
    openProject.getComponentManagerImpl().registerComponents(modules = modules, app = app, listenerCallbacks = listenerCallbacks)

    for (module in ModuleManager.getInstance(openProject).modules) {
      module.getComponentManagerImpl().registerComponents(modules = modules, app = app, listenerCallbacks = listenerCallbacks)
    }
  }

  (ActionManager.getInstance() as ActionManagerImpl).registerActions(modules)
}

private fun analyzeSnapshot(hprofPath: String, pluginId: PluginId): String {
  FileChannel.open(Paths.get(hprofPath), StandardOpenOption.READ).use { channel ->
    val analysis = HProfAnalysis(channel, SystemTempFilenameSupplier()) { analysisContext, listProvider, progressIndicator ->
      AnalyzeClassloaderReferencesGraph(analysisContext, listProvider, pluginId.idString).analyze(progressIndicator).mainReport.toString()
    }
    analysis.onlyStrongReferences = true
    analysis.includeClassesAsRoots = false
    analysis.setIncludeMetaInfo(false)
    return analysis.analyze(ProgressManager.getGlobalProgressIndicator() ?: EmptyProgressIndicator())
  }
}

private fun createDisposeTreePredicate(pluginDescriptor: IdeaPluginDescriptorImpl): Predicate<Disposable>? {
  val classLoader = pluginDescriptor.pluginClassLoader as? PluginClassLoader ?: return null
  return Predicate {
    it::class.java.classLoader === classLoader
  }
}

private fun processOptionalDependenciesOnPlugin(
  dependencyPlugin: IdeaPluginDescriptorImpl,
  pluginSet: PluginSet,
  isLoaded: Boolean,
  processor: (pluginDescriptor: IdeaPluginDescriptorImpl, moduleDescriptor: IdeaPluginDescriptorImpl) -> Boolean,
) {
  processDependenciesOnPlugin(
    dependencyTarget = dependencyPlugin,
    pluginSet = pluginSet,
    onlyOptional = true,
    loadStateFilter = if (isLoaded) LoadStateFilter.LOADED else LoadStateFilter.NOT_LOADED,
    processor = processor,
  )
}

private fun processDependenciesOnPlugin(
  dependencyTarget: IdeaPluginDescriptorImpl,
  pluginSet: PluginSet,
  loadStateFilter: LoadStateFilter,
  onlyOptional: Boolean,
  processor: (pluginDescriptor: IdeaPluginDescriptorImpl, moduleDescriptor: IdeaPluginDescriptorImpl) -> Boolean,
) {
  val wantedIds = HashSet<String>(1 + dependencyTarget.contentModules.size)
  wantedIds.add(dependencyTarget.pluginId.idString)
  for (module in dependencyTarget.contentModules) {
    wantedIds.add(module.moduleName)
  }
  // FIXME plugin aliases probably missing?

  for (plugin in pluginSet.enabledPlugins) {
    if (plugin === dependencyTarget) {
      continue
    }

    if (!processPluginDependenciesOnPlugin(dependencyTargetId = dependencyTarget.pluginId,
                                           mainDescriptor = plugin,
                                           loadStateFilter = loadStateFilter,
                                           onlyOptional = onlyOptional,
                                           processor = processor)) {
      return
    }

    for (module in plugin.contentModules) {
      if (loadStateFilter != LoadStateFilter.ANY) {
        val isModuleLoaded = module.pluginClassLoader != null
        if (isModuleLoaded != (loadStateFilter == LoadStateFilter.LOADED)) {
          continue
        }
      }
      for (item in module.moduleDependencies.modules) {
        if (wantedIds.contains(item.name) && !processor(plugin, module)) {
          return
        }
      }
      for (item in module.moduleDependencies.plugins) {
        if (dependencyTarget.pluginId == item.id && !processor(plugin, module)) {
          return
        }
      }
    }
  }
}

private enum class LoadStateFilter {
  LOADED, NOT_LOADED, ANY
}

private fun processPluginDependenciesOnPlugin(
  dependencyTargetId: PluginId,
  mainDescriptor: IdeaPluginDescriptorImpl,
  loadStateFilter: LoadStateFilter,
  onlyOptional: Boolean,
  processor: (main: IdeaPluginDescriptorImpl, sub: IdeaPluginDescriptorImpl) -> Boolean
): Boolean {
  for (dependency in mainDescriptor.dependencies) {
    if (dependency.isOptional) {
      val subDescriptor = dependency.subDescriptor ?: continue
      if (loadStateFilter != LoadStateFilter.ANY) {
        val isModuleLoaded = subDescriptor.pluginClassLoader != null
        if (isModuleLoaded != (loadStateFilter == LoadStateFilter.LOADED)) {
          continue
        }
      }
      if (dependency.pluginId == dependencyTargetId && !processor(mainDescriptor, subDescriptor)) {
        return false
      }
      if (!processPluginDependenciesOnPlugin(
          dependencyTargetId = dependencyTargetId,
          mainDescriptor = subDescriptor,
          loadStateFilter = loadStateFilter,
          onlyOptional = onlyOptional,
          processor = processor)) {
        return false
      }
    }
    else {
      if (!onlyOptional && dependency.pluginId == dependencyTargetId && !processor(mainDescriptor, mainDescriptor)) {
        return false
      }
    }
  }
  return true
}

private fun doCheckExtensionsCanUnloadWithoutRestart(
  extensions: Map<String, List<ExtensionDescriptor>>,
  descriptor: IdeaPluginDescriptorImpl,
  baseDescriptor: IdeaPluginDescriptorImpl?,
  app: Application,
  optionalDependencyPluginId: PluginId?,
  context: List<IdeaPluginDescriptorImpl>,
  pluginSet: PluginSet,
): String? {
  val firstProject = ProjectUtil.getOpenProjects().firstOrNull()
  val anyProject = firstProject ?: ProjectManager.getInstance().defaultProject
  val anyModule = firstProject?.let { ModuleManager.getInstance(it).modules.firstOrNull() }

  val seenPlugins: MutableSet<IdeaPluginDescriptorImpl> = Collections.newSetFromMap(IdentityHashMap())
  epLoop@ for (epName in extensions.keys) {
    seenPlugins.clear()

    fun getNonDynamicUnloadError(optionalDependencyPluginId: PluginId?): String = optionalDependencyPluginId?.let {
      "Plugin ${baseDescriptor?.pluginId} is not unload-safe because of use of non-dynamic EP $epName in plugin $it that optionally depends on it"
    } ?: "Plugin ${descriptor.pluginId} is not unload-safe because of extension to non-dynamic EP $epName"

    val result = findLoadedPluginExtensionPointRecursive(
      pluginDescriptor = baseDescriptor ?: descriptor,
      epName = epName,
      pluginSet = pluginSet,
      context = context,
      seenPlugins = seenPlugins,
    )
    if (result != null) {
      val (pluginExtensionPoint, foundInDependencies) = result // descriptor.pluginId is null when we check the optional dependencies of the plugin which is being loaded
      // if an optional dependency of a plugin extends a non-dynamic EP of that plugin, it shouldn't prevent plugin loading
      if (!pluginExtensionPoint.isDynamic) {
        if (baseDescriptor == null || foundInDependencies) {
          return getNonDynamicUnloadError(null)
        }
        else if (descriptor === baseDescriptor) {
          return getNonDynamicUnloadError(descriptor.pluginId)
        }
      }
      continue
    }

    val ep = app.extensionArea.getExtensionPointIfRegistered<Any>(epName)
             ?: anyProject.extensionArea.getExtensionPointIfRegistered<Any>(epName)
             ?: anyModule?.extensionArea?.getExtensionPointIfRegistered<Any>(epName)
    if (ep != null) {
      if (!ep.isDynamic) {
        return getNonDynamicUnloadError(optionalDependencyPluginId)
      }
      continue
    }

    if (anyModule == null) {
      val corePlugin = pluginSet.findEnabledPlugin(PluginManagerCore.CORE_ID)
      if (corePlugin != null) {
        val coreEP = findPluginExtensionPoint(corePlugin, epName)
        if (coreEP != null) {
          if (!coreEP.isDynamic) {
            return getNonDynamicUnloadError(optionalDependencyPluginId)
          }
          continue
        }
      }
    }

    for (contextPlugin in context) {
      val contextEp = findPluginExtensionPoint(contextPlugin, epName) ?: continue
      if (!contextEp.isDynamic) {
        return getNonDynamicUnloadError(null)
      }
      continue@epLoop
    }

    // special case Kotlin EPs registered via code in Kotlin compiler
    if (epName.startsWith("org.jetbrains.kotlin") && descriptor.pluginId.idString == "org.jetbrains.kotlin") {
      continue
    }
    // Workaround until SID-207 fixed
    if (epName.startsWith("Pythonid.template") && descriptor.pluginId.idString in listOf("com.intellij.python.django", "org.jetbrains.dbt")) {
      continue
    }

    return "Plugin ${descriptor.pluginId} is not unload-safe because of unresolved extension $epName"
  }
  return null
}

private fun findPluginExtensionPoint(pluginDescriptor: IdeaPluginDescriptorImpl, epName: String): ExtensionPointDescriptor? {
  fun findInContainer(containerDescriptor: ContainerDescriptor): ExtensionPointDescriptor? {
    return containerDescriptor.extensionPoints.find { it.nameEquals(epName, pluginDescriptor) }
  }
  fun IdeaPluginDescriptorImpl.findInAnyScope() = findInContainer(appContainerDescriptor)
                                                  ?: findInContainer(projectContainerDescriptor)
                                                  ?: findInContainer(moduleContainerDescriptor)
  pluginDescriptor.findInAnyScope()?.let { return it }
  pluginDescriptor.contentModules.forEach { contentModule ->
    // FIXME incomplete fix for IJPL-190703
    if (contentModule.moduleLoadingRule.required) {
      contentModule.findInAnyScope()?.let { return it }
    }
  }
  return null
}

private fun findLoadedPluginExtensionPointRecursive(pluginDescriptor: IdeaPluginDescriptorImpl,
                                                    epName: String,
                                                    pluginSet: PluginSet,
                                                    context: List<IdeaPluginDescriptorImpl>,
                                                    seenPlugins: MutableSet<IdeaPluginDescriptorImpl>): Pair<ExtensionPointDescriptor, Boolean>? {
  if (!seenPlugins.add(pluginDescriptor)) {
    return null
  }

  findPluginExtensionPoint(pluginDescriptor, epName)?.let { return it to false }
  for (dependency in pluginDescriptor.dependencies) {
    if (pluginSet.isPluginEnabled(dependency.pluginId) || context.any { it.pluginId == dependency.pluginId }) {
      dependency.subDescriptor?.let { subDescriptor ->
        findLoadedPluginExtensionPointRecursive(subDescriptor, epName, pluginSet, context, seenPlugins)?.let { return it }
      }
      pluginSet.findEnabledPlugin(dependency.pluginId)?.let { dependencyDescriptor ->
        findLoadedPluginExtensionPointRecursive(dependencyDescriptor, epName, pluginSet, context, seenPlugins)?.let { return it.first to true }
      }
    }
  }

  processDirectDependencies(pluginDescriptor, pluginSet) { dependency ->
    findLoadedPluginExtensionPointRecursive(dependency, epName, pluginSet, context, seenPlugins)?.let { return it.first to true }
  }
  return null
}

private inline fun processDirectDependencies(module: IdeaPluginDescriptorImpl,
                                             pluginSet: PluginSet,
                                             processor: (IdeaPluginDescriptorImpl) -> Unit) {
   for (item in module.moduleDependencies.modules) {
     val descriptor = pluginSet.findEnabledModule(item.name)
     if (descriptor != null) {
       processor(descriptor)
    }
  }
  for (item in module.moduleDependencies.plugins) {
    val descriptor = pluginSet.findEnabledPlugin(item.id)
    if (descriptor != null) {
      processor(descriptor)
    }
  }
}

private fun unloadClassLoader(pluginDescriptor: IdeaPluginDescriptorImpl, timeoutMs: Int): Boolean {
  if (timeoutMs == 0) {
    pluginDescriptor.pluginClassLoader = null
    return true
  }

  val watcher = GCWatcher.tracking(pluginDescriptor.pluginClassLoader)
  pluginDescriptor.pluginClassLoader = null
  return watcher.tryCollect(timeoutMs)
}

private fun setClassLoaderState(pluginDescriptor: IdeaPluginDescriptorImpl, state: Int) {
  (pluginDescriptor.pluginClassLoader as? PluginClassLoader)?.state = state
  for (dependency in pluginDescriptor.dependencies) {
    dependency.subDescriptor?.let { setClassLoaderState(it, state) }
  }
}

private fun clearPluginClassLoaderParentListCache(pluginSet: PluginSet) {
  // yes, clear not only enabled plugins, but (all + enabledModules) because enabledModules is a superset of enabledPlugins
  // it's a cheap operation and even if some modules may repeat due to concatenation, it should be not a problem (making a set is probably even slower)
  for (descriptor in pluginSet.allPlugins + pluginSet.getEnabledModules()) {
    (descriptor.pluginClassLoader as? PluginClassLoader)?.clearParentListCache()
  }
}

private fun clearCachedValues() {
  for (project in ProjectUtil.getOpenProjects()) {
    (CachedValuesManager.getManager(project) as? CachedValuesManagerImpl)?.clearCachedValues()
  }
}

private fun checkUnloadActions(module: IdeaPluginDescriptorImpl): String? {
  for (descriptor in module.actions) {
    val element = descriptor.element
    val elementName = descriptor.name
    if (elementName != ActionElementName.action &&
        !(elementName == ActionElementName.group && canUnloadActionGroup(element)) && elementName != ActionElementName.reference) {
      return "Plugin $module is not unload-safe because of action element $elementName"
    }
  }
  return null
}

internal class FallbackPluginVetoer : DynamicPluginVetoer {
  override fun vetoPluginUnload(pluginDescriptor: IdeaPluginDescriptor): @Nls String? {
    val vetoMessage = DynamicPluginUnloaderCompatibilityLayer.queryPluginUnloadVetoers(pluginDescriptor, ApplicationManager.getApplication().messageBus)
    if (vetoMessage != null) return vetoMessage

    for (project in ProjectManager.getInstance().openProjects) {
      val vetoMessage = DynamicPluginUnloaderCompatibilityLayer.queryPluginUnloadVetoers(pluginDescriptor, project.messageBus)
      if (vetoMessage != null) return vetoMessage
    }
    return null
  }
}
