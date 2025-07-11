// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.impl.coverage

import com.intellij.rt.coverage.aggregate.api.AggregatorApi
import com.intellij.rt.coverage.instrumentation.CoverageArgs
import com.intellij.rt.coverage.report.api.Filters
import com.intellij.rt.coverage.report.api.ReportApi
import com.intellij.rt.coverage.util.ErrorReporter
import com.intellij.util.io.Compressor
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.intellij.build.CompilationContext
import org.jetbrains.intellij.build.dependencies.TeamCityHelper
import org.jetbrains.intellij.build.telemetry.block
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.java.JpsJavaClasspathKind
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.library.JpsOrderRootType
import org.jetbrains.jps.model.module.JpsModule
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.useLines

/**
 * See [org.jetbrains.intellij.build.TestingOptions.enableCoverage]
 */
@ApiStatus.Internal
interface Coverage {
  val reportDir: Path

  fun enable(jvmOptions: MutableList<String>, systemProperties: MutableMap<String, String>)

  suspend fun generateReport()

  suspend fun aggregateAndReport(dataToMerge: List<Path>)
}

@OptIn(ExperimentalPathApi::class)
internal class CoverageImpl(
  private val context: CompilationContext,
  coveredModuleNames: List<String>,
  private val coveredClasses: List<Regex>,
) : Coverage {
  private val buildId: String = TeamCityHelper.allProperties["teamcity.build.id"] ?: ""

  private val data: Path = context.paths.tempDir.resolve("coverage$buildId.ic")

  init {
    require(coveredModuleNames.any()) {
      "No module names to collect coverage for"
    }
    require(coveredClasses.any()) {
      "No class patterns to collect coverage for"
    }
    data.deleteIfExists()
  }

  override val reportDir: Path = context.paths.tempDir.resolve("coverage-report")

  private val agentJar: Path by lazy {
    val libraryName = "jetbrains.intellij.deps.coverage.reporter"
    val libraryModule = "intellij.platform.buildScripts"
    val jarName = "intellij-coverage-agent"
    val library = context.findRequiredModule(libraryModule)
                    .libraryCollection
                    .findLibrary(libraryName)
                  ?: error("Can't find the '$libraryName' library in the '$libraryModule' module")
    val jar = library.getPaths(JpsOrderRootType.COMPILED)
                .firstOrNull { it.name.startsWith(jarName) && it.extension == "jar" }
              ?: error("Can't find the '$jarName' jar in '$libraryName' library")
    check(jar.exists()) { "'$jar' doesn't exist" }
    jar
  }

  private val coverageArgs: String by lazy {
    val args = CoverageArgs()
    args.mergeData = true // required to merge results from multiple runs; for example, both JUni5 and JUnit3/4
    args.branchCoverage = true // enables both line and branch coverage
    args.includePatterns = coveredClasses.map { it.toPattern() }
    val argsString = sequenceOf(
      data.absolutePathString(),
      args.testTracking,
      args.calcUnloaded,
      args.mergeData,
      args.branchCoverage.not(),
      *args.includePatterns.toTypedArray(),
    ).joinToString(separator = ",")
    val parsedArgs = CoverageArgs.fromString(argsString)
    check(args.testTracking == parsedArgs.testTracking)
    check(args.calcUnloaded == parsedArgs.calcUnloaded)
    check(args.mergeData == parsedArgs.mergeData)
    check(args.branchCoverage == parsedArgs.branchCoverage)
    check(args.includePatterns.joinToString() == parsedArgs.includePatterns.joinToString())
    argsString
  }

  /**
   * [org.jetbrains.jps.model.java.JpsJavaDependenciesEnumerator.productionOnly] isn't called
   * and [JpsJavaClasspathKind] includes tests because there are modules containing only tests,
   * therefore, the result should be filtered later using [JavaSourceRootType] and [CompilationContext.getModuleOutputRoots]
   */
  private val coveredModulesWithTransitiveDependencies: Collection<JpsModule> by lazy {
    coveredModuleNames.flatMap {
      JpsJavaExtensionService
        .dependencies(context.findRequiredModule(it))
        .withoutLibraries().withoutSdk().recursively()
        .includedIn(JpsJavaClasspathKind.runtime(true))
        .modules
    }
  }

  /**
   * See https://github.com/JetBrains/intellij-coverage/blob/master/HOWTORUN.md#generate-html-report
   */
  private fun generateReport(outputRoots: List<Path>, sourceRoots: List<Path>) {
    check(data.exists()) {
      "Coverage results file '${data.absolutePathString()}' is expected to be generated by the coverage agent"
    }
    context.notifyArtifactBuilt(data)
    reportDir.deleteRecursively()
    @Suppress("IO_FILE_USAGE")
    ReportApi.htmlReport(
      reportDir.toFile(),
      null, "UTF-8",
      listOf(data.toFile()),
      outputRoots.map { it.toFile() },
      sourceRoots.map { it.toFile() },
      Filters(
        coveredClasses.map { it.toPattern() },
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
      ),
    )
    val indexPage = reportDir.resolve("index.html")
    check(indexPage.exists()) {
      "Failed to generate report in $reportDir"
    }
    check(indexPage.useLines {
      it.none { line ->
        line.contains("No coverage information was found", ignoreCase = true)
      }
    }) {
      "No coverage information was found in $indexPage"
    }
  }

  private fun publishReport() {
    val reportZip = context.paths.tempDir.resolve("coverage-report.zip")
    Compressor.Zip(reportZip).use {
      it.addDirectory(reportDir)
    }
    context.notifyArtifactBuilt(reportZip)
  }

  private suspend fun outputRoots(): List<Path> {
    val outputRoots = coveredModulesWithTransitiveDependencies.flatMap {
      context.getModuleOutputRoots(it, false)
    }
    check(outputRoots.any()) {
      "No output roots for '${coveredModulesWithTransitiveDependencies.map { it.name }}'"
    }
    check(outputRoots.any { it.exists() }) {
      outputRoots.asSequence()
        .take(10)
        .joinToString(prefix = "Output roots don't exist:\n", separator = "\n")
    }
    return outputRoots
  }

  private val sourceRoots: List<Path> by lazy {
    val sourceRoots = coveredModulesWithTransitiveDependencies.asSequence().flatMap { module ->
      module.sourceRoots.asSequence().filter {
        it.rootType == JavaSourceRootType.SOURCE
      }
    }.map { it.path }.toList()
    check(sourceRoots.any()) {
      "Source roots for '${coveredModulesWithTransitiveDependencies.map { it.name }}'"
    }
    val nonExistentRoots = sourceRoots.filterNot { it.exists() }
    check(nonExistentRoots.none()) {
      nonExistentRoots.asSequence()
        .take(10)
        .joinToString(prefix = "The following source roots don't exist:\n", separator = "\n")
    }
    sourceRoots
  }

  override fun enable(jvmOptions: MutableList<String>, systemProperties: MutableMap<String, String>) {
    check(coveredModulesWithTransitiveDependencies.any()) {
      "No modules to collect coverage for"
    }
    jvmOptions += "-javaagent:$agentJar=$coverageArgs"
    systemProperties[ErrorReporter.LOG_LEVEL_SYSTEM_PROPERTY] = "info"
  }

  override suspend fun generateReport() {
    block("Generating a coverage report") {
      generateReport(outputRoots = outputRoots(), sourceRoots = sourceRoots)
      publishReport()
    }
  }

  override suspend fun aggregateAndReport(dataToMerge: List<Path>) {
    require(dataToMerge.any()) {
      "No coverage data files to merge"
    }
    block("Merging coverage data files") {
      @Suppress("IO_FILE_USAGE")
      AggregatorApi.merge(
        dataToMerge.map { it.toFile() },
        data.toFile(),
      )
    }
    generateReport()
  }
}