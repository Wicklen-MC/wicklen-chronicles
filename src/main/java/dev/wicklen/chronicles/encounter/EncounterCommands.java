package dev.wicklen.chronicles.encounter;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.core.encounter.EncounterMode;
import dev.wicklen.chronicles.exploration.ExplorationService;
import dev.wicklen.chronicles.station.StationService;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.UUID;

final class EncounterCommands {
    private EncounterCommands() {}

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
            Commands.literal("spirit").then(Commands.literal("encounter")
                .executes(EncounterCommands::list)
                .then(Commands.literal("prepare")
                    .then(Commands.argument("mode", StringArgumentType.word())
                        .suggests((context, builder) -> { builder.suggest("normal"); builder.suggest("empowered"); return builder.buildFuture(); })
                        .executes(context -> prepare(context, null))
                        .then(Commands.argument("placeId", StringArgumentType.greedyString())
                            .executes(context -> prepare(context, StringArgumentType.getString(context, "placeId"))))))
                .then(Commands.literal("join").then(Commands.argument("encounterId", StringArgumentType.word())
                    .executes(context -> action(context, "join"))))
                .then(Commands.literal("start").then(Commands.argument("encounterId", StringArgumentType.word())
                    .executes(context -> action(context, "start"))))
                .then(Commands.literal("leave").then(Commands.argument("encounterId", StringArgumentType.word())
                    .executes(context -> action(context, "leave"))))
                .then(Commands.literal("claim").then(Commands.argument("rewardId", StringArgumentType.word())
                    .executes(context -> action(context, "claim")))))));
    }

    private static int list(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        try {
            var views = EncounterService.knownEncounters(player);
            if (views.isEmpty()) context.getSource().sendSuccess(() -> Component.literal(
                "Испытание добровольное. Изучи станцию с двором и выполни /spirit encounter prepare normal или empowered."), false);
            for (var view : views) {
                context.getSource().sendSuccess(() -> Component.literal(view.mode() + " · " + view.status() + " · " + view.id()), false);
                for (var reward : view.rewards()) if (!reward.claimed()) context.getSource().sendSuccess(() ->
                    Component.literal("Реликвия ждёт: /spirit encounter claim " + reward.id()), false);
            }
            return 1;
        } catch (RuntimeException error) {
            context.getSource().sendFailure(Component.literal("Испытания сейчас недоступны. Данные сохранены для восстановления."));
            return 0;
        }
    }

    private static int prepare(CommandContext<CommandSourceStack> context, String placeId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        try {
            var mode = EncounterMode.valueOf(StringArgumentType.getString(context, "mode").toUpperCase(java.util.Locale.ROOT));
            DiscoveryKey key;
            if (placeId != null) key = new DiscoveryKey(DiscoveryKind.PLACE, placeId);
            else key = ExplorationService.snapshot(player).entries().stream().map(entry -> entry.key())
                .filter(value -> value.kind() == DiscoveryKind.PLACE)
                .filter(value -> StationService.knownStation(player, value)
                    .filter(station -> station.arena() != null && station.bounds().contains(player.blockPosition())).isPresent())
                .findFirst().orElse(null);
            if (key == null) {
                context.getSource().sendFailure(Component.literal("Нужна лично изученная станция с двором. Встань на её территории."));
                return 0;
            }
            return reply(context, EncounterService.prepare(player, key, mode));
        } catch (IllegalArgumentException error) {
            context.getSource().sendFailure(Component.literal("Режимы: normal / empowered. ID места берётся из личного атласа."));
            return 0;
        } catch (IllegalStateException error) {
            context.getSource().sendFailure(Component.literal("Подготовка сейчас недоступна. Данные сохранены."));
            return 0;
        }
    }

    private static int action(CommandContext<CommandSourceStack> context, String action)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        try {
            String argument = action.equals("claim") ? "rewardId" : "encounterId";
            UUID id = UUID.fromString(StringArgumentType.getString(context, argument));
            return reply(context, switch (action) {
                case "join" -> EncounterService.join(player, id);
                case "start" -> EncounterService.start(player, id);
                case "leave" -> EncounterService.leave(player, id);
                case "claim" -> EncounterService.claim(player, id);
                default -> throw new IllegalArgumentException("Unknown encounter action");
            });
        } catch (IllegalArgumentException error) {
            context.getSource().sendFailure(Component.literal("Нужен корректный ID из /spirit encounter."));
            return 0;
        }
    }

    private static int reply(CommandContext<CommandSourceStack> context, EncounterService.ActionResult result) {
        if (result.success()) context.getSource().sendSuccess(() -> Component.literal(result.message()), false);
        else context.getSource().sendFailure(Component.literal(result.message()));
        return result.success() ? 1 : 0;
    }
}
