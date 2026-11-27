package com.hdz.wearcontrol;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 瑞士国际主义风格纯黑 HUD 布局与非触屏焦点/快捷键导航辅助:
 * 面向智能眼镜（光波导零反光纯黑底）、XR 头显与 Android TV（3 米外高对比克莱因蓝焦点）。
 */
public final class SwissHudHelper {
    public static final int COLOR_BG = 0xFF000000;
    public static final int COLOR_SURFACE = 0xFF0C0E14;
    public static final int COLOR_BORDER = 0xFF262B36;
    public static final int COLOR_IKB = 0xFF002FA7;
    public static final int COLOR_FOCUS_BLUE = 0xFF3B82F6;
    public static final int COLOR_TEXT = 0xFFFFFFFF;
    public static final int COLOR_TEXT_DIM = 0xFFB0B8C8;

    private static final ColorStateList CONTROL_TEXT = new ColorStateList(
            new int[][] {
                    {android.R.attr.state_selected}, {android.R.attr.state_focused},
                    {android.R.attr.state_pressed}, {}
            },
            new int[] {COLOR_TEXT, COLOR_TEXT, COLOR_TEXT, COLOR_TEXT_DIM});

    private static final float FOCUS_SCALE = 1.02f;
    private static final String HEADER_BRAND = "WEAREARS // HUD";
    private static final String HEADER_HINTS = "DPAD \u00B7 OK \u00B7 1/2/3";

    /**
     * 镜腿滑动、电视遥控器、VR 手柄与蓝牙智能戒指统一快捷键回调。
     */
    public interface HudKeyActions {
        /** 直接切换降噪三态（{@link Controls#MODE_ANC} / {@link Controls#MODE_TRANS} / {@link Controls#MODE_OFF}） */
        void onSetNoiseMode(int mode);

        /** 刷新耳机状态 */
        void onRefresh();

        /** 循环切换降噪模式 */
        void onCycleNoiseMode();

        /**
         * 当滑块/降噪条处于聚焦态时响应左右方向键步进。
         *
         * @param delta -1（向左/减小）或 +1（向右/增大）
         * @return true 表示已消费按键；false 放行系统默认 DPAD 焦点移动
         */
        default boolean onStepFocusedControl(int delta) {
            return false;
        }
    }

    private SwissHudHelper() {
    }

    /**
     * 将状态栏与控制栏包裹为瑞士国际主义风格纯黑双栏 HUD 容器:
     * <ul>
     *   <li>顶部紧凑瑞士网格信息条（IKB 蓝小方块 + "WEAREARS // HUD" + "DPAD · OK · 1/2/3"）+ 1dp 细线</li>
     *   <li>左 38% 固定状态与电量栏 + 1dp 竖向细线分割</li>
     *   <li>右 62% 可垂直滚动控制栏（支持 DPAD 焦点自动滚入视野）</li>
     * </ul>
     */
    public static LinearLayout wrapAsSwissHudSplit(Context ctx, View statusPane, View controlsPane) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 1. 顶部瑞士网格信息条
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(COLOR_BG);
        int hPadX = dp(ctx, 18);
        int hPadY = dp(ctx, 10);
        header.setPadding(hPadX, hPadY, hPadX, hPadY);

        View ikbSquare = new View(ctx);
        ikbSquare.setBackgroundColor(COLOR_IKB);
        int sq = dp(ctx, 10);
        LinearLayout.LayoutParams sqLp = new LinearLayout.LayoutParams(sq, sq);
        sqLp.setMarginEnd(dp(ctx, 10));
        header.addView(ikbSquare, sqLp);

        TextView brandView = new TextView(ctx);
        brandView.setText(HEADER_BRAND);
        brandView.setTextColor(COLOR_TEXT);
        brandView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        brandView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        brandView.setLetterSpacing(0.12f);
        brandView.setSingleLine(true);
        header.addView(brandView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        View spacer = new View(ctx);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView hintView = new TextView(ctx);
        hintView.setText(HEADER_HINTS);
        hintView.setTextColor(COLOR_TEXT_DIM);
        hintView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        hintView.setLetterSpacing(0.06f);
        hintView.setSingleLine(true);
        header.addView(hintView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // 顶栏下方 1dp 瑞士水平细线
        View topRule = new View(ctx);
        topRule.setBackgroundColor(COLOR_BORDER);
        root.addView(topRule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(ctx, 1))
        ));

        // 2. 主体左右双栏（左 38% 固定状态栏 | 1dp 竖线 | 右 62% 滚动控制栏）
        LinearLayout splitRow = new LinearLayout(ctx);
        splitRow.setOrientation(LinearLayout.HORIZONTAL);
        splitRow.setBackgroundColor(COLOR_BG);

        int panePad = dp(ctx, 16);

