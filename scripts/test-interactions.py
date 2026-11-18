"""Execute production touch/controller code with deterministic Android boundaries.
This checks event ordering, not Android rendering, Bluetooth firmware or haptics.
"""
from pathlib import Path
import os
import subprocess
import textwrap

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build' / 'touch-checks'
SRC = OUT / 'src'
JDK = Path(os.environ['USERPROFILE']) / 'android-tools' / 'jdk-ms' / 'bin'
files = {}

def android(name, body):
    package, cls = name.rsplit('.', 1)
    files[name.replace('.', '/') + '.java'] = 'package ' + package + ';\n' + body

android('android.os.Looper', 'public class Looper { static final Looper MAIN=new Looper(); public static Looper getMainLooper(){return MAIN;} public static Looper myLooper(){return MAIN;} }')
android('android.os.Handler', '''
public class Handler {
 static class Job { Runnable r; long at; Job(Runnable r,long at){this.r=r;this.at=at;} }
 static final java.util.List<Job> jobs=new java.util.ArrayList<Job>(); static long now;
 public Handler(Looper l){} public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long delay){synchronized(jobs){jobs.add(new Job(r,now+delay));}return true;}
 public void removeCallbacks(Runnable r){synchronized(jobs){for(int i=jobs.size()-1;i>=0;i--)if(jobs.get(i).r==r)jobs.remove(i);}}
 public void removeCallbacksAndMessages(Object token){reset();}
 public static void advance(long ms){now+=ms;drain();}
 public static void drain(){for(int limit=0;limit<10000;limit++){Runnable r=null;synchronized(jobs){for(int i=0;i<jobs.size();i++)if(jobs.get(i).at<=now){r=jobs.remove(i).r;break;}}if(r==null)return;r.run();}throw new AssertionError("Timer loop");}
 public static void reset(){synchronized(jobs){jobs.clear();now=0;}}
}''')
android('android.os.Build', 'public class Build { public static class VERSION { public static int SDK_INT=34; } }')
android('android.Manifest', 'public class Manifest { public static class permission { public static final String BLUETOOTH_CONNECT="bt"; } }')
android('android.content.pm.PackageManager', 'public class PackageManager { public static final int PERMISSION_GRANTED=0; }')
android('android.util.AttributeSet', 'public interface AttributeSet {}')
android('android.util.Log', 'public class Log { public static int d(String t,String s){return 0;} public static int w(String t,String s,Throwable e){return 0;} }')
android('android.util.DisplayMetrics', 'public class DisplayMetrics { public float density=2.25f; public int widthPixels=450,heightPixels=450; }')
android('android.content.res.Configuration', 'public class Configuration {public boolean isScreenRound(){return true;}}')
android('android.content.res.Resources', 'public class Resources { public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();} public Configuration getConfiguration(){return new Configuration();} }')
android('android.content.Context', '''
public class Context {
 public static final String BLUETOOTH_SERVICE="bluetooth",AUDIO_SERVICE="audio";
 public static final int MODE_PRIVATE=0;
 private final SharedPreferences prefs=new SharedPreferences();
 public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
 public android.graphics.drawable.Drawable getDrawable(int id){return null;}
 public Context getApplicationContext(){return this;} public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 public Object getSystemService(String s){return new android.bluetooth.BluetoothManager();} public int checkSelfPermission(String s){return 0;}
 public Object registerReceiver(BroadcastReceiver r,IntentFilter f){return null;} public void unregisterReceiver(BroadcastReceiver r){}
 public String getString(int id){return Integer.toString(id);}
}''')
android('android.content.SharedPreferences', '''public class SharedPreferences {
 private final java.util.Map<String,Boolean> values=new java.util.HashMap<String,Boolean>();
 public boolean getBoolean(String key,boolean fallback){return values.containsKey(key)?values.get(key):fallback;}
 public Editor edit(){return new Editor();}
 public class Editor {
  public Editor putBoolean(String key,boolean value){values.put(key,value);return this;}
  public void apply(){}
 }
}''')
android('android.content.DialogInterface', '''public interface DialogInterface {
 public interface OnClickListener {void onClick(DialogInterface dialog,int which);}
}''')
android('android.content.BroadcastReceiver', 'public abstract class BroadcastReceiver { public abstract void onReceive(Context c,Intent i); }')
android('android.content.IntentFilter', 'public class IntentFilter { public IntentFilter(String s){} public void addAction(String s){} }')
android('android.content.Intent', 'public class Intent { public String getAction(){return "";} public <T> T getParcelableExtra(String k){return null;} public int getIntExtra(String k,int d){return d;} }')
android('android.bluetooth.BluetoothDevice', 'public class BluetoothDevice { public static final String ACTION_ACL_DISCONNECTED="acl_off",EXTRA_DEVICE="device"; public String getAddress(){return "AA:BB:CC:DD:EE:FF";} public String getName(){return "Test buds";} }')
android('android.bluetooth.BluetoothAdapter', 'public class BluetoothAdapter { public static final int ERROR=-1,STATE_OFF=10,STATE_TURNING_OFF=13,STATE_ON=12; public static final String EXTRA_STATE="state",ACTION_STATE_CHANGED="state_changed"; public boolean isEnabled(){return true;} }')
android('android.bluetooth.BluetoothManager', 'public class BluetoothManager { public BluetoothAdapter getAdapter(){return new BluetoothAdapter();} }')
android('android.view.ViewParent', 'public interface ViewParent { void requestDisallowInterceptTouchEvent(boolean b); }')
android('android.view.HapticFeedbackConstants', 'public class HapticFeedbackConstants { public static final int LONG_PRESS=0,CLOCK_TICK=4,KEYBOARD_TAP=3,CONFIRM=16,REJECT=17; }')
android('android.view.ViewConfiguration', 'public class ViewConfiguration { public static ViewConfiguration get(android.content.Context c){return new ViewConfiguration();} public static int getLongPressTimeout(){return 500;} public int getScaledTouchSlop(){return 8;} }')
android('android.view.MotionEvent', '''public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5;
 final int action;final float x,y;public MotionEvent(int a,float x,float y){action=a;this.x=x;this.y=y;}
 public int getActionMasked(){return action;}public float getX(){return x;}public float getY(){return y;}
}''')
android('android.view.View', '''public class View {
 private final android.content.Context c;private int width=450,height=72;
 private int visibility=VISIBLE; private boolean enabled=true; private CharSequence stateDescription;
 public interface OnClickListener {void onClick(View view);}
 private OnClickListener clickListener;
 public View(android.content.Context c,android.util.AttributeSet a){this.c=c;}
 public View(android.content.Context c,android.util.AttributeSet a,int style){this.c=c;}
 public static final int GONE=8,VISIBLE=0;
 public void setVisibility(int value){visibility=value;} public int getVisibility(){return visibility;}
 public void setEnabled(boolean value){enabled=value;} public boolean isEnabled(){return enabled;}
 public void setStateDescription(CharSequence value){stateDescription=value;} public CharSequence getStateDescription(){return stateDescription;}
 public void setOnClickListener(OnClickListener listener){clickListener=listener;}
 public void setBackgroundResource(int id){} public void setAlpha(float alpha){} public void postInvalidateOnAnimation(){}
 public android.content.Context getContext(){return c;}public android.content.res.Resources getResources(){return c.getResources();}
 public int getWidth(){return width;} public int getHeight(){return height;}
 public void layout(int l,int t,int r,int b){width=r-l;height=b-t;}
 public void invalidate(){} public void setClickable(boolean b){} public void setContentDescription(String s){}
 public boolean performHapticFeedback(int i){return true;}
 public boolean performClick(){if(clickListener!=null)clickListener.onClick(this);return true;}
 public boolean postDelayed(Runnable r,long d){return new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r,d);}
 public boolean removeCallbacks(Runnable r){new android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(r);return true;}
 public ViewParent getParent(){return null;} public boolean onTouchEvent(MotionEvent e){return false;}
 protected void onDraw(android.graphics.Canvas c){} protected void onDetachedFromWindow(){}
}''')
android('android.animation.ValueAnimator', '''public class ValueAnimator {
 public interface AnimatorUpdateListener { void onAnimationUpdate(ValueAnimator a); }
 AnimatorUpdateListener l; public static ValueAnimator ofFloat(float...v){return new ValueAnimator();}
 public void setDuration(long d){} public void addUpdateListener(AnimatorUpdateListener l){this.l=l;}
 public float getAnimatedFraction(){return 1;} public void start(){if(l!=null)l.onAnimationUpdate(this);}public void cancel(){}
}''')
android('android.graphics.RectF', '''public class RectF {
 public float left,top,right,bottom;public RectF(){}public RectF(float l,float t,float r,float b){set(l,t,r,b);}
 public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;} public float width(){return right-left;}public float height(){return bottom-top;}
 public void inset(float x,float y){left+=x;right-=x;top+=y;bottom-=y;}
 public void setEmpty(){set(0,0,0,0);}public boolean isEmpty(){return left>=right||top>=bottom;}
}''')
# Rendering is outside this regression; the View's actual touch code is executed.
android('android.graphics.Shader', 'public class Shader {}')
android('android.graphics.Paint', """public class Paint {
 public static final int ANTI_ALIAS_FLAG=1;public enum Style{FILL,STROKE}public enum Cap{ROUND}
 public enum Align{LEFT,CENTER} public static class FontMetrics{public float ascent,descent;}
 public void setTypeface(Typeface t){}public void setTextAlign(Align a){}public void setTextSize(float v){}public float measureText(String text){return text.length();} public FontMetrics getFontMetrics(){return new FontMetrics();}
 public Paint(int f){}public void setColor(int c){}public void setStyle(Style s){}public void setStrokeWidth(float w){}public void setStrokeCap(Cap c){}public void setShader(Shader s){}
}""")
android('android.graphics.Path', """public class Path {
 public enum Direction{CW}public void reset(){}public void moveTo(float x,float y){}public void lineTo(float x,float y){}public void close(){}public void addRoundRect(RectF r,float x,float y,Direction d){}
}""")
android('android.graphics.Canvas', """public class Canvas {
 public int save(){return 0;}public void restore(){}public void clipPath(Path p){}
 public void drawText(String t,float x,float y,Paint p){}public void drawText(CharSequence t,int start,int end,float x,float y,Paint p){}
 public void drawPath(Path path,Paint p){}public void drawLine(float x,float y,float endX,float endY,Paint p){}public void rotate(float d,float x,float y){}
 public void drawArc(float l,float t,float r,float b,float start,float span,boolean center,Paint p){}
 public void drawCircle(float x,float y,float r,Paint p){}public void drawRect(RectF r,Paint p){}public void drawRect(float x,float y,float r,float b,Paint p){}public void drawRoundRect(RectF r,float x,float y,Paint p){}public void drawArc(RectF r,float a,float b,boolean c,Paint p){}
}""")

