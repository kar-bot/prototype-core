package dev.prototype.core.client;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Compact, searchable administration for players and installed addons. */
final class PrototypeAdminScreen extends AdminPanelScreen {
    private enum Tab { PLAYERS, ADDONS }
    private Tab tab = Tab.PLAYERS;
    private final ThumbnailResolver thumbnails = new ThumbnailResolver();

    PrototypeAdminScreen(AdminSnapshot snapshot) {
        super(snapshot, Component.translatable("screen.prototype.admin"));
    }

    @Override
    void buildNavigation(int x, int y) {
        navigationButton("Players", x, y, 68, () -> select(Tab.PLAYERS), "Manage player class authorization")
            .active = tab != Tab.PLAYERS;
        navigationButton("Addons", x + 74, y, 68, () -> select(Tab.ADDONS), "Manage runtime addon availability")
            .active = tab != Tab.ADDONS;
        navigationButton("Refresh", panelX + panelWidth - 136, y, 62, this::refresh,
            "Request current server state");
        navigationButton("Done", panelX + panelWidth - 68, y, 58, this::onClose, "Close administration");
    }

    private void select(Tab selected) {
        tab = selected;
        query = "";
        scroll = 0;
        rebuildWidgets();
    }

    @Override String searchHint() { return tab == Tab.PLAYERS ? "Search name or UUID" : "Search addon, class, or ID"; }
    @Override String subtitle() {
        return tab == Tab.PLAYERS ? "One active class · Authorizations remain stored while banned or disabled"
            : "Runtime controls · Installed items and addon progression remain stored";
    }
    @Override int rowHeight() { return tab == Tab.ADDONS ? 58 : narrow() ? 61 : 38; }
    @Override int resultCount() { return tab == Tab.PLAYERS ? players().size() : addons().size(); }
    private boolean narrow() { return panelWidth < 500; }

    private List<AdminSnapshot.Player> players() {
        String needle = query.toLowerCase(Locale.ROOT);
        return snapshot.players().stream().filter(player ->
            player.name().toLowerCase(Locale.ROOT).contains(needle) || player.uuid().toString().contains(needle))
            .toList();
    }

    private List<AdminSnapshot.Addon> addons() {
        String needle = query.toLowerCase(Locale.ROOT);
        return snapshot.addons().stream().filter(addon -> addon.displayName().toLowerCase(Locale.ROOT).contains(needle)
            || addon.id().contains(needle)
            || addon.classes().stream().anyMatch(type -> type.displayName().toLowerCase(Locale.ROOT).contains(needle)
                || type.id().contains(needle))).toList();
    }

    private int classWidth() {
        return narrow() ? panelWidth - 190 : Math.min(210, (panelWidth - 20) / 3);
    }

    private int classX() { return narrow() ? panelX + 10 : panelX + panelWidth - classWidth() - 172; }
    private int hybridX() { return panelX + panelWidth - 166; }
    private int banX() { return panelX + panelWidth - 94; }

    @Override
    void buildRows() {
        if (tab == Tab.ADDONS) {
            List<AdminSnapshot.Addon> list = addons();
            for (int row = 0; row < visibleRows() && scroll + row < list.size(); row++) {
                var addon = list.get(scroll + row);
                rowButton(addon.enabled() ? "Disable" : "Enable", panelX + panelWidth - 80, rowY(row) + 19,
                    64, 20, () -> request("set_addon", null, addon.id(), !addon.enabled()),
                    (addon.enabled() ? "Disable" : "Enable") + " " + addon.displayName()
                        + ". Changes runtime authorization without removing installed items or stored data.",
                    true, true);
            }
            return;
        }
        List<AdminSnapshot.Player> list = players();
        for (int row = 0; row < visibleRows() && scroll + row < list.size(); row++) {
            var player = list.get(scroll + row);
            int y = rowY(row) + (narrow() ? 29 : 3);
            String selected = snapshot.className(player.activeClass());
            String selectorLabel = narrow() ? "Active: " + clip(selected, classWidth() - 60)
                : clip(selected, classWidth() - 18);
            rowButton(selectorLabel + " ▾", classX(), y, classWidth(), 20,
                () -> minecraft.gui.setScreen(new ClassSelectionScreen(this, player.uuid())),
                "Stored active class: " + selected + "\nAllowed classes: " + player.allowedClasses().size()
                    + "\nOpen " + (player.hybridEnabled() ? "multiple-class authorization" : "single-class selection"),
                true, false);
            rowButton("Hybrid " + (player.hybridEnabled() ? "ON" : "OFF"), hybridX(), y, 66, 20,
                () -> request("set_hybrid", player.uuid(), null, !player.hybridEnabled()),
                player.hybridEnabled() ? "Disable hybrid. Keep the active class where possible and reduce authorization to one class."
                    : "Enable authorization for multiple classes. Only one class can be active at a time.", true, true);
            rowButton("Class Ban " + (player.classBanned() ? "ON" : "OFF"), banX(), y, 84, 20,
                () -> request("set_ban", player.uuid(), null, !player.classBanned()),
                "Class restriction only. The player can still join and play. Stored class authorizations and addon progression are preserved.",
                true, true);
        }
    }

