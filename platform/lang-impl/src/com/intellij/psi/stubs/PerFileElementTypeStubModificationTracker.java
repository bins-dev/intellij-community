// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.stubs;

import com.intellij.diagnostic.PluginException;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Attachment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCloseListener;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWithId;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.util.SystemProperties;
import com.intellij.util.concurrency.SynchronizedClearableLazy;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.*;
import com.intellij.util.messages.SimpleMessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

final class PerFileElementTypeStubModificationTracker implements StubIndexImpl.FileUpdateProcessor {
  static final Logger LOG = Logger.getInstance(PerFileElementTypeStubModificationTracker.class);
  public static final int PRECISE_CHECK_THRESHOLD =
    SystemProperties.getIntProperty("stub.index.per.file.element.type.modification.tracker.precise.check.threshold", 20);

  private final ConcurrentMap<String, List<IFileElementType>> myFileElementTypesCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<IFileElementType, Long> myModCounts = new ConcurrentHashMap<>();
  private final SynchronizedClearableLazy<@Nullable StubUpdatingIndexStorage> myStubUpdatingIndexStorage =
    new SynchronizedClearableLazy<>(() -> {
      if (FileBasedIndex.USE_IN_MEMORY_INDEX) {
        return null;
      }
      final FileBasedIndexImpl fileBasedIndex = (FileBasedIndexImpl)FileBasedIndex.getInstance();
      fileBasedIndex.waitUntilIndicesAreInitialized();
      try {
        UpdatableIndex<?, ?, ?, ?> index = fileBasedIndex.getIndex(StubUpdatingIndex.INDEX_ID);
        while (index instanceof FileBasedIndexInfrastructureExtensionUpdatableIndex) {
          index = ((FileBasedIndexInfrastructureExtensionUpdatableIndex<?, ?, ?, ?>)index).getBaseIndex();
        }
        return (StubUpdatingIndexStorage)index;
      }
      catch (Exception e) { // EA-753513 Index is not created for `Stubs`
        if (FileBasedIndexExtension.EXTENSION_POINT_NAME.findExtension(StubUpdatingIndex.class) == null) {
          // StubUpdatingIndex is not registered
          return null;
        }
        LOG.error("Couldn't get stub indexing storage. Mod counts will be incremented without a precise check", e);
        return null;
      }
    });

  private final SimpleMessageBusConnection myProjectCloseListener;

  private final AtomicReference<Throwable> disposeTrace = new AtomicReference<>();

  PerFileElementTypeStubModificationTracker() {
    myProjectCloseListener = ApplicationManager.getApplication().getMessageBus().simpleConnect();
    myProjectCloseListener.subscribe(ProjectCloseListener.TOPIC, new ProjectCloseListener() {
      @Override
      public void projectClosing(@NotNull Project project) {
        // currently called from EDT
        // given that the project is closing, it is acceptable not to process updates precisely
        endUpdatesBatchOnProjectClose();
      }
    });
  }

  private record FileInfo(
    @NotNull VirtualFile file,
    @NotNull Project project,
    @NotNull IFileElementType type
  ) {}

  private final Queue<VirtualFile> myPendingUpdates = new ArrayDeque<>();
  private final Queue<FileInfo> myProbablyExpensiveUpdates = new ArrayDeque<>();
  private final Set<IFileElementType> myModificationsInCurrentBatch = new HashSet<>();

  private void registerModificationForAllElementTypes() {
    for (IFileElementType fileElementType : myModCounts.keySet()) {
      myModCounts.merge(fileElementType, 1L, (count, value) -> count + value);
    }
  }

  private void registerModificationFor(@NotNull IFileElementType fileElementType) {
    myModificationsInCurrentBatch.add(fileElementType);
    myModCounts.compute(fileElementType, (__, value) -> {
      if (value == null) return 1L;
      return value + 1;
    });
  }

  private boolean wereModificationsInCurrentBatch(@NotNull IFileElementType fileElementType) {
    return myModificationsInCurrentBatch.contains(fileElementType);
  }

  // TODO optimization: if nobody asked for a modification tracker of fileElementType, we don't have to count stub changes for it then.
  //    Hence precise check for such fileElementTypes can be omitted.
  public Long getModificationStamp(@NotNull IFileElementType fileElementType) {
    return myModCounts.getOrDefault(fileElementType, 0L);
  }

  @Override
  public synchronized void processUpdate(@NotNull VirtualFile file) {
    myPendingUpdates.add(file);
  }

  @Override
  public synchronized void endUpdatesBatch() {
    myModificationsInCurrentBatch.clear();
    ReadAction.run(() -> {
      if (disposeTrace.get() != null) {
        throw new IllegalStateException("Cannot end updates batch because the tracker is disposed! Disposal trace is in the cause", disposeTrace.get());
      }
      fastCheck();
      if (myProbablyExpensiveUpdates.size() > PRECISE_CHECK_THRESHOLD) {
        coarseCheck();
      }
      else {
        preciseCheck();
      }
    });
  }

