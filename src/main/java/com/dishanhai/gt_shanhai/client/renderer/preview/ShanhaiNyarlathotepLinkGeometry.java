package com.dishanhai.gt_shanhai.client.renderer.preview;

import java.util.ArrayList;
import java.util.List;
import static com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.*;

/** 山海：黑日與歸墟間的立體祭儀通道、分叉時空裂紋及方向性衝擊；不讀取任何世界。 */
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
        // 正常模式理論上限 2016 面（含每字最多九筆的雙層密文），低特效上限 868 面。
        int channels=degraded?2:CHANNELS,segments=degraded?24:48;
        int lanes=degraded?2:4,steps=degraded?16:28,fragments=degraded?6:20;
        double clock=seconds+Math.floorMod(seed^(seed>>>32),65536)/65536.0*9;
        var opaque=new ArrayList<Quad>(channels*32);
        var glow=new ArrayList<Quad>(channels*640);
        for(int channel=0;channel<channels;channel++) {
            Point portal=portalCenter(guixu,channel,horizon),normal=direction(channel);
            Point u=side(normal),v=normal.cross(u).unit();
            // 薄黑色橢圓裂口，與光暈分批提交；未連結時兩批都完全沒有幾何。
            for(int step=0;step<32;step++) {
                double a=step*Math.PI/16,b=(step+1)*Math.PI/16;
                Point first=ellipse(portal,u,v,2.55,4.3,a),second=ellipse(portal,u,v,2.55,4.3,b);
                opaque.add(new Quad(portal,first,second,portal,.008f,.004f,.018f,1));
            }
            double ritualWeight=.5+.5*Math.sin(clock*1.4+channel);
            for(int ring=0;ring<3;ring++) {
                int ringSegments=ring==2?segments/2:segments;
                for(int step=0;step<ringSegments;step++) {
                    if(Math.floorMod(step+channel*3+ring*2,13)==0) continue;
                    double a=step*Math.PI*2/ringSegments,b=(step+1)*Math.PI*2/ringSegments;
                    double pulse=1+.035*Math.sin(clock*2+step*.7+channel);
                    double ru=(2.65+ring*.45)*pulse,rv=(4.45+ring*.45)*pulse,width=ring==0?.15:ring==1?.35:.75;
                    Point pa=ellipse(portal,u,v,ru,rv,a),pb=ellipse(portal,u,v,ru,rv,b);
                    Point radial=u.scale(Math.cos((a+b)/2)).add(v.scale(Math.sin((a+b)/2))).scale(width);
                    float alpha=(float)((ring==0?.85:ring==1?.28:.065)*(.72+.28*Math.sin(clock*3+a*5))
                            *(1-.35*ritualWeight));
                    strip(glow,pa,pb,radial,ring==0?.55f:.25f,ring==0?.9f:.32f,1,alpha);
                }
            }
            throat(glow,portal,normal,u,v,clock,channel,degraded);
            cipherRings(glow,portal,normal,u,v,clock,seed,channel,degraded);
            crossingPulses(glow,guixu,channel,clock,horizon,u,v,degraded);
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
            rifts(glow,portal,normal,u,v,clock,channel,degraded);
        }
        if(opaque.size()+glow.size()>MAX_QUADS) throw new IllegalStateException("Portal geometry budget exceeded");
        return new Frame(List.copyOf(opaque),List.copyOf(glow));
    }

    /** 黑色封底兩側均有縱深，繞至背面仍能看到喉部；亮環不藏在封底後。 */
    private static void throat(List<Quad> out,Point portal,Point normal,Point u,Point v,
                               double clock,int channel,boolean degraded) {
        int steps=degraded?4:6;
        for(int face=-1;face<=1;face+=2) {
            for(int ring=0;ring<2;ring++) {
                double depth=(ring+1)/3.0,scale=1-depth*.8;
                Point center=portal.add(normal.scale(face*depth*3.5));
                int segments=degraded?12:16;
                for(int step=0;step<segments;step++) {
                    double a=step*Math.PI*2/segments+clock*.15*face,b=a+Math.PI*2/segments*.82;
                    Point pa=ellipse(center,u,v,1.85*scale,3.1*scale,a);
                    Point pb=ellipse(center,u,v,1.85*scale,3.1*scale,b);
                    Point width=u.scale(Math.cos((a+b)/2)).add(v.scale(Math.sin((a+b)/2))).scale(.085);
                    strip(out,pa,pb,width,.4f,.75f,1,(float)(.35*(1-depth)));
                }
            }
            for(int arm=0;arm<3;arm++) for(int step=0;step<steps;step++) {
                double a=step/(double)steps,b=(step+1)/(double)steps;
                double phase=face*clock*.9+arm*Math.PI*2/3+channel*.5;
                Point pa=ellipse(portal.add(normal.scale(face*(.12+3.6*a))),u,v,
                        2.35*(1-a),3.9*(1-a),phase+a*5);
                Point pb=ellipse(portal.add(normal.scale(face*(.12+3.6*b))),u,v,
                        2.35*(1-b),3.9*(1-b),phase+b*5);
                Point width=u.scale(Math.cos(phase+a*5)).add(v.scale(Math.sin(phase+a*5))).scale(.075);
                strip(out,pa,pb,width,.38f,.68f,1,(float)(.42*(1-a)));
            }
        }
    }

    private static void cipherRings(List<Quad> out,Point portal,Point normal,Point u,Point v,
                                    double clock,long seed,int channel,boolean degraded) {
        int count=degraded?2:4;
        for(int halo=0;halo<2;halo++) for(int index=0;index<count;index++) {
            double turn=clock*(halo==0?.16:-.12),angle=index*Math.PI*2/count+turn+channel*.4;
            Point center=ellipse(portal.add(normal.scale(halo==0?-.18:.18)),u,v,
                    3.9+halo*.55,5.7+halo*.55,angle);
            Point tangent=u.scale(-Math.sin(angle)).add(v.scale(Math.cos(angle)));
            Point radial=u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle)));
            double cycle=clock/6.5+index*.17+halo*.23+channel*.11;
            double weight=.55+.45*(.5+.5*Math.sin(clock*1.4+channel));
            ShanhaiCipherGeometry.glyph(out,center,tangent,radial,1.8,cycle,
                    (int)(seed^(seed>>>32))+channel*173+halo*101+index*31,weight);
        }
    }

    /** 共享的穿越波前：奈亞收縮至裂口，歸墟向外擴散並朝黑洞方向前進。 */
    static Point pulsePosition(boolean guixu,int channel,double progress,double angle,double horizon) {
        validate(0,horizon);
        if(!Double.isFinite(progress) || progress<0 || progress>1 || !Double.isFinite(angle))
            throw new IllegalArgumentException("Invalid crossing pulse");
        Point normal=direction(channel),u=side(normal),v=normal.cross(u).unit();
        double travel=guixu?progress:1-progress,scale=1+1.2*travel;
        Point center=portalCenter(guixu,channel,horizon).add(normal.scale(-(.35+3.2*travel)));
        return ellipse(center,u,v,2.7*scale,4.5*scale,angle);
    }

    private static void crossingPulses(List<Quad> out,boolean guixu,int channel,double clock,
                                       double horizon,Point u,Point v,boolean degraded) {
        int rings=degraded?1:2,steps=degraded?12:16;
        for(int ring=0;ring<rings;ring++) {
            double life=fract(clock*.42+ring/(double)rings+channel*.173);
            float alpha=(float)(.5*Math.pow(Math.sin(Math.PI*life),2));
            for(int step=0;step<steps;step++) {
                double a=step*Math.PI*2/steps,b=(step+1)*Math.PI*2/steps;
                Point pa=pulsePosition(guixu,channel,life,a,horizon),pb=pulsePosition(guixu,channel,life,b,horizon);
                Point width=u.scale(Math.cos((a+b)/2)).add(v.scale(Math.sin((a+b)/2))).scale(.12+.18*life);
                strip(out,pa,pb,width,.55f,.95f,1,alpha);
            }
        }
    }

    /** 長主幹＋折線分叉，亮芯與寬光暈共用節點；連續變形避免每幀隨機閃斷。 */
    private static void rifts(List<Quad> out,Point portal,Point normal,Point u,Point v,
                              double clock,int channel,boolean degraded) {
        int count=degraded?3:6;
        for(int bolt=0;bolt<count;bolt++) {
            double phase=bolt*2.37+channel*1.71;
            double angle=bolt*Math.PI*2/count+channel*.4+clock*.07;
            double energy=.35+.65*Math.pow(.5+.5*Math.sin(clock*2.1+phase),3);
            Point width=u.scale(-Math.sin(angle)).add(v.scale(Math.cos(angle)));
            for(int step=0;step<4;step++) {
                Point a=riftPoint(portal,normal,u,v,angle,step/4.0,clock,phase,energy);
                Point b=riftPoint(portal,normal,u,v,angle,(step+1)/4.0,clock,phase,energy);
                riftSegment(out,a,b,width,(float)energy);
            }
            Point branch=riftPoint(portal,normal,u,v,angle,.5,clock,phase,energy);
            Point tip=riftPoint(portal,normal,u,v,angle+.22,.92,clock,phase,energy);
            Point bend=branch.add(tip).scale(.5).add(width.scale(1.1*Math.sin(clock*3+phase)));
            riftSegment(out,branch,bend,width,(float)(energy*.75));
            riftSegment(out,bend,tip,width,(float)(energy*.5));
        }
    }

    private static Point riftPoint(Point portal,Point normal,Point u,Point v,double angle,
                                    double progress,double clock,double phase,double energy) {
        double spread=1+progress*(.85+.3*energy);
        double kink=Math.sin(Math.PI*progress)*(.16*Math.sin(clock*3.7+phase+progress*19));
        Point center=portal.add(normal.scale(.35*Math.sin(clock*1.3+phase+progress*8)));
        return ellipse(center,u,v,3.05*spread,4.65*spread,angle+kink);
    }

    private static void riftSegment(List<Quad> out,Point a,Point b,Point width,float alpha) {
        strip(out,a,b,width.scale(.4),.45f,.22f,1,alpha*.4f);
        strip(out,a,b,width.scale(.085),.9f,.7f,1,alpha);
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
