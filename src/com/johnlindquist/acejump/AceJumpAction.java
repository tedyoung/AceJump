package com.johnlindquist.acejump;

import com.intellij.application.options.colors.*;
import com.intellij.codeInsight.editorActions.SelectWordUtil;
import com.intellij.find.FindManager;
import com.intellij.find.FindModel;
import com.intellij.find.FindResult;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.impl.EditorColorsManagerImpl;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.editor.impl.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.popup.AbstractPopup;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.UsageInfo2UsageAdapter;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;

/**
 * User: John Lindquist
 * Date: 8/6/2012
 * Time: 12:10 AM
 */
public class AceJumpAction extends AnAction {

    protected Project project;
    protected EditorImpl editor;
    protected FindModel findModel;
    protected FindManager findManager;
    protected AbstractPopup popup;
    protected VirtualFile virtualFile;
    protected DocumentImpl document;
    protected FoldingModelImpl foldingModel;
    protected SearchBox searchBox;
    protected DataContext dataContext;
    protected AnActionEvent inputEvent;
    protected CaretModel caretModel;

    private CharSequence allowedCharacters = "abcdefghijklmnopqrstuvwxyz0123456789-=[];',./";
    private Font font;
    private Graphics2D aceGraphics;
    private Component aceCanvas;
    private EditorColorsScheme scheme;

    public void actionPerformed(AnActionEvent e) {
        inputEvent = e;

        project = e.getData(PlatformDataKeys.PROJECT);
        editor = (EditorImpl) e.getData(PlatformDataKeys.EDITOR);
        virtualFile = e.getData(PlatformDataKeys.VIRTUAL_FILE);
        document = (DocumentImpl) editor.getDocument();
        foldingModel = editor.getFoldingModel();
        dataContext = e.getDataContext();
        caretModel = editor.getCaretModel();

        findManager = FindManager.getInstance(project);
        findModel = createFindModel(findManager);

        scheme = EditorColorsManager.getInstance().getGlobalScheme();

        font = new Font(scheme.getEditorFontName(), Font.BOLD, scheme.getEditorFontSize());
        searchBox = new SearchBox();

        searchBox.setFont(font);

        ComponentPopupBuilder popupBuilder = JBPopupFactory.getInstance().createComponentPopupBuilder(searchBox, searchBox);
        popupBuilder.setCancelKeyEnabled(true);

        popup = (AbstractPopup) popupBuilder.createPopup();

//        popup.getContent().setBorder(new BlockBorder());

        popup.show(guessBestLocation(editor));

        popup.addListener(new JBPopupAdapter() {
            @Override
            public void onClosed(LightweightWindowEvent event) {
                searchBox.hideBalloons();
            }
        });


        Dimension dimension = new Dimension(searchBox.getFontMetrics(font).stringWidth("w") * 2, editor.getLineHeight());
        popup.setSize(dimension);
        searchBox.setSize(dimension);
        searchBox.setFocusable(true);
        searchBox.requestFocus();
    }


    protected FindModel createFindModel(FindManager findManager) {
        FindModel clone = (FindModel) findManager.getFindInFileModel().clone();
        clone.setFindAll(true);
        clone.setFromCursor(true);
        clone.setForward(true);
        clone.setRegularExpressions(false);
        clone.setWholeWordsOnly(false);
        clone.setCaseSensitive(false);
        clone.setSearchHighlighters(true);
        clone.setPreserveCase(false);

        return clone;
    }

    public RelativePoint guessBestLocation(Editor editor) {
        VisualPosition logicalPosition = editor.getCaretModel().getVisualPosition();
        RelativePoint pointFromVisualPosition = getPointFromVisualPosition(editor, logicalPosition);
        return pointFromVisualPosition;
    }

    protected static RelativePoint getPointFromVisualPosition(Editor editor, VisualPosition logicalPosition) {
        Point p = editor.visualPositionToXY(new VisualPosition(logicalPosition.line, logicalPosition.column));
        return new RelativePoint(editor.getContentComponent(), p);
    }

    protected void moveCaret(Integer offset) {
        editor.getCaretModel().moveToOffset(offset);
//        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
    }

    protected void clearSelection() {
        popup.cancel();
        editor.getSelectionModel().removeSelection();
    }

    protected class SearchBox extends JTextField {
        private ArrayList<JBPopup> resultPopups = new ArrayList<JBPopup>();
        protected HashMap<String, Integer> offsetHash = new HashMap<String, Integer>();
        protected int key;
        protected List<Integer> results;
        protected int startResult;
        protected int endResult;
        private SearchArea searchArea;
        private boolean searchMode = true;
        private boolean mnemonicsDisabled;


