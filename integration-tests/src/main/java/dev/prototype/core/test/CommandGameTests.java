package dev.prototype.core.test;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.prototype.core.PrototypeRuntime;
import dev.prototype.core.api.CoreService;
import dev.prototype.core.persistence.StateStore;
import dev.prototype.demo.DemoAddon;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

/** Exercises the real registered Brigadier tree and managed world state on a dedicated server. */
public final class CommandGameTests {
    private record Actor(ServerPlayer player, Recorder messages, CommandSourceStack source) {}
    private static final class Recorder implements CommandSource {
        private final List<String> messages = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    private static CoreService service(GameTestHelper helper) { return PrototypeRuntime.service(helper.getLevel().getServer()); }
    private static Actor actor(GameTestHelper helper, PermissionSet permissions) {
        UUID uuid = UUID.randomUUID();
        String name = "pc_" + uuid.toString().substring(0, 8);
        var server = helper.getLevel().getServer();
        var player = new ServerPlayer(server, helper.getLevel(), new GameProfile(uuid, name), ClientInformation.createDefault());
        server.services().nameToIdCache().add(new NameAndId(uuid, name));
        Recorder messages = new Recorder();
        return new Actor(player, messages, player.createCommandSourceStack().withSource(messages).withPermission(permissions));
    }
    private static int execute(GameTestHelper helper, Actor actor, String command) throws CommandSyntaxException {
        actor.messages().messages.clear();
        return helper.getLevel().getServer().getCommands().getDispatcher().execute(command, actor.source());
    }
    private static List<String> suggestions(GameTestHelper helper, Actor actor) {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        return dispatcher.getCompletionSuggestions(dispatcher.parse("prototype switch ", actor.source())).join()
                .getList().stream().map(suggestion -> suggestion.getText()).toList();
    }

    @GameTest public void namespaced_switch_parses_and_does_not_convert(GameTestHelper helper) throws Exception {
        Actor actor = actor(helper, PermissionSet.NO_PERMISSIONS);
        var service = service(helper);
        service.setAddonEnabled(DemoAddon.ADDON_ID, true);
        service.setClass(actor.player().getUUID(), DemoAddon.CLASS_ID);
        service.setHybrid(actor.player().getUUID(), true);
        service.setClass(actor.player().getUUID(), PrototypeTestAddon.SECRET_CLASS);
        ItemStack original = new ItemStack(Items.WOODEN_SWORD);
        actor.player().setItemSlot(EquipmentSlot.MAINHAND, original);
        helper.assertValueEqual(execute(helper, actor, "prototype switch " + DemoAddon.CLASS_ID), 1, "Namespaced player switch executes");
        helper.assertTrue(actor.player().getMainHandItem() == original, "Class switching preserves the held item object");
        helper.assertValueEqual(service.activeClass(actor.player().getUUID()).orElseThrow(), DemoAddon.CLASS_ID, "Exactly one active class selected");
        helper.succeed();
    }

    @GameTest public void ordinary_suggestions_only_authorized_and_ban_hides_all(GameTestHelper helper) {
        Actor actor = actor(helper, PermissionSet.NO_PERMISSIONS);
        var service = service(helper);
        service.setAddonEnabled(DemoAddon.ADDON_ID, true);
        helper.assertTrue(suggestions(helper, actor).isEmpty(), "Unassigned actor discovers no installed class IDs");
        service.setClass(actor.player().getUUID(), DemoAddon.CLASS_ID);
        helper.assertValueEqual(suggestions(helper, actor), List.of(DemoAddon.CLASS_ID.toString()), "Autocomplete contains only owned class IDs");
        service.setClassBanned(actor.player().getUUID(), true);
        helper.assertTrue(suggestions(helper, actor).isEmpty(), "Class ban removes all switch suggestions");
        helper.succeed();
    }

    @GameTest public void unauthorized_and_nonexistent_class_fail_identically(GameTestHelper helper) throws Exception {
        Actor actor = actor(helper, PermissionSet.NO_PERMISSIONS);
        var before = service(helper).exportState();
        helper.assertValueEqual(execute(helper, actor, "prototype switch " + PrototypeTestAddon.SECRET_CLASS), 0, "Installed unauthorized class fails");
        List<String> realFailure = List.copyOf(actor.messages().messages);
        helper.assertValueEqual(execute(helper, actor, "prototype switch undiscovered:nonexistent"), 0, "Nonexistent class fails");
        helper.assertValueEqual(actor.messages().messages, realFailure, "Failures do not expose registration existence");
        helper.assertValueEqual(realFailure, List.of("You do not have access to that class."), "Generic privacy response");
        helper.assertValueEqual(service(helper).exportState(), before, "Neither request mutates authorization");
        helper.succeed();
    }

