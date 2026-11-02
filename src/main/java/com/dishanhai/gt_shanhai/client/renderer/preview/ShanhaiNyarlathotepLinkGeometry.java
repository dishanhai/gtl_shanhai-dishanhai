package com.dishanhai.gt_shanhai.client.renderer.preview;

import java.util.ArrayList;
import java.util.List;
import static com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.*;

/** 山海：黑日送入通道、歸墟通道吐出後吸入黑洞；純幾何，不讀取任何世界。 */
public final class ShanhaiNyarlathotepLinkGeometry {
    public static final int CHANNELS = 3;
    public static final int MAX_QUADS = 2048;
    private static final double PORTAL_SCALE = 2;
    public record Frame(List<Quad> opaque, List<Quad> glow) {}
    private static final Frame EMPTY = new Frame(List.of(), List.of());

    private ShanhaiNyarlathotepLinkGeometry() {}

    private static Point center(boolean guixu) {
        return guixu ? new Point(0,0,0) : ShanhaiBlackSunGeometry.CENTER;
    }
    private static Point direction(int channel) {
        if (channel<0 || channel>=CHANNELS) throw new IllegalArgumentException("Invalid link channel");
        double angle=channel*Math.PI*2/CHANNELS+Math.PI/6;
        return new Point(Math.cos(angle)*.92,channel==1?.32:-.24,Math.sin(angle)*.92).unit();
    }
    private static Point side(Point normal) { return new Point(0,1,0).cross(normal).unit(); }
    private static double radius(boolean guixu,double horizon) { return guixu?horizon:ShanhaiBlackSunGeometry.RADIUS; }
    private static void validate(double seconds,double horizon) {
        if(!Double.isFinite(seconds) || !Double.isFinite(horizon) || horizon<=0 || horizon>512)
            throw new IllegalArgumentException("Invalid portal time or radius");
    }

    /** 在各自控制器座標系內；歸墟座標原點必須已套既有環心 pivot。 */
    public static Point portalCenter(boolean guixu,int channel,double horizon) {
        validate(0,horizon);
        double r=radius(guixu,horizon);
        return center(guixu).add(direction(channel).scale(guixu?r*3+24:r+14));
    }

    /** 進度永遠沿實際物質流向遞增：黑日→通道，或通道→黑洞視界內。 */
    public static Point materialPosition(boolean guixu,int channel,double progress,double seconds,double horizon) {
        validate(seconds,horizon);
        if(!Double.isFinite(progress) || progress<0 || progress>1) throw new IllegalArgumentException("Invalid stream progress");
        Point normal=direction(channel),u=side(normal),v=normal.cross(u).unit();
        Point portal=portalCenter(guixu,channel,horizon);
        Point near=center(guixu).add(normal.scale(radius(guixu,horizon)*(guixu?.75:1.12)));
        Point start=guixu?portal:near,end=guixu?near:portal;
        Point position=start.scale(1-progress).add(end.scale(progress));
        double envelope=Math.sin(Math.PI*progress),phase=progress*Math.PI*3+seconds*.75+channel*2;
        return position.add(u.scale(envelope*Math.sin(phase)*2.3))
                .add(v.scale(envelope*Math.cos(phase)*1.4));
    }

