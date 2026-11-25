package com.hdz.wearcontrol;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class MainPanels {
    interface OptionSelectListener {
        void onOptionSelected(OptionItem option);
    }

    private final List<MainPanel> panels;

    MainPanels(Activity activity, MainPanel.Actions actions) {
        panels = Arrays.asList(
                new BatteryPanel(activity, actions),
                new NoisePanel(activity, actions),
                new VolumePanel(activity, actions),
                new EffectsPanel(activity, actions),
                new DeviceFeaturesPanel(activity, actions));
    }

    void render(DeviceState state, int pending) { for (MainPanel panel : panels) panel.render(state, pending); }
    void resume() { for (MainPanel panel : panels) panel.resume(); }
    void pause() { for (MainPanel panel : panels) panel.pause(); }
    void volume(int level, int max) { for (MainPanel panel : panels) panel.volume(level, max); }
    void refreshVolume() { for (MainPanel panel : panels) panel.refreshVolume(); }

    /** Renders OptionItem chips into horizontal SnapChipRow or vertical multi-row grid containers. */
    static int renderOptionChips(Context ctx, LinearLayout row, SnapChipRow scroller,
                                 OptionItem[] options, int activeId, boolean enabled,
                                 boolean pending, int uiStyle, boolean english,
                                 boolean compact, int lastSelection,
                                 final OptionSelectListener listener) {
        if (row == null) return -1;
        OptionItem[] items = options == null ? new OptionItem[0] : options;
        if (scroller != null) scroller.setCardColor(NeoStyle.cardColor(uiStyle));

        List<TextView> chips = collectOrBuildChips(ctx, row, items, compact);
        int selectedIndex = -1;
        for (int i = 0; i < items.length; i++) {
            final OptionItem opt = items[i];
            TextView chip = chips.get(i);
            boolean selected = opt.id == activeId;
            chip.setVisibility(View.VISIBLE);
            chip.setText(opt.label(english));
            chip.setEnabled(enabled && !pending);
            chip.setAlpha(enabled ? 1f : 0.45f);
            NeoStyle.styleChip(chip, uiStyle, selected);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (listener != null) listener.onOptionSelected(opt);
                }
            });
            if (selected) selectedIndex = i;
        }
        for (int i = items.length; i < chips.size(); i++) {
            TextView extra = chips.get(i);
            extra.setVisibility(View.GONE);
            extra.setOnClickListener(null);
        }
        if (scroller != null && selectedIndex != lastSelection) scroller.select(selectedIndex, true);
        return selectedIndex;
    }

    private static List<TextView> collectOrBuildChips(Context ctx, LinearLayout row,
                                                      OptionItem[] items, boolean compact) {
        if (row.getOrientation() == LinearLayout.VERTICAL) {
            return prepareVerticalGridChips(ctx, row, items);
        }
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView) chips.add((TextView) child);
        }
        while (chips.size() < items.length) {
            TextView chip = createHorizontalChip(ctx, compact, !chips.isEmpty());
            row.addView(chip);
            chips.add(chip);
        }
        return chips;
    }

    private static List<TextView> prepareVerticalGridChips(Context ctx, LinearLayout grid, OptionItem[] items) {
        StringBuilder sig = new StringBuilder();
        sig.append(items.length).append(':');
        for (OptionItem item : items) sig.append(item.id).append(',');
        String key = sig.toString();
        Object prev = grid.getTag(R.id.deepx_row);
        if (key.equals(prev) || (items.length <= 5 && prev == null && collectNestedTextViews(grid).size() >= items.length)) {
            List<TextView> existing = collectNestedTextViews(grid);
            if (existing.size() >= items.length) return existing;
        }
        grid.setTag(R.id.deepx_row, key);
        grid.removeAllViews();
        List<TextView> chips = new ArrayList<>(items.length);
        if (items.length == 0) return chips;
        float d = ctx.getResources().getDisplayMetrics().density;
        int cols = items.length > 6 ? 4 : 3;
        int gap = Math.round(3f * d);
        int h = Math.round(22f * d);
        LinearLayout currentLine = null;
        for (int i = 0; i < items.length; i++) {
            if (i % cols == 0) {
                currentLine = new LinearLayout(ctx);
                currentLine.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowLp.topMargin = gap;
                grid.addView(currentLine, rowLp);
            }
            TextView chip = new TextView(ctx);
            chip.setGravity(Gravity.CENTER);
            chip.setMaxLines(1);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setTextSize(9f);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            int pad = Math.round(4f * d);
            chip.setPadding(pad, 0, pad, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
            boolean lastInRow = (i % cols == cols - 1) || (i == items.length - 1);
            if (!lastInRow) lp.setMarginEnd(gap);
            currentLine.addView(chip, lp);
            chips.add(chip);
        }
        return chips;
    }

    private static List<TextView> collectNestedTextViews(ViewGroup group) {
        List<TextView> out = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) out.add((TextView) child);
            else if (child instanceof ViewGroup) out.addAll(collectNestedTextViews((ViewGroup) child));
        }
        return out;
    }

    private static TextView createHorizontalChip(Context ctx, boolean compact, boolean hasBefore) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView chip = new TextView(ctx);
        chip.setGravity(Gravity.CENTER);
        chip.setMaxLines(1);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setMinWidth(Math.round((compact ? 48f : 58f) * d));
        int hPad = Math.round((compact ? 11f : 14f) * d);
        chip.setPadding(hPad, 0, hPad, 0);
        chip.setTextSize(compact ? 10f : 11f);
        if (AppPrefs.uiStyle(ctx) == AppPrefs.STYLE_SUPREME) chip.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round((compact ? 28f : 34f) * d));
        int side = Math.round((compact ? 4f : 6f) * d);
        if (hasBefore) lp.setMarginStart(side);
        lp.setMarginEnd(side);
        chip.setLayoutParams(lp);
        return chip;
    }
}
