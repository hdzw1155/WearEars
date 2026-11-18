package com.hdz.wearcontrol;

import android.app.Activity;
import android.text.Spannable;
import android.text.SpannableString;
import android.view.View;
import android.widget.TextView;

/** 三种电池画法共用一个数据入口，断连清空只影响当前 UI。 */
final class BatteryPanel implements MainPanel {
    private final Activity activity;
    private final int style;
    private final SuprematistDialView dial;
    /** 新系列: 一块自绘的电量主视图, 替代旧的卡片/环/液位块 */
    private final NeoBatteryView neo;
    private final TextView[] labels = new TextView[3];
    private final BatteryBarView[] bars = new BatteryBarView[3];
    private final BatteryRectRingView[] rings = new BatteryRectRingView[3];
    private final BatteryBlockView[] blocks = new BatteryBlockView[3];
    private final View[] bolts = new View[3];

    public BatteryPanel(Activity activity, MainPanel.Actions actions) {
        this.activity = activity;
        style = AppPrefs.uiStyle(activity);
        dial = activity.findViewById(R.id.sup_dial);
        neo = activity.findViewById(R.id.neo_battery);
        if (neo != null) {
            int fam = AppPrefs.family(style);
            View neoCard = activity.findViewById(R.id.neo_battery_card);
            if (fam == AppPrefs.STYLE_NEW && neoCard != null) {
                neoCard.setPadding(0, 0, 0, 0);
            } else if (fam == AppPrefs.STYLE_CLASSIC) {
                visible(R.id.battery_header, false);
                visible(R.id.neo_classic_header, true);
                float d = activity.getResources().getDisplayMetrics().density;
                int padX = Math.round(14f * d);
                int padTop = Math.round(12f * d);
                if (neoCard != null) neoCard.setPadding(padX, padTop, padX, padTop);
                android.view.ViewGroup.MarginLayoutParams lp =
                        (android.view.ViewGroup.MarginLayoutParams) neo.getLayoutParams();
                if (lp != null) {
                    lp.topMargin = Math.round(6f * d);
                    neo.setLayoutParams(lp);
                }
            }
        }
        if (dial != null) dial.enablePanel(TwsClient.CAP_BATTERY);
        int[] percentages = {R.id.pct_l, R.id.pct_r, R.id.pct_c};
        int[] barIds = {R.id.bar_l, R.id.bar_r, R.id.bar_c};
        int[] ringIds = {R.id.ring_l, R.id.ring_r, R.id.ring_c};
        int[] blockIds = {R.id.block_l, R.id.block_r, R.id.block_c};
        int[] boltIds = {R.id.bolt_l, R.id.bolt_r, R.id.bolt_c};
        for (int i = 0; i < 3; i++) {
            labels[i] = activity.findViewById(percentages[i]);
            bars[i] = activity.findViewById(barIds[i]);
            rings[i] = activity.findViewById(ringIds[i]);
            blocks[i] = activity.findViewById(blockIds[i]);
            bolts[i] = activity.findViewById(boltIds[i]);
            if (rings[i] != null) rings[i].setCornerRadiusDp(14f);
        }
    }

    @Override public void render(DeviceState state, int pendingMode) {
        boolean single = (state.potentialCaps & TwsClient.CAP_BATTERY_SINGLE) != 0;
        if (neo != null) {
            int chg = state.connected && state.charge >= 0 ? state.charge : 0;
            if (state.connected) neo.setBattery(state.left, state.right, state.box, chg, single);
            else neo.setBattery(-1, -1, -1, 0, single);
            return;
        }
        boolean cards = style == AppPrefs.STYLE_NEW || style == AppPrefs.STYLE_SUPREME;
        visible(R.id.card_l, cards || single);
        visible(R.id.card_r, cards && !single);
        visible(R.id.card_c, cards && !single);
        visible(R.id.battery_header, cards || single);
        visible(R.id.battery_classic_card, !cards && !single);
        visible(R.id.ring_row, style == AppPrefs.STYLE_CLASSIC && !single);
        visible(R.id.block_row, style == AppPrefs.STYLE_LIQUID && !single);
        TextView leftLabel = activity.findViewById(R.id.lbl_l);
        if (leftLabel != null) leftLabel.setText(single ? R.string.label_total : R.string.label_left);
        int flags = state.connected && state.charge >= 0 ? state.charge : 0;
        int[] values = state.connected ? new int[]{state.left, state.right, state.box} : new int[]{-1, -1, -1};
        if (dial != null) dial.setBattery(values[0], values[1], values[2],
                (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, single);
        for (int i = 0; i < 3; i++) renderCell(i, values[i], (flags & (1 << i)) != 0);
    }

    private void renderCell(int index, int value, boolean charging) {
        boolean known = DeviceState.known(value), supreme = style == AppPrefs.STYLE_SUPREME;
        if (labels[index] != null) {
            if (known) {
                String num = String.valueOf(value);
                SpannableString text = new SpannableString(index == 0 ? num + "%" : "%" + num);
                int start = index == 0 ? num.length() : 0;
                text.setSpan(new android.text.style.RelativeSizeSpan(0.58f), start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                labels[index].setText(text);
            } else labels[index].setText("--");
            labels[index].setTextColor(known ? (supreme ? 0xFF101316 : 0xFFF2F2F5) : (supreme ? 0xFF8C939E : 0x61F2F2F5));
        }
        if (bars[index] != null) {
            if (supreme) { bars[index].setTrackColor(0xFFE2E2DC); bars[index].setCornerRadiusDp(1f); }
            bars[index].setPercent(known ? value : -1);
            int color = supreme ? 0xFF2E7D52 : 0xFF4CC38A;
            if (charging) color = supreme ? 0xFFD97706 : 0xFFF5A524;
            else if (known && value <= 20) color = supreme ? 0xFFC9433E : 0xFFF0524F;
            bars[index].setFillColor(color);
        }
        if (rings[index] != null) rings[index].setBattery(value, charging);
        if (blocks[index] != null) blocks[index].setBattery(value, charging);
        if (bolts[index] != null) bolts[index].setVisibility(style != AppPrefs.STYLE_CLASSIC && known && charging ? View.VISIBLE : View.GONE);
    }

    private void visible(int id, boolean show) {
        View view = activity.findViewById(id);
        if (view != null) view.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