    public static Frame frame(double seconds,long seed,boolean guixu,boolean linked,boolean degraded,double horizon) {
        validate(seconds,horizon);
        if(!linked) return EMPTY;
        int channels=degraded?2:CHANNELS,segments=degraded?32:64;
        int lanes=degraded?2:4,steps=degraded?20:40,fragments=degraded?8:32;
        double clock=seconds+Math.floorMod(seed^(seed>>>32),65536)/65536.0*9;
        var opaque=new ArrayList<Quad>(channels*32);
        var glow=new ArrayList<Quad>(channels*400);
        for(int channel=0;channel<channels;channel++) {
            Point portal=portalCenter(guixu,channel,horizon),normal=direction(channel);
            Point u=side(normal),v=normal.cross(u).unit();
            // 薄黑色橢圓裂口，與光暈分批提交；未連結時兩批都完全沒有幾何。
            for(int step=0;step<32;step++) {
                double a=step*Math.PI/16,b=(step+1)*Math.PI/16;
                Point first=ellipse(portal,u,v,2.55,4.3,a),second=ellipse(portal,u,v,2.55,4.3,b);
                opaque.add(new Quad(portal,first,second,portal,.008f,.004f,.018f,1));
            }
            for(int ring=0;ring<3;ring++) for(int step=0;step<segments;step++) {
                if(Math.floorMod(step+channel*3+ring*2,13)==0) continue;
                double a=step*Math.PI*2/segments,b=(step+1)*Math.PI*2/segments;
                double pulse=1+.035*Math.sin(clock*2+step*.7+channel);
                double ru=(2.65+ring*.45)*pulse,rv=(4.45+ring*.45)*pulse,width=ring==0?.15:ring==1?.35:.75;
                Point pa=ellipse(portal,u,v,ru,rv,a),pb=ellipse(portal,u,v,ru,rv,b);
                Point radial=u.scale(Math.cos((a+b)/2)).add(v.scale(Math.sin((a+b)/2))).scale(width);
                float alpha=(float)((ring==0?.85:ring==1?.28:.065)*(.72+.28*Math.sin(clock*3+a*5)));
                strip(glow,pa,pb,radial,ring==0?.55f:.25f,ring==0?.9f:.32f,1,alpha);
            }
            // 裂口內三道旋入暗面的短螺旋，讓通道有喉部深度而非一個平面圈。
            for(int arm=0;arm<3;arm++) for(int step=0;step<8;step++) {
                double a=step/8.0,b=(step+1)/8.0,phase=clock*.65+arm*Math.PI*2/3;
                Point pa=ellipse(portal,u,v,2.3*a,3.8*a,phase+a*3.8);
                Point pb=ellipse(portal,u,v,2.3*b,3.8*b,phase+b*3.8);
                strip(glow,pa,pb,u.scale(.06),.32f,.65f,1,(float)(.18*(1-a)));
            }
            // 四股物質絲沿同一有向路徑流動，端點收束；不畫跨維度長直線。
            for(int lane=0;lane<lanes;lane++) for(int step=0;step<steps;step++) {
                double a=step/(double)steps,b=(step+1)/(double)steps;
                Point pa=lanePosition(guixu,channel,lane,a,clock,horizon);
                Point pb=lanePosition(guixu,channel,lane,b,clock,horizon);
                double streak=.5+.5*Math.sin(clock*5-a*20+lane*2);
                float alpha=(float)(.08+.26*streak);
                strip(glow,pa,pb,u.scale(.045+streak*.035),.42f,.7f,1,alpha);
            }
            // 碎片以進度循環，不是只閃爍的靜態點；近端淡出避免瞬移。
            for(int fragment=0;fragment<fragments;fragment++) {
                double t=fract(clock*.24+fragment/(double)fragments+channel*.31);
                Point p=lanePosition(guixu,channel,fragment%lanes,t,clock,horizon);
                double fade=Math.min(1,Math.min(t,1-t)*10),size=.07+.16*fract(fragment*.618+channel*.27);
                float alpha=(float)(.75*fade);
                fragment(glow,p,u,v,normal,size,alpha);
            }
            // 裂口邊緣的短電弧，讓三個通道具有同源而不同相位的脈動。
            for(int spark=0;spark<(degraded?6:12);spark++) {
                double angle=clock*.3+spark*Math.PI/6+channel;
                Point start=ellipse(portal,u,v,3.2,5,angle);
                Point end=ellipse(portal,u,v,3.5+.7*Math.sin(clock*4+spark),5.6,angle+.12);
                strip(glow,start,end,u.scale(.055),.75f,.35f,1,.42f);
            }
        }
        if(opaque.size()+glow.size()>MAX_QUADS) throw new IllegalStateException("Portal geometry budget exceeded");
        return new Frame(List.copyOf(opaque),List.copyOf(glow));
    }
    private static Point lanePosition(boolean guixu,int channel,int lane,double t,double clock,double horizon) {
        Point n=direction(channel),u=side(n),v=n.cross(u).unit();
        double phase=lane*Math.PI/2+t*9-clock*.8,width=Math.sin(Math.PI*t)*(.25+lane*.18);
        return materialPosition(guixu,channel,t,clock,horizon)
                .add(u.scale(Math.cos(phase)*width)).add(v.scale(Math.sin(phase)*width));
    }
    private static Point ellipse(Point center,Point u,Point v,double ru,double rv,double angle) {
        return center.add(u.scale(Math.cos(angle)*ru*PORTAL_SCALE))
                .add(v.scale(Math.sin(angle)*rv*PORTAL_SCALE));
    }
    private static double fract(double n) { return n-Math.floor(n); }
    private static void fragment(List<Quad> out,Point p,Point u,Point v,Point n,double size,float alpha) {
        Point a=u.scale(size),b=v.scale(size*.7),c=n.scale(size*1.8);
        out.add(new Quad(p.add(a),p.add(b),p.add(a.scale(-1)),p.add(b.scale(-1)),.65f,.85f,1,alpha));
        out.add(new Quad(p.add(a),p.add(c),p.add(a.scale(-1)),p.add(c.scale(-1)),.38f,.55f,.8f,alpha));
        out.add(new Quad(p.add(b),p.add(c),p.add(b.scale(-1)),p.add(c.scale(-1)),.78f,.48f,1,alpha));
    }
}
