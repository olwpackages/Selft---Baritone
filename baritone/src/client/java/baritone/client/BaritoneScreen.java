package baritone.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class BaritoneScreen extends Screen {
    private static final int PANEL_WIDTH = 330;
    private static final int PANEL_HEIGHT = 252;
    private static final int PURPLE = 0xFF9B5CFF;
    private static final int TEXT = 0xFFF5EEFF;
    private static final int MUTED = 0xFFB9A8C9;
    private static final int CARD = 0xFF21152E;
    private static final int CARD_HOVER = 0xFF302044;
    private static final int ON = 0xFFB56CFF;
    private static final int OFF = 0xFF61546B;

    private final BaritoneConfig config;
    private int panelLeft;
    private int panelTop;

    public BaritoneScreen(Minecraft minecraft, BaritoneConfig config) {
        super(Component.literal("Baritone"));
        this.config = config;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = width / 2;
        int centerY = height / 2;
        panelLeft = centerX - PANEL_WIDTH / 2;
        panelTop = centerY - PANEL_HEIGHT / 2;

        graphics.fillGradient(0, 0, width, height, 0xE80D0914, 0xF01A0D25);
        graphics.fill(panelLeft - 3, panelTop - 3, panelLeft + PANEL_WIDTH + 3,
                panelTop + PANEL_HEIGHT + 3, 0x441C0A2C);
        graphics.fillGradient(panelLeft, panelTop, panelLeft + PANEL_WIDTH,
                panelTop + PANEL_HEIGHT, 0xFF241334, 0xFF130C1D);

        int columnLeft = centerX - 2;
        graphics.fillGradient(columnLeft, panelTop + 18, columnLeft + 4,
                panelTop + PANEL_HEIGHT - 18, 0x009D5CFF, 0x705B2C82);
        graphics.fill(panelLeft + 18, panelTop + 57, panelLeft + PANEL_WIDTH - 18,
                panelTop + 58, 0xFF6D3E91);
        graphics.fill(panelLeft + 18, panelTop + PANEL_HEIGHT - 32,
                panelLeft + PANEL_WIDTH - 18, panelTop + PANEL_HEIGHT - 31, 0xFF3C2351);

        graphics.centeredText(font, "BARITONE", centerX, panelTop + 25, TEXT);
        graphics.centeredText(font, "VISUAL CONTROL", centerX, panelTop + 42, MUTED);

        drawToggle(graphics, mouseX, mouseY, panelTop + 78,
                "ORE HIGHLIGHTS", "3D markers through walls", config.oreHighlights());
        drawToggle(graphics, mouseX, mouseY, panelTop + 132,
                "PATH HIGHLIGHTS", "route line and turns", config.pathHighlights());

        graphics.centeredText(font, "CLICK TO TOGGLE   •   ESC TO CLOSE   •   SAVED", centerX,
                panelTop + PANEL_HEIGHT - 20, MUTED);
    }

    private void drawToggle(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int top,
                            String title, String subtitle, boolean enabled) {
        int left = panelLeft + 24;
        int right = panelLeft + PANEL_WIDTH - 24;
        boolean hovered = mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= top + 42;
        graphics.fill(left, top, right, top + 42, hovered ? CARD_HOVER : CARD);
        graphics.fill(left, top, left + 3, top + 42, enabled ? ON : OFF);
        graphics.text(font, title, left + 14, top + 8, TEXT);
        graphics.text(font, subtitle, left + 14, top + 23, MUTED);

        int switchLeft = right - 42;
        int switchTop = top + 12;
        graphics.fill(switchLeft, switchTop, switchLeft + 28, switchTop + 14, enabled ? 0xFF68408B : 0xFF3C3344);
        graphics.fill(enabled ? switchLeft + 16 : switchLeft + 2, switchTop + 2,
                enabled ? switchLeft + 26 : switchLeft + 12, switchTop + 12,
                enabled ? 0xFFEAD8FF : 0xFF95869C);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        int top = panelTop + 78;
        if (inside(panelLeft + 24, top, PANEL_WIDTH - 48, 42, event.x(), event.y())) {
            config.setOreHighlights(!config.oreHighlights());
            return true;
        }
        top = panelTop + 132;
        if (inside(panelLeft + 24, top, PANEL_WIDTH - 48, 42, event.x(), event.y())) {
            config.setPathHighlights(!config.pathHighlights());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        config.save();
        minecraft.setScreenAndShow(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean inside(int left, int top, int width, int height, double x, double y) {
        return x >= left && x <= left + width && y >= top && y <= top + height;
    }
}