android('android.graphics.Typeface','public class Typeface {public static final Typeface DEFAULT=new Typeface();public static final int BOLD=1,BOLD_ITALIC=3;public static Typeface create(Typeface f,int style){return f;}}')
android('android.graphics.drawable.Drawable','public class Drawable {public Drawable mutate(){return this;}public void setTint(int v){}public void setBounds(int l,int t,int r,int b){}public void draw(android.graphics.Canvas c){}}')
android('android.text.TextPaint','public class TextPaint extends android.graphics.Paint {public TextPaint(int flags){super(flags);}}')
android('android.text.TextUtils','public class TextUtils {public enum TruncateAt{END}public static CharSequence ellipsize(CharSequence s,TextPaint p,float width,TruncateAt at){return s;}}')
android('android.os.SystemClock','public class SystemClock {public static long uptimeMillis(){return Handler.now;}}')
android('android.media.AudioManager','''public class AudioManager {
 public static final int STREAM_MUSIC=3; public int current;public boolean fixed;
 public int getStreamMaxVolume(int stream){return 15;}public int getStreamVolume(int stream){return current;}
 public void setStreamVolume(int stream,int value,int flags){if(stream!=STREAM_MUSIC||value<0||value>15)throw new AssertionError(); if(!fixed)current=value;}
}''')
android('android.view.Window','public class Window {public View getDecorView(){return new View(new android.content.Context(),null);}}')
android('android.app.Activity','''public class Activity extends android.content.Context {
 public final android.media.AudioManager music=new android.media.AudioManager(); public final java.util.Map<Integer,android.view.View> views=new java.util.HashMap<Integer,android.view.View>();
 public Object getSystemService(String key){return AUDIO_SERVICE.equals(key)?music:super.getSystemService(key);}public android.view.Window getWindow(){return new android.view.Window();}
 @SuppressWarnings("unchecked") public <T extends android.view.View>T findViewById(int id){return (T)views.get(id);}
}''')
android('android.app.AlertDialog', '''public class AlertDialog implements android.content.DialogInterface {
 public static AlertDialog last; private boolean showing; private OnClickListener positive;
 public boolean isShowing(){return showing;} public void dismiss(){showing=false;}
 public void confirm(){if(showing && positive!=null)positive.onClick(this,-1);dismiss();}
 public static class Builder {
  private final AlertDialog dialog=new AlertDialog();
  public Builder(Activity activity){} public Builder setTitle(int id){return this;}
  public Builder setMessage(int id){return this;}
  public Builder setPositiveButton(int id,OnClickListener listener){dialog.positive=listener;return this;}
  public Builder setNegativeButton(int id,OnClickListener listener){return this;}
  public AlertDialog show(){dialog.showing=true;last=dialog;return dialog;}
 }
}''')
android('android.graphics.Color','public class Color {public static final int WHITE=-1;}')
android('android.widget.TextView','''public class TextView extends android.view.View {
 private CharSequence text;
 public TextView(){super(new android.content.Context(),null);}
 public void setText(CharSequence value){text=value;} public CharSequence getText(){return text;}
 public void setText(int id){setText(getContext().getString(id));} public void setTextColor(int color){}
}''')
android('android.widget.CompoundButton','''public class CompoundButton extends TextView {
 public interface OnCheckedChangeListener{void onCheckedChanged(CompoundButton button,boolean value);}
 private OnCheckedChangeListener listener; private boolean checked,broadcasting;
 public void setOnCheckedChangeListener(OnCheckedChangeListener value){listener=value;}
 public boolean isChecked(){return checked;}
 public void setChecked(boolean value){
  if(checked==value)return;
  checked=value;
  if(broadcasting||listener==null)return;
  broadcasting=true;
  try{listener.onCheckedChanged(this,value);}finally{broadcasting=false;}
 }
 public boolean performClick(){if(!isEnabled())return false;setChecked(!checked);return super.performClick();}
}''')
android('android.widget.Switch','public class Switch extends CompoundButton {}')
android('android.widget.Toast','''public class Toast {
 public static final int LENGTH_SHORT=0,LENGTH_LONG=1; public static int lastMessage;
 private int message;
 public static Toast makeText(android.content.Context context,int id,int duration){Toast toast=new Toast();toast.message=id;return toast;}
 public void show(){lastMessage=message;}
}''')