    @GameTest public void op_three_cannot_parse_any_admin_mutation(GameTestHelper helper) throws Exception {
        Actor actor = actor(helper, LevelBasedPermissionSet.ADMIN);
        String target = actor.player().getGameProfile().name();
        var before = service(helper).exportState();
        for (String command : List.of("prototype class set " + target + " " + DemoAddon.CLASS_ID,
                "prototype hybrid enable " + target, "prototype classunban " + target,
                "prototype addon enable " + DemoAddon.ADDON_ID, "prototype class clear " + target)) {
            boolean rejected = false;
            try { execute(helper, actor, command); } catch (CommandSyntaxException expected) { rejected = true; }
            helper.assertTrue(rejected, "OP level 3 cannot access " + command);
        }
        helper.assertValueEqual(service(helper).exportState(), before, "Unauthorized command tree never mutates state");
        helper.succeed();
    }

    @GameTest public void permission_rechecked_after_previously_valid_parse(GameTestHelper helper) throws Exception {
        Actor owner = actor(helper, LevelBasedPermissionSet.OWNER);
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        var parsed = dispatcher.parse("prototype hybrid enable " + owner.player().getGameProfile().name(), owner.source());
        var before = service(helper).exportState();
        var downgraded = Commands.mapSource(parsed, source -> source.withPermission(LevelBasedPermissionSet.ADMIN));
        helper.assertValueEqual(dispatcher.execute(downgraded), 0, "Permission downgrade blocks cached command execution");
        helper.assertValueEqual(service(helper).exportState(), before, "Cached admin parse does not grant authority");
        helper.succeed();
    }

    @GameTest public void owner_commands_mutate_and_persist_world_authorization(GameTestHelper helper) throws Exception {
        Actor owner = actor(helper, LevelBasedPermissionSet.OWNER);
        var service = service(helper);
        String target = owner.player().getGameProfile().name();
        helper.assertValueEqual(execute(helper, owner, "prototype class set " + target + " " + DemoAddon.CLASS_ID), 1, "Owner assigns class with namespaced ID");
        helper.assertValueEqual(execute(helper, owner, "prototype hybrid enable " + target), 1, "Owner enables hybrid");
        helper.assertValueEqual(execute(helper, owner, "prototype classban " + target), 1, "Owner applies class ban");
        var current = service.state(owner.player().getUUID());
        helper.assertTrue(current.hybridEnabled() && current.classBanned(), "Hybrid and class ban stored together");
        helper.assertTrue(current.allowedClasses().contains(DemoAddon.CLASS_ID), "Class ban preserves assignment");
        var directory = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data/prototype");
        var restored = new StateStore(directory).load();
        helper.assertValueEqual(restored.players().get(owner.player().getUUID()), current, "Administrative state persisted by command execution");
        helper.assertValueEqual(execute(helper, owner, "prototype classunban " + target), 1, "Owner removes class ban");
        helper.assertValueEqual(execute(helper, owner, "prototype hybrid disable " + target), 1, "Owner disables hybrid safely");
        helper.assertValueEqual(execute(helper, owner, "prototype class clear " + target), 1, "Owner clears assignment");
        helper.assertTrue(service.state(owner.player().getUUID()).allowedClasses().isEmpty(), "Clear command changes server-owned assignment");
        helper.succeed();
    }

    @GameTest public void runtime_addon_commands_accept_colon_and_restore_cleanly(GameTestHelper helper) throws Exception {
        Actor owner = actor(helper, LevelBasedPermissionSet.OWNER);
        var service = service(helper);
        try {
            helper.assertValueEqual(execute(helper, owner, "prototype addon disable " + DemoAddon.ADDON_ID), 1, "Namespaced addon disable parses");
            helper.assertFalse(service.isAddonEnabled(DemoAddon.ADDON_ID), "Disable takes effect without reboot");
            var directory = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data/prototype");
            helper.assertFalse(new StateStore(directory).load().addonEnabled().get(DemoAddon.ADDON_ID), "Disable flag persists immediately");
            helper.assertValueEqual(execute(helper, owner, "prototype addon enable " + DemoAddon.ADDON_ID), 1, "Namespaced addon enable parses");
            helper.assertTrue(service.isAddonEnabled(DemoAddon.ADDON_ID), "Addon cleanly enabled again");
        } finally { service.setAddonEnabled(DemoAddon.ADDON_ID, true); }
        helper.succeed();
    }

    @GameTest public void malformed_admin_identifier_does_not_mutate(GameTestHelper helper) throws Exception {
        Actor owner = actor(helper, LevelBasedPermissionSet.OWNER);
        var before = service(helper).exportState();
        helper.assertValueEqual(execute(helper, owner, "prototype class set " + owner.player().getGameProfile().name() + " BAD_CLASS"), 0,
                "Malformed admin identifier safely rejected");
        helper.assertValueEqual(service(helper).exportState(), before, "Malformed ID cannot affect authorization or revision");
        helper.succeed();
    }
}
