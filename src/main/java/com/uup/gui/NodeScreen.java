package com.uup.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.uup.UniversalUnlimitedPipe;
import com.uup.core.network.TransferMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class NodeScreen extends AbstractContainerScreen<NodeMenu> {

    private static final ResourceLocation TEXTURE = new ResourceLocation(UniversalUnlimitedPipe.MODID, "textures/gui/node_gui.png");

    private Button modeButton;
    private Button channelButton;

    public NodeScreen(NodeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        int left = this.leftPos;
        int top = this.topPos;

        // Mode Button (Top-Left)
        modeButton = addRenderableWidget(Button.builder(Component.literal("モード"), btn -> {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 4);
            }
        }).bounds(left + 10, top + 18, 58, 16).build());

        // Channel Button (Top-Right)
        channelButton = addRenderableWidget(Button.builder(Component.literal("Ch: 0"), btn -> {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 5);
            }
        }).bounds(left + 108, top + 18, 58, 16).build());

        // Priority Buttons
        addRenderableWidget(Button.builder(Component.literal("-10"), btn -> sendButtonClick(0))
                .bounds(left + 10, top + 56, 26, 16).build());
        addRenderableWidget(Button.builder(Component.literal("-1"), btn -> sendButtonClick(1))
                .bounds(left + 38, top + 56, 20, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+1"), btn -> sendButtonClick(2))
                .bounds(left + 118, top + 56, 20, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+10"), btn -> sendButtonClick(3))
                .bounds(left + 140, top + 56, 26, 16).build());
    }

    private void sendButtonClick(int buttonId) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        TransferMode mode = menu.getMode();
        String modeColor = switch (mode) {
            case EXTRACT -> "§c搬出";
            case INSERT -> "§9搬入";
            case BOTH -> "§a両方";
            case DISABLED -> "§7無効";
        };
        modeButton.setMessage(Component.literal(modeColor));
        channelButton.setMessage(Component.literal("§6Ch: " + menu.getChannelId()));
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        // Draw clean modern background
        guiGraphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xFFC6C6C6);
        guiGraphics.fill(x + 1, y + 1, x + this.imageWidth - 1, y + this.imageHeight - 1, 0xFFE0E0E0);
        guiGraphics.fill(x + 2, y + 2, x + this.imageWidth - 2, y + this.imageHeight - 2, 0xFFC6C6C6);

        // Upgrade Slot Box (Center: 80, 36)
        int slotX = x + 80 - 1;
        int slotY = y + 36 - 1;
        guiGraphics.fill(slotX, slotY, slotX + 18, slotY + 18, 0xFF373737);
        guiGraphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0xFF8B8B8B);

        // Player Inventory Slots Backgrounds
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                int px = x + 8 + col * 18 - 1;
                int py = y + 84 + row * 18 - 1;
                guiGraphics.fill(px, py, px + 18, py + 18, 0xFF373737);
                guiGraphics.fill(px + 1, py + 1, px + 17, py + 17, 0xFF8B8B8B);
            }
        }
        for (int col = 0; col < 9; ++col) {
            int px = x + 8 + col * 18 - 1;
            int py = y + 142 - 1;
            guiGraphics.fill(px, py, px + 18, py + 18, 0xFF373737);
            guiGraphics.fill(px + 1, py + 1, px + 17, py + 17, 0xFF8B8B8B);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, 6, 0x404040, false);
        
        // Upgrade slot label
        guiGraphics.drawCenteredString(this.font, "§8強化", 89, 25, 0x555555);

        // Priority text
        int prio = menu.getPriority();
        String prioText = "§1優先度: " + (prio >= 0 ? "+" + prio : prio);
        guiGraphics.drawCenteredString(this.font, prioText, 89, 60, 0x222222);

        guiGraphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }
}
