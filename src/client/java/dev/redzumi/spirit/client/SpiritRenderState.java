package dev.redzumi.spirit.client;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/** Рендер-состояние духа: строки «облачка» над головой. */
public class SpiritRenderState extends LivingEntityRenderState {
    public int spiritColor = 0x9FE8FF;
    public String[] bubbleLines = new String[0];
    public int bubbleColor = 0x9FE8FF;
}