  private synchronized void endUpdatesBatchOnProjectClose() {
    myModificationsInCurrentBatch.clear();
    zeroCheck();
  }

  // consumes myPendingUpdates, all unprocessed updates are placed in myProbablyExpensiveUpdates
  private void fastCheck() {
    var index = myStubUpdatingIndexStorage.getValue();
    if (index == null) { // if indexes are not ready, then just increment global mod count and exit
      zeroCheck();
      return;
    }
    while (!myPendingUpdates.isEmpty()) {
      VirtualFile file = myPendingUpdates.remove();

      if (file.isDirectory()) continue;
      if (!file.isValid()) {
        // file is deleted or changed externally
        var beforeSuitableTypes = determinePreviousFileElementType(FileBasedIndex.getFileId(file), index);
        for (var type : beforeSuitableTypes) {
          registerModificationFor(type);
        }
        continue;
      }
      Project project = ((FileBasedIndexImpl)FileBasedIndex.getInstance()).findProjectForFileId(((VirtualFileWithId)file).getId());
      if (project == null || project.isDisposed()) continue;
      IndexedFile indexedFile = new IndexedFileImpl(file, project);
      var current = determineCurrentFileElementType(indexedFile);
      var beforeSuitableTypes = determinePreviousFileElementType(FileBasedIndex.getFileId(file), index);
      if (beforeSuitableTypes.size() > 1) {
        for (var type : beforeSuitableTypes) {
          registerModificationFor(type);
        }
        if (current != null) registerModificationFor(current);
        continue;
      }
      var before = beforeSuitableTypes.isEmpty() ? null : beforeSuitableTypes.get(0);
      if (current != before) {
        if (current != null) registerModificationFor(current);
        if (before != null) registerModificationFor(before);
      }
      else {
        if (current != null) myProbablyExpensiveUpdates.add(new FileInfo(file, project, current));
      }
    }
  }

  // consumes myPendingUpdates
  private void zeroCheck() {
    myPendingUpdates.clear();
    registerModificationForAllElementTypes();
  }

  // consumes myProbablyExpensiveUpdates
  private void coarseCheck() {
    while (!myProbablyExpensiveUpdates.isEmpty()) {
      FileInfo info = myProbablyExpensiveUpdates.remove();
      if (wereModificationsInCurrentBatch(info.type)) continue;
      registerModificationFor(info.type);
    }
  }

  // consumes myProbablyExpensiveUpdates
  private void preciseCheck() {
    var index = myStubUpdatingIndexStorage.getValue();
    if (index == null) {
      myProbablyExpensiveUpdates.clear();
      registerModificationForAllElementTypes();
      return;
    }
    DataIndexer<Integer, SerializedStubTree, FileContent> stubIndexer = index.getIndexer();
    while (!myProbablyExpensiveUpdates.isEmpty()) {
      FileInfo info = myProbablyExpensiveUpdates.remove();
      if (wereModificationsInCurrentBatch(info.type) || info.project.isDisposed()) continue;
      FileBasedIndexImpl.markFileIndexed(info.file, null);
      try {
        var diffBuilder = (StubCumulativeInputDiffBuilder)index.getForwardIndexAccessor()
          .getDiffBuilder(
            FileBasedIndex.getFileId(info.file),
            null // see SingleEntryIndexForwardIndexAccessor#getDiffBuilder
          );
        // file might be deleted from actual fs, but still be "valid" in vfs (e.g. that happen sometimes in tests)
        final FileContent fileContent = getTransientAwareFileContent(info);
        if (fileContent == null) {
          registerModificationFor(info.type);
          continue;
        }
        final Stub stub = StubTreeBuilder.buildStubTree(fileContent);
        Map<Integer, SerializedStubTree> serializedStub = stub == null ? Collections.emptyMap() : stubIndexer.map(fileContent);
        if (diffBuilder.differentiate(serializedStub, (__, ___, ____, _____) -> { }, true)) {
          registerModificationFor(info.type);
        }
      }
      catch (IOException | StorageException e) {
        LOG.error(e);
      }
      finally {
        FileBasedIndexImpl.unmarkBeingIndexed();
      }
    }
  }

  private static @Nullable FileContent getTransientAwareFileContent(FileInfo info) throws IOException {
    var file = info.file;
    var doc = FileDocumentManager.getInstance().getCachedDocument(file);
    var project = info.project;
    if (doc == null) {
      try {
        return FileContentImpl.createByFile(file, project);
      }
      catch (FileNotFoundException | NoSuchFileException ignored) {
        return null;
      }
    }
    PsiFile psi = PsiDocumentManager.getInstance(project).getPsiFile(doc);
    DocumentContent content = FileBasedIndexImpl.findLatestContent(doc, psi);
    return FileContentImpl.createByText(file, content.getText(), project);
  }