files['com/hdz/wearcontrol/RuntimeFakes.java'] = '''package com.hdz.wearcontrol;
import android.content.Context;import android.bluetooth.BluetoothDevice;
class AppPrefs {static final int STYLE_NEW=0,STYLE_CLASSIC=1,STYLE_LIQUID=2,STYLE_SUPREME=3;static int family(int s){return s;}
 static final String LANG_EN="en";static String lang(Context c){return "zh";}static int uiStyle(Context c){return 0;} static boolean isNeo(int s){return false;}
 static boolean showVolume(Context c){return true;}static void setShowVolume(Context c,boolean visible){} }
class NeoStyle {static int lockColor(int s){return 0;} static int volumeFill(int s){return 0;} static int volumeTrack(int s){return 0;}
 static int chipBg(int style,boolean selected){return 0;} static int chipText(int style,boolean selected){return 0;}}
class SnapChipRow extends android.view.View {SnapChipRow(){super(new Context(),null);}void select(int index,boolean animated){} }
class AppLocale {static Context localizedContext(Context c){return c;} }
class NeoVolumeArt {void draw(android.graphics.Canvas c,int f,float w,float h,float frac,float x,float r,float p,boolean u,float s,float ph,float e,float d){}}
class BluetoothLink {static final int UNKNOWN=0,CONNECTED=1,DISCONNECTED=2;}
class DeviceSelector {interface Callback{void onResult(BluetoothDevice d);}static BluetoothDevice findAddress(android.bluetooth.BluetoothAdapter a,String address){return new BluetoothDevice();}static void findTarget(Context c,android.bluetooth.BluetoothAdapter a,Callback cb){cb.onResult(new BluetoothDevice());}}
class DriverRegistry {static final java.util.Queue<FakeDriver> next=new java.util.ArrayDeque<FakeDriver>();static int created;static TwsClient create(Context c,BluetoothDevice d){created++;if(next.isEmpty())throw new AssertionError("Unexpected extra connection");return next.remove();}}
class TileStore {static String address="";static int cachedCaps;static void push(Context c){}static void pending(Context c,int m){}static String deviceAddress(Context c){return address;}
 static final String PREFS="tile";
 static TileSnapshot readOrEmpty(Context c){return new TileSnapshot("Test",0,-1,-1,-1,-1,-1,-1,cachedCaps,0);}
 static void selectDevice(Context c,String a,String n,int cap,int potential,int v){address=a;}
 static boolean saveName(Context c,String n){return false;}static boolean saveCaps(Context c,int cap,int potential,int v){return false;}static boolean saveMode(Context c,int m){return true;}
 static boolean saveBatterySingle(Context c,int l){return true;}static boolean saveBattery(Context c,int l,int r,int b,int f){return true;}static boolean noteOk(Context c){return true;}
}
class FakeDriver implements TwsClient {
 final java.util.List<Listener> listeners=new java.util.ArrayList<Listener>();volatile int queries;boolean physical,autoConnect=true;int caps=CAP_BATTERY;
 public void init(Context c){}public void addListener(Listener l){listeners.add(l);}public void removeListener(Listener l){listeners.remove(l);}
 public void connect(BluetoothDevice d){if(autoConnect)up();}void up(){physical=true;for(Listener l:new java.util.ArrayList<Listener>(listeners))l.onConnected("Test");}
 public void disconnect(){physical=false;}public boolean isConnected(){return physical;}public int caps(){return caps;}public int potentialCaps(){return CAP_BATTERY|CAP_NOISE;}
 public int verifLevel(){return 0;}public String brandName(){return "Test";}public void queryBattery(){queries++;}public void queryNoise(){queries++;}public void querySpatial(){}public void queryDeepX(){}public void setSpatial(boolean b){}public void setDeepX(int m){}public void setNoiseMode(int m){}
 void battery(){for(Listener l:new java.util.ArrayList<Listener>(listeners))l.onBattery(50,60,70,0);}void noise(){caps|=CAP_NOISE;for(Listener l:new java.util.ArrayList<Listener>(listeners))l.onNoiseMode(MODE_OFF);}
}
class StandardClient extends FakeDriver {public int potentialCaps(){return CAP_BATTERY;}}
'''

OUT.mkdir(parents=True, exist_ok=True)
for name, body in files.items():
    path = SRC / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(body), encoding='utf-8')
sources = [SRC / name for name in files]
sources += [ROOT / 'src/com/hdz/wearcontrol' / (name + '.java') for name in (
    'VolumeGuard', 'VolumeBarView', 'SuprematistDialView', 'VolumePanel', 'MainPanel', 'EffectsPanel',
    'TileSnapshot', 'DeviceState', 'TwsClient', 'SoundPreset', 'HeadsetController')]
sources += [ROOT / 'build/gen/com/hdz/wearcontrol/R.java', ROOT / 'scripts/InteractionChecks.java']
args = OUT / 'sources.txt'
args.write_text('\n'.join('"' + str(p).replace('\\', '/') + '"' for p in sources), encoding='utf-8')
subprocess.run([str(JDK / 'javac.exe'), '-J-Dfile.encoding=UTF-8', '-J-Duser.language=en', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                '-d', str(OUT), '@' + str(args)], check=True)
subprocess.run([str(JDK / 'java.exe'), '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-cp', str(OUT),
                'com.hdz.wearcontrol.InteractionChecks', str(OUT)], check=True)
