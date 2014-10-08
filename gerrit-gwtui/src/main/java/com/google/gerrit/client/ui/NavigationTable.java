// Copyright (C) 2009 The Android Open Source Project
// Copyright (C) 2014 Digia Plc and/or its subsidiary(-ies).
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.client.ui;

import com.google.gerrit.client.Gerrit;
import com.google.gwt.dom.client.Document;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.HTMLTable.CellFormatter;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.ScrollPanel;
import com.google.gwt.user.client.ui.UIObject;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwtexpui.safehtml.client.SafeHtml;

import java.util.LinkedHashMap;
import java.util.Map.Entry;

public abstract class NavigationTable<RowItem> extends FancyFlexTable<RowItem> {
  protected class MyFlexTable extends FancyFlexTable.MyFlexTable {
    public MyFlexTable() {
      sinkEvents(Event.ONDBLCLICK | Event.ONCLICK);
    }

    @Override
    public void onBrowserEvent(final Event event) {
      switch (DOM.eventGetType(event)) {
        case Event.ONCLICK: {
          // Find out which cell was actually clicked.
          final Element td = getEventTargetCell(event);
          if (td == null) {
            break;
          }
          final int row = rowOf(td);
          if (getRowItem(row) != null) {
            onCellSingleClick(rowOf(td), columnOf(td));
            return;
          }
          break;
        }
        case Event.ONDBLCLICK: {
          // Find out which cell was actually clicked.
          Element td = getEventTargetCell(event);
          if (td == null) {
            return;
          }
          onCellDoubleClick(rowOf(td), columnOf(td));
          return;
        }
      }
      super.onBrowserEvent(event);
    }
  }

