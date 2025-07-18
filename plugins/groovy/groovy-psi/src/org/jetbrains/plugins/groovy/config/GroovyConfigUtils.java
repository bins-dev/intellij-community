// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.config;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.util.LibrariesUtil;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class GroovyConfigUtils extends AbstractConfigUtils {

  // to avoid java modules deps the same pattern was copied at org.jetbrains.plugins.gradle.service.GradleInstallationManager.GROOVY_ALL_JAR_PATTERN
  // please update it as well for further changes
  public static final @NonNls Pattern GROOVY_ALL_JAR_PATTERN = Pattern.compile("groovy-all(-minimal)?(-(?<version>\\d+(\\.\\d+)*(-(?!indy)\\w+(-\\d+)?)?))?(-indy)?\\.jar");
  public static final @NonNls Pattern GROOVY_JAR_PATTERN = Pattern.compile("groovy(-(?<version>\\d+(\\.\\d+)*(-(?!indy)\\w+(-\\d+)?)?))?(-indy)?\\.jar");

  public static final @NlsSafe String NO_VERSION = "<no version>";
  public static final @NlsSafe String GROOVY1_7 = "1.7";
  public static final @NlsSafe String GROOVY1_8 = "1.8";
  public static final @NlsSafe String GROOVY2_0 = "2.0";
  public static final @NlsSafe String GROOVY2_1 = "2.1";
  public static final @NlsSafe String GROOVY2_2 = "2.2";
  public static final @NlsSafe String GROOVY2_2_2 = "2.2.2";
  public static final @NlsSafe String GROOVY2_5_2 = "2.5.2";
  public static final @NlsSafe String GROOVY2_3 = "2.3";
  public static final @NlsSafe String GROOVY2_4 = "2.4";
  public static final @NlsSafe String GROOVY2_5 = "2.5";
  public static final @NlsSafe String GROOVY3_0 = "3.0";
  public static final @NlsSafe String GROOVY4_0 = "4.0";
  public static final @NlsSafe String GROOVY5_0 = "5.0";

  private static final GroovyConfigUtils ourGroovyConfigUtils = new GroovyConfigUtils();
  private static final Map<String, Map<String, Integer>> versionsCompareMap = new LRUMap<>();

  private static final @NonNls String LIB = "/lib";
  private static final @NonNls String EMBEDDABLE = "/embeddable";

  private static final @NlsSafe String ALPHA = "alpha";
  private static final @NlsSafe String BETA = "beta";
  private static final @NlsSafe String RC = "rc";
  private static final @NlsSafe String SNAPSHOT = "SNAPSHOT";


  private GroovyConfigUtils() {}

  public static GroovyConfigUtils getInstance() {
    return ourGroovyConfigUtils;
  }

  public static File @NotNull [] getGroovyAllJars(@NotNull String path) {
    return LibrariesUtil.getFilesInDirectoryByPattern(path, GROOVY_ALL_JAR_PATTERN);
  }

  public static boolean matchesGroovyAll(@NotNull String name) {
    return GROOVY_ALL_JAR_PATTERN.matcher(name).matches() && !name.contains("src") && !name.contains("doc");
  }

  public static boolean isAtLeastGroovy25(@NotNull PsiElement element) {
    return getInstance().isVersionAtLeast(element, GROOVY2_5);
  }

  public static boolean isAtLeastGroovy40(@NotNull PsiElement element) {
    return getInstance().isVersionAtLeast(element, GROOVY4_0);
  }

  public static @NlsSafe String getMavenSdkRepository(@NotNull String groovyVersion) {
    if (compareSdkVersions(groovyVersion, GROOVY4_0) >= 0) {
      return "org.apache.groovy";
    }
    else {
      return "org.codehaus.groovy";
    }
  }

  @Override
  public @NlsSafe @Nullable String getSDKVersionOrNull(@NlsSafe @NotNull String path) {
    String groovyJarVersion = getSDKJarVersion(path + LIB, GROOVY_JAR_PATTERN, MANIFEST_PATH);
    if (groovyJarVersion == null) {
      groovyJarVersion = getSDKJarVersion(path + LIB, GROOVY_ALL_JAR_PATTERN, MANIFEST_PATH);
    }
    if (groovyJarVersion == null) {
      groovyJarVersion = getSDKJarVersion(path + EMBEDDABLE, GROOVY_ALL_JAR_PATTERN, MANIFEST_PATH);
    }
    if (groovyJarVersion == null) {
      groovyJarVersion = getSDKJarVersion(path, GROOVY_ALL_JAR_PATTERN, MANIFEST_PATH);
    }
    if (groovyJarVersion == null) {
      groovyJarVersion = getSDKJarVersion(path, GROOVY_JAR_PATTERN, MANIFEST_PATH);
    }
    return groovyJarVersion;
  }

  @Override
  public boolean isSDKLibrary(Library library) {
    if (library == null) return false;
    return LibrariesUtil.getGroovyLibraryHome(library.getFiles(OrderRootType.CLASSES)) != null;
  }

  public @Nullable @NlsSafe String getSDKVersion(final @NotNull Module module) {
    return GroovyConfigUtilsKt.getSdkVersion(module);
  }

  public boolean isVersionAtLeast(PsiElement psiElement, String version) {
    return isVersionAtLeast(psiElement, version, true);
  }

  public boolean isVersionAtLeast(PsiElement psiElement, String version, boolean unknownResult) {
    Module module = ModuleUtilCore.findModuleForPsiElement(psiElement);
    if (module == null) return unknownResult;
    final String sdkVersion = getSDKVersion(module);
    if (sdkVersion == null) return unknownResult;
    return compareSdkVersions(sdkVersion, version) >= 0;
  }

  public static int compareSdkVersions(@NotNull String leftVersion, @NotNull String rightVersion) {
    return getInstance().compareSdkVersionsInner(leftVersion, rightVersion);
  }

  private synchronized int compareSdkVersionsInner(@NotNull String leftVersion, @NotNull String rightVersion) {
    Map<String, Integer> rightVersionToResultMap = versionsCompareMap.computeIfAbsent(leftVersion, key -> new LRUMap<>());

    return rightVersionToResultMap.computeIfAbsent(rightVersion, key -> {
      String[] leftVersionParts = leftVersion.split("[.-]");
      String[] rightVersionParts = rightVersion.split("[.-]");
      int sizes = Math.max(leftVersionParts.length, rightVersionParts.length);
      for (int i = 0; i < sizes; ++i) {
        int leftNumber = getVersionPart(leftVersionParts, i);
        int rightNumber = getVersionPart(rightVersionParts, i);
        if (leftNumber < rightNumber) {
          return -1;
        } else if (leftNumber > rightNumber) {
          return 1;
        }
      }
      return 0;
    });
  }

  public static boolean isUnstable(@NotNull String version) {
    return version.contains(ALPHA) || version.contains(BETA) || version.contains(RC);
  }

  private static int getVersionPart(String[] parts, int index) {
    String part = index < parts.length ? parts[index] : "-4";
    return switch (part) {
      case SNAPSHOT -> 999;
      case ALPHA -> -3;
      case BETA -> -2;
      case RC -> -1;
      default -> {
        try {
          yield Integer.parseInt(part);
        }
        catch (NumberFormatException __) {
          yield -4;
        }
      }
    };
  }

  public @NotNull @NlsSafe String getSDKVersion(PsiElement psiElement) {
    final Module module = ModuleUtilCore.findModuleForPsiElement(psiElement);
    if (module == null) {
      return NO_VERSION;
    }
    final String s = getSDKVersion(module);
    return s != null ? s : NO_VERSION;
  }


  @Override
  public boolean isSDKHome(VirtualFile file) {
    if (file != null && file.isDirectory()) {
      final String path = file.getPath();
      GroovyHomeKind kind = GroovyHomeKind.fromString(path);
      return kind != null;
    }
    return false;
  }

  public Collection<String> getSDKVersions(Library[] libraries) {
    return ContainerUtil.map(libraries, library -> getSDKVersion(LibrariesUtil.getGroovyLibraryHome(library)));
  }

  private static class LRUMap<K, V> extends LinkedHashMap<K, V> {
    private static final int MAX_SIZE = 50;

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
      return size() > MAX_SIZE;
    }
  }
}
