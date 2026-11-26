package com.hdz.wearcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 开发者选项独立页：收纳多端局域网协同、应用层内网防火墙与本机应用联动。
 * 统一适配 4 套皮肤、日/夜主题、手表圆屏表冠滚动、手机 480dp 居中列及 HUD 非触屏焦点。
 */
public class DeveloperSettingsActivity extends Activity {
    private AppScrollView scroll;
    private ArcIndicatorView indicator;
    private AlertDialog meshDialog;
    private AlertDialog meshSubDialog;
    private AlertDialog fwDialog;
    private AlertDialog fwSubDialog;

    static Intent intent(Context ctx) {
        return new Intent(ctx, DeveloperSettingsActivity.class);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AppPrefs.hasDisclaimer(this)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_developer_settings);
        applyPhoneWindowChrome();
        scroll = (AppScrollView) findViewById(R.id.dev_settings_scroll);
        indicator = (ArcIndicatorView) findViewById(R.id.dev_settings_indicator);
        int formOverride = AppPrefs.hostFormOverride(this);
        DeviceForm.styleScrollContainer(this, scroll, indicator, formOverride);
        if (indicator != null && DeviceForm.isWatch(this, formOverride)) {
            indicator.attachTo(scroll);
        }

        LocalMeshManager.get(this).syncFirewallFromPrefs(this);

