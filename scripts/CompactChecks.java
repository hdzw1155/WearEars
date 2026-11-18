package com.hdz.wearcontrol;

import androidx.wear.tiles.ActionBuilders;
import androidx.wear.tiles.LayoutElementBuilders;
import androidx.wear.tiles.ModifiersBuilders;
import androidx.wear.tiles.ResourceBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TimelineBuilders;

/** R8 之后的离线检查：反射入口及 protobuf 序列化必须仍可用。 */
public final class CompactChecks {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = CompactChecks.class.getClassLoader();
        for (String name : new String[]{"BatteryPanel", "NoisePanel", "VolumePanel", "EffectsPanel"}) {
            Class.forName("com.hdz.wearcontrol." + name, false, loader)
                    .getDeclaredConstructor(android.app.Activity.class, MainPanel.Actions.class);
        }
        String[] drivers = {"vivo.VivoGaiaClient", "huawei.HuaweiSppClient", "xiaomi.XiaomiMmaClient",
                "oppo.OppoOpoClient", "EdifierClient", "RoseLinkClient", "NiceHckClient", "SonyClient", "TechnicsClient"};
        String[] models = {"vivo TWS Air3 Pro", "HUAWEI FreeBuds 6i", "Redmi Buds 6", "OPPO Enco Air5s",
                "EDIFIER NeoBuds Pro", "roseselsa CERAMICS-U", "NiceHCK OriG in", "Sony WF-1000XM5", "Technics EAH-AZ80"};
        for (int i = 0; i < drivers.length; i++) {
            DriverModule module = (DriverModule) Class.forName("com.hdz.wearcontrol." + drivers[i] + "$Module", false, loader)
                    .getDeclaredConstructor().newInstance();
            if (!module.matches(models[i])) throw new AssertionError("Module no longer matches: " + models[i]);
            if (i == 2 && module.matches("Xiaomi Buds 5 Pro")) throw new AssertionError("Compact GAIA must not use RCSP");
        }
        Class.forName("com.hdz.wearcontrol.TwsTileService", false, loader)
                .getMethod("requestUpdate", android.content.Context.class);

        ModifiersBuilders.Clickable action = new ModifiersBuilders.Clickable.Builder().setId("tile-check")
                .setOnClick(new ActionBuilders.LoadAction.Builder().build()).build();
        LayoutElementBuilders.Layout layout = new LayoutElementBuilders.Layout.Builder()
                .setRoot(new LayoutElementBuilders.Box.Builder()
                        .addContent(new LayoutElementBuilders.Text.Builder().setText("WearEars-check").build())
                        .setModifiers(new ModifiersBuilders.Modifiers.Builder().setClickable(action).build()).build()).build();
        byte[] tile = new TileBuilders.Tile.Builder().setResourcesVersion("resource-check")
                .setTimeline(new TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build()).build())
                .build().toProto().toByteArray();
        requireContains(tile, "WearEars-check");
        requireContains(tile, "tile-check");
        requireContains(tile, "resource-check");
        byte[] resource = new ResourceBuilders.Resources.Builder().setVersion("images-check").build().toProto().toByteArray();
        requireContains(resource, "images-check");
        System.out.println("PASS: compact module entry points and Tile/Resources protobuf serialization");
    }

    private static void requireContains(byte[] bytes, String value) throws Exception {
        if (!new String(bytes, "UTF-8").contains(value)) throw new AssertionError("Missing serialized value: " + value);
    }
}
