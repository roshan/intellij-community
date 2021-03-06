/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.openapi.components.impl.stores;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.NotificationsManager;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.StateStorage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.components.TrackingPathMacroSubstitutor;
import com.intellij.openapi.components.store.ReadOnlyModificationException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.DocumentRunnable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectBundle;
import com.intellij.openapi.project.ex.ProjectEx;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.util.LineSeparator;
import com.intellij.util.SmartList;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jdom.Element;
import org.jdom.Parent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.HyperlinkEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;

public class StorageUtil {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.components.impl.stores.StorageUtil");

  private static final byte[] XML_PROLOG = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>".getBytes(CharsetToolkit.UTF8_CHARSET);

  private static final Pair<byte[], String> NON_EXISTENT_FILE_DATA = Pair.create(null, SystemProperties.getLineSeparator());

  private StorageUtil() { }

  public static boolean isChangedByStorageOrSaveSession(@NotNull VirtualFileEvent event) {
    return event.getRequestor() instanceof StateStorage.SaveSession || event.getRequestor() instanceof StateStorage;
  }

  public static void notifyUnknownMacros(@NotNull TrackingPathMacroSubstitutor substitutor,
                                         @NotNull final Project project,
                                         @Nullable final String componentName) {
    final LinkedHashSet<String> macros = new LinkedHashSet<String>(substitutor.getUnknownMacros(componentName));
    if (macros.isEmpty()) {
      return;
    }

    UIUtil.invokeLaterIfNeeded(new Runnable() {
      @Override
      public void run() {
        List<String> notified = null;
        NotificationsManager manager = NotificationsManager.getNotificationsManager();
        for (UnknownMacroNotification notification : manager.getNotificationsOfType(UnknownMacroNotification.class, project)) {
          if (notified == null) {
            notified = new SmartList<String>();
          }
          notified.addAll(notification.getMacros());
        }
        if (!ContainerUtil.isEmpty(notified)) {
          macros.removeAll(notified);
        }

        if (!macros.isEmpty()) {
          LOG.debug("Reporting unknown path macros " + macros + " in component " + componentName);
          String format = "<p><i>%s</i> %s undefined. <a href=\"define\">Fix it</a></p>";
          String productName = ApplicationNamesInfo.getInstance().getProductName();
          String content = String.format(format, StringUtil.join(macros, ", "), macros.size() == 1 ? "is" : "are") +
                           "<br>Path variables are used to substitute absolute paths " +
                           "in " + productName + " project files " +
                           "and allow project file sharing in version control systems.<br>" +
                           "Some of the files describing the current project settings contain unknown path variables " +
                           "and " + productName + " cannot restore those paths.";
          new UnknownMacroNotification("Load Error", "Load error: undefined path variables", content, NotificationType.ERROR,
                                       new NotificationListener() {
                                         @Override
                                         public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
                                           ((ProjectEx)project).checkUnknownMacros(true);
                                         }
                                       }, macros).notify(project);
        }
      }
    });
  }

  @NotNull
  public static VirtualFile writeFile(@Nullable File file, @NotNull Object requestor, @Nullable VirtualFile virtualFile, @NotNull BufferExposingByteArrayOutputStream content, @Nullable LineSeparator lineSeparatorIfPrependXmlProlog) throws IOException {
    // mark this action as modifying the file which daemon analyzer should ignore
    AccessToken token = ApplicationManager.getApplication().acquireWriteActionLock(DocumentRunnable.IgnoreDocumentRunnable.class);
    try {
      if (file != null && (virtualFile == null || !virtualFile.isValid())) {
        virtualFile = getOrCreateVirtualFile(requestor, file);
      }
      assert virtualFile != null;
      OutputStream out = virtualFile.getOutputStream(requestor);
      try {
        if (lineSeparatorIfPrependXmlProlog != null) {
          out.write(XML_PROLOG);
          out.write(lineSeparatorIfPrependXmlProlog.getSeparatorBytes());
        }
        content.writeTo(out);
      }
      finally {
        out.close();
      }
      return virtualFile;
    }
    catch (FileNotFoundException e) {
      if (virtualFile == null) {
        throw e;
      }
      else {
        throw new ReadOnlyModificationException(virtualFile, e);
      }
    }
    finally {
      token.finish();
    }
  }

  public static void deleteFile(@NotNull File file, @NotNull Object requestor, @Nullable VirtualFile virtualFile) throws IOException {
    if (virtualFile == null) {
      LOG.warn("Cannot find virtual file " + file.getAbsolutePath());
    }

    if (virtualFile == null) {
      if (file.exists()) {
        FileUtil.delete(file);
      }
    }
    else if (virtualFile.exists()) {
      deleteFile(requestor, virtualFile);
    }
  }

  public static void deleteFile(@NotNull Object requestor, @NotNull VirtualFile virtualFile) throws IOException {
    AccessToken token = ApplicationManager.getApplication().acquireWriteActionLock(DocumentRunnable.IgnoreDocumentRunnable.class);
    try {
      virtualFile.delete(requestor);
    }
    catch (FileNotFoundException e) {
      throw new ReadOnlyModificationException(virtualFile, e);
    }
    finally {
      token.finish();
    }
  }

  @NotNull
  public static BufferExposingByteArrayOutputStream writeToBytes(@NotNull Parent element, @NotNull String lineSeparator) throws IOException {
    BufferExposingByteArrayOutputStream out = new BufferExposingByteArrayOutputStream(512);
    JDOMUtil.writeParent(element, out, lineSeparator);
    return out;
  }

  @NotNull
  private static VirtualFile getOrCreateVirtualFile(@Nullable Object requestor, @NotNull File ioFile) throws IOException {
    VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
    if (virtualFile == null) {
      File parentFile = ioFile.getParentFile();
      // need refresh if the directory has just been created
      VirtualFile parentVirtualFile = parentFile == null ? null : LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parentFile);
      if (parentVirtualFile == null) {
        throw new IOException(ProjectBundle.message("project.configuration.save.file.not.found", parentFile == null ? "" : parentFile.getPath()));
      }
      virtualFile = parentVirtualFile.createChildData(requestor, ioFile.getName());
    }
    return virtualFile;
  }

  /**
   * @return pair.first - file contents (null if file does not exist), pair.second - file line separators
   */
  @NotNull
  public static Pair<byte[], String> loadFile(@Nullable final VirtualFile file) throws IOException {
    if (file == null || !file.exists()) {
      return NON_EXISTENT_FILE_DATA;
    }

    byte[] bytes = file.contentsToByteArray();
    String lineSeparator = file.getDetectedLineSeparator();
    if (lineSeparator == null) {
      lineSeparator = detectLineSeparators(CharsetToolkit.UTF8_CHARSET.decode(ByteBuffer.wrap(bytes)), null).getSeparatorString();
    }
    return Pair.create(bytes, lineSeparator);
  }

  @NotNull
  public static LineSeparator detectLineSeparators(@NotNull CharSequence chars, @Nullable LineSeparator defaultSeparator) {
    for (int i = 0, n = chars.length(); i < n; i++) {
      char c = chars.charAt(i);
      if (c == '\r') {
        return LineSeparator.CRLF;
      }
      else if (c == '\n') {
        // if we are here, there was no \r before
        return LineSeparator.LF;
      }
    }
    return defaultSeparator == null ? LineSeparator.getSystemLineSeparator() : defaultSeparator;
  }

  public static void delete(@NotNull StreamProvider provider, @NotNull String fileSpec, @NotNull RoamingType type) {
    if (provider.isApplicable(fileSpec, type)) {
      provider.delete(fileSpec, type);
    }
  }

  /**
   * You must call {@link StreamProvider#isApplicable(String, com.intellij.openapi.components.RoamingType)} before
   */
  public static void sendContent(@NotNull StreamProvider provider, @NotNull String fileSpec, @NotNull Element element, @NotNull RoamingType type, boolean async) throws IOException {
    // we should use standard line-separator (\n) - stream provider can share file content on any OS
    BufferExposingByteArrayOutputStream content = writeToBytes(element, "\n");
    provider.saveContent(fileSpec, content.getInternalBuffer(), content.size(), type, async);
  }

  public static boolean isProjectOrModuleFile(@NotNull String fileSpec) {
    return StoragePathMacros.PROJECT_FILE.equals(fileSpec) || fileSpec.startsWith(StoragePathMacros.PROJECT_CONFIG_DIR) || fileSpec.equals(StoragePathMacros.MODULE_FILE);
  }
}
