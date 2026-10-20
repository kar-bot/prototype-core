package dev.prototype.demo.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Entirely addon-owned. Vanilla owns background extraction, as in Core's admin screens. */
final class DemoStatusScreen extends Screen {
    private final boolean stealthed;

    DemoStatusScreen(boolean stealthed) {
        super(Component.literal("Demo Class Status"));
        this.stealthed = stealthed;
    }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 - 60, height / 2 + 30, 120, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.centeredText(font, title, width / 2, height / 2 - 30, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("Neutral stealth: " + (stealthed ? "on" : "off")),
                width / 2, height / 2, 0xFFE0E0E0);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override public boolean isPauseScreen() { return false; }
}
