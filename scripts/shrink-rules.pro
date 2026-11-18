# Keep readable names and avoid optimizer rewrites of the protocol state machines.
-dontobfuscate
-dontoptimize
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Android instantiates these from the manifest or XML.
-keep public class com.hdz.wearcontrol.** extends android.app.Activity { public <init>(); }
-keep public class com.hdz.wearcontrol.** extends android.app.Service { public <init>(); }
-keep public class com.hdz.wearcontrol.** extends android.content.BroadcastReceiver { public <init>(); }
-keep public class com.hdz.wearcontrol.** extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context,android.util.AttributeSet);
    public <init>(android.content.Context,android.util.AttributeSet,int);
}

# Optional modules are loaded by name. Preserve their interfaces and constructors.
-keep class com.hdz.wearcontrol.**$Module { *; }
-keep class com.hdz.wearcontrol.*Panel { *; }
-keepclassmembers class com.hdz.wearcontrol.TwsTileService {
    public static void requestUpdate(android.content.Context);
}

# Shaded protobuf-lite uses field names to build serialization schemas.
-keepclassmembers class * extends androidx.wear.tiles.protobuf.GeneratedMessageLite {
    <fields>;
}