  @SuppressWarnings("serial")
  private static final LinkedHashMap<String, Object> savedPositions =
      new LinkedHashMap<String, Object>(10, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<String, Object> eldest) {
          return size() >= 20;
        }
      };

  protected class DefaultKeyNavigation extends AbstractKeyNavigation {
    public DefaultKeyNavigation(Widget parent) {
      super(parent);
    }

    @Override
    protected void onNext() {
      ensurePointerVisible();
      onDown();
    }

    @Override
    protected void onPrev() {
      ensurePointerVisible();
      onUp();
    }

    @Override
    protected void onOpen() {
      ensurePointerVisible();
      onOpenCurrent();
    }
  }

  private final Image pointer;
  private int currentRow = -1;
  private String saveId;

  private boolean computedScrollType;
  private ScrollPanel parentScrollPanel;

  protected AbstractKeyNavigation keyNavigation;

  protected NavigationTable(String itemHelpName) {
    this();
  }

  protected NavigationTable() {
    pointer = new Image(Gerrit.RESOURCES.arrowRight());
  }

  protected abstract void onOpenRow(int row);

  protected abstract Object getRowItemKey(RowItem item);

  public void onUp() {
    for (int row = currentRow - 1; row >= 0; row--) {
      if (getRowItem(row) != null) {
        movePointerTo(row);
        break;
      }
    }
  }

  public void onDown() {
    final int max = table.getRowCount();
    for (int row = currentRow + 1; row < max; row++) {
      if (getRowItem(row) != null) {
        movePointerTo(row);
        break;
      }
    }
  }

  public void onOpenCurrent() {
    if (0 <= currentRow && currentRow < table.getRowCount()) {
      if (getRowItem(currentRow) != null) {
        onOpenRow(currentRow);
      }
    }
  }

  /** Invoked when the user double clicks on a table cell. */
  protected void onCellDoubleClick(int row, int column) {
    onOpenRow(row);
  }

  /** Invoked when the user clicks on a table cell. */
  protected void onCellSingleClick(int row, int column) {
    movePointerTo(row);
  }

  public int getCurrentRow() {
    return currentRow;
  }

  public int getMaxRows() {
    return table.getRowCount();
  }

  public void ensurePointerVisible() {
    final int max = table.getRowCount();
    int row = currentRow;
    final int init = row;
    if (row < 0) {
      row = 0;
    } else if (max <= row) {
      row = max - 1;
    }

    final CellFormatter fmt = table.getCellFormatter();
    final int sTop = Document.get().getScrollTop();
    final int sEnd = sTop + Document.get().getClientHeight();

    while (0 <= row && row < max) {
      final Element cur = DOM.getParent(fmt.getElement(row, C_ARROW));
      final int cTop = cur.getAbsoluteTop();
      final int cEnd = cTop + cur.getOffsetHeight();

      if (cEnd < sTop) {
        row++;
      } else if (sEnd < cTop) {
        row--;
      } else {
        break;
      }
    }

    if (init != row) {
      movePointerTo(row, false);
    }
  }

  public void hideCursor() {
    final int noCursor = -1;
    if (currentRow != noCursor) {
      final CellFormatter fmt = table.getCellFormatter();
      final Element tr = DOM.getParent(fmt.getElement(currentRow, C_ARROW));
      UIObject.setStyleName(tr, Gerrit.RESOURCES.css().activeRow(), false);

      table.setWidget(currentRow, C_ARROW, null);
      pointer.removeFromParent();
    }
  }

  public void showCursor() {
    final int noCursor = -1;
    if (currentRow != noCursor) {
      final CellFormatter fmt = table.getCellFormatter();
      table.setWidget(currentRow, C_ARROW, pointer);
      final Element tr = DOM.getParent(fmt.getElement(currentRow, C_ARROW));
      UIObject.setStyleName(tr, Gerrit.RESOURCES.css().activeRow(), true);
    }
  }


  protected void movePointerTo(final int newRow) {
    movePointerTo(newRow, true);
  }

  protected void movePointerTo(final int newRow, final boolean scroll) {
    final CellFormatter fmt = table.getCellFormatter();
    final boolean clear = 0 <= currentRow && currentRow < table.getRowCount();
    if (clear) {
      final Element tr = DOM.getParent(fmt.getElement(currentRow, C_ARROW));
      UIObject.setStyleName(tr, Gerrit.RESOURCES.css().activeRow(), false);
    }
    if (newRow >= 0) {
      table.setWidget(newRow, C_ARROW, pointer);
      final Element tr = DOM.getParent(fmt.getElement(newRow, C_ARROW));
      UIObject.setStyleName(tr, Gerrit.RESOURCES.css().activeRow(), true);
      if (scroll) {
        scrollIntoView(tr);
      }
    } else if (clear) {
      table.setWidget(currentRow, C_ARROW, null);
      pointer.removeFromParent();
    }
    currentRow = newRow;
  }

  protected void scrollIntoView(final Element tr) {
    if (!computedScrollType) {
      parentScrollPanel = null;
      Widget w = getParent();
      while (w != null) {
        if (w instanceof ScrollPanel) {
          parentScrollPanel = (ScrollPanel) w;
          break;
        }
        w = w.getParent();
      }
      computedScrollType = true;
    }

    if (parentScrollPanel != null) {
      parentScrollPanel.ensureVisible(new UIObject() {
        {
          setElement(tr);
        }
      });
    } else {
      // tr.scrollIntoView(); works just for Firefox, not for Chrome and IE
      // so replacing it with following which works for all three browsers
      final int sTop = Window.getScrollTop();
      final int sEnd = sTop + Window.getClientHeight();
      final int eTop = tr.getAbsoluteTop();
      final int eEnd = eTop + tr.getClientHeight();
      // Element below view area
      if (eEnd > sEnd) {
        Window.scrollTo(Window.getScrollLeft(), eEnd - Window.getClientHeight());
      } else if (eTop < sTop) { // Element above view area
        Window.scrollTo(Window.getScrollLeft(), eTop);
      }
    }
  }

  protected void movePointerTo(final Object oldId) {
    final int row = findRow(oldId);
    if (0 <= row) {
      movePointerTo(row);
    }
  }

  protected int findRow(final Object oldId) {
    if (oldId != null) {
      final int max = table.getRowCount();
      for (int row = 0; row < max; row++) {
        final RowItem c = getRowItem(row);
        if (c != null && oldId.equals(getRowItemKey(c))) {
          return row;
        }
      }
    }
    return -1;
  }

  @Override
  protected void resetHtml(SafeHtml body) {
    currentRow = -1;
    super.resetHtml(body);
  }

  public void finishDisplay() {
    if (saveId != null) {
      movePointerTo(savedPositions.get(saveId));
    }
    if (currentRow < 0) {
      onDown();
    }
  }

  public void setSavePointerId(final String id) {
    saveId = id;
  }

  public void setRegisterKeys(final boolean on) {
    if (keyNavigation != null) {
      if (on && isAttached()) {
        keyNavigation.setRegisterKeys(true);
      } else {
        keyNavigation.setRegisterKeys(false);
      }
    }
  }

  @Override
  protected void onLoad() {
    computedScrollType = false;
    parentScrollPanel = null;
  }

  @Override
  protected void onUnload() {
    setRegisterKeys(false);

    if (saveId != null && currentRow >= 0) {
      final RowItem c = getRowItem(currentRow);
      if (c != null) {
        savedPositions.put(saveId, getRowItemKey(c));
      }
    }

    computedScrollType = false;
    parentScrollPanel = null;
    super.onUnload();
  }

  @Override
  protected MyFlexTable createFlexTable() {
    return new MyFlexTable();
  }
}