        LinearLayout leftColumn = new LinearLayout(ctx);
        leftColumn.setOrientation(LinearLayout.VERTICAL);
        leftColumn.setGravity(Gravity.CENTER_VERTICAL);
        leftColumn.setBackgroundColor(COLOR_BG);
        leftColumn.setPadding(panePad, panePad, panePad, panePad);
        if (statusPane != null) {
            detachFromParent(statusPane);
            leftColumn.addView(statusPane, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
        splitRow.addView(leftColumn, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                0.38f
        ));

        View verticalRule = new View(ctx);
        verticalRule.setBackgroundColor(COLOR_BORDER);
        splitRow.addView(verticalRule, new LinearLayout.LayoutParams(
                Math.max(1, dp(ctx, 1)),
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ScrollView rightScroll = new ScrollView(ctx);
        rightScroll.setBackgroundColor(COLOR_BG);
        rightScroll.setFillViewport(true);
        rightScroll.setFocusable(false);
        rightScroll.setFocusableInTouchMode(false);
        rightScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        rightScroll.setVerticalScrollBarEnabled(true);
        rightScroll.setHorizontalScrollBarEnabled(false);
        rightScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rightScroll.setSmoothScrollingEnabled(true);

        LinearLayout rightInner = new LinearLayout(ctx);
        rightInner.setOrientation(LinearLayout.VERTICAL);
        rightInner.setBackgroundColor(COLOR_BG);
        rightInner.setPadding(panePad, panePad, panePad, panePad);
        rightInner.setClipToPadding(false);
        rightInner.setClipChildren(false);
        if (controlsPane != null) {
            detachFromParent(controlsPane);
            rightInner.addView(controlsPane, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
        rightScroll.addView(rightInner, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        splitRow.addView(rightScroll, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                0.62f
        ));

        root.addView(splitRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        if (controlsPane != null) {
            applyFocusTree(ctx, controlsPane);
        }
        return root;
    }

    /**
     * 为静态信息卡片套用纯黑/极深炭 0dp 直角瑞士卡片样式（零阴影零渐变）。
     */
    public static void applySwissCardStyle(View card) {
        if (card == null) {
            return;
        }
        card.setBackgroundResource(R.drawable.bg_swiss_hud_card);
        card.setElevation(0f);
        card.setStateListAnimator(null);
    }

    /**
     * 为可点击控件启用非触屏 DPAD 焦点与瑞士高对比聚焦态（0dp 直角 + 2dp 克莱因亮蓝描边 + 聚焦微缩放）。
     */
    public static void applySwissFocus(Context ctx, View target) {
        if (ctx == null || target == null) {
            return;
        }
        target.setFocusable(true);
        target.setFocusableInTouchMode(false);
        target.setBackgroundResource(R.drawable.bg_swiss_hud_focus);
        if (target instanceof TextView) ((TextView) target).setTextColor(CONTROL_TEXT);
        target.setElevation(0f);
        target.setStateListAnimator(null);

        final View.OnFocusChangeListener prev = target.getOnFocusChangeListener();
        if (!(prev instanceof SwissFocusListener)) {
            target.setOnFocusChangeListener(new SwissFocusListener(prev));
        }
        updateFocusVisual(target, target.hasFocus());
    }

    /**
     * 递归扫描视图树，为所有可交互按钮、开关、滑块与可点击卡片套用瑞士高对比焦点样式。
     */
    public static void applyFocusTree(Context ctx, View root) {
        if (ctx == null || root == null) {
            return;
        }
        if (root instanceof ScrollView || root instanceof HorizontalScrollView) {
            root.setFocusable(false);
            root.setFocusableInTouchMode(false);
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyFocusTree(ctx, vg.getChildAt(i));
            }
            return;
        }

        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            boolean hasInteractiveChild = hasInteractiveDescendant(vg);
            if (!hasInteractiveChild && vg.isClickable()) {
                applySwissFocus(ctx, vg);
                return;
            }
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyFocusTree(ctx, vg.getChildAt(i));
            }
            return;
        }

        if (isInteractiveLeaf(root)) {
            applySwissFocus(ctx, root);
        }
    }

    /**
     * 在无触屏 HUD 启动后自动聚焦首个可见可用交互控件，避免首屏无焦点提示。
     */
    public static boolean requestInitialFocus(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE) {
            return false;
        }
        if (root.isFocusable() && root.isEnabled() && !(root instanceof ScrollView)
                && !(root instanceof HorizontalScrollView)) {
            return root.requestFocus();
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (requestInitialFocus(vg.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 统一处理镜腿滑动、电视遥控器、VR 手柄与蓝牙智能戒指快捷键。
     *
     * @return true 表示事件已消费；false 交给系统默认分发
     */
    public static boolean handleHudKeyEvent(KeyEvent event, HudKeyActions actions) {
        if (event == null || actions == null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        int code = event.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_NUMPAD_1:
                if (event.getRepeatCount() == 0) {
                    actions.onSetNoiseMode(Controls.MODE_ANC);
                }
                return true;
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_NUMPAD_2:
                if (event.getRepeatCount() == 0) {
                    actions.onSetNoiseMode(Controls.MODE_TRANS);
                }
                return true;
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_NUMPAD_3:
                if (event.getRepeatCount() == 0) {
                    actions.onSetNoiseMode(Controls.MODE_OFF);
                }
                return true;
            case KeyEvent.KEYCODE_R:
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0) {
                    actions.onRefresh();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (event.getRepeatCount() == 0) {
                    actions.onCycleNoiseMode();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return actions.onStepFocusedControl(-1);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return actions.onStepFocusedControl(1);
            default:
                return false;
        }
    }

    private static boolean isInteractiveLeaf(View v) {
        return v.isClickable()
                || v instanceof Button
                || v instanceof CompoundButton
                || v instanceof SeekBar;
    }

    private static boolean hasInteractiveDescendant(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup childGroup = (ViewGroup) child;
                if (childGroup.isClickable() || hasInteractiveDescendant(childGroup)) {
                    return true;
                }
            } else if (isInteractiveLeaf(child)) {
                return true;
            }
        }
        return false;
    }

    private static void detachFromParent(View view) {
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
    }

    private static void updateFocusVisual(View v, boolean hasFocus) {
        float scale = hasFocus ? FOCUS_SCALE : 1.0f;
        v.setScaleX(scale);
        v.setScaleY(scale);
    }

    private static int dp(Context ctx, int value) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static final class SwissFocusListener implements View.OnFocusChangeListener {
        private final View.OnFocusChangeListener delegate;

        SwissFocusListener(View.OnFocusChangeListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            updateFocusVisual(v, hasFocus);
            if (delegate != null) {
                delegate.onFocusChange(v, hasFocus);
            }
        }
    }
}
