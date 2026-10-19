package dev.prototype.core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared native controls and scrolling. A request changes no presentation state until the server answers. */
abstract class AdminPanelScreen extends Screen {
    static final int TEXT = 0xFFF1F1F3;
    static final int MUTED = 0xFFADB0BE;
    static final int GREEN = 0xFF8FE0A4;
    static final int RED = 0xFFFF9999;
    static final int ACCENT = 0xFFC1ACFF;
    protected AdminSnapshot snapshot;
    protected int panelX, panelWidth, bodyTop, bodyBottom;
    protected int scroll;
    protected String query = "";
    protected boolean pending;
    private int pendingTicks;
    private String localStatus = "";
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();

    AdminPanelScreen(AdminSnapshot snapshot, Component title) {
        super(title);
        this.snapshot = snapshot;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(760, Math.max(220, width - 24));
        panelX = (width - panelWidth) / 2;
        bodyTop = 116;
        bodyBottom = Math.max(bodyTop + 24, height - 48);
        rowWidgets.clear();
        buildNavigation(panelX + 10, 51);
        EditBox search = new EditBox(font, panelX + 10, 77, panelWidth - 20, 18,
            Component.literal(searchHint()));
        search.setMaxLength(96);
        search.setHint(Component.literal(searchHint()));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            scroll = 0;
            rebuildRows();
        });
        addRenderableWidget(search);
        setInitialFocus(search);
        rebuildRows();
    }

    abstract void buildNavigation(int x, int y);
    abstract String searchHint();
    abstract int rowHeight();
    abstract int resultCount();
    abstract void buildRows();
    abstract void drawRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY);
    abstract String subtitle();

    protected int visibleRows() { return Math.max(1, (bodyBottom - bodyTop) / rowHeight()); }
    protected int maximumScroll() { return Math.max(0, resultCount() - visibleRows()); }
    protected int rowY(int index) { return bodyTop + index * rowHeight(); }

    protected final void rebuildRows() {
        for (AbstractWidget widget : rowWidgets) removeWidget(widget);
        rowWidgets.clear();
        scroll = Math.max(0, Math.min(scroll, maximumScroll()));
        buildRows();
        int footer = height - 37;
        rowButton("<", panelX + panelWidth - 69, footer, 24, 20,
            () -> scroll(-visibleRows()), "Previous page", scroll > 0, false);
        rowButton(">", panelX + panelWidth - 39, footer, 24, 20,
            () -> scroll(visibleRows()), "Next page", scroll < maximumScroll(), false);
    }

    protected Button rowButton(String text, int x, int y, int w, int h, Runnable action,
                               String tooltip, boolean available, boolean mutation) {
        Button button = Button.builder(Component.literal(text), ignored -> action.run())
            .bounds(x, y, w, h).tooltip(Tooltip.create(Component.literal(tooltip))).build();
        button.active = available && (!mutation || !pending);
        rowWidgets.add(button);
        return addRenderableWidget(button);
    }

    protected Button navigationButton(String text, int x, int y, int w, Runnable action, String tip) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run())
            .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.literal(tip))).build());
    }

    private void scroll(int amount) {
        int next = Math.max(0, Math.min(scroll + amount, maximumScroll()));
        if (next != scroll) {
            scroll = next;
            rebuildRows();
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y >= bodyTop && y <= bodyBottom && x >= panelX && x <= panelX + panelWidth) {
            scroll(vertical > 0 ? -1 : vertical < 0 ? 1 : 0);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    protected void request(String action, UUID player, String id, Boolean enabled) {
        if (pending) return;
        if (PrototypeClient.send(action, snapshot.revision(), player, id, enabled)) {
            pending = true;
            pendingTicks = 0;
            localStatus = "Waiting for server…";
        } else {
            localStatus = "Prototype Core is unavailable on this connection.";
        }
        rebuildRows();
    }

    protected void refresh() {
        PrototypeClient.send("refresh", snapshot.revision(), null, null, null);
        localStatus = "Refreshing from server…";
    }

    void update(AdminSnapshot latest) {
        snapshot = latest;
        pending = false;
        pendingTicks = 0;
        localStatus = "";
        if (width > 0) rebuildRows();
    }

    @Override
    public void tick() {
        super.tick();
        if (pending && ++pendingTicks >= 100) {
            pending = false;
            localStatus = "Server reply delayed. Refresh before retrying.";
            rebuildRows();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Screen's final frame wrapper already extracts the background, including its one blur.
        graphics.fillGradient(panelX, 12, panelX + panelWidth, height - 12, 0xF0222330, 0xF016171E);
        graphics.outline(panelX, 12, panelWidth, height - 24, 0xFF555563);
        graphics.fill(panelX + 1, 13, panelX + panelWidth - 1, 15, 0xFF9985D8);
        graphics.text(font, title, panelX + 10, 24, TEXT);
        graphics.text(font, clip(subtitle(), panelWidth - 20), panelX + 10, 38, MUTED);
        graphics.horizontalLine(panelX + 10, panelX + panelWidth - 11, bodyTop - 4, 0xFF494A56);
        drawRows(graphics, mouseX, mouseY);
        if (resultCount() == 0) {
            graphics.centeredText(font, query.isBlank() ? emptyMessage() : "No matching results",
                panelX + panelWidth / 2, bodyTop + 20, MUTED);
        }
        String status = localStatus.isEmpty() ? snapshot.message() : localStatus;
        if (status.isBlank()) {
            int end = Math.min(scroll + visibleRows(), resultCount());
            status = (resultCount() == 0 ? "0" : (scroll + 1) + "–" + end) + " of " + resultCount()
                + "  ·  Revision " + snapshot.revision();
        }
        graphics.text(font, clip(status, panelWidth - 96), panelX + 10, height - 31,
            pending ? ACCENT : MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    protected String emptyMessage() { return "Nothing registered yet"; }

    protected String clip(String text, int pixels) {
        if (font.width(text) <= Math.max(0, pixels)) return text;
        return font.plainSubstrByWidth(text, Math.max(0, pixels - font.width("…"))) + "…";
    }

    protected static boolean hovered(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
