package cn.crossdesk.mobile;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.SparseIntArray;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.LinkedHashSet;
import java.util.Set;

/** Three-mode remote keyboard: Native IME, Shortcut Grid, and Two-Page PC Keyboard. */
@android.annotation.SuppressLint("ViewConstructor")
final class RemoteKeyboard extends LinearLayout {
    private static final int MODE_IME = 0, MODE_SHORTCUTS = 1, MODE_COMPUTER = 2;

    private final MobileUi ui;
    private final SessionControls.Actions actions;
    final RemoteKeyboardInput editor;
    private final Runnable changed;
    private final LinearLayout toolbar, keys;
    private final TextView[] tabLabels = new TextView[3];
    private final View[] tabIndicators = new View[3];
    private final RemoteShortcutPanel shortcutPanel;

    private String hostPlatform = "unknown";
    private int mode = MODE_IME;
    private int targetMode = MODE_IME;
    private boolean functions = false;
    private boolean comboMode = false;
    private final Set<Integer> modifiers = new LinkedHashSet<>();
    private final SparseIntArray heights = new SparseIntArray();
    private boolean opened, imeVisible, measuring, switching;
    private int imeHeight, navigationBottom, layoutWidth, layoutHeight, revision, lastInsetsHeight = -1, lastInsetsKey;
    private CancellationSignal transition;

    RemoteKeyboard(Context context, MobileUi ui, SessionControls.Actions actions, Runnable changed) {
        super(context);
        this.ui = ui;
        this.actions = actions;
        this.changed = changed;
        setOrientation(VERTICAL);
        setVisibility(GONE);
        setClickable(true);

        editor = new RemoteKeyboardInput(context, new RemoteKeyboardInput.Sender() {
            public boolean edit(int count, String text) { return actions.textEdit(count, text); }
            public void key(int code) { actions.key(code); }
        });
        shortcutPanel = new RemoteShortcutPanel(context, ui, this::sendShortcut, () -> {
            layoutWidth = layoutHeight = 0;
            render();
            changed.run();
        });

        FrameLayout header = new FrameLayout(context);
        addView(header, new LayoutParams(-1, ui.dp(44)));
        header.addView(editor, new FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM));

        toolbar = ui.row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        header.addView(toolbar, new LayoutParams(-1, -1));

        LinearLayout tabGroup = ui.row();
        tabGroup.setGravity(Gravity.CENTER);
        toolbar.addView(tabGroup, new LayoutParams(0, -1, 1));

        String[] titles = {L10n.tr("输入法"), L10n.tr("快捷键"), L10n.tr("电脑键盘")};
        for (int i = 0; i < 3; i++) {
            final int tabMode = i;
            LinearLayout tab = ui.column();
            tab.setGravity(Gravity.CENTER);
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setOnClickListener(v -> { feedback(v); switchToMode(tabMode); });

            TextView label = ui.text(titles[i], 14, true);
            label.setGravity(Gravity.CENTER);
            tab.addView(label, new LayoutParams(-2, 0, 1));

            View indicator = new View(context);
            indicator.setBackgroundColor(0xFF3A8BFF);
            tab.addView(indicator, new LayoutParams(ui.dp(48), ui.dp(2.5f)));

            tabLabels[i] = label;
            tabIndicators[i] = indicator;
            tabGroup.addView(tab, new LayoutParams(0, -1, 1));
        }