        @Override
        protected void paintBorder(Graphics g) {
            //do nothing
        }


        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth("w"), editor.getLineHeight());
        }

        public SearchBox() {
            final UISettings settings = UISettings.getInstance();
            addFocusListener(new FocusListener() {
                @Override
                public void focusGained(FocusEvent e) {
                    JComponent contentComponent = editor.getContentComponent();
                    aceCanvas = contentComponent.add(new JComponent() {

                    });
                    JViewport viewport = editor.getScrollPane().getViewport();
                    //the 1000s are for the panels on the sides
                    aceCanvas.setBounds(0, 0, viewport.getWidth() + 1000, viewport.getHeight() + 1000);
                    System.out.println(aceCanvas.getWidth());

                    Point locationOnScreen = contentComponent.getLocationOnScreen();
                    //probably need to check for menuBar visibility
                    int menuBarHeight = editor.getComponent().getRootPane().getJMenuBar().getHeight();
                    aceCanvas.setLocation(-locationOnScreen.x, -locationOnScreen.y + menuBarHeight);

                    aceGraphics = (Graphics2D) aceCanvas.getGraphics();
                    aceGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    aceGraphics.setClip(0, 0, aceCanvas.getWidth(), aceCanvas.getHeight());
                    mnemonicsDisabled = settings.DISABLE_MNEMONICS;

                    if (!mnemonicsDisabled) {
                        settings.DISABLE_MNEMONICS = true;
                        settings.fireUISettingsChanged();
                    }
                }

                @Override
                public void focusLost(FocusEvent e) {
                    reset();
                    if (!mnemonicsDisabled) {
                        settings.DISABLE_MNEMONICS = false;
                        settings.fireUISettingsChanged();
                    }
                }
            });
        }

        //todo: clean up keys
        @Override
        protected void processKeyEvent(final KeyEvent e) {

            //todo: refactor to behaviors, just spiking for now
            boolean isSpecialChar = false;
            switch (e.getKeyCode()) {
                case KeyEvent.VK_HOME:
                    findText("(?<=^\\s*)\\S", true);
                    searchMode = false;
                    isSpecialChar = true;
                    break;
                case KeyEvent.VK_END:
                    findText("\n", true);
                    searchMode = false;
                    isSpecialChar = true;
                    break;

                case KeyEvent.VK_SPACE:
                    searchMode = false;
                    isSpecialChar = true;
                    break;

                case KeyEvent.VK_BACK_SPACE:
                    popup.cancel();
                    break;
            }

            if (isSpecialChar) return;
            super.processKeyEvent(e);

            //only watch "key_typed" events
            if (e.getID() != KeyEvent.KEY_TYPED) return;

            char keyChar = e.getKeyChar();
            key = Character.getNumericValue(keyChar);

            if (!searchMode) {
                System.out.println("navigating" + e.getKeyChar());
//                System.out.println("value: " + key + " code " + keyCode + " char " + e.getKeyChar() + " location: " + e.getKeyLocation());
//                System.out.println("---------passed: " + "value: " + key + " code " + keyCode + " char " + e.getKeyChar() + " location: " + e.getKeyLocation());


                Integer offset = offsetHash.get(getLowerCaseStringFromChar(keyChar));
                if (offset != null) {
                    clearSelection();
                    if (e.isShiftDown()) {
                        editor.getSelectionModel().removeSelection();
                        int caretOffset = caretModel.getOffset();
                        int offsetModifer = 1;
                        if (offset < caretOffset) {
                            offset = offset + searchBox.getText().length();
                            offsetModifer = -2;
                        }
                        editor.getSelectionModel().setSelection(caretOffset, offset + offsetModifer);
                    } else if (e.isAltDown()) {
                        moveCaret(offset);
                        selectWordAtCaret();

                        ActionManager actionManager = ActionManagerImpl.getInstance();
                        final AnAction action = actionManager.getAction(IdeActions.ACTION_CODE_COMPLETION);
                        AnActionEvent event = new AnActionEvent(null, editor.getDataContext(), IdeActions.ACTION_CODE_COMPLETION, inputEvent.getPresentation(), ActionManager.getInstance(), 0);
                        action.actionPerformed(event);

                    } else {
                        moveCaret(offset);
                    }
                }

            }

            if (searchMode && getText().length() == 1) {
                System.out.println("searching " + e.getKeyChar() + "\n");
                findText(getText(), false);
                searchMode = false;
            }


        }

        protected void selectWordAtCaret() {
            CharSequence text = document.getCharsSequence();
            List<TextRange> ranges = new ArrayList<TextRange>();
            SelectWordUtil.addWordSelection(false, text, editor.getCaretModel().getOffset(), ranges);
            if (ranges.isEmpty()) return;

            int startWordOffset = Math.max(0, ranges.get(0).getStartOffset());
            int endWordOffset = Math.min(ranges.get(0).getEndOffset(), document.getTextLength());

            if (ranges.size() == 2 && editor.getSelectionModel().getSelectionStart() == startWordOffset &&
                    editor.getSelectionModel().getSelectionEnd() == endWordOffset) {
                startWordOffset = Math.max(0, ranges.get(1).getStartOffset());
                endWordOffset = Math.min(ranges.get(1).getEndOffset(), document.getTextLength());
            }

            editor.getSelectionModel().setSelection(startWordOffset, endWordOffset);
        }

        /*todo: I hate this. Strict mapping to my USA keyboard :(*/
        private String getLowerCaseStringFromChar(char keyChar) {

            String s = String.valueOf(keyChar);
            if (s.equals("!")) {
                return "1";

            } else if (s.equals("@")) {
                return "2";

            } else if (s.equals("#")) {
                return "3";

            } else if (s.equals("$")) {
                return "4";

            } else if (s.equals("%")) {
                return "5";

            } else if (s.equals("^")) {
                return "6";

            } else if (s.equals("&")) {
                return "7";

            } else if (s.equals("*")) {
                return "8";

            } else if (s.equals("(")) {
                return "9";

            } else if (s.equals(")")) {
                return "0";
            } else if (s.equals("_")) {
                return "-";
            } else if (s.equals("+")) {
                return "=";
            } else if (s.equals("{")) {
                return "[";
            } else if (s.equals("}")) {
                return "]";
            } else if (s.equals("|")) {
                return "\\";
            } else if (s.equals(":")) {
                return ";";
            } else if (s.equals("<")) {
                return ",";
            } else if (s.equals(">")) {
                return ".";
            } else if (s.equals("?")) {
                return "/";
            }
            return s.toLowerCase();
        }

        private void findText(String text, boolean isRegEx) {
            findModel.setStringToFind(text);
            findModel.setRegularExpressions(isRegEx);

            ApplicationManager.getApplication().runReadAction(new Runnable() {
                @Override
                public void run() {
                    searchArea = new SearchArea();
                    searchArea.invoke();
                    if (searchArea.getPsiFile() == null) return;
                    results = new ArrayList<Integer>();
                    results = findAllVisible();
                }

            });

            ApplicationManager.getApplication().invokeLater(new Runnable() {
                @Override
                public void run() {

                    final int caretOffset = editor.getCaretModel().getOffset();
                    int lineNumber = document.getLineNumber(caretOffset);
                    final int lineStartOffset = document.getLineStartOffset(lineNumber);
                    final int lineEndOffset = document.getLineEndOffset(lineNumber);


                    Collections.sort(results, new Comparator<Integer>() {
                        @Override
                        public int compare(Integer o1, Integer o2) {
                            int i1 = Math.abs(caretOffset - o1);
                            int i2 = Math.abs(caretOffset - o2);
                            boolean o1OnSameLine = o1 >= lineStartOffset && o1 <= lineEndOffset;
                            boolean o2OnSameLine = o2 >= lineStartOffset && o2 <= lineEndOffset;

                            if (i1 > i2) {
                                if (!o2OnSameLine && o1OnSameLine) {
                                    return -1;
                                }
                                return 1;
                            } else if (i1 == i2) {
                                return 0;
                            } else {
                                if (!o1OnSameLine && o2OnSameLine) {
                                    return 1;
                                }
                                return -1;
                            }
                        }
                    });

                    startResult = 0;
                    endResult = allowedCharacters.length();

                    showBalloons(results, startResult, endResult);//To change body of implemented methods use File | Settings | File Templates.
                }
            });
        }

        private void showBalloons(List<Integer> results, int start, int end) {
            hideBalloons();


            int size = results.size();
            if (end > size) {
                end = size;
            }

            for (int i = start; i < end; i++) {

                int textOffset = results.get(i);
                RelativePoint point = getPointFromVisualPosition(editor, editor.offsetToVisualPosition(textOffset));
                Point originalPoint = point.getOriginalPoint();
                char resultChar = allowedCharacters.charAt(i % allowedCharacters.length());
                final String text = String.valueOf(resultChar);

                Color defaultForeground = scheme.getDefaultForeground();
                Color defaultBackground = scheme.getDefaultBackground();

//                aceGraphics.setStroke(new BasicStroke(3));
//                aceGraphics.setColor(Color.DARK_GRAY);
//                aceGraphics.drawRect(originalPoint.x - 4, originalPoint.y -2, getFontMetrics(getFont()).stringWidth("w") + 8, editor.getLineHeight() + 4);
                aceGraphics.setColor(defaultForeground);
                aceGraphics.fillRect(originalPoint.x - 2, originalPoint.y, getFontMetrics(getFont()).stringWidth("w") + 5, editor.getLineHeight() + 1);

                aceGraphics.setFont(font);
                aceGraphics.setColor(defaultBackground);
                aceGraphics.drawString(text, originalPoint.x, originalPoint.y + scheme.getEditorFontSize());

                offsetHash.put(text, textOffset);
            }

        }


        private void hideBalloons() {
            offsetHash.clear();
        }

        @Nullable
        protected java.util.List<Integer> findAllVisible() {
            System.out.println("----- findAllVisible");
            int offset = searchArea.getOffset();
            int endOffset = searchArea.getEndOffset();
            CharSequence text = searchArea.getText();
            PsiFile psiFile = searchArea.getPsiFile();
            Rectangle visibleArea = searchArea.getVisibleArea();


            List<Integer> offsets = new ArrayList<Integer>();
            FoldRegion[] allFoldRegions = foldingModel.getAllFoldRegions();

            offsetWhile:
            while (offset < endOffset) {
//                System.out.println("offset: " + offset + "/" + endOffset);

//                System.out.println("Finding: " + findModel.getStringToFind() + " = " + offset);

                //skip folded regions. Re-think approach.
                for (FoldRegion foldRegion : allFoldRegions) {
                    if (!foldRegion.isExpanded()) {
                        if (offset >= foldRegion.getStartOffset() && offset <= foldRegion.getEndOffset()) {
//                            System.out.println("before offset: " + offset);
                            offset = foldRegion.getEndOffset() + 1;
//                            System.out.println("after offset: " + offset);
                            continue offsetWhile;
                        }
                    }
                }

                FindResult result = findManager.findString(text, offset, findModel, virtualFile);
                if (!result.isStringFound()) {
                    System.out.println(findModel.getStringToFind() + ": not found");
                    break;
                }


                System.out.println("result: " + result.toString());

                UsageInfo2UsageAdapter usageAdapter = new UsageInfo2UsageAdapter(new UsageInfo(psiFile, result.getStartOffset(), result.getEndOffset()));
                Point point = editor.logicalPositionToXY(editor.offsetToLogicalPosition(usageAdapter.getUsageInfo().getNavigationOffset()));
                if (visibleArea.contains(point)) {
                    UsageInfo usageInfo = usageAdapter.getUsageInfo();
                    int navigationOffset = usageInfo.getNavigationOffset();
                    if (navigationOffset != caretModel.getOffset()) {
                        if (!results.contains(navigationOffset)) {
                            System.out.println("Adding: " + navigationOffset + "-> " + usageAdapter.getPlainText());
                            offsets.add(navigationOffset);
                        }
                    }
                }


                final int prevOffset = offset;
                offset = result.getEndOffset();


                if (prevOffset == offset) {
                    ++offset;
                }
            }

            return offsets;
        }

        //todo: can probably refactor this out now
        public class SearchArea {
            private PsiFile psiFile;
            private CharSequence text;
            private Rectangle visibleArea;
            private int offset;
            private int endOffset;

            public PsiFile getPsiFile() {
                return psiFile;
            }

            public CharSequence getText() {
                return text;
            }

            public Rectangle getVisibleArea() {
                return visibleArea;
            }

            public int getOffset() {
                return offset;
            }

            public int getEndOffset() {
                return endOffset;
            }

            public void invoke() {
                psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
                if (psiFile == null) {
                    return;
                }

                text = document.getCharsSequence();

                JViewport viewport = editor.getScrollPane().getViewport();
                double viewportY = viewport.getViewPosition().getY();

                ScrollingModelImpl scrollingModel = (ScrollingModelImpl) editor.getScrollingModel();
                //you need the "visibleArea" to see if the point is inside of it
                visibleArea = scrollingModel.getVisibleArea();

                //TODO: Can this be more accurate?
                double linesAbove = viewportY / editor.getLineHeight();
                double visibleLines = editor.getPreferredHeight();
                if (linesAbove < 0) linesAbove = 0;
                offset = document.getLineStartOffset((int) linesAbove);
                int endLine = (int) (linesAbove + visibleLines);
                int lineCount = document.getLineCount() - 1;
                if (endLine > lineCount) {
                    endLine = lineCount;
                }
                endOffset = document.getLineEndOffset(endLine);
            }
        }
    }

    private void reset() {
        if (aceCanvas != null) {
            editor.getContentComponent().remove(aceCanvas);
            aceCanvas = null;
        }
    }
}
