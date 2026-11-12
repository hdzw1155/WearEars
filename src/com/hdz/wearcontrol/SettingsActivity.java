package com.hdz.wearcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** 所有常用选项共用旧版设置页；布局、卡片和选择态沿用 0.3.5。 */
public class SettingsActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 1;
    private static final int[] UI_IDS = {R.id.style_new, R.id.style_classic, R.id.style_liquid, R.id.style_supreme};
    private static final int[] TILE_IDS = {R.id.tile_new, R.id.tile_classic, R.id.tile_liquid, R.id.tile_supreme};
    private AppScrollView scroll;
    private ArcIndicatorView indicator;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        scroll = (AppScrollView) findViewById(R.id.settings_scroll);
        indicator = (ArcIndicatorView) findViewById(R.id.settings_indicator);
        indicator.attachTo(scroll);
        for (int style = 0; style < UI_IDS.length; style++) {
            bindStyle(UI_IDS[style], style, false);
            bindStyle(TILE_IDS[style], style, true);
        }
        bindStyle(R.id.tile_follow, AppPrefs.STYLE_FOLLOW, true);
        bindLanguage(R.id.lang_zh, AppPrefs.LANG_ZH);
        bindLanguage(R.id.lang_en, AppPrefs.LANG_EN);
        bindLanguage(R.id.lang_system, "system");
        bindScrollColor(R.id.scroll_color_dynamic, AppPrefs.SCROLL_COLOR_DYNAMIC);
        bindScrollColor(R.id.scroll_color_classic, AppPrefs.SCROLL_COLOR_CLASSIC);
        bindThemeChoice(R.id.theme_night, false);
        bindThemeChoice(R.id.theme_day, true);
        findViewById(R.id.device_choice).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { chooseDevice(); }
        });
        findViewById(R.id.volume_choice).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                AppPrefs.setShowVolume(SettingsActivity.this, !AppPrefs.showVolume(SettingsActivity.this));
                refreshChoices();
            }
        });
        refreshChoices();
    }

    @Override protected void onResume() {
        super.onResume();
        scroll.setActive(true);
        indicator.setActive(true);
    }

    @Override protected void onPause() {
        super.onPause();
        scroll.setActive(false);
        indicator.setActive(false);
    }

    private void bindStyle(int id, final int style, final boolean tile) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (tile) AppPrefs.setTileStyle(SettingsActivity.this, style);
                else AppPrefs.setUiStyle(SettingsActivity.this, style);
                refreshChoices();
                TileStore.push(SettingsActivity.this);
            }
        });
    }

    private void bindLanguage(int id, final String language) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (language.equals(AppPrefs.langChoice(SettingsActivity.this))) return;
                AppPrefs.setLang(SettingsActivity.this, language);
                TileStore.push(SettingsActivity.this);
                recreate();
            }
        });
    }

    private void bindScrollColor(int id, final int colorStyle) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                AppPrefs.setScrollColorStyle(SettingsActivity.this, colorStyle);
                if (indicator != null) indicator.refreshColorStyle();
                refreshChoices();
            }
        });
    }

    private void bindThemeChoice(int id, final boolean day) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                AppPrefs.setSettingsDayMode(SettingsActivity.this, day);
                refreshChoices();
            }
        });
    }

    private void refreshChoices() {
        boolean day = AppPrefs.settingsDayMode(this);
        applySettingsTheme(day);
        int style = AppPrefs.uiStyle(this), tile = AppPrefs.tileStyleChoice(this);
        setChoice(R.id.theme_night, !day, day);
        setChoice(R.id.theme_day, day, day);
        for (int i = 0; i < UI_IDS.length; i++) {
            setChoice(UI_IDS[i], style == i, day);
            setChoice(TILE_IDS[i], tile == i, day);
        }
        setChoice(R.id.tile_follow, tile == AppPrefs.STYLE_FOLLOW, day);
        String language = AppPrefs.langChoice(this);
        setChoice(R.id.lang_zh, AppPrefs.LANG_ZH.equals(language), day);
        setChoice(R.id.lang_en, AppPrefs.LANG_EN.equals(language), day);
        setChoice(R.id.lang_system, "system".equals(language), day);
        int scrollColor = AppPrefs.scrollColorStyle(this);
        setChoice(R.id.scroll_color_dynamic, scrollColor == AppPrefs.SCROLL_COLOR_DYNAMIC, day);
        setChoice(R.id.scroll_color_classic, scrollColor == AppPrefs.SCROLL_COLOR_CLASSIC, day);
        boolean volume = AppPrefs.showVolume(this);
        setChoice(R.id.volume_choice, volume, day);
        ((TextView) findViewById(R.id.volume_choice)).setText(
                volume ? R.string.settings_volume_hide : R.string.settings_volume_show);
        String address = AppPrefs.deviceAddress(this), label = getString(R.string.settings_device_auto);
        if (!address.isEmpty()) {
            label = address;
            if (hasBluetoothPermission()) {
                try {
                    BluetoothAdapter adapter = adapter();
                    if (adapter != null) {
                        String name = adapter.getRemoteDevice(address).getName();
                        if (name != null) label = name;
                    }
                } catch (RuntimeException ignored) { }
            }
        }
        ((TextView) findViewById(R.id.device_choice)).setText(label);
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 只读取已配对耳机，选择时不扫描也不建立后台连接。 */
    private void chooseDevice() {
        if (!hasBluetoothPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
            return;
        }
        try {
            final BluetoothAdapter adapter = adapter();
            final List<BluetoothDevice> devices = DriverRegistry.audioDevices(adapter);
            String[] labels = new String[devices.size() + 1];
            labels[0] = getString(R.string.settings_device_auto);
            String selected = AppPrefs.deviceAddress(this);
            int checked = selected.isEmpty() ? 0 : -1;
            for (int i = 0; i < devices.size(); i++) {
                BluetoothDevice device = devices.get(i);
                String name = device.getName();
                labels[i + 1] = name == null ? device.getAddress() : name;
                if (selected.equals(device.getAddress())) checked = i + 1;
            }
            new AlertDialog.Builder(this).setTitle(R.string.settings_device)
                    .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            BluetoothDevice device = which == 0 ? null : devices.get(which - 1);
                            String address = device == null ? "" : device.getAddress();
                            if (!address.equals(AppPrefs.deviceAddress(SettingsActivity.this))) {
                                AppPrefs.setDeviceAddress(SettingsActivity.this, address);
                                HeadsetController.get(SettingsActivity.this).deviceChanged();
                                // 选择不同耳机时立即清除旧快照，避免磁贴沿用另一台设备的电量。
                                try {
                                    if (device == null && adapter != null) device = DriverRegistry.findTarget(adapter);
                                    String target = device == null ? "" : device.getAddress();
                                    String old = getSharedPreferences(TileStore.PREFS, MODE_PRIVATE)
                                            .getString("tile_address", "");
                                    if (!target.equals(old)) {
                                        TileStore.selectDevice(SettingsActivity.this, target,
                                                device == null ? "" : device.getName(), 0, TwsClient.VERIF_UNTESTED);
                                        TileStore.pushNow(SettingsActivity.this);
                                    }
                                } catch (SecurityException ignored) { }
                            }
                            refreshChoices();
                            dialog.dismiss();
                        }
                    }).setNegativeButton(R.string.dialog_cancel, null).show();
        } catch (SecurityException e) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_BLUETOOTH && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) chooseDevice();
    }

    private void applySettingsTheme(boolean day) {
        View root = findViewById(R.id.settings_root);
        if (root != null) {
            root.setBackgroundColor(day ? 0xFFF4F3EE : 0xFF000000);
        }
        TextView title = (TextView) findViewById(R.id.settings_title);
        if (title != null) {
            title.setTextColor(day ? 0xFF111317 : 0xFFFFFFFF);
        }
        TextView hint = (TextView) findViewById(R.id.settings_hint);
        if (hint != null) {
            hint.setTextColor(day ? 0xFF5A606B : 0x85FFFFFF);
        }
        View list = findViewById(R.id.settings_list);
        if (list instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) list;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof LinearLayout) {
                    child.setBackgroundResource(day ? R.drawable.bg_card_day : R.drawable.bg_card);
                    View caption = ((LinearLayout) child).getChildAt(0);
                    if (caption instanceof TextView) {
                        ((TextView) caption).setTextColor(day ? 0xFF5A606B : 0x85FFFFFF);
                    }
                }
            }
        }
    }

    private void setChoice(int id, boolean selected, boolean day) {
        TextView chip = (TextView) findViewById(id);
        if (day) {
            chip.setBackgroundResource(selected ? R.drawable.bg_choice_on_day : R.drawable.bg_choice_day);
            chip.setTextColor(selected ? 0xFFFFFFFF : 0xFF111317);
        } else {
            chip.setBackgroundResource(selected ? R.drawable.bg_choice_on : R.drawable.bg_choice);
            chip.setTextColor(getResources().getColor(selected ? R.color.text : R.color.text_dim));
        }
        chip.setSelected(selected);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return scroll != null && scroll.onGenericMotionEvent(event) || super.onGenericMotionEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        return scroll != null && scroll.handleRotaryKey(keyCode) || super.onKeyDown(keyCode, event);
    }

    static Intent intent(Context ctx) { return new Intent(ctx, SettingsActivity.class); }
}
