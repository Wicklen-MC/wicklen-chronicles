package dev.wicklen.chronicles.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Native focus/narration/input with a quiet, legible field-journal skin. */
final class JournalButton extends Button {
    private final int accent;
    private final boolean selected;
    private final ItemStack icon;
    private JournalButton(Builder b) {
        super(b.x,b.y,b.w,b.h,b.label,b.press,DEFAULT_NARRATION);
        accent=b.accent; selected=b.selected; icon=b.icon;
        if (b.tooltip!=null) setTooltip(b.tooltip);
    }
    static Builder colored(Component label, OnPress press) { return new Builder(label,press); }
    @Override protected void extractContents(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta) {
        int x=getX(),y=getY(),w=getWidth(),h=getHeight();
        boolean hover=active&&isHoveredOrFocused();
        int bg=selected?accent:hover?0xFFF7EDD9:active?0xFFE7D6B8:0xFFDDD3C0;
        g.fill(x,y+1,x+w,y+h,0xFFAB967C);
        g.fill(x,y,x+w,y+h-1,bg);
        if (selected||hover) g.fill(x,y,x+3,y+h-1,selected?0xFFFAEBCB:accent);
        if (isFocused()) g.outline(x,y,w,h,0xFF426F65);
        var font=Minecraft.getInstance().font;
        int textColor=selected?0xFFFFF5DD:active?SpiritDialogScreen.INK:0xFF897D69;
        int pad=icon.isEmpty()?7:26;
        if (!icon.isEmpty()) g.fakeItem(icon,x+6,y+(h-16)/2);
        String text=font.plainSubstrByWidth(getMessage().getString(),Math.max(1,w-pad-5));
        g.text(font,text,x+pad,y+(h-8)/2,textColor,false);
    }
    static final class Builder extends Button.Builder {
        private final Component label; private final OnPress press;
        private int x,y,w=150,h=20,accent=0xFF567B69;
        private boolean selected;
        private Tooltip tooltip;
        private ItemStack icon=ItemStack.EMPTY;
        Builder(Component label,OnPress press) { super(label,press);this.label=label;this.press=press; }
        @Override public Builder bounds(int x,int y,int w,int h) { this.x=x;this.y=y;this.w=w;this.h=h;return this; }
        @Override public Builder tooltip(Tooltip tooltip) { this.tooltip=tooltip;return this; }
        Builder accent(int value) { accent=value;return this; }
        Builder selected(boolean value) { selected=value;return this; }
        Builder icon(ItemStack value) { icon=value;return this; }
        @Override public JournalButton build() { return new JournalButton(this); }
    }
}
