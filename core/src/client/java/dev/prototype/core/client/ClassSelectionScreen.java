package dev.prototype.core.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Admin-only selection: checkboxes mean authorization; the separate Active control selects one class. */
final class ClassSelectionScreen extends AdminPanelScreen {
    private final PrototypeAdminScreen parent;
    private final UUID playerId;

    ClassSelectionScreen(PrototypeAdminScreen parent, UUID playerId) {
        super(parent.snapshot, Component.literal("Classes · " + parent.snapshot.players().stream()
            .filter(player -> player.uuid().equals(playerId)).map(AdminSnapshot.Player::name).findFirst().orElse("Player")));
        this.parent = parent;
        this.playerId = playerId;
    }

    private AdminSnapshot.Player player() {
        return snapshot.players().stream().filter(player -> player.uuid().equals(playerId)).findFirst().orElse(null);
    }

    private List<AdminSnapshot.ClassInfo> classes() {
        List<AdminSnapshot.ClassInfo> list = new ArrayList<>(snapshot.classes().values());
        var player = player();
        if (player != null) for (String id : player.allowedClasses()) {
            if (list.stream().noneMatch(type -> type.id().equals(id)))
                list.add(new AdminSnapshot.ClassInfo(id, id, false));
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return list.stream().filter(type -> type.displayName().toLowerCase(Locale.ROOT).contains(needle)
            || type.id().contains(needle))
            .sorted(Comparator.comparing(AdminSnapshot.ClassInfo::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AdminSnapshot.ClassInfo::id)).toList();
    }

    @Override
    void buildNavigation(int x, int y) {
        navigationButton("< Players", x, y, 84, this::onClose, "Return to player administration");
        navigationButton("Refresh", panelX + panelWidth - 163, y, 62, this::refresh, "Request current server state");
        navigationButton("Clear classes", panelX + panelWidth - 95, y, 85,
            () -> request("clear_classes", playerId, null, null),
            "Clear Core class authorization and active selection. Addon progression remains untouched.");
    }

    @Override String searchHint() { return "Search class name or ID"; }
    @Override int rowHeight() { return 35; }
    @Override int resultCount() { return classes().size(); }
    @Override String subtitle() {
        var player = player();
        if (player == null) return "Player is no longer in the server snapshot";
        return player.hybridEnabled()
            ? "Checkbox = allowed · Active = one selected class" + (player.classBanned() ? " · Class Ban pauses all classes" : "")
            : "Single selection replaces the prior class" + (player.classBanned() ? " · Class Ban pauses all classes" : "");
    }

    @Override
    void buildRows() {
        var player = player();
        if (player == null) return;
        List<AdminSnapshot.ClassInfo> list = classes();
        var registered = snapshot.classes();
        for (int row = 0; row < visibleRows() && scroll + row < list.size(); row++) {
            var type = list.get(scroll + row);
            int y = rowY(row);
            boolean allowed = player.allowedClasses().contains(type.id());
            boolean active = type.id().equals(player.activeClass());
            boolean known = registered.containsKey(type.id());
            String indicator = player.hybridEnabled() ? (allowed ? "[x]" : "[ ]") : (allowed ? "(*)" : "( )");
            String action = player.hybridEnabled() ? (allowed ? "remove_class" : "add_class") : "set_class";
            rowButton(indicator, panelX + 13, y + 5, 26, 20,
                () -> request(action, playerId, type.id(), null),
                player.hybridEnabled() ? (allowed ? "Remove authorization for " : "Authorize ") + type.displayName()
                    : "Assign and activate " + type.displayName() + ". Replaces the prior single class.",
                known || (allowed && player.hybridEnabled()), true);
            rowButton(active ? "ACTIVE" : "Activate", panelX + panelWidth - 84, y + 5, 68, 20,
                () -> request("set_class", playerId, type.id(), null),
                active ? "Stored active selection" + (player.classBanned() ? "; suspended by Class Ban." : ".")
                    : "Assign and activate this class. " + (player.hybridEnabled()
                        ? "Keeps other allowed classes. " : "Replaces the prior class. ") + "Does not convert a sword.",
                known && !active, true);
        }
    }

    @Override
    void drawRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var player = player();
        graphics.text(font, player != null && player.hybridEnabled() ? "ALLOWED CLASSES" : "SELECT ONE CLASS",
            panelX + 10, 103, MUTED);
        graphics.text(font, "ACTIVE CLASS", panelX + panelWidth - 91, 103, MUTED);
        if (player == null) return;
        List<AdminSnapshot.ClassInfo> list = classes();
        var registered = snapshot.classes();
        for (int row = 0; row < visibleRows() && scroll + row < list.size(); row++) {
            var type = list.get(scroll + row);
            int y = rowY(row);
            boolean active = type.id().equals(player.activeClass());
            boolean known = registered.containsKey(type.id());
            boolean usable = type.enabled() && !player.classBanned();
            graphics.fill(panelX + 8, y, panelX + panelWidth - 8, y + 32,
                active ? 0x55544871 : row % 2 == 0 ? 0x55363846 : 0x33363846);
            if (active) graphics.fill(panelX + 8, y + 1, panelX + 10, y + 31, ACCENT);
            int nameWidth = panelWidth - 141;
            graphics.text(font, clip(type.displayName(), nameWidth), panelX + 46, y + 5,
                usable ? TEXT : MUTED);
            String detail = type.id() + (!known ? " · missing addon" : !type.enabled() ? " · disabled" : "");
            graphics.text(font, clip(detail, nameWidth), panelX + 46, y + 18, MUTED);
            if (hovered(mouseX, mouseY, panelX + 43, y, Math.max(0, nameWidth), 32)) {
                graphics.setTooltipForNextFrame(Component.literal(type.displayName() + "\n" + type.id()
                    + (active ? "\nStored active class" : "")
                    + (player.allowedClasses().contains(type.id()) ? "\nAllowed" : "\nNot yet allowed")
                    + (player.classBanned() ? "\nClass Ban: all class use is suspended" : "")
                    + (!known ? "\nAddon is missing; historical authorization is retained"
                        : !type.enabled() ? "\nAddon disabled: class use and conversion are suspended" : "")),
                    mouseX, mouseY);
            }
        }
    }

    @Override
    void update(AdminSnapshot latest) {
        parent.update(latest);
        super.update(latest);
    }

    @Override protected String emptyMessage() { return "No classes registered or retained for this player"; }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
