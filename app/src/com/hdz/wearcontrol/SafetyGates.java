package com.hdz.wearcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Shared safety gates for spatial/head-tracking audio risks, session experimental features,
 * and the 3-layer Find My Earbuds hearing protection flow.
 */
final class SafetyGates {
    private static final WeakHashMap<Activity, HostDialogs> HOSTS = new WeakHashMap<>();

    private static final class HostDialogs {
        AlertDialog prompt;
        AlertDialog ringing;
    }

    private SafetyGates() {
    }

    private static HostDialogs host(Activity activity) {
        HostDialogs h = HOSTS.get(activity);
        if (h == null) {
            h = new HostDialogs();
            HOSTS.put(activity, h);
        }
        return h;
    }

    private static boolean validSession(Activity activity, DeviceState state, long session) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && state != null && state.connected && state.session == session
                && AppPrefs.hasDisclaimer(activity);
    }

    private static void showPrompt(Activity activity, AlertDialog.Builder builder) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final HostDialogs h = host(activity);
        if (h.prompt != null) {
            h.prompt.dismiss();
            h.prompt = null;
        }
        final AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                if (h.prompt == dialog) h.prompt = null;
            }
        });
        h.prompt = dialog;
        dialog.show();
    }

    static void dismissAll(Activity activity) {
        if (activity == null) return;
        HostDialogs h = HOSTS.get(activity);
        if (h == null) return;
        if (h.prompt != null) {
            h.prompt.dismiss();
            h.prompt = null;
        }
        if (h.ringing != null) {
            h.ringing.dismiss();
            h.ringing = null;
        }
    }

    static void confirmAudioRisk(final Activity activity, int titleRes, int msgRes,
                                 final long session, final DeviceState state,
                                 final Runnable onConfirm) {
        if (!validSession(activity, state, session)) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, NeoStyle.dialogTheme(activity))
                .setTitle(titleRes)
                .setMessage(msgRes)
                .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (validSession(activity, state, session) && onConfirm != null) {
                            onConfirm.run();
                        }
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null);
        showPrompt(activity, builder);
    }

    static void confirmExperimentalOnce(final Activity activity, final FeatureDescriptor spec,
                                        final Set<FeatureId> sessionConfirmed,
                                        final long session, final DeviceState state,
                                        final Runnable onConfirm) {
        if (spec == null || !validSession(activity, state, session)) return;
        if (spec.verif != Controls.VERIF_UNTESTED
                || (sessionConfirmed != null && sessionConfirmed.contains(spec.id))) {
            if (onConfirm != null) onConfirm.run();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, NeoStyle.dialogTheme(activity))
                .setTitle(R.string.exp_first_warn_title)
                .setMessage(R.string.exp_first_warn_msg)
                .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (!validSession(activity, state, session)) return;
                        if (sessionConfirmed != null) sessionConfirmed.add(spec.id);
                        if (onConfirm != null) onConfirm.run();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null);
        showPrompt(activity, builder);
    }

    static void startFindBudsFlow(final Activity activity, final DeviceState state,
                                  final FeatureDescriptor spec, final MainPanel.Actions actions) {
        if (activity == null || state == null || !state.connected || actions == null) return;
        final long session = state.session;
        if (!validSession(activity, state, session)) return;

        // Layer 1: Hard-block if any earbud is currently in-ear.
        if (state.wearStatus == DeviceState.WEAR_BOTH || state.isAnyBudInEar()) {
            showPrompt(activity, new AlertDialog.Builder(activity, NeoStyle.dialogTheme(activity))
                    .setTitle(R.string.find_buds_btn)
                    .setMessage(R.string.find_buds_blocked_wear)
                    .setPositiveButton(R.string.dialog_ok, null));
            return;
        }

        // Layer 2A: Resolve ring targets (skip picker when descriptor exposes a single ring target).
        final List<OptionItem> targets = resolveFindTargets(activity, spec);
        if (targets.size() <= 1) {
            int singleTarget = targets.isEmpty() ? 1 : targets.get(0).id;
            confirmFindBudsSafety(activity, state, session, singleTarget, actions);
            return;
        }

        final boolean english = AppPrefs.LANG_EN.equals(AppPrefs.lang(activity));
        final String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) labels[i] = targets.get(i).label(english);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                activity, android.R.layout.select_dialog_item, labels) {
            @Override public boolean isEnabled(int position) {
                return !state.isTargetInEar(targets.get(position).id);
            }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                if (row instanceof TextView) {
                    boolean allowed = isEnabled(position);
                    ((TextView) row).setAlpha(allowed ? 1f : 0.4f);
                }
                return row;
            }
        };
        showPrompt(activity, new AlertDialog.Builder(activity, NeoStyle.dialogTheme(activity))
                .setTitle(R.string.find_buds_pick_title)
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (!validSession(activity, state, session)) return;
                        OptionItem chosen = targets.get(which);
                        if (state.isAnyBudInEar() || state.isTargetInEar(chosen.id)) {
                            Toast.makeText(activity, R.string.find_buds_blocked_wear, Toast.LENGTH_LONG).show();
                            return;
                        }
                        confirmFindBudsSafety(activity, state, session, chosen.id, actions);
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null));
    }

    private static List<OptionItem> resolveFindTargets(Activity activity, FeatureDescriptor spec) {
        List<OptionItem> out = new ArrayList<>();
        if (spec != null && spec.options.length > 0) {
            for (OptionItem opt : spec.options) {
                if (opt != null && opt.id > 0) out.add(opt);
            }
            if (!out.isEmpty()) return out;
        }
        String left = activity.getString(R.string.find_buds_left);
        String right = activity.getString(R.string.find_buds_right);
        String both = activity.getString(R.string.find_buds_both);
        out.add(new OptionItem(1, left, left));
        out.add(new OptionItem(2, right, right));
        out.add(new OptionItem(3, both, both));
        return out;
    }

    private static void confirmFindBudsSafety(final Activity activity, final DeviceState state,
                                              final long session, final int target,
                                              final MainPanel.Actions actions) {
        if (!validSession(activity, state, session)) return;
        StringBuilder msg = new StringBuilder(activity.getString(R.string.find_buds_confirm_msg));
        if (state.wearStatus == DeviceState.WEAR_UNKNOWN) {
            msg.append(activity.getString(R.string.find_buds_confirm_unknown_wear));
        }
        showPrompt(activity, new AlertDialog.Builder(activity, NeoStyle.dialogTheme(activity))
                .setTitle(R.string.find_buds_confirm_title)
                .setMessage(msg.toString())
                .setPositiveButton(R.string.find_buds_confirm_ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (!validSession(activity, state, session)) return;
                        if (state.isAnyBudInEar() || state.isTargetInEar(target)) {
                            Toast.makeText(activity, R.string.find_buds_blocked_wear, Toast.LENGTH_LONG).show();
                            return;
                        }
                        actions.selectFeature(FeatureId.FIND_MY_BUDS, target);
                        syncFindBudsRinging(activity, state, actions);
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null));
    }

    static void syncFindBudsRinging(final Activity activity, final DeviceState state,
                                    final MainPanel.Actions actions) {
        if (activity == null) return;
        final HostDialogs h = host(activity);
        int pending = actions != null ? actions.pendingFeature(FeatureId.FIND_MY_BUDS) : -1;
        Integer confirmed = state != null ? state.featureValue(FeatureId.FIND_MY_BUDS) : null;
        boolean ringing = state != null && state.connected && !state.isAnyBudInEar()
                && (pending > 0 || (pending != 0 && confirmed != null && confirmed > 0));
        if (!ringing) {
            if (h.ringing != null) {
                h.ringing.dismiss();
                h.ringing = null;
            }
            return;
        }
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (h.ringing != null && h.ringing.isShowing()) return;

        final AlertDialog dialog = new AlertDialog.Builder(activity, NeoStyle.dialogTheme(activity))
                .setTitle(R.string.find_buds_ringing_title)
                .setMessage(R.string.find_buds_ringing_msg)
                .setPositiveButton(R.string.find_buds_stop, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (actions != null) actions.stopFindBuds();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) {
                        if (actions != null) actions.stopFindBuds();
                    }
                })
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                if (h.ringing == dialog) h.ringing = null;
            }
        });
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                if (dialog.getButton(DialogInterface.BUTTON_POSITIVE) != null) {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(0xFFDF1B22);
                }
            }
        });
        h.ringing = dialog;
        dialog.show();
    }
}
