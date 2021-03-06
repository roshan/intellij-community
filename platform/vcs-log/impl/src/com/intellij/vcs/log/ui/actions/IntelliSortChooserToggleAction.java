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
package com.intellij.vcs.log.ui.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.util.IconUtil;
import com.intellij.vcs.log.VcsLogDataKeys;
import com.intellij.vcs.log.VcsLogUi;
import com.intellij.vcs.log.graph.PermanentGraph;
import com.intellij.vcs.log.graph.impl.facade.bek.BekSorter;
import icons.VcsLogIcons;
import org.jetbrains.annotations.NotNull;

public class IntelliSortChooserToggleAction extends ToggleAction implements DumbAware {
  @NotNull private static final String DEFAULT_DESCRIPTION = "Turn IntelliSort On/Off";

  public IntelliSortChooserToggleAction() {
    super("IntelliSort", DEFAULT_DESCRIPTION, IconUtil.flip(VcsLogIcons.Branch, false));
  }

  @Override
  public boolean isSelected(AnActionEvent e) {
    VcsLogUi logUI = e.getRequiredData(VcsLogDataKeys.VCS_LOG_UI);
    return !logUI.getBekType().equals(PermanentGraph.SortType.Normal);
  }

  @Override
  public void setSelected(AnActionEvent e, boolean state) {
    VcsLogUi logUI = e.getRequiredData(VcsLogDataKeys.VCS_LOG_UI);
    logUI.setBekType(state ? PermanentGraph.SortType.Bek : PermanentGraph.SortType.Normal);
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    super.update(e);

    VcsLogUi logUI = e.getData(VcsLogDataKeys.VCS_LOG_UI);
    e.getPresentation().setVisible(BekSorter.isBekEnabled());
    e.getPresentation().setVisible(BekSorter.isBekEnabled() && logUI != null);

    if (logUI != null) {
      e.getPresentation().setDescription("Turn IntelliSort " + (logUI.getBekType() == PermanentGraph.SortType.Normal ? "On" : "Off" + ""));
    }
    else {
      e.getPresentation().setDescription(DEFAULT_DESCRIPTION);
    }
  }
}
