package com.hdz.wearcontrol;

import com.hdz.wearcontrol.xiaomi.XiaomiAuth;
import com.hdz.wearcontrol.xiaomi.XiaomiProtocol;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.hdz.wearcontrol.Controls.*;

/** Real driver methods, golden bytes and an explicit transport-flush clock. */
public final class WireChecks {
    private static int checks;
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
    private static byte[] hex(String text) { return ProtocolChecks.hex(text); }

    private static final class Rig implements WireDriver.Port, Controls.Listener {
        final WireDriver driver;
        final CoreChecks.Clock clock = new CoreChecks.Clock();
        final List<byte[]> frames = new ArrayList<>();
        final List<Runnable> completions = new ArrayList<>();
        int connections, errors, failures, mode = -1, left = -1, right = -1, box = -1, charge = -1;
        Boolean spatial;
        Integer eq;
        UUID bleService, notifyId, writeId;
        long settle;
        Rig(String name) {
            DriverModule module = null;
            for (DriverModule candidate : InstalledDrivers.ALL) if (candidate.matches(name)) {
                if (module != null) throw new AssertionError("Ambiguous private protocol for " + name);
                module = candidate;
            }
            if (module == null) throw new AssertionError("Missing driver for " + name);
            driver = module.create();
            driver.bind(name, this, this);
        }
        void open() { driver.onOpen(); }
        void receive(String value) { driver.received(hex(value)); }
        void last(String expected) {
            check(!frames.isEmpty() && Arrays.equals(frames.get(frames.size()-1), hex(expected)),
                    driver.brandName()+" frame expected "+expected+", got "+
                            (frames.isEmpty() ? "(none)" : XiaomiAuth.toHex(frames.get(frames.size()-1))));
        }
        void flushLast() {
            int i = completions.size()-1;
            Runnable r = completions.set(i, null);
            if (r == null) throw new AssertionError("No flush callback");
            r.run();
        }
        void readback(int delay, String write, String query) {
            int before = frames.size();
            int[] completed = {0};
            driver.changeNoise(MODE_TRANS, () -> completed[0]++);
            last(write);
            clock.tick(1200);
            check(completed[0] == 0 && frames.size() == before + 1, "no read before flush");
            flushLast(); clock.tick(delay-1);
            check(completed[0] == 1 && frames.size() == before + 1, "full firmware interval after flush");
            clock.tick(1);
            check(frames.size() >= before + 2 && Arrays.equals(frames.get(before + 1), hex(query)),
                    "first post-write readback on " + driver.brandName());
        }
        public void send(byte[] bytes, Runnable flushed) { frames.add(bytes.clone()); completions.add(flushed); }
        public void after(Runnable task, long ms) { clock.after(task, ms); }
        public void cancel(Runnable task) { clock.cancel(task); }
        public void useBle(UUID svc, UUID notify, UUID write, long delay, long timeout) {
            bleService=svc; notifyId=notify; writeId=write; settle=delay;
        }
        public void fail(String reason) { failures++; }
        public long millis() { return clock.now; }
        public void trace(String tag, String text) { }
        public void onConnected(String name) { connections++; }
        public void onDisconnected(String reason) { errors++; }
        public void onError(String message) { errors++; }
        public void onBattery(int l, int r, int b, int c) { left=l; right=r; box=b; charge=c; }
        public void onNoiseMode(int value) { mode=value; }
        public void onSpatial(Boolean value) { spatial=value; }
        public void onDeepX(Integer value) { eq=value; }
        public void onEqualizer(Integer value) { eq=value; }
    }

