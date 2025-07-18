// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.ide.impl.jps.serialization

import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.startup.StartupManager
import com.intellij.platform.backend.observation.ActivityKey
import com.intellij.platform.backend.observation.trackActivity
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.diagnostic.telemetry.helpers.MillisecondsMeasurer
import com.intellij.platform.workspace.jps.JpsFileEntitySource
import com.intellij.platform.workspace.jps.JpsMetrics
import com.intellij.workspaceModel.ide.JpsProjectLoadedListener
import com.intellij.workspaceModel.ide.impl.WorkspaceModelImpl
import io.opentelemetry.api.metrics.Meter
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting
import kotlin.system.measureTimeMillis

/**
 * Loading the real state of the project after loading from cache.
 *
 * Initially IJ loads the state of a workspace model from the cache. In this startup activity it synchronizes the state
 * of a workspace model with project model files (iml/xml).
 *
 * If this synchronizer overrides your changes, and you'd like to postpone the changes to be after this synchronization,
 *   you can use [com.intellij.workspaceModel.ide.JpsProjectLoadingManager].
 */
@ApiStatus.Internal
@VisibleForTesting
class DelayedProjectSynchronizer : ProjectActivity {
  private object ProjectSynchronizerActivityKey : ActivityKey {
    override val presentableName: String
      get() = "sync-project-model"
  }

  override suspend fun execute(project: Project) {
    val workspaceModel = project.serviceAsync<WorkspaceModel>() as WorkspaceModelImpl

    fun logJpsEntities(state: String) {
      val logger = thisLogger()
      if (logger.isDebugEnabled) {
        try {
          fun <T> List<T>.safeSubList(fromIndex: Int, toIndex: Int): List<T> {
            return this.subList(fromIndex.coerceAtLeast(0), toIndex.coerceAtMost(this.size))
          }

          val wsm = workspaceModel.currentSnapshot
          val jpsEntities = wsm.entitiesBySource { entitySource -> entitySource is JpsFileEntitySource }.toList()
          val sampleSize = 50
          val entitySources = jpsEntities.asSequence().map { it.entitySource }.distinct().take(sampleSize).toList()
          logger.warn("$state: ${jpsEntities.size} entities.\n" +
                      "First $sampleSize entities are: ${jpsEntities.safeSubList(0, sampleSize)}.\n" +
                      "First $sampleSize entity sources are: ${entitySources}")
        } catch (_: Throwable) {
          // do nothing. Imagine that this code was never existed. We are debugging BAZEL-1750.
        }
      }
    }

    logJpsEntities("Before Util.doSync")
    project.trackActivity(ProjectSynchronizerActivityKey) {
      Util.doSync(project, workspaceModel)
    }
    logJpsEntities("After Util.doSync")
  }

  object Util {
    init {
      setupOpenTelemetryReporting(JpsMetrics.getInstance().meter)
    }

    // This function is effectively "private". It's internal because otherwise it's not available for DelayedProjectSynchronizer
    internal suspend fun doSync(project: Project, workspaceModel: WorkspaceModelImpl) {
      if (!workspaceModel.loadedFromCache) {
        return
      }

      val projectModelSynchronizer = project.serviceAsync<JpsProjectModelSynchronizer>()
      val loadingTime = measureTimeMillis {
        val projectEntities = projectModelSynchronizer.loadProjectToEmptyStorage(project, workspaceModel)
        projectModelSynchronizer.applyLoadedStorage(projectEntities, workspaceModel)
        project.messageBus.syncPublisher(JpsProjectLoadedListener.LOADED).loaded()
      }
      syncTimeMs.duration.addAndGet(loadingTime)
      thisLogger().info(
        "Workspace model loaded from cache. Syncing real project state into workspace model in $loadingTime ms. ${Thread.currentThread()}"
      )
    }

    @TestOnly
    suspend fun backgroundPostStartupProjectLoading(project: Project) {
      // Due to making [DelayedProjectSynchronizer] as backgroundPostStartupActivity, we should have this hack because
      // background activity doesn't start in the tests
      project.serviceAsync<StartupManager>().allActivitiesPassedFuture.join()
      doSync(project, project.serviceAsync<WorkspaceModel>() as WorkspaceModelImpl)
    }

    private val syncTimeMs = MillisecondsMeasurer()

    private fun setupOpenTelemetryReporting(meter: Meter) {
      val syncTimeCounter = meter.counterBuilder("workspaceModel.delayed.project.synchronizer.sync.ms").buildObserver()

      meter.batchCallback({ syncTimeCounter.record(syncTimeMs.asMilliseconds()) }, syncTimeCounter)
    }
  }
}