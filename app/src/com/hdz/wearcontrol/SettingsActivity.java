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
import android.database.MatrixCursor;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.CursorAdapter;
import android.widget.EditText;
import android.widget.HeaderViewListAdapter;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** 所有常用选项共用旧版设置页；布局、卡片和选择态沿用 0.3.5。 */
public class SettingsActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 1;
    private static final String DEVICE_LABEL = "device_label";
    private static final int[] UI_IDS = {
            R.id.style_supreme,
            R.id.style_neo_new, R.id.style_neo_classic, R.id.style_neo_liquid};
    private static final int[] TILE_IDS = {
            R.id.tile_supreme,
            R.id.tile_neo_new, R.id.tile_neo_classic, R.id.tile_neo_liquid};
    private AppScrollView scroll;
    private ArcIndicatorView indicator;
    private AlertDialog deviceDialog;
    private AlertDialog meshDialog;
    private AlertDialog meshSubDialog;

    @Override protected void onDestroy() {
        LocalMeshManager.get(this).stopDiscovery();
        if (deviceDialog != null) deviceDialog.dismiss();
        if (meshDialog != null) meshDialog.dismiss();
        if (meshSubDialog != null) meshSubDialog.dismiss();
        super.onDestroy();
    }

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AppPrefs.hasDisclaimer(this)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_settings);
        applyPhoneWindowChrome();
        scroll = (AppScrollView) findViewById(R.id.settings_scroll);
        indicator = (ArcIndicatorView) findViewById(R.id.settings_indicator);
        int formOverride = AppPrefs.hostFormOverride(this);
        DeviceForm.styleScrollContainer(this, scroll, indicator, formOverride);
        if (indicator != null && DeviceForm.isWatch(this, formOverride)) {
            indicator.attachTo(scroll);
        }
        for (int style = 0; style < UI_IDS.length; style++) {
            bindStyle(UI_IDS[style], style + StyleChoice.SUPREME, false);
            bindStyle(TILE_IDS[style], style + StyleChoice.SUPREME, true);
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
        bindPhoneConnMode(R.id.conn_mode_burst, true);
        bindPhoneConnMode(R.id.conn_mode_keep, false);
        bindHostForm(R.id.form_auto, HostFormLogic.FORM_AUTO);
        bindHostForm(R.id.form_watch, HostFormLogic.FORM_WATCH);
        bindHostForm(R.id.form_phone, HostFormLogic.FORM_PHONE);
        bindHostForm(R.id.form_hud, HostFormLogic.FORM_HUD);
        bindMeshMode(R.id.mesh_mode_off, LocalMeshManager.MODE_OFF);
        bindMeshMode(R.id.mesh_mode_host, LocalMeshManager.MODE_HOST);
        bindMeshMode(R.id.mesh_mode_client, LocalMeshManager.MODE_CLIENT);
        View meshAction = findViewById(R.id.mesh_action_btn);
        if (meshAction != null) {
            meshAction.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { onMeshActionClicked(); }
            });
        }
        View bridgeChoice = findViewById(R.id.intent_bridge_choice);
        if (bridgeChoice != null) {
            bridgeChoice.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    boolean next = !AppPrefs.localIntentBridge(SettingsActivity.this);
                    AppPrefs.setLocalIntentBridge(SettingsActivity.this, next);
                    LocalControlReceiver.setBridgeEnabled(next);
                    refreshChoices();
                }
            });
        }
        findViewById(R.id.volume_choice).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                AppPrefs.setShowVolume(SettingsActivity.this, !AppPrefs.showVolume(SettingsActivity.this));
                refreshChoices();
            }
        });
        refreshChoices();
        applyPhoneInsets();
        if (isHudMode()) {
            View list = findViewById(R.id.settings_list);
            if (list != null) {
                list.post(new Runnable() {
                    @Override public void run() { SwissHudHelper.requestInitialFocus(list); }
                });
            }
        }
    }

    private boolean isHudMode() {
        return DeviceForm.isHud(this, AppPrefs.hostFormOverride(this));
    }

    private boolean isLightSurface() {
        if (isHudMode()) return false;
        return AppPrefs.settingsDayMode(this) || AppPrefs.uiStyle(this) == AppPrefs.STYLE_SUPREME;
    }

    private void applyPhoneWindowChrome() {
        if (getResources().getConfiguration().isScreenRound()) {
            return;
        }
        boolean light = isLightSurface();
        int barCol = light ? 0xFFF4F3EE : 0xFF000000;
        getWindow().setStatusBarColor(barCol);
        getWindow().setNavigationBarColor(barCol);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        decor.setSystemUiVisibility(flags);
    }

    private void applyPhoneInsets() {
        int formOverride = AppPrefs.hostFormOverride(this);
        DeviceForm.styleScrollContainer(this, scroll, indicator, formOverride);
        if (getResources().getConfiguration().isScreenRound()) {
            return;
        }
        final View root = findViewById(android.R.id.content);
        final View list = findViewById(R.id.settings_list);
        if (root == null || list == null) {
            return;
        }
        final int baseTop = list.getPaddingTop();
        final int baseBot = list.getPaddingBottom();
        final int phoneSide = Math.round(24f * getResources().getDisplayMetrics().density);
        final int maxColPx = DeviceForm.isWatch(this, formOverride) ? 0 : DeviceForm.phoneColumnWidthPx(this);
        final int[] lastInsets = new int[4];
        final Runnable syncPadding = new Runnable() {
            @Override
            public void run() {
                int avail = root.getWidth() > 0
                        ? root.getWidth()
                        : getResources().getDisplayMetrics().widthPixels;
                int contentAvail = Math.max(0, avail - lastInsets[2] - lastInsets[3]);
                int side = maxColPx > 0
                        ? Math.max(phoneSide, (contentAvail - maxColPx) / 2)
                        : phoneSide;
                int padL = lastInsets[2] + side;
                int padT = baseTop + lastInsets[0];
                int padR = lastInsets[3] + side;
                int padB = baseBot + lastInsets[1];
                if (list.getPaddingLeft() != padL || list.getPaddingTop() != padT
                        || list.getPaddingRight() != padR || list.getPaddingBottom() != padB) {
                    list.setPadding(padL, padT, padR, padB);
                }
            }
        };
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                lastInsets[0] = insets.getSystemWindowInsetTop();
                lastInsets[1] = insets.getSystemWindowInsetBottom();
                lastInsets[2] = insets.getSystemWindowInsetLeft();
                lastInsets[3] = insets.getSystemWindowInsetRight();
                syncPadding.run();
                if (indicator != null && indicator.getVisibility() == View.VISIBLE) {
                    indicator.setPadding(0, lastInsets[0], 0, lastInsets[1]);
                }
                return insets;
            }
        });
        if (maxColPx > 0) {
            root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int oldL, int oldT, int oldR, int oldB) {
                    if ((r - l) != (oldR - oldL)) {
                        syncPadding.run();
                    }
                }
            });
        }
        root.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume();
        if (scroll != null) scroll.setActive(true);
        if (indicator != null && DeviceForm.isWatch(this, AppPrefs.hostFormOverride(this))) indicator.setActive(true);
    }

    @Override protected void onPause() {
        super.onPause();
        LocalMeshManager.get(this).stopDiscovery();
        if (scroll != null) scroll.setActive(false);
        if (indicator != null && DeviceForm.isWatch(this, AppPrefs.hostFormOverride(this))) indicator.setActive(false);
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
                if (indicator != null && DeviceForm.isWatch(SettingsActivity.this,
                        AppPrefs.hostFormOverride(SettingsActivity.this))) {
                    indicator.refreshColorStyle();
                }
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

    private void bindPhoneConnMode(int id, final boolean burst) {
        View btn = findViewById(id);
        if (btn == null) return;
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (AppPrefs.phoneBurstMode(SettingsActivity.this) == burst) return;
                AppPrefs.setPhoneBurstMode(SettingsActivity.this, burst);
                ControlHub.get(SettingsActivity.this).connectionModeChanged();
                refreshChoices();
            }
        });
    }

    private void bindHostForm(int id, final int form) {
        View btn = findViewById(id);
        if (btn == null) return;
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (AppPrefs.hostFormOverride(SettingsActivity.this) == form) return;
                AppPrefs.setHostFormOverride(SettingsActivity.this, form);
                ControlHub.get(SettingsActivity.this).connectionModeChanged();
                refreshChoices();
            }
        });
    }

    private void bindMeshMode(int id, final int mode) {
        View btn = findViewById(id);
        if (btn == null) return;
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (AppPrefs.meshMode(SettingsActivity.this) == mode) return;
                AppPrefs.setMeshMode(SettingsActivity.this, mode);
                if (mode == LocalMeshManager.MODE_OFF) {
                    LocalMeshManager.get(SettingsActivity.this).stopAll();
                } else if (mode == LocalMeshManager.MODE_HOST) {
                    LocalMeshManager.get(SettingsActivity.this).pin();
                }
                refreshChoices();
            }
        });
    }

    private void onMeshActionClicked() {
        int mode = AppPrefs.meshMode(this);
        if (mode == LocalMeshManager.MODE_HOST) {
            LocalMeshManager.get(this).regeneratePin();
            refreshChoices();
        } else if (mode == LocalMeshManager.MODE_CLIENT) {
            showMeshScanDialog();
        }
    }

    private void showMeshScanDialog() {
        if (isFinishing() || isDestroyed()) return;
        if (meshDialog != null && meshDialog.isShowing()) meshDialog.dismiss();

        final LocalMeshManager mesh = LocalMeshManager.get(this);
        final List<LocalMeshManager.DiscoveredPeer> peers = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.settings_mesh_scanning));

        int dialogTheme = NeoStyle.dialogTheme(isLightSurface());
        Context dialogCtx = new ContextThemeWrapper(this, dialogTheme);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                dialogCtx, android.R.layout.simple_list_item_1, labels);
        final ListView listView = new ListView(dialogCtx);
        listView.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(this, dialogTheme)
                .setTitle(R.string.settings_mesh_scan_title)
                .setView(listView)
                .setNeutralButton(R.string.settings_mesh_manual_btn, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        showManualMeshDialog();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .create();
        meshDialog = dialog;

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (pos < 0 || pos >= peers.size()) return;
                LocalMeshManager.DiscoveredPeer peer = peers.get(pos);
                dialog.dismiss();
                showPinPromptDialog(peer);
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                mesh.stopDiscovery();
            }
        });

        mesh.startDiscovery(new LocalMeshManager.DiscoveryListener() {
            @Override public void onPeersUpdated(List<LocalMeshManager.DiscoveredPeer> updated) {
                if (meshDialog != dialog || !dialog.isShowing() || isFinishing() || isDestroyed()) return;
                peers.clear();
                labels.clear();
                if (updated == null || updated.isEmpty()) {
                    labels.add(getString(R.string.settings_mesh_scanning));
                } else {
                    for (LocalMeshManager.DiscoveredPeer p : updated) {
                        peers.add(p);
                        String state = getString(p.connected
                                ? R.string.settings_mesh_peer_connected
                                : R.string.settings_mesh_peer_idle);
                        String dev = (p.deviceName != null && !p.deviceName.isEmpty())
                                ? " · " + p.deviceName : "";
                        labels.add(p.hostName + " (" + p.ip + ":" + p.port + ")\n" + state + dev);
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });

        dialog.show();
    }

    private void showPinPromptDialog(final LocalMeshManager.DiscoveredPeer peer) {
        if (peer == null || isFinishing() || isDestroyed()) return;
        if (meshSubDialog != null && meshSubDialog.isShowing()) meshSubDialog.dismiss();

        int dialogTheme = NeoStyle.dialogTheme(isLightSurface());
        Context dialogCtx = new ContextThemeWrapper(this, dialogTheme);
        int pad = Math.round(16f * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(dialogCtx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        TextView desc = new TextView(dialogCtx);
        desc.setText(getString(R.string.settings_mesh_pin_for, peer.hostName, peer.ip, peer.port));
        box.addView(desc);

        final EditText pinInput = new EditText(dialogCtx);
        pinInput.setHint(R.string.settings_mesh_pin_hint);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        pinInput.setText(AppPrefs.meshPin(this));
        box.addView(pinInput);

        meshSubDialog = new AlertDialog.Builder(this, dialogTheme)
                .setTitle(R.string.settings_mesh_pin_title)
                .setView(box)
                .setPositiveButton(R.string.settings_mesh_save, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String pin = pinInput.getText().toString().trim();
                        if (!LocalMeshProtocol.isValidPin(pin)) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.settings_mesh_invalid_pin, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        AppPrefs.setMeshHostIp(SettingsActivity.this, peer.ip);
                        AppPrefs.setMeshHostPort(SettingsActivity.this, peer.port);
                        AppPrefs.setMeshPin(SettingsActivity.this, pin);
                        AppPrefs.setMeshMode(SettingsActivity.this, LocalMeshManager.MODE_CLIENT);
                        refreshChoices();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showManualMeshDialog() {
        if (isFinishing() || isDestroyed()) return;
        if (meshSubDialog != null && meshSubDialog.isShowing()) meshSubDialog.dismiss();

        int dialogTheme = NeoStyle.dialogTheme(isLightSurface());
        Context dialogCtx = new ContextThemeWrapper(this, dialogTheme);
        int pad = Math.round(16f * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(dialogCtx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        final EditText ipInput = new EditText(dialogCtx);
        ipInput.setHint(R.string.settings_mesh_ip_hint);
        ipInput.setSingleLine(true);
        ipInput.setText(AppPrefs.meshHostIp(this));
        box.addView(ipInput);

        final EditText portInput = new EditText(dialogCtx);
        portInput.setHint(R.string.settings_mesh_port_hint);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setSingleLine(true);
        portInput.setText(String.valueOf(AppPrefs.meshHostPort(this)));
        box.addView(portInput);

        final EditText pinInput = new EditText(dialogCtx);
        pinInput.setHint(R.string.settings_mesh_pin_hint);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        pinInput.setText(AppPrefs.meshPin(this));
        box.addView(pinInput);

        meshSubDialog = new AlertDialog.Builder(this, dialogTheme)
                .setTitle(R.string.settings_mesh_manual_title)
                .setView(box)
                .setPositiveButton(R.string.settings_mesh_save, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String ip = ipInput.getText().toString().trim();
                        String portStr = portInput.getText().toString().trim();
                        String pin = pinInput.getText().toString().trim();
                        if (!LocalMeshProtocol.isPrivateLanAddress(ip)) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.settings_mesh_invalid_ip, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int port = LocalMeshProtocol.DEFAULT_TCP_PORT;
                        if (!portStr.isEmpty()) {
                            try {
                                port = Integer.parseInt(portStr);
                            } catch (NumberFormatException ignored) {
                                port = -1;
                            }
                        }
                        if (port < 1 || port > 65535) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.settings_mesh_invalid_port, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!LocalMeshProtocol.isValidPin(pin)) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.settings_mesh_invalid_pin, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        AppPrefs.setMeshHostIp(SettingsActivity.this, ip);
                        AppPrefs.setMeshHostPort(SettingsActivity.this, port);
                        AppPrefs.setMeshPin(SettingsActivity.this, pin);
                        AppPrefs.setMeshMode(SettingsActivity.this, LocalMeshManager.MODE_CLIENT);
                        refreshChoices();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void refreshChoices() {
        boolean hud = isHudMode();
        boolean day = !hud && AppPrefs.settingsDayMode(this);
        int style = hud ? AppPrefs.STYLE_NEO_NEW : AppPrefs.uiStyle(this);
        int tile = AppPrefs.tileStyleChoice(this);
        applySettingsTheme(day, style);
        setChoice(R.id.theme_night, !day, day, style);
        setChoice(R.id.theme_day, day, day, style);
        for (int i = 0; i < UI_IDS.length; i++) {
            setChoice(UI_IDS[i], AppPrefs.uiStyle(this) == i + StyleChoice.SUPREME, day, style);
            setChoice(TILE_IDS[i], tile == i + StyleChoice.SUPREME, day, style);
        }
        setChoice(R.id.tile_follow, tile == AppPrefs.STYLE_FOLLOW, day, style);
        String language = AppPrefs.langChoice(this);
        setChoice(R.id.lang_zh, AppPrefs.LANG_ZH.equals(language), day, style);
        setChoice(R.id.lang_en, AppPrefs.LANG_EN.equals(language), day, style);
        setChoice(R.id.lang_system, "system".equals(language), day, style);
        int scrollColor = AppPrefs.scrollColorStyle(this);
        setChoice(R.id.scroll_color_dynamic, scrollColor == AppPrefs.SCROLL_COLOR_DYNAMIC, day, style);
        setChoice(R.id.scroll_color_classic, scrollColor == AppPrefs.SCROLL_COLOR_CLASSIC, day, style);

        int formOverride = AppPrefs.hostFormOverride(this);
        View phoneConnCard = findViewById(R.id.phone_conn_card);
        if (phoneConnCard != null) {
            phoneConnCard.setVisibility(DeviceForm.isPhone(this, formOverride) ? View.VISIBLE : View.GONE);
        }
        boolean burstMode = AppPrefs.phoneBurstMode(this);
        setChoice(R.id.conn_mode_burst, burstMode, day, style);
        setChoice(R.id.conn_mode_keep, !burstMode, day, style);

        setChoice(R.id.form_auto, formOverride == HostFormLogic.FORM_AUTO, day, style);
        setChoice(R.id.form_watch, formOverride == HostFormLogic.FORM_WATCH, day, style);
        setChoice(R.id.form_phone, formOverride == HostFormLogic.FORM_PHONE, day, style);
        setChoice(R.id.form_hud, formOverride == HostFormLogic.FORM_HUD, day, style);

        int meshMode = AppPrefs.meshMode(this);
        setChoice(R.id.mesh_mode_off, meshMode == LocalMeshManager.MODE_OFF, day, style);
        setChoice(R.id.mesh_mode_host, meshMode == LocalMeshManager.MODE_HOST, day, style);
        setChoice(R.id.mesh_mode_client, meshMode == LocalMeshManager.MODE_CLIENT, day, style);
        TextView meshStatus = (TextView) findViewById(R.id.mesh_status_text);
        TextView meshAction = (TextView) findViewById(R.id.mesh_action_btn);
        if (meshStatus != null && meshAction != null) {
            boolean light = day || style == AppPrefs.STYLE_SUPREME;
            meshStatus.setTextColor(light ? 0xFF5A606B : NeoStyle.textDim(style));
            if (meshMode == LocalMeshManager.MODE_HOST) {
                LocalMeshManager mesh = LocalMeshManager.get(this);
                int port = mesh.listenPort() > 0 ? mesh.listenPort() : LocalMeshProtocol.DEFAULT_TCP_PORT;
                meshStatus.setText(getString(R.string.settings_mesh_host_info,
                        mesh.localLanIp(), port, mesh.pin()));
                meshStatus.setVisibility(View.VISIBLE);
                meshAction.setText(R.string.settings_mesh_reset_pin);
                meshAction.setVisibility(View.VISIBLE);
                setChoice(R.id.mesh_action_btn, false, day, style);
            } else if (meshMode == LocalMeshManager.MODE_CLIENT) {
                String ip = AppPrefs.meshHostIp(this);
                int port = AppPrefs.meshHostPort(this);
                String pin = AppPrefs.meshPin(this);
                boolean configured = LocalMeshProtocol.isPrivateLanAddress(ip)
                        && LocalMeshProtocol.isValidPin(pin);
                meshStatus.setText(configured
                        ? getString(R.string.settings_mesh_client_configured, ip, port)
                        : getString(R.string.settings_mesh_client_unconfigured));
                meshStatus.setVisibility(View.VISIBLE);
                meshAction.setText(R.string.settings_mesh_pick_host);
                meshAction.setVisibility(View.VISIBLE);
                setChoice(R.id.mesh_action_btn, configured, day, style);
            } else {
                meshStatus.setVisibility(View.GONE);
                meshAction.setVisibility(View.GONE);
            }
        }

        boolean bridgeOn = AppPrefs.localIntentBridge(this);
        LocalControlReceiver.setBridgeEnabled(bridgeOn);
        setChoice(R.id.intent_bridge_choice, bridgeOn, day, style);
        TextView bridgeView = (TextView) findViewById(R.id.intent_bridge_choice);
        if (bridgeView != null) {
            bridgeView.setText(bridgeOn
                    ? R.string.settings_intent_bridge_on
                    : R.string.settings_intent_bridge_off);
        }

        boolean volume = AppPrefs.showVolume(this);
        setChoice(R.id.volume_choice, volume, day, style);
        ((TextView) findViewById(R.id.volume_choice)).setText(
                volume ? R.string.settings_volume_hide : R.string.settings_volume_show);
        setChoice(R.id.device_choice, !AppPrefs.deviceAddress(this).isEmpty(), day, style);
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
                } catch (RuntimeException e) { Log.w("Settings", "Cannot resolve selected device name", e); }
            }
        }
        ((TextView) findViewById(R.id.device_choice)).setText(label);

        if (hud) {
            View list = findViewById(R.id.settings_list);
            if (list != null) {
                SwissHudHelper.applyFocusTree(this, list);
            }
        }
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
        if (isFinishing() || isDestroyed() || (deviceDialog != null && deviceDialog.isShowing())) return;
        try {
            final BluetoothAdapter adapter = adapter();
            final List<BluetoothDevice> devices = DeviceSelector.audioDevices(adapter);
            final AlertDialog picker = new AlertDialog.Builder(this, NeoStyle.dialogTheme(isLightSurface()))
                    .setTitle(R.string.settings_device)
                    .setSingleChoiceItems(deviceRows(devices), checkedDevice(devices), DEVICE_LABEL,
                            new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            try {
                                selectDevice(which == 0 ? null : devices.get(which - 1));
                            } catch (SecurityException e) {
                                Log.w("Settings", "Cannot select Bluetooth device", e);
                            }
                            refreshChoices();
                            dialog.dismiss();
                        }
                    }).setNegativeButton(R.string.dialog_cancel, null).show();
            deviceDialog = picker;
            // Native single-choice rows keep the dialog theme. Append discoveries so visible choices keep their indices.
            ListAdapter listAdapter = picker.getListView().getAdapter();
            if (listAdapter instanceof HeaderViewListAdapter) {
                listAdapter = ((HeaderViewListAdapter) listAdapter).getWrappedAdapter();
            }
            final CursorAdapter rows = listAdapter instanceof CursorAdapter ? (CursorAdapter) listAdapter : null;
            picker.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface dialog) {
                    if (rows != null) rows.changeCursor(null);
                }
            });
            AudioConnections.query(this, 0, new AudioConnections.Callback() {
                @Override public void onResult(List<BluetoothDevice> connected, boolean complete) {
                    if (deviceDialog != picker || !picker.isShowing() || isFinishing() || isDestroyed()) return;
                    try {
                        int previousCount = devices.size();
                        for (BluetoothDevice device : DeviceSelector.audioDevices(adapter, connected)) {
                            if (!devices.contains(device)) devices.add(device);
                        }
                        if (rows != null && devices.size() != previousCount) {
                            rows.changeCursor(deviceRows(devices));
                            int checked = checkedDevice(devices);
                            if (checked >= 0) picker.getListView().setItemChecked(checked, true);
                        }
                    } catch (SecurityException e) {
                        Log.w("Settings", "Cannot add connected audio devices", e);
                    }
                }
            });
        } catch (SecurityException e) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
        }
    }

    private MatrixCursor deviceRows(List<BluetoothDevice> devices) {
        MatrixCursor rows = new MatrixCursor(new String[]{"_id", DEVICE_LABEL}, devices.size() + 1);
        rows.addRow(new Object[]{0, getString(R.string.settings_device_auto)});
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice device = devices.get(i);
            String name = device.getName();
            rows.addRow(new Object[]{i + 1, name == null ? device.getAddress() : name});
        }
        return rows;
    }

    private int checkedDevice(List<BluetoothDevice> devices) {
        String selected = AppPrefs.deviceAddress(this);
        if (selected.isEmpty()) return 0;
        for (int i = 0; i < devices.size(); i++) {
            if (selected.equals(devices.get(i).getAddress())) return i + 1;
        }
        return -1;
    }

    private void selectDevice(BluetoothDevice device) {
        String address = device == null ? "" : device.getAddress();
        if (address.equals(AppPrefs.deviceAddress(this))) return;
        String name = device == null ? "" : device.getName();
        AppPrefs.setDeviceAddress(this, address);
        ControlHub.get(this).deviceChanged();
        // 切换到自动模式时，等主页选定新设备再绑定磁贴地址。
        if (!address.equals(TileStore.deviceAddress(this))) {
            TileStore.selectDevice(this, address, name, 0, TwsClient.VERIF_UNTESTED);
            TileStore.pushNow(this);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_BLUETOOTH && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) chooseDevice();
    }

    private void applySettingsTheme(boolean day, int style) {
        applyPhoneWindowChrome();
        boolean light = day || style == AppPrefs.STYLE_SUPREME;
        View root = findViewById(R.id.settings_root);
        if (root != null) {
            root.setBackgroundColor(light ? 0xFFF4F3EE : 0xFF000000);
        }
        TextView title = (TextView) findViewById(R.id.settings_title);
        if (title != null) {
            title.setTextColor(light ? 0xFF111317 : 0xFFFFFFFF);
        }
        TextView hint = (TextView) findViewById(R.id.settings_hint);
        if (hint != null) {
            hint.setTextColor(light ? 0xFF5A606B : NeoStyle.textFaint(style));
        }
        int cardBg = isHudMode()
                ? R.drawable.bg_swiss_hud_card
                : (light
                        ? (style == AppPrefs.STYLE_SUPREME ? R.drawable.bg_sup_card_paper : R.drawable.bg_set_card_day)
                        : NeoStyle.cardDrawable(style));
        int captionColor = light ? 0xFF5A606B : NeoStyle.textFaint(style);
        View list = findViewById(R.id.settings_list);
        if (list instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) list;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof LinearLayout) {
                    child.setBackgroundResource(cardBg);
                    View caption = ((LinearLayout) child).getChildAt(0);
                    if (caption instanceof TextView) {
                        ((TextView) caption).setTextColor(captionColor);
                    }
                }
            }
        }
    }

    private void setChoice(int id, boolean selected, boolean day, int style) {
        TextView chip = (TextView) findViewById(id);
        if (chip == null) return;
        if (style == AppPrefs.STYLE_SUPREME) {
            chip.setBackgroundResource(selected ? R.drawable.bg_sup_chip_sel : R.drawable.bg_sup_chip);
            chip.setTextColor(selected ? 0xFFFFFFFF : 0xFF101316);
        } else if (day) {
            chip.setBackgroundResource(selected ? R.drawable.bg_set_choice_on_day : R.drawable.bg_set_choice_day);
            chip.setTextColor(selected ? 0xFFFFFFFF : 0xFF111317);
        } else {
            NeoStyle.styleChip(chip, style, selected);
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