  void undispose() {
    // we have a real problem when plugins are installed. At the moment suppress it, because there seems to be
    // one more problem (possible race between dispose() and endUpdatesBatch()) which is otherwise masked by this one.
    disposeTrace.set(null);
  }

  public void dispose() {
    disposeTrace.set(new Throwable());
    myProjectCloseListener.disconnect();
    myFileElementTypesCache.clear();
    myModCounts.clear();
    myPendingUpdates.clear();
    myProbablyExpensiveUpdates.clear();
    myModificationsInCurrentBatch.clear();
    myStubUpdatingIndexStorage.drop();
  }

  @Override
  public String toString() {
    return super.toString() + " (isDisposed=" + (disposeTrace.get() != null) +
           ", pendingUpdates=" + myPendingUpdates.size() +
           ", probablyExpensiveUpdates=" + myProbablyExpensiveUpdates.size() +
           ", modificationsInCurrentBatch=" + myModificationsInCurrentBatch.size() +
           ", modCounts=" + myModCounts + ")";
  }

  private static @Nullable IFileElementType determineCurrentFileElementType(IndexedFile indexedFile) {
    if (shouldSkipFile(indexedFile.getFile())) return null;
    var stubBuilderType = StubTreeBuilder.getStubBuilderType(indexedFile, true);
    if (stubBuilderType == null) return null;
    return stubBuilderType.getFileElementType();
  }

  private @NotNull List<IFileElementType> determinePreviousFileElementType(int fileId, @NotNull StubUpdatingIndexStorage index) {
    String storedVersion = index.getStoredSubIndexerVersion(fileId);
    if (storedVersion == null) return Collections.emptyList();
    return myFileElementTypesCache.compute(storedVersion, (__, value) -> {
      if (value != null) return value;
      List<IFileElementType> types = StubBuilderType.getStubFileElementTypeFromVersion(storedVersion);
      if (types.size() > 1) {
        reportStubFileElementTypeVersionConflict(types, storedVersion);
      }
      return types;
    });
  }

  private static void reportStubFileElementTypeVersionConflict(List<IFileElementType> types, String storedVersion) {
    var data = describeStubFileElementTypes(types);
    var responsiblePluginIds = ContainerUtil.mapNotNull(data, p -> p.second);
    var responsiblePluginId = responsiblePluginIds.isEmpty()
                              ? null
                              // eventually, all relevant plugins will be notified
                              : responsiblePluginIds.get(new Random(System.nanoTime()).nextInt(responsiblePluginIds.size()));
    var attachment = new Attachment(
      "element-types.txt",
      "StubFileElementType version: " + storedVersion + "\n" +
      "List of suitable conflicting StubFileElementTypes:\n" +
      String.join("\n", ContainerUtil.map(data, p -> p.first))
    );
    attachment.setIncluded(true);
    var message = "Cannot distinguish StubFileElementTypes. This might worsen the performance. " +
                  "Providing unique externalId or adding a distinctive debugName when instantiating StubFileElementTypes can help " +
                  "(override getExternalId() and/or getDebugName() in StubFileElementType). " +
                  "See attachment for additional information.";
    if (responsiblePluginId != null) {
      LOG.error(new PluginException(message, responsiblePluginId, List.of(attachment)));
    } else {
      LOG.error(message, attachment);
    }
  }

  private static @Unmodifiable @NotNull List<Pair<String, @Nullable PluginId>> describeStubFileElementTypes(List<IFileElementType> types) {
    StubElementRegistryService stubElementRegistryService = StubElementRegistryService.getInstance();
    return ContainerUtil.map(types, (elemType) -> {
      var plugin = PluginManager.getPluginByClass(elemType.getClass());
      var pluginId = plugin == null ? null : plugin.getPluginId();
      ObjectStubSerializer<?, @NotNull Stub> serializer = Objects.requireNonNull(stubElementRegistryService.getStubSerializer(elemType));
      String desc = elemType.getClass().getName() + ": " +
                    "plugin=" + pluginId +
                    ", language=" + elemType.getLanguage() +
                    ", externalId=" + serializer.getExternalId() +
                    ", debugName=" + elemType.getDebugName();
      return Pair.pair(desc, pluginId);
    });
  }

  /**
   * There is no need to process binary files (e.g. .class), so we should just skip them.
   * Their processing might trigger content read which is expensive.
   * Also, we should not process files which weren't indexed by StubIndex yet (or won't be, such as large files).
   */
  private static boolean shouldSkipFile(VirtualFile file) {
    if (((FileBasedIndexImpl)FileBasedIndex.getInstance()).isTooLarge(file)) {
      return true;
    }
    { // this code snippet is taken from StubTreeBuilder#getStubBuilderType
      FileType fileType = file.getFileType();
      final BinaryFileStubBuilder builder = BinaryFileStubBuilders.INSTANCE.forFileType(fileType);
      if (builder != null) return true;
    }
    if (!StubUpdatingIndex.canHaveStub(file)) return true;
    return false;
  }
}