        // Local LAN Mesh bindings
        bindMeshMode(R.id.mesh_mode_off, LocalMeshManager.MODE_OFF);
        bindMeshMode(R.id.mesh_mode_host, LocalMeshManager.MODE_HOST);
        bindMeshMode(R.id.mesh_mode_client, LocalMeshManager.MODE_CLIENT);
        View meshAction = findViewById(R.id.mesh_action_btn);
        if (meshAction != null) {
            meshAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onMeshActionClicked();
                }
            });
        }

        // Firewall bindings
        bindClick(R.id.fw_toggle_enabled, new Runnable() {
            @Override
            public void run() {
                AppPrefs.setFwEnabled(DeveloperSettingsActivity.this,
                        !AppPrefs.fwEnabled(DeveloperSettingsActivity.this));
                syncFirewallAndRefresh();
            }
        });
        bindClick(R.id.fw_toggle_same_subnet, new Runnable() {
            @Override
            public void run() {
                AppPrefs.setFwSameSubnetOnly(DeveloperSettingsActivity.this,
                        !AppPrefs.fwSameSubnetOnly(DeveloperSettingsActivity.this));
                syncFirewallAndRefresh();
            }
        });
        bindClick(R.id.fw_toggle_allowlist, new Runnable() {
            @Override
            public void run() {
                AppPrefs.setFwAllowlistOnly(DeveloperSettingsActivity.this,
                        !AppPrefs.fwAllowlistOnly(DeveloperSettingsActivity.this));
                syncFirewallAndRefresh();
            }
        });
        bindClick(R.id.fw_toggle_readonly, new Runnable() {
            @Override
            public void run() {
                AppPrefs.setFwReadOnly(DeveloperSettingsActivity.this,
                        !AppPrefs.fwReadOnly(DeveloperSettingsActivity.this));
                syncFirewallAndRefresh();
            }
        });
        bindClick(R.id.fw_allowlist_btn, new Runnable() {
            @Override
            public void run() {
                showAllowlistDialog();
            }
        });
        bindClick(R.id.fw_logs_btn, new Runnable() {
            @Override
            public void run() {
                showFirewallLogsDialog();
            }
        });
        bindClick(R.id.fw_clear_bans_btn, new Runnable() {
            @Override
            public void run() {
                LocalMeshManager.get(DeveloperSettingsActivity.this).firewall().clearBans();
                Toast.makeText(DeveloperSettingsActivity.this,
                        R.string.fw_bans_cleared, Toast.LENGTH_SHORT).show();
                refreshChoices();
            }
        });

        // Local App Bridge binding
        bindClick(R.id.intent_bridge_choice, new Runnable() {
            @Override
            public void run() {
                boolean next = !AppPrefs.localIntentBridge(DeveloperSettingsActivity.this);
                AppPrefs.setLocalIntentBridge(DeveloperSettingsActivity.this, next);
                LocalControlReceiver.setBridgeEnabled(next);
                refreshChoices();
            }
        });

        refreshChoices();
        applyPhoneInsets();
        if (isHudMode()) {
            View list = findViewById(R.id.dev_settings_list);
            if (list != null) {
                list.post(new Runnable() {
                    @Override
                    public void run() {
                        SwissHudHelper.requestInitialFocus(list);
                    }
                });
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalMeshManager.get(this).syncFirewallFromPrefs(this);
        refreshChoices();
        if (scroll != null) scroll.setActive(true);
        if (indicator != null && DeviceForm.isWatch(this, AppPrefs.hostFormOverride(this))) {
            indicator.setActive(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalMeshManager.get(this).stopDiscovery();
        if (scroll != null) scroll.setActive(false);
        if (indicator != null && DeviceForm.isWatch(this, AppPrefs.hostFormOverride(this))) {
            indicator.setActive(false);
        }
    }

    @Override
    protected void onDestroy() {
        LocalMeshManager.get(this).stopDiscovery();
        dismissQuietly(meshDialog);
        dismissQuietly(meshSubDialog);
        dismissQuietly(fwDialog);
        dismissQuietly(fwSubDialog);
        super.onDestroy();
    }

    private void bindClick(int id, final Runnable action) {
        View v = findViewById(id);
        if (v == null) return;
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                action.run();
            }
        });
    }

    private void syncFirewallAndRefresh() {
        LocalMeshManager.get(this).syncFirewallFromPrefs(this);
        refreshChoices();
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
        final View list = findViewById(R.id.dev_settings_list);
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

    // ---- Local Mesh actions ----

    private void bindMeshMode(int id, final int mode) {
        View btn = findViewById(id);
        if (btn == null) return;
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (AppPrefs.meshMode(DeveloperSettingsActivity.this) == mode) return;
                AppPrefs.setMeshMode(DeveloperSettingsActivity.this, mode);
                if (mode == LocalMeshManager.MODE_OFF) {
                    LocalMeshManager.get(DeveloperSettingsActivity.this).stopAll();
                } else if (mode == LocalMeshManager.MODE_HOST) {
                    LocalMeshManager.get(DeveloperSettingsActivity.this).pin();
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
        dismissQuietly(meshDialog);

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
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showManualMeshDialog();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .create();
        meshDialog = dialog;

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < 0 || pos >= peers.size()) return;
                LocalMeshManager.DiscoveredPeer peer = peers.get(pos);
                dialog.dismiss();
                showPinPromptDialog(peer);
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                mesh.stopDiscovery();
            }
        });

        mesh.startDiscovery(new LocalMeshManager.DiscoveryListener() {
            @Override
            public void onPeersUpdated(List<LocalMeshManager.DiscoveredPeer> updated) {
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
                                ? " \u00B7 " + p.deviceName : "";
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
        dismissQuietly(meshSubDialog);

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
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String pin = pinInput.getText().toString().trim();
                        if (!LocalMeshProtocol.isValidPin(pin)) {
                            Toast.makeText(DeveloperSettingsActivity.this,
                                    R.string.settings_mesh_invalid_pin, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        AppPrefs.setMeshHostIp(DeveloperSettingsActivity.this, peer.ip);
                        AppPrefs.setMeshHostPort(DeveloperSettingsActivity.this, peer.port);
                        AppPrefs.setMeshPin(DeveloperSettingsActivity.this, pin);
                        AppPrefs.setMeshMode(DeveloperSettingsActivity.this, LocalMeshManager.MODE_CLIENT);
                        refreshChoices();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showManualMeshDialog() {
        if (isFinishing() || isDestroyed()) return;
        dismissQuietly(meshSubDialog);

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
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String ip = ipInput.getText().toString().trim();
                        String portStr = portInput.getText().toString().trim();
                        String pin = pinInput.getText().toString().trim();
                        if (!LocalMeshProtocol.isPrivateLanAddress(ip)) {
                            Toast.makeText(DeveloperSettingsActivity.this,
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
                            Toast.makeText(DeveloperSettingsActivity.this,
                                    R.string.settings_mesh_invalid_port, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!LocalMeshProtocol.isValidPin(pin)) {
                            Toast.makeText(DeveloperSettingsActivity.this,
                                    R.string.settings_mesh_invalid_pin, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        AppPrefs.setMeshHostIp(DeveloperSettingsActivity.this, ip);
                        AppPrefs.setMeshHostPort(DeveloperSettingsActivity.this, port);
                        AppPrefs.setMeshPin(DeveloperSettingsActivity.this, pin);
                        AppPrefs.setMeshMode(DeveloperSettingsActivity.this, LocalMeshManager.MODE_CLIENT);
                        refreshChoices();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    // ---- Firewall dialogs ----

    private void showAllowlistDialog() {
        if (isFinishing() || isDestroyed()) return;
        dismissQuietly(fwDialog);

        final LocalMeshFirewall fw = LocalMeshManager.get(this).firewall();
        final List<String> ips = new ArrayList<>();
        final List<String> rows = new ArrayList<>();

        int dialogTheme = NeoStyle.dialogTheme(isLightSurface());
        Context dialogCtx = new ContextThemeWrapper(this, dialogTheme);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                dialogCtx, android.R.layout.simple_list_item_1, rows);
        final ListView listView = new ListView(dialogCtx);
        listView.setAdapter(adapter);

        final Runnable reloadList = new Runnable() {
            @Override
            public void run() {
                ips.clear();
                rows.clear();
                Set<String> snap = fw.allowlistSnapshot();
                if (snap.isEmpty()) {
                    rows.add(getString(R.string.fw_allowlist_empty));
                } else {
                    for (String ip : snap) {
                        ips.add(ip);
                        rows.add(ip + "  " + getString(R.string.fw_allowlist_tap_remove));
                    }
                }
                adapter.notifyDataSetChanged();
            }
        };
        reloadList.run();

        final AlertDialog dialog = new AlertDialog.Builder(this, dialogTheme)
                .setTitle(R.string.fw_allowlist_dialog_title)
                .setView(listView)
                .setPositiveButton(R.string.fw_allowlist_add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showAddAllowlistIpDialog();
                    }
                })
                .setNeutralButton(R.string.fw_allowlist_clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        fw.clearAllowlist();
                        AppPrefs.setFwAllowlist(DeveloperSettingsActivity.this, "");
                        Toast.makeText(DeveloperSettingsActivity.this,
                                R.string.fw_allowlist_cleared, Toast.LENGTH_SHORT).show();
                        refreshChoices();
                    }
                })
                .setNegativeButton(R.string.fw_dialog_close, null)
                .create();
        fwDialog = dialog;

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < 0 || pos >= ips.size()) return;
                String removed = ips.get(pos);
                fw.removeAllowedIp(removed);
                AppPrefs.setFwAllowlist(DeveloperSettingsActivity.this, fw.allowlistToCsv());
                Toast.makeText(DeveloperSettingsActivity.this,
                        getString(R.string.fw_allowlist_removed, removed), Toast.LENGTH_SHORT).show();
                reloadList.run();
                refreshChoices();
            }
        });

        dialog.show();
    }

    private void showAddAllowlistIpDialog() {
        if (isFinishing() || isDestroyed()) return;
        dismissQuietly(fwSubDialog);

        int dialogTheme = NeoStyle.dialogTheme(isLightSurface());
        Context dialogCtx = new ContextThemeWrapper(this, dialogTheme);
        int pad = Math.round(16f * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(dialogCtx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        TextView desc = new TextView(dialogCtx);
        desc.setText(R.string.fw_allowlist_add_hint);
        box.addView(desc);

        final EditText ipInput = new EditText(dialogCtx);
        ipInput.setHint(R.string.settings_mesh_ip_hint);
        ipInput.setSingleLine(true);
        box.addView(ipInput);

        fwSubDialog = new AlertDialog.Builder(this, dialogTheme)
                .setTitle(R.string.fw_allowlist_add_title)
                .setView(box)
                .setPositiveButton(R.string.settings_mesh_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String ip = ipInput.getText().toString().trim();
                        if (!LocalMeshProtocol.isPrivateLanAddress(ip)) {
                            Toast.makeText(DeveloperSettingsActivity.this,
                                    R.string.settings_mesh_invalid_ip, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        LocalMeshFirewall fw = LocalMeshManager.get(DeveloperSettingsActivity.this).firewall();
                        fw.addAllowedIp(ip);
                        AppPrefs.setFwAllowlist(DeveloperSettingsActivity.this, fw.allowlistToCsv());
                        Toast.makeText(DeveloperSettingsActivity.this,
                                getString(R.string.fw_allowlist_added, ip), Toast.LENGTH_SHORT).show();
                        refreshChoices();
                        showAllowlistDialog();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showFirewallLogsDialog() {
        if (isFinishing() || isDestroyed()) return;
        dismissQuietly(fwDialog);

        List<LocalMeshFirewall.AuditEvent> events =
                LocalMeshManager.get(this).firewall().recentEvents();
        List<String> rows = new ArrayList<>();
        if (events.isEmpty()) {
            rows.add(getString(R.string.fw_logs_empty));
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
            for (int i = events.size() - 1; i >= 0; i--) {
                LocalMeshFirewall.AuditEvent ev = events.get(i);
                String ts = fmt.format(new Date(ev.timeMs));
                rows.add(ts + " \u00B7 " + ev.ip + "\n" + localizeReason(ev.reason));
            }
        }

        int dialogTheme = NeoStyle.dialogTheme(isLightSurface());
        Context dialogCtx = new ContextThemeWrapper(this, dialogTheme);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                dialogCtx, android.R.layout.simple_list_item_1, rows);
        ListView listView = new ListView(dialogCtx);
        listView.setAdapter(adapter);

        fwDialog = new AlertDialog.Builder(this, dialogTheme)
                .setTitle(R.string.fw_logs_dialog_title)
                .setView(listView)
                .setNegativeButton(R.string.fw_dialog_close, null)
                .show();
    }

    private String localizeReason(String reason) {
        if (LocalMeshFirewall.REASON_NON_PRIVATE_IP.equals(reason)) {
            return getString(R.string.fw_reason_non_private);
        }
        if (LocalMeshFirewall.REASON_IP_BANNED.equals(reason)) {
            return getString(R.string.fw_reason_banned);
        }
        if (LocalMeshFirewall.REASON_CROSS_SUBNET.equals(reason)) {
            return getString(R.string.fw_reason_cross_subnet);
        }
        if (LocalMeshFirewall.REASON_NOT_IN_ALLOWLIST.equals(reason)) {
            return getString(R.string.fw_reason_not_allowlisted);
        }
        if (LocalMeshFirewall.REASON_CONN_RATE_LIMIT.equals(reason)) {
            return getString(R.string.fw_reason_conn_rate);
        }
        if (LocalMeshFirewall.REASON_AUTH_FAIL.equals(reason)) {
            return getString(R.string.fw_reason_auth_fail);
        }
        if (LocalMeshFirewall.REASON_AUTH_LOCKOUT.equals(reason)) {
            return getString(R.string.fw_reason_auth_lockout);
        }
        if (LocalMeshFirewall.REASON_FIND_BUDS_HARD_BLOCK.equals(reason)) {
            return getString(R.string.fw_reason_find_buds);
        }
        if (LocalMeshFirewall.REASON_READ_ONLY_MODE.equals(reason)) {
            return getString(R.string.fw_reason_readonly);
        }
        if (LocalMeshFirewall.REASON_CMD_RATE_LIMIT.equals(reason)) {
            return getString(R.string.fw_reason_cmd_rate);
        }
        return reason == null ? "" : reason;
    }

    // ---- Rendering & theme ----

    private void refreshChoices() {
        boolean hud = isHudMode();
        boolean day = !hud && AppPrefs.settingsDayMode(this);
        int style = hud ? AppPrefs.STYLE_NEO_NEW : AppPrefs.uiStyle(this);
        boolean light = day || style == AppPrefs.STYLE_SUPREME;
        applyTheme(day, style);

        // Local Mesh section
        int meshMode = AppPrefs.meshMode(this);
        setChoice(R.id.mesh_mode_off, meshMode == LocalMeshManager.MODE_OFF, day, style);
        setChoice(R.id.mesh_mode_host, meshMode == LocalMeshManager.MODE_HOST, day, style);
        setChoice(R.id.mesh_mode_client, meshMode == LocalMeshManager.MODE_CLIENT, day, style);
        TextView meshStatus = (TextView) findViewById(R.id.mesh_status_text);
        TextView meshAction = (TextView) findViewById(R.id.mesh_action_btn);
        if (meshStatus != null && meshAction != null) {
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

        // Firewall section
        LocalMeshFirewall fw = LocalMeshManager.get(this).firewall();
        boolean fwOn = AppPrefs.fwEnabled(this);
        boolean sameSubnet = AppPrefs.fwSameSubnetOnly(this);
        boolean allowlistOnly = AppPrefs.fwAllowlistOnly(this);
        boolean readOnly = AppPrefs.fwReadOnly(this);

        updateToggle(R.id.fw_toggle_enabled, fwOn,
                R.string.fw_enabled_on, R.string.fw_enabled_off, day, style);
        updateToggle(R.id.fw_toggle_same_subnet, sameSubnet,
                R.string.fw_same_subnet_on, R.string.fw_same_subnet_off, day, style);
        updateToggle(R.id.fw_toggle_allowlist, allowlistOnly,
                R.string.fw_allowlist_only_on, R.string.fw_allowlist_only_off, day, style);
        updateToggle(R.id.fw_toggle_readonly, readOnly,
                R.string.fw_readonly_on, R.string.fw_readonly_off, day, style);

        int allowCount = fw.allowlistSnapshot().size();
        TextView allowBtn = (TextView) findViewById(R.id.fw_allowlist_btn);
        if (allowBtn != null) {
            allowBtn.setText(getString(R.string.fw_manage_allowlist_count, allowCount));
            setChoice(R.id.fw_allowlist_btn, allowCount > 0 && allowlistOnly, day, style);
        }

        TextView fwStatus = (TextView) findViewById(R.id.fw_status_text);
        if (fwStatus != null) {
            fwStatus.setTextColor(light ? 0xFF5A606B : NeoStyle.textDim(style));
            fwStatus.setText(getString(R.string.fw_status_summary,
                    fw.blockedCount(), fw.bannedIpCount()));
        }
        setChoice(R.id.fw_logs_btn, false, day, style);
        setChoice(R.id.fw_clear_bans_btn, fw.bannedIpCount() > 0, day, style);

        // Local App Bridge section
        boolean bridgeOn = AppPrefs.localIntentBridge(this);
        LocalControlReceiver.setBridgeEnabled(bridgeOn);
        setChoice(R.id.intent_bridge_choice, bridgeOn, day, style);
        TextView bridgeView = (TextView) findViewById(R.id.intent_bridge_choice);
        if (bridgeView != null) {
            bridgeView.setText(bridgeOn
                    ? R.string.settings_intent_bridge_on
                    : R.string.settings_intent_bridge_off);
        }

        if (hud) {
            View list = findViewById(R.id.dev_settings_list);
            if (list != null) {
                SwissHudHelper.applyFocusTree(this, list);
            }
        }
    }

    private void updateToggle(int id, boolean on, int onRes, int offRes, boolean day, int style) {
        TextView view = (TextView) findViewById(id);
        if (view != null) {
            view.setText(on ? onRes : offRes);
            setChoice(id, on, day, style);
        }
    }

    private void applyTheme(boolean day, int style) {
        applyPhoneWindowChrome();
        boolean light = day || style == AppPrefs.STYLE_SUPREME;
        View root = findViewById(R.id.dev_settings_root);
        if (root != null) {
            root.setBackgroundColor(light ? 0xFFF4F3EE : 0xFF000000);
        }
        TextView title = (TextView) findViewById(R.id.dev_settings_title);
        if (title != null) {
            title.setTextColor(light ? 0xFF111317 : 0xFFFFFFFF);
        }
        TextView hint = (TextView) findViewById(R.id.dev_settings_hint);
        if (hint != null) {
            hint.setTextColor(light ? 0xFF5A606B : NeoStyle.textFaint(style));
        }
        int cardBg = isHudMode()
                ? R.drawable.bg_swiss_hud_card
                : (light
                        ? (style == AppPrefs.STYLE_SUPREME ? R.drawable.bg_sup_card_paper : R.drawable.bg_set_card_day)
                        : NeoStyle.cardDrawable(style));
        int captionColor = light ? 0xFF5A606B : NeoStyle.textFaint(style);
        View list = findViewById(R.id.dev_settings_list);
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

    private static void dismissQuietly(AlertDialog dialog) {
        if (dialog != null) {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return scroll != null && scroll.onGenericMotionEvent(event) || super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return scroll != null && scroll.handleRotaryKey(keyCode) || super.onKeyDown(keyCode, event);
    }
}
