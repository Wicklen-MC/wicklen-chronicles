package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.WicklenChronicles;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Companion speech is a small field card, independent of chat and the aiming/action bar. */
final class SpiritSpeechHud {
    private static final SpiritSpeechQueue QUEUE=new SpiritSpeechQueue();
    private static KeyMapping journalKey;
    private static boolean registered;
    private static long clock() {return System.nanoTime()/1_000_000;}
    static void register(KeyMapping key) {
        journalKey=key;
        if(registered)return;
        registered=true;
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID,"spirit_speech"),SpiritSpeechHud::render);
    }
    static void accept(String title,int color,String text,int ttlMs) {
        if (!ClientUiSettings.snapshot().speechVisible()) { clear(); return; }
        var screen=Minecraft.getInstance().gui.screen();
        // The open conversation already receives the complete reply in its persistent scrollback.
        if(screen instanceof SpiritDialogScreen journal && journal.tab()==SpiritDialogScreen.Tab.CONVERSATION)return;
        QUEUE.offer(title,color,text,ttlMs,clock());
    }
    static void tick(Minecraft client) {
        if(client.player==null || client.level==null || !ClientUiSettings.snapshot().speechVisible()){clear();return;}
        QUEUE.advance(clock(),displayAllowed(client));
    }
    static boolean displayAllowed(Minecraft client) {
        return ClientUiSettings.snapshot().speechVisible() && client.player!=null && client.level!=null && client.gui.screen()==null
            && client.gui.overlay()==null && !client.gui.hud.isHidden();
    }
    static void clear(){QUEUE.clear();}
    static java.util.List<String> texts(){return QUEUE.texts();}
    static int size(){return QUEUE.size();}

    record Bounds(int x,int y,int width,int height) {}
    static Bounds bounds(int screenWidth,int screenHeight,int lines) {
        int width=Math.min(260,Math.max(96,screenWidth/2-20));
        int height=44+Math.clamp(lines,1,4)*10;
        return new Bounds(12,Math.max(28,screenHeight-92-height),width,height);
    }
    private static void render(GuiGraphicsExtractor g,net.minecraft.client.DeltaTracker delta) {
        var client=Minecraft.getInstance();
        if(!displayAllowed(client))return;
        var active=QUEUE.current();
        if(active==null)return;
        float opacity=active.opacity(clock()) * (float) ClientUiSettings.snapshot().speechOpacity();
        if(opacity<=0)return;
        float scale=effectiveScale(g.guiWidth(),g.guiHeight());
        g.pose().pushMatrix();
        try {
            g.pose().scale(scale,scale);
            renderCard(g,client,active,opacity,scale);
        } finally {
            g.pose().popMatrix();
        }
    }

    static float effectiveScale(int width,int height) {
        // Reserve the guidance card above and the hotbar/action bar below, even at the largest setting.
        return Math.max(0.4f,Math.min((float)ClientUiSettings.snapshot().speechScale(),(height-156f)/84));
    }

    private static void renderCard(GuiGraphicsExtractor g,Minecraft client,SpiritSpeechQueue.Visible active,float opacity,float scale) {
        var speech=active.speech();
        int virtualWidth=(int)(g.guiWidth()/scale),virtualHeight=(int)((g.guiHeight()-92)/scale+92);
        var provisional=bounds(virtualWidth,virtualHeight,4);
        var font=client.font;
        var wrapped=font.splitIgnoringLanguage(Component.literal(speech.text()),provisional.width()-24);
        int count=Math.min(4,wrapped.size());
        var b=bounds(virtualWidth,virtualHeight,count);
        int x=b.x(),y=b.y(),w=b.width(),h=b.height();
        int accent=lighten(speech.color());
        // Stepped corners belong to the same pixel vocabulary as the field journal.
        g.fill(x+4,y+4,x+w+3,y+h+3,tint(0x211C25,0.48f*opacity));
        g.fill(x+2,y,x+w-2,y+h,tint(0x887085,opacity));
        g.fill(x,y+2,x+w,y+h-2,tint(0x887085,opacity));
        g.fill(x+2,y+2,x+w-2,y+h-2,tint(0x302934,0.96f*opacity));
        g.fill(x+2,y+3,x+4,y+h-3,tint(accent,opacity));
        portrait(g,x+10,y+7,speech.color(),opacity);
        String title=font.plainSubstrByWidth(speech.title(),w-46);
        g.text(font,title,x+35,y+12,tint(accent,opacity),false);
        for(int i=0;i<count;i++) {
            String line=wrapped.get(i).getString();
            if(i==count-1 && wrapped.size()>count)line=font.plainSubstrByWidth(line,w-34)+"…";
            g.text(font,line,x+12,y+30+i*10,tint(0xF4E9D8,opacity),false);
        }
        var key=journalKey==null?Component.literal("G"):journalKey.getTranslatedKeyMessage();
        g.text(font,Component.translatable("hud.wicklen_chronicles.history",key),x+12,y+h-13,tint(0xB7B5A2,opacity),false);
    }
    /** Compact hood portrait uses the same cloth, patch, lining and persona-eye palette as the model. */
    private static void portrait(GuiGraphicsExtractor g,int x,int y,int color,float alpha) {
        int cloth=tint(SpiritMaterials.CLOTH,alpha),patch=tint(SpiritMaterials.PATCH,alpha);
        int lining=tint(SpiritMaterials.LINING,alpha),eye=tint(color,alpha);
        g.fill(x+5,y,x+13,y+2,cloth);
        g.fill(x+3,y+2,x+15,y+11,cloth);
        g.fill(x+5,y+4,x+13,y+10,lining);
        g.fill(x+6,y+6,x+8,y+8,eye);g.fill(x+10,y+6,x+12,y+8,eye);
        g.fill(x+13,y+2,x+15,y+6,patch);
        g.fill(x+4,y+11,x+14,y+15,cloth);
        g.fill(x+3,y+14,x+7,y+17,cloth);g.fill(x+10,y+14,x+14,y+16,cloth);
        g.fill(x+11,y+12,x+14,y+14,patch);
        g.fill(x+8,y+12,x+9,y+15,tint(SpiritMaterials.STITCH,alpha));
    }
    private static int lighten(int rgb) {
        int r=((rgb>>16)&255),green=((rgb>>8)&255),blue=(rgb&255);
        return ((r+255)/2<<16)|((green+235)/2<<8)|((blue+218)/2);
    }
    private static int tint(int rgb,float alpha) {return Math.clamp((int)(alpha*255),0,255)<<24|(rgb&0xFFFFFF);}
    private SpiritSpeechHud() {}
}