        FrameLayout closeBtn = new FrameLayout(context);
        closeBtn.setContentDescription(L10n.tr("收起键盘"));
        closeBtn.setClickable(true);
        closeBtn.setFocusable(true);
        GradientDrawable closeBg = ui.background(0xFFE5E5EA, 13);
        FrameLayout circle = new FrameLayout(context);
        circle.setBackground(closeBg);
        circle.addView(ui.icon("close", 0xFF1C1C1E), new FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), Gravity.CENTER));
        closeBtn.addView(circle, new FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER));
        closeBtn.setOnClickListener(v -> { feedback(v); close(); });
        toolbar.addView(closeBtn, new LayoutParams(ui.dp(40), ui.dp(40)));

        keys = ui.column();
        keys.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4));
        addView(keys, new LayoutParams(-1, 0, 1));
        render();
    }

    private void feedback(View view) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }

    boolean isOpen() { return opened; }
    boolean isComputer() { return mode != MODE_IME; }

    void setPlatform(String value) {
        hostPlatform = RemoteShortcuts.platform(value);
        shortcutPanel.platform(hostPlatform);
        if (opened) render();
    }

    int bottomInset() { return opened && mode == MODE_IME ? imeHeight : 0; }

    private int sizeKey() {
        Configuration c = getResources().getConfiguration();
        return c.screenWidthDp * 10000 + c.screenHeightDp;
    }

    private int keyAreaHeight() {
        int height = heights.get(sizeKey(), 0);
        if (height == 0) {
            Configuration c = getResources().getConfiguration();
            height = ui.dp(c.screenWidthDp > c.screenHeightDp ? 220 : 290);
        }
        int available = getParent() instanceof View ? ((View) getParent()).getMeasuredHeight() - ui.dp(44) : height;
        return available > 0 ? Math.min(height, available) : height;
    }

    int preferredHeight() { return ui.dp(44) + (mode != MODE_IME ? keyAreaHeight() : 0); }

    void toggle() { if (opened) close(); else open(); }

    void open() {
        opened = true;
        mode = targetMode = MODE_IME;
        modifiers.clear();
        setVisibility(VISIBLE);
        render();
        changed.run();
        post(() -> { if (opened && mode == MODE_IME) changeIme(true, false, false); });
    }

    boolean close() {
        shortcutPanel.close();
        if (!opened) return false;
        opened = false;
        measuring = switching = false;
        cancelTransition();
        modifiers.clear();
        editor.stop();
        getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0);
        setVisibility(GONE);
        changed.run();
        return true;
    }

    void resetContext() { editor.finishInputContext(); }

    private void switchToMode(int nextMode) {
        if (!opened || switching || mode == nextMode) return;
        boolean wasIme = (mode == MODE_IME);
        boolean nextIme = (nextMode == MODE_IME);
        if (wasIme && !prepareShortcut()) return;

        targetMode = nextMode;
        if (!wasIme && !nextIme) {
            mode = nextMode;
            modifiers.clear();
            layoutWidth = layoutHeight = 0;
            render();
            changed.run();
            return;
        }

        switching = true;
        modifiers.clear();
        if (!nextIme) {
            editor.pause();
            mode = nextMode;
            layoutWidth = layoutHeight = 0;
            render();
            changed.run();
        } else {
            editor.start();
        }

        if (!nextIme && Build.VERSION.SDK_INT >= 30 && isHardwareAccelerated()) {
            int token = revision;
            getViewTreeObserver().registerFrameCommitCallback(() -> post(() -> {
                if (opened && switching && targetMode != MODE_IME && token == revision) {
                    changeIme(false, true, false);
                }
            }));
            invalidate();
        } else {
            changeIme(nextIme, true, false);
        }
    }

    private boolean prepareShortcut() {
        if (mode != MODE_IME) return true;
        if (editor.hasPendingComposition()) {
            Toast.makeText(getContext(), L10n.tr("请先选词或取消组词"), Toast.LENGTH_SHORT).show();
            return false;
        }
        return editor.prepareShortcut();
    }

    private void sendShortcut(RemoteShortcuts.Item item) {
        if (!opened || switching || !item.platform.equals(hostPlatform) || !prepareShortcut()) return;
        modifiers.clear();
        if (item.service) actions.secureAttention();
        else actions.chord(item.key, item.modifiers);
    }

    private void finishSwitch() {
        if (!switching) return;
        switching = false;
        if (mode != targetMode) {
            mode = targetMode;
            layoutWidth = layoutHeight = 0;
            render();
        }
        changed.run();
    }

    void updateInsets(int height, boolean visible, int navigation) {
        int key = sizeKey();
        if (lastInsetsHeight == height && lastInsetsKey == key && imeVisible == visible && navigationBottom == navigation) return;
        lastInsetsHeight = height;
        lastInsetsKey = key;
        navigationBottom = navigation;
        imeHeight = Build.VERSION.SDK_INT >= 30 ? Math.max(0, height) : 0;
        boolean wasVisible = imeVisible;
        imeVisible = visible;
        if (visible && height > ui.dp(80)) {
            heights.put(sizeKey(), height);
            if (measuring) {
                measuring = false;
                post(() -> { if (opened && mode != MODE_IME) changeIme(false, true, false); });
            }
        }
        if (switching && visible != (targetMode != MODE_IME) && (!visible || height > ui.dp(80))) finishSwitch();
        if (opened && mode == MODE_IME && !switching && wasVisible && !visible && transition == null) { close(); return; }
        if (opened) {
            renderKeysIfNeeded(getMeasuredWidth());
            changed.run();
        }
    }

    private void cancelTransition() {
        revision++;
        if (transition != null) {
            CancellationSignal old = transition;
            transition = null;
            old.cancel();
        }
    }

    private void changeIme(boolean show, boolean immediate, boolean measureOnly) {
        cancelTransition();
        if (!opened) return;
        measuring = measureOnly;
        if (Build.VERSION.SDK_INT >= 30 && immediate && getWindowInsetsController() != null) {
            if (show) editor.start();
            else if (measureOnly) editor.requestFocus();
            int token = revision;
            transition = new CancellationSignal();
            getWindowInsetsController().controlWindowInsetsAnimation(WindowInsets.Type.ime(), 0, null, transition, new WindowInsetsAnimationControlListener() {
                public void onReady(WindowInsetsAnimationController controller, int types) {
                    if (token != revision || !opened) { controller.finish(false); return; }
                    int height = controller.getShownStateInsets().bottom - navigationBottom;
                    if (height > ui.dp(80)) {
                        heights.put(sizeKey(), height);
                        measuring = false;
                        if (!measureOnly) { imeHeight = show ? height : 0; finishSwitch(); }
                        controller.finish(show && !measureOnly);
                    } else if (measureOnly) {
                        controller.finish(true);
                    } else controller.finish(show);
                    renderKeysIfNeeded(getMeasuredWidth());
                    changed.run();
                }
                public void onFinished(WindowInsetsAnimationController controller) { if (token == revision) transition = null; }
                public void onCancelled(WindowInsetsAnimationController controller) {
                    if (token != revision) return;
                    transition = null;
                    if (opened) normalIme(show || measureOnly);
                }
            });
        } else normalIme(show || measureOnly);
    }

    private void normalIme(boolean show) {
        InputMethodManager manager = getContext().getSystemService(InputMethodManager.class);
        if (show) {
            if (mode != MODE_IME) editor.requestFocus();
            else editor.start();
            manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        } else {
            manager.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        }
    }

    private void render() {
        setBackgroundColor(0xFF141519);
        toolbar.setBackgroundColor(0xFF1E1F24);
        for (int i = 0; i < 3; i++) {
            boolean selected = (mode == i);
            tabLabels[i].setTextColor(selected ? 0xFF3A8BFF : 0xFFE5E5EA);
            tabIndicators[i].setVisibility(selected ? VISIBLE : INVISIBLE);
        }
        keys.setVisibility(mode != MODE_IME ? VISIBLE : GONE);
        layoutWidth = layoutHeight = 0;
        renderKeysIfNeeded(getMeasuredWidth());
    }

    private void renderKeysIfNeeded(int width) {
        if (mode == MODE_IME) return;
        int height = keyAreaHeight();
        if (layoutWidth == width && layoutHeight == height) return;
        layoutWidth = width;
        layoutHeight = height;
        keys.removeAllViews();

        if (mode == MODE_SHORTCUTS) {
            keys.addView(shortcutPanel.create(), new LayoutParams(-1, -1));
            return;
        }

        // Top modifier bar: [ ] 组合键模式 + Ctrl + Shift + Alt + Win
        LinearLayout modBar = ui.row();
        modBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout comboWrap = ui.row();
        comboWrap.setGravity(Gravity.CENTER_VERTICAL);
        comboWrap.setPadding(ui.dp(6), 0, ui.dp(8), 0);
        CheckBox comboBox = new CheckBox(getContext());
        comboBox.setChecked(comboMode);
        comboBox.setButtonTintList(android.content.res.ColorStateList.valueOf(comboMode ? 0xFF3A8BFF : 0xFFD1D1D6));
        comboBox.setOnCheckedChangeListener((btn, checked) -> {
            comboMode = checked;
            if (!checked) { modifiers.clear(); layoutWidth = layoutHeight = 0; render(); }
        });
        comboWrap.addView(comboBox, ui.size(28, 28));
        TextView comboText = ui.text(L10n.tr("组合键模式"), 12, false);
        comboText.setTextColor(0xFFE5E5EA);
        comboWrap.addView(comboText);
        comboWrap.setOnClickListener(v -> comboBox.toggle());
        modBar.addView(comboWrap, new LayoutParams(-2, -1));

        String[] mods = {"Ctrl", "Shift", "Alt", "Win"};
        for (String m : mods) {
            int code = modifier(m);
            boolean active = modifiers.contains(code);
            TextView modBtn = ui.action(m.equals("Win") ? RemoteShortcuts.modifierName(91, hostPlatform) : m,
                    active ? Color.WHITE : 0xFFE5E5EA, () -> toggleModifier(code));
            modBtn.setTextSize(13);
            modBtn.setMinHeight(0);
            modBtn.setTypeface(android.graphics.Typeface.DEFAULT);
            GradientDrawable bg = ui.background(active ? 0xFF3A8BFF : 0xFF282A30, 6);
            if (active) bg.setStroke(ui.dp(1), 0xFF60A5FA);
            modBtn.setBackground(bg);
            LayoutParams lp = new LayoutParams(0, -1, 1);
            lp.setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2));
            modBar.addView(modBtn, lp);
        }
        keys.addView(modBar, new LayoutParams(-1, ui.dp(38)));

        // Keyboard rows (4 rows, 10 columns each)
        LinearLayout grid = ui.column();
        keys.addView(grid, new LayoutParams(-1, 0, 1));

        String[][] rows;
        if (!functions) {
            rows = new String[][]{
                {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
                {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
                {"A", "S", "D", "F", "G", "H", "J", "K", "L", "⌫"},
                {"Z", "X", "C", "V", "B", "N", "M", "Space", "Enter"}
            };
        } else {
            rows = new String[][]{
                {"_", "+", "{", "}", "|", ":", "\"", "<", ">", "?"},
                {"F1", "F2", "F3", "F4", "PrtScr", "ScrLK", "Pause", "Esc", "Tab", "~"},
                {"F5", "F6", "F7", "F8", "Ins", "Home", "PgUp", "Caps", "▲", ""},
                {"F9", "F10", "F11", "F12", "Del", "End", "PgDn", "◄", "▼", "►"}
            };
        }
        for (String[] row : rows) {
            grid.addView(buildKeyRow(row), new LayoutParams(-1, 0, 1));
        }

        // Bottom page dots indicator (clickable to flip between page 1 & page 2)
        LinearLayout dots = ui.row();
        dots.setGravity(Gravity.CENTER);
        dots.setClickable(true);
        dots.setOnClickListener(v -> { functions = !functions; layoutWidth = layoutHeight = 0; render(); });
        for (int i = 0; i < 2; i++) {
            final boolean targetFn = (i == 1);
            View dot = new View(getContext());
            dot.setBackground(ui.background((functions == targetFn) ? Color.WHITE : 0xFF555761, 2));
            dot.setOnClickListener(v -> {
                if (functions != targetFn) { functions = targetFn; layoutWidth = layoutHeight = 0; render(); }
            });
            LayoutParams dp = new LayoutParams(ui.dp(18), ui.dp(3.5f));
            dp.setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(2));
            dots.addView(dot, dp);
        }
        keys.addView(dots, new LayoutParams(-1, ui.dp(16)));
    }

    private LinearLayout buildKeyRow(String[] values) {
        LinearLayout line = ui.row();
        line.setGravity(Gravity.CENTER);
        for (String value : values) {
            LayoutParams p;
            if (value.isEmpty()) {
                p = new LayoutParams(0, -1, 1);
                p.setMargins(ui.dp(2.5f), ui.dp(2.5f), ui.dp(2.5f), ui.dp(2.5f));
                line.addView(new View(getContext()), p);
                continue;
            }
            float weight = value.equals("Space") ? 1.5f : value.equals("Enter") ? 1.5f : 1f;
            String displayLabel = formatKeyLabel(value);
            TextView key = ui.action(displayLabel, Color.WHITE, () -> { feedback(this); pressKey(value); });
            key.setPadding(0, 0, 0, 0);
            key.setTypeface(android.graphics.Typeface.DEFAULT);
            key.setTextSize(displayLabel.length() > 3 ? 11 : displayLabel.contains("\n") ? 11 : 14);
            key.setLineSpacing(0, 0.85f);
            key.setMinHeight(0);
            key.setMaxLines(2);

            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_pressed}, ui.background(0xFF4A4D57, 6));
            bg.addState(new int[]{}, ui.background(0xFF282A30, 6));
            key.setBackground(bg);

            p = new LayoutParams(0, -1, weight);
            p.setMargins(ui.dp(2.5f), ui.dp(2.5f), ui.dp(2.5f), ui.dp(2.5f));
            line.addView(key, p);
        }
        return line;
    }

    private String formatKeyLabel(String v) {
        return switch (v) {
            case "_" -> "_\n-";
            case "+" -> "+\n=";
            case "{" -> "{\n[";
            case "}" -> "}\n]";
            case "|" -> "|\n\\";
            case ":" -> ":\n;";
            case "\"" -> "\"\n'";
            case "<" -> "<\n,";
            case ">" -> ">\n.";
            case "?" -> "?\n/";
            case "~" -> "~\n`";
            default -> v;
        };
    }

    private void toggleModifier(int code) {
        if (!modifiers.add(code)) modifiers.remove(code);
        layoutWidth = layoutHeight = 0;
        render();
    }

    private static int modifier(String value) {
        return switch (value) {
            case "Shift" -> 16;
            case "Ctrl" -> 17;
            case "Alt" -> 18;
            case "Win" -> 91;
            default -> 0;
        };
    }

    private void pressKey(String value) {
        int code = switch (value) {
            case "Space" -> 32;
            case "Enter" -> 13;
            case "⌫" -> 8;
            case "Esc" -> 27;
            case "Tab" -> 9;
            case "Caps" -> 20;
            case "Ins" -> 45;
            case "Del" -> 46;
            case "Home" -> 36;
            case "End" -> 35;
            case "PgUp" -> 33;
            case "PgDn" -> 34;
            case "PrtScr" -> 44;
            case "ScrLK" -> 145;
            case "Pause" -> 19;
            case "▲" -> 38;
            case "▼" -> 40;
            case "◄" -> 37;
            case "►" -> 39;
            case "_" -> 189;
            case "+" -> 187;
            case "{" -> 219;
            case "}" -> 221;
            case "|" -> 220;
            case ":" -> 186;
            case "\"" -> 222;
            case "<" -> 188;
            case ">" -> 190;
            case "?" -> 191;
            case "~" -> 192;
            default -> value.startsWith("F") && value.length() <= 3
                    ? 111 + Integer.parseInt(value.substring(1))
                    : KeyMap.ascii(value.toLowerCase(java.util.Locale.ROOT).charAt(0)) & 255;
        };
        actions.chord(code, modifiers.stream().mapToInt(Integer::intValue).toArray());
        if (!comboMode && !modifiers.isEmpty()) {
            modifiers.clear();
            layoutWidth = layoutHeight = 0;
            render();
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        renderKeysIfNeeded(MeasureSpec.getSize(widthSpec));
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (opened && mode != MODE_IME && (width != oldWidth || height != oldHeight) && heights.get(sizeKey(), 0) == 0) {
            post(() -> {
                if (opened && mode != MODE_IME && !switching && !measuring && transition == null && heights.get(sizeKey(), 0) == 0) {
                    changeIme(false, true, true);
                }
            });
        }
    }

    @Override protected void onDetachedFromWindow() {
        close();
        super.onDetachedFromWindow();
    }
}