    @Override
    void drawRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (tab == Tab.ADDONS) drawAddons(graphics, mouseX, mouseY);
        else drawPlayers(graphics, mouseX, mouseY);
    }

    private void drawPlayers(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, "PLAYER", panelX + 10, 103, MUTED);
        if (!narrow()) graphics.text(font, "ACTIVE / ALLOWED", classX(), 103, MUTED);
        graphics.text(font, "CLASS AUTHORIZATION", hybridX(), 103, MUTED);
        List<AdminSnapshot.Player> list = players();
        for (int row = 0; row < visibleRows() && scroll + row < list.size(); row++) {
            var player = list.get(scroll + row);
            int y = rowY(row);
            graphics.fill(panelX + 8, y, panelX + panelWidth - 8, y + rowHeight() - 3,
                player.classBanned() ? 0x552F1922 : row % 2 == 0 ? 0x55363846 : 0x33363846);
            PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(player.uuid());
            PlayerFaceExtractor.extractRenderState(graphics,
                info == null ? DefaultPlayerSkin.get(player.uuid()) : info.getSkin(), panelX + 13, y + 5, 20);
            int nameWidth = narrow() ? panelWidth - 67 : classX() - panelX - 54;
            graphics.text(font, clip(player.name(), nameWidth), panelX + 40, y + 5, TEXT);
            String status = (player.online() ? "Online" : "Offline")
                + (narrow() ? " · Allowed: " + player.allowedClasses().size() : "");
            graphics.text(font, status, panelX + 40, y + 18,
                player.online() ? GREEN : MUTED);
            if (hovered(mouseX, mouseY, panelX + 10, y, narrow() ? panelWidth - 20 : nameWidth + 35, 26)) {
                graphics.setTooltipForNextFrame(Component.literal(player.name() + "\n" + player.uuid()), mouseX, mouseY);
            }
            if (!narrow()) graphics.text(font,
                "Allowed: " + player.allowedClasses().size() + (player.classBanned() ? " · paused" : ""),
                classX() + 3, y + 26, player.classBanned() ? RED : MUTED);
        }
    }

    private void drawAddons(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, "INSTALLED ADDONS", panelX + 10, 103, MUTED);
        graphics.text(font, "RUNTIME STATE", panelX + panelWidth - 122, 103, MUTED);
        List<AdminSnapshot.Addon> list = addons();
        for (int row = 0; row < visibleRows() && scroll + row < list.size(); row++) {
            var addon = list.get(scroll + row);
            int y = rowY(row);
            graphics.fill(panelX + 8, y, panelX + panelWidth - 8, y + rowHeight() - 4,
                addon.enabled() ? 0x55363846 : 0x44322B33);
            Identifier thumbnail = thumbnails.resolve(minecraft, addon.thumbnail());
            // 26.3's normalized blit takes endpoints and u0/u1/v0/v1, not dimensions.
            graphics.blit(thumbnail, panelX + 13, y + 7, panelX + 53, y + 47, 0, 1, 0, 1);
            graphics.outline(panelX + 12, y + 6, 42, 42, 0xFF565562);
            int textWidth = panelWidth - 156;
            graphics.text(font, clip(addon.displayName(), textWidth), panelX + 62, y + 7, TEXT);
            graphics.text(font, clip(addon.id() + (addon.version().isBlank() ? "" : " · " + addon.version()), textWidth),
                panelX + 62, y + 20, MUTED);
            graphics.text(font, clip(addon.classes().size() + " class" + (addon.classes().size() == 1 ? "" : "es"), textWidth),
                panelX + 62, y + 36, MUTED);
            String state = addon.enabled() ? "ENABLED" : "DISABLED";
            graphics.text(font, state, panelX + panelWidth - 78, y + 6, addon.enabled() ? GREEN : RED);
            if (hovered(mouseX, mouseY, panelX + 12, y + 6, 43, 43)) {
                graphics.setTooltipForNextFrame(Component.literal(addon.displayName()
                    + (thumbnail.equals(ThumbnailResolver.FALLBACK) ? "\nAddon thumbnail unavailable" : "")), mouseX, mouseY);
            }
            if (hovered(mouseX, mouseY, panelX + 60, y, Math.max(0, textWidth), 54)) {
                graphics.setTooltipForNextFrame(Component.literal(addon.displayName() + "\n" + addon.id()
                    + "\nClasses: " + addon.classes().stream().map(AdminSnapshot.ClassInfo::displayName)
                        .collect(java.util.stream.Collectors.joining(", "))), mouseX, mouseY);
            }
        }
    }

    @Override protected String emptyMessage() {
        return tab == Tab.PLAYERS ? "No known players yet" : "No compatible addons registered";
    }

    @Override
    public void onClose() {
        PrototypeClient.send("close", snapshot.revision(), null, null, null);
        minecraft.gui.setScreen(null);
    }
}
