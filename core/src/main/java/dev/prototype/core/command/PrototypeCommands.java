package dev.prototype.core.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.prototype.core.PrototypeCore;
import dev.prototype.core.PrototypeRuntime;
import dev.prototype.core.api.ActionResult;
import dev.prototype.core.api.CoreService;
import dev.prototype.core.api.Id;
import dev.prototype.core.network.PrototypeNetworking;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/** Every privileged execution rechecks server permission; player suggestions are never global. */
public final class PrototypeCommands {
    private PrototypeCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("prototype").executes(c -> {
            c.getSource().sendSuccess(() -> Component.literal("Prototype Core: /prototype switch <class>, /prototype convert"), false);
            return 1;
        });
        root.then(Commands.literal("switch").then(Commands.argument("class", StringArgumentType.greedyString())
            .suggests((c,b) -> SharedSuggestionProvider.suggest(c.getSource().isPlayer()
                ? service(c).switchSuggestions(c.getSource().getPlayer().getUUID()).stream().map(Object::toString)
                : java.util.stream.Stream.empty(), b))
            .executes(c -> {
                var player = c.getSource().getPlayerOrException();
                if (!PrototypeRuntime.stateWritable(c.getSource().getServer())) return failure(c, "Prototype state is read-only; inspect the server log.");
                Id id;
                try { id = Id.parse(StringArgumentType.getString(c,"class")); }
                catch (IllegalArgumentException e) { return failure(c,"You do not have access to that class."); }
                ActionResult result = service(c).switchClass(player.getUUID(), id);
                return report(c, result, "Active class updated. Held weapon unchanged.");
            })));
        root.then(Commands.literal("convert").executes(c -> {
            var player = c.getSource().getPlayerOrException();
            var result = PrototypeCore.conversions().convert(player, service(c));
            if (!result.success()) return failure(c,result.message());
            c.getSource().sendSuccess(() -> Component.literal(result.message()),false);
            return 1;
        }));
        root.then(Commands.literal("admin").requires(PrototypeCommands::admin).executes(c -> {
            var player = c.getSource().getPlayerOrException();
            if (!PrototypeRuntime.isAdmin(player)) return failure(c,"Administrator permission is required.");
            PrototypeNetworking.openAdmin(player);
            return 1;
        }));
        var classes = Commands.literal("class").requires(PrototypeCommands::admin);
        classes.then(classMutation("set", (s,p,c) -> s.setClass(p,c)));
        classes.then(classMutation("add", (s,p,c) -> s.addClass(p,c)));
        classes.then(classMutation("remove", (s,p,c) -> s.removeClass(p,c)));
        classes.then(Commands.literal("clear").then(Commands.argument("player",GameProfileArgument.gameProfile())
            .executes(c -> mutatePlayer(c, CoreService::clearClasses))));
        root.then(classes);
        root.then(Commands.literal("hybrid").requires(PrototypeCommands::admin)
            .then(playerToggle("enable",(s,p) -> s.setHybrid(p,true)))
            .then(playerToggle("disable",(s,p) -> s.setHybrid(p,false))));
        root.then(playerToggle("classban",(s,p) -> s.setClassBanned(p,true)).requires(PrototypeCommands::admin));
        root.then(playerToggle("classunban",(s,p) -> s.setClassBanned(p,false)).requires(PrototypeCommands::admin));
        root.then(Commands.literal("addon").requires(PrototypeCommands::admin)
            .then(addonToggle("enable",true)).then(addonToggle("disable",false)));
        dispatcher.register(root);
    }
    private static boolean admin(CommandSourceStack source) { return source.permissions().hasPermission(Permissions.COMMANDS_OWNER); }
    private static CoreService service(CommandContext<CommandSourceStack> c) { return PrototypeRuntime.service(c.getSource().getServer()); }
    @FunctionalInterface private interface ClassAction { ActionResult run(CoreService service,UUID player,Id id); }
    private static LiteralArgumentBuilder<CommandSourceStack> classMutation(String name,ClassAction action) {
        return Commands.literal(name).then(Commands.argument("player",GameProfileArgument.gameProfile())
            .then(Commands.argument("class",StringArgumentType.greedyString())
                .suggests((c,b)->SharedSuggestionProvider.suggest(service(c).registry().classes().stream().map(x->x.id().toString()),b))
                .executes(c -> mutatePlayer(c,(s,p)->{
                    try { return action.run(s,p,Id.parse(StringArgumentType.getString(c,"class"))); }
                    catch (IllegalArgumentException e) { return new ActionResult(ActionResult.Status.INVALID_REQUEST,"Invalid class identifier."); }
                }))));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> playerToggle(String name,BiFunction<CoreService,UUID,ActionResult> action) {
        return Commands.literal(name).then(Commands.argument("player",GameProfileArgument.gameProfile()).executes(c->mutatePlayer(c,action)));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> addonToggle(String name,boolean enabled) {
        return Commands.literal(name).then(Commands.argument("addon",StringArgumentType.greedyString())
            .suggests((c,b)->SharedSuggestionProvider.suggest(service(c).registry().addons().stream().map(a->a.id().toString()),b))
            .executes(c -> {
                if (!admin(c.getSource())) return failure(c,"Administrator permission is required.");
                if (!PrototypeRuntime.stateWritable(c.getSource().getServer())) return failure(c,"Prototype state is read-only; inspect the server log.");
                try { return report(c,service(c).setAddonEnabled(Id.parse(StringArgumentType.getString(c,"addon")),enabled),"Addon runtime state updated."); }
                catch (IllegalArgumentException e) { return failure(c,"Invalid addon identifier."); }
            }));
    }
    private static int mutatePlayer(CommandContext<CommandSourceStack> c,BiFunction<CoreService,UUID,ActionResult> action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!admin(c.getSource())) return failure(c,"Administrator permission is required.");
        if (!PrototypeRuntime.stateWritable(c.getSource().getServer())) return failure(c,"Prototype state is read-only; inspect the server log.");
        var profiles = GameProfileArgument.getGameProfiles(c,"player");
        if (profiles.size()!=1) return failure(c,"Select exactly one player.");
        return report(c,action.apply(service(c),profiles.iterator().next().id()),"Player class authorization updated.");
    }
    private static int report(CommandContext<CommandSourceStack> c,ActionResult result,String success) {
        if (!result.success()) return failure(c,result.message());
        try { PrototypeRuntime.afterMutation(c.getSource().getServer()); }
        catch (IllegalStateException e) { return failure(c,e.getMessage()); }
        c.getSource().sendSuccess(()->Component.literal(success),false);
        return 1;
    }
    private static int failure(CommandContext<CommandSourceStack> c,String message) {
        c.getSource().sendFailure(Component.literal(message)); return 0;
    }
}