    private static void vivo() {
        String[] names = {"vivo TWS Air3 Pro", "vivo TWS 4", "vivo TWS 3e"};
        String[] frames = {"FF030003001B0130020400","FF040003001B0130020301","FF030002001B01300203"};
        for (int i=0; i<names.length; i++) {
            Rig r = new Rig(names[i]); r.open();
            check(Arrays.equals(r.driver.channels(),new int[]{13}) && r.driver.failureCooldown()==2000, "vivo fixed route");
            int before = r.frames.size();
            r.driver.setNoiseMode(MODE_TRANS);
            check(r.frames.size()==before, "unconfirmed ANC never writes");
            r.receive("FF55FF04FF"); r.receive("FF040004001B823000010400");
            check(r.mode==MODE_OFF, "GAIA resynchronizes before mode");
            r.driver.setNoiseMode(MODE_TRANS); r.last(frames[i]);
        }
        Rig r = new Rig(names[0]); r.open();
        r.receive("FF030002001B82180001");
        check(Integer.valueOf(1).equals(r.eq), "DeepX confirmed by report");
        for (int mode : new int[]{0,1,2,3,5}) {
            r.driver.setDeepX(mode); r.last("FF030001001B01180"+mode);
        }
        int n=r.frames.size(); r.driver.setDeepX(4);
        check(r.frames.size()==n, "reserved DeepX slot");
        r.receive("FF030002001B81390001"); check(r.spatial==null, "spatial ACK is not evidence");
        r.receive("FF030003001B8239000001"); check(Boolean.FALSE.equals(r.spatial), "spatial evidence");
        r.driver.setSpatial(true); r.last("FF030001001B013901");
    }
    private static void oppo() {
        Rig r=new Rig("OPPO Enco Free4"); r.open();
        check(Arrays.equals(r.driver.channels(),new int[]{15}), "OPPO fixed channel");
        int[] steps={0x0100,0x0103,0x0200,0x0205,0x010D,0x010C,0x0106,0x0109,0x0108,0x0105};
        for (int i=0; i<steps.length; i++) {
            r.clock.tick(i==0 ? 299 : 199);
            check(r.frames.size()==i, "OPPO step "+i+" wait");
            r.clock.tick(1);
            byte[] f=r.frames.get(i);
            check((f[0]&255)==0xAA && ((f[4]&255)|((f[5]&255)<<8))==steps[i] && f[6]==i+1, "OPPO step "+i);
            if(i==5) r.receive("AA0C00000C812205000001011000");
            r.driver.setNoiseMode(MODE_TRANS); r.driver.queryBattery(); r.clock.tick(1000);
            check(r.frames.size()==i+1 && r.connections==0, "blocked write cannot advance initialization");
            r.flushLast();
        }
        r.clock.tick(399); check(r.connections==0 && r.mode==-1, "400ms final delay");
        r.clock.tick(1); check(r.connections==1 && r.mode==MODE_ANC, "handshake state delivered once");
        r.readback(350,"AA0A00000404F30300010104","AA0900000C01F102000101");
    }
    private static void huawei() {
        Rig r=new Rig("HUAWEI FreeBuds 6i"); r.open(); r.clock.tick(250);
        check(r.driver.cancelDiscovery() && Arrays.equals(r.driver.channels(),new int[]{1,16}), "Huawei discovery and channels");
        r.receive("5A0007002B2A010200010511"); check(r.mode==-1, "bad CRC rejected");
        r.receive("5A0007002B2A010200010510"); check(r.mode==MODE_ANC, "Huawei good CRC");
        r.readback(400,"5A0007002B04010202FFAABF","5A0007002B2A010002001D33");
        r.driver.queryEqualizer(); r.last("5A0009002B4A010002000300ACA2");
        r.receive("5A000E002B4A0101000201020303010209A694");
        check(Integer.valueOf(2).equals(r.eq), "Huawei EQ report");
        r.driver.setEqualizer(2); r.last("5A0006002B490101021F79");
    }
    private static void nice() {
        Rig r=new Rig("NiceHCK OriG in"); r.open(); r.clock.tick(120);
        check(r.driver.failureCooldown()==1500, "NiceHCK cooldown");
        r.receive("4E040000010103"); check(r.mode==MODE_ANC, "deep ANC is ANC");
        r.readback(150,"4E05000001020100","4E0300000101");
    }
    private static void xiaomi() {
        Rig r=new Rig("Redmi Buds 6"); r.open();
        check(Arrays.equals(r.driver.channels(),new int[]{6,29}), "Redmi routes");
        byte[] hello=r.frames.get(0);
        int sn=hello[7]&255;
        check(hello[4]==0x50 && hello.length==26 && hello[8]==1, "normal authentication challenge");
        byte[] answer=new byte[17];
        answer[0]=1;
        System.arraycopy(XiaomiAuth.e21(Arrays.copyOfRange(hello,9,25)),0,answer,1,16);
        r.driver.received(XiaomiProtocol.response(0x50,1,(sn+1)&255,0,answer));
        check(r.frames.size()==1 && !r.driver.isConnected(), "stale auth serial ignored");
        byte[] challenge=hex("01385C2AECB03BFB32AF3C54EC18DB5C02");
        r.driver.received(XiaomiProtocol.command(0x50,1,123,challenge));
        byte[] response=r.frames.get(1);
        check(response[4]==0x50 && response[7]==0 && response[8]==123 &&
                Arrays.equals(Arrays.copyOfRange(response,9,response.length-1),hex("0199A16782B8E05DF000099BC2CB3417BF")),
                "device challenge answered using published E21 vector");
        r.driver.received(XiaomiProtocol.response(0x50,1,sn,0,answer));
        byte[] done=r.frames.get(2); check(done[4]==0x51 && !r.driver.isConnected(),"auth done must be acknowledged");
        r.driver.received(XiaomiProtocol.response(0x51,1,done[7]&255,0,new byte[]{1}));
        check(r.connections==1,"authenticated session ready");
        Rig wrong=new Rig("Redmi Buds 6"); wrong.open();
        wrong.driver.received(XiaomiProtocol.response(0x50,1,wrong.frames.get(0)[7]&255,0,new byte[17]));
        check(wrong.failures==1 && !wrong.driver.isConnected(),"failed authentication");
        Rig buds5=new Rig("Redmi Buds 5"); buds5.open();
        check(buds5.frames.size()==2 && buds5.frames.get(0)[4]==2 && (buds5.frames.get(1)[4]&255)==0xF3,
                "Buds 5 starts with two documented read-only requests");
        buds5.driver.received(XiaomiProtocol.response(2,4,0,0,hex("04076163FF")));
        check(buds5.connections==1 && buds5.left==97 && buds5.box==255,"Buds 5 valid status opens link");
        r.receive("FEDCBAC106000101EF");
        for(byte[] f:r.frames) check(f[4]!=6,"driver never transmits disconnect opcode");
        for(boolean reply:new boolean[]{false,true}) {
            boolean rejected=false;
            try {
                if(reply) XiaomiProtocol.response(6,0,0,0,new byte[0]);
                else XiaomiProtocol.command(6,0,0,new byte[0]);
            } catch(IllegalArgumentException expected) { rejected=true; }
            check(rejected,"encoder rejects prohibited opcode");
        }
    }
    private static void experimental() {
        Rig ed=new Rig("EDIFIER FitBuds Turbo"); ed.driver.openFallback();
        check(ed.bleService==null && ed.notifyId!=null && ed.writeId!=null && ed.settle==250 &&
                ed.driver.bleFragmentDelay()==30 && ed.driver.paceLastBleFragment() && ed.driver.failureCooldown()==1500,
                "Edifier fallback and pacing policy");
        ed.open();
        check(ed.connections==0,"Edifier probes before ready");
        ed.receive("BBECF200060364640003117E");
        check(ed.left==100 && ed.right==100 && ed.box==255, "FitBuds Turbo captured battery report");
        ed.receive("BBECCC0002100186");
        check(ed.connections==1 && ed.mode==MODE_ANC,"FitBuds Turbo plaintext response");
        ed.driver.setNoiseMode(MODE_TRANS);
        for(byte[] frame:ed.frames) {
            int opcode=frame[2]&255;
            check(opcode==0xF2 || opcode==0xD0 || opcode==0xCC || opcode==0xC1,"Edifier outbound allowlist");
        }
        byte[] last=ed.frames.get(ed.frames.size()-1);
        check((last[2]&255)==0xC1 && last[5]==0x10 && last[6]==4,"plaintext V2 noise set");

        Rig rose=new Rig("CERAMICS-U"); rose.driver.openFallback();
        check(rose.bleService!=null && rose.notifyId!=null && rose.writeId!=null && rose.driver.bleFragmentDelay()==40,"Rose BLE policy");
        rose.open(); rose.clock.tick(299); rose.driver.queryNoise();
        check(rose.frames.size()==1,"Rose deduplicates queries throughout 300ms");
        rose.clock.tick(1); rose.driver.queryNoise(); check(rose.frames.size()==2, "Rose next read after 300ms");
        rose.receive("DD00040CE463FF0209013EAA"); check(rose.connections==0,"Rose bad state ignored");
        rose.receive("DD00040CE463FF0209013FAA"); check(rose.connections==1 && rose.mode==MODE_ANC,"Rose requires valid status");
        Rig sony=new Rig("WF-1000XM5"); sony.open();
        check(sony.connections==0 && sony.frames.size()==1,"Sony waits for version");
        sony.clock.tick(5000); check(sony.frames.size()==1,"Sony ACK budget starts after flush");
        sony.flushLast(); sony.clock.tick(999); check(sony.frames.size()==1,"Sony first ACK interval");
        sony.clock.tick(1); check(sony.frames.size()==2 && Arrays.equals(sony.frames.get(0),sony.frames.get(1)),"Sony retransmit");
        sony.driver.received(SonyProtocol.encode(12,0,hex("0100000000000000")));
        check(sony.connections==1,"Sony identified MDR version");
        Rig az=new Rig("Technics EAH-AZ80"); az.open(); az.clock.tick(0);
        az.receive("055B0300D60C00"); check(az.connections==0,"Technics ACK not readiness");
        az.receive("055B06000A0000026432"); check(az.connections==1 && az.mode==MODE_TRANS,"RACE outside-control report");
    }
    private static void evidence() {
        for(String name:new String[]{"vivo TWS Air3 Pro","FreeBuds 6i","FreeBuds SE 4 ANC","HONOR Earbuds X9",
                "Redmi Buds 6","OPPO Enco Air5 Pro","Enco Free4","NiceHCK OriG in","原道原点"}) {
            check(new Rig(name).driver.verifLevel()==VERIF_REAL,"baseline hardware evidence for "+name);
        }
        for(String name:new String[]{"vivo TWS 3e","FreeBuds 5i","Redmi Buds 5","OPPO Enco Air3 Pro",
                "NiceHCK OriG in 2","WF-1000XM5","Technics EAH-AZ80","EDIFIER FitBuds Turbo","CERAMICS-U"}) {
            check(new Rig(name).driver.verifLevel()==VERIF_UNTESTED,"experimental model "+name);
        }
        for(String name:new String[]{"Generic Original Earbuds","Xiaomi Buds 5 Pro","小米 Buds 5 Pro","Galaxy Buds3"}) {
            for(DriverModule m:InstalledDrivers.ALL) check(!m.matches(name),"standard-only device "+name);
        }
    }
    public static void main(String[] args) {
        vivo(); oppo(); huawei(); nice(); xiaomi(); experimental(); evidence();
        System.out.println("PASS "+checks+" driver wire checks");
    }
}
