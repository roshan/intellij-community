/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.ui.tabs.impl.singleRow;

import com.intellij.ui.tabs.JBTabsPosition;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.impl.JBTabsImpl;
import com.intellij.ui.tabs.impl.LayoutPassInfo;
import com.intellij.ui.tabs.impl.TabLabel;
import com.intellij.util.ui.GraphicsUtil;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class CompressibleSingleRowLayout extends SingleRowLayout {
  public CompressibleSingleRowLayout(JBTabsImpl tabs) {
    super(tabs);
  }

  @Override
  public LayoutPassInfo layoutSingleRow(List<TabInfo> visibleInfos) {
    SingleRowPassInfo data = (SingleRowPassInfo)super.layoutSingleRow(visibleInfos);
    if (data.toLayout.size() > 0) {
      final TabLabel firstLabel = myTabs.myInfo2Label.get(data.toLayout.get(0));
      final TabLabel lastLabel = findLastVisibleLabel(data);
      if (firstLabel != null && lastLabel != null) {
        data.tabRectangle.x = firstLabel.getBounds().x;
        data.tabRectangle.y = firstLabel.getBounds().y;
        data.tabRectangle.width = data.requiredLength;
        data.tabRectangle.height = (int)lastLabel.getBounds().getMaxY() - data.tabRectangle.y;
      }
    }

    return data;
  }

  @Override
  protected void recomputeToLayout(SingleRowPassInfo data) {
    calculateRequiredLength(data);
    data.firstGhostVisible = false;
    data.lastGhostVisible = false;
  }

  @Override
  protected void layoutLabelsAndGhosts(SingleRowPassInfo data) {
    if (myTabs.getPresentation().getTabsPosition() != JBTabsPosition.top
        && myTabs.getPresentation().getTabsPosition() != JBTabsPosition.bottom) {
      super.layoutLabelsAndGhosts(data);
      return;
    }

    int maxGridSize = 0;
    int spentLength = 0;
    int lengthEstimation = 0;

    int[] lengths = new int[data.toLayout.size()];

    List<TabInfo> layout = data.toLayout;
    for (int i = 0; i < layout.size(); i++) {
      final TabLabel label = myTabs.myInfo2Label.get(layout.get(i));
      if (maxGridSize == 0) {
        maxGridSize = GraphicsUtil.stringWidth("m", label.getLabelComponent().getFont()) * 20;
      }
      int lengthIncrement = label.getPreferredSize().width;
      lengths[i] = lengthIncrement;
      lengthEstimation += lengthIncrement;
    }

    final int extraWidth = data.toFitLength - lengthEstimation;

    Arrays.sort(lengths);
    double acc = 0;
    int actualGridSize = 0;
    for (int i = 0; i < lengths.length; i++) {
      int length = lengths[i];
      acc += length;
      actualGridSize = (int)Math.min(maxGridSize, (acc + extraWidth) / (i+1));
      if (i < lengths.length - 1 && actualGridSize < lengths[i+1]) break;
    }


    for (Iterator<TabInfo> iterator = data.toLayout.iterator(); iterator.hasNext(); ) {
      final TabLabel label = myTabs.myInfo2Label.get(iterator.next());
      label.setActionPanelVisible(true);

      int length;
      int lengthIncrement = label.getPreferredSize().width;
      if (!iterator.hasNext()) {
        length = Math.min(data.toFitLength - spentLength, Math.max(actualGridSize, lengthIncrement));
      }
      else if (extraWidth <= 0 ) {//need compress
        length = (int)(lengthIncrement * (float)data.toFitLength / lengthEstimation);
      }
      else {
        length = Math.max(lengthIncrement, actualGridSize);
      }
      spentLength += length + myTabs.getInterTabSpaceLength();
      applyTabLayout(data, label, length, 0);
      data.position = (int)label.getBounds().getMaxX() + myTabs.getInterTabSpaceLength();
    }

    for (TabInfo eachInfo : data.toDrop) {
      JBTabsImpl.resetLayout(myTabs.myInfo2Label.get(eachInfo));
    }
  }

  @Override
  protected boolean applyTabLayout(SingleRowPassInfo data, TabLabel label, int length, int deltaToFit) {
    boolean result = super.applyTabLayout(data, label, length, deltaToFit);
    label.setAlignmentToCenter(false);
    return result;
  }
}
