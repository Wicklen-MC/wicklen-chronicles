package dev.redzumi.spirit.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.redzumi.spirit.ai.SpiritQuests;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import dev.redzumi.spirit.RedzSpirit;
import dev.redzumi.spirit.entity.SpiritEntity;

/** /spirit summon|dismiss — управление духом. Скелет v0.1. */
public final class SpiritCommand {
    private SpiritCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
            dispatcher.register(Commands.literal("spirit")
                .then(Commands.literal("summon")
                    .executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException();
                        dev.redzumi.spirit.ai.SpiritPrefs.setHidden(player.getUUID(), false);
                        dev.redzumi.spirit.SpiritManager.ensureSpirit(player);
                        dev.redzumi.spirit.SpiritBubbles.speak(player, "Я здесь. Нажми G — поговорим!");
                        ctx.getSource().sendSuccess(() -> Component.literal("Дух призван и кружит рядом."), false);
                        return 1;
                    }))
                .then(Commands.literal("dismiss")
                    .executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException();
                        dev.redzumi.spirit.ai.SpiritPrefs.setHidden(player.getUUID(), true);
                        int removed = dev.redzumi.spirit.SpiritManager.discardSpirits(
                            player.getScoreboardName(), ctx.getSource().getServer());
                        int r = removed;
                        ctx.getSource().sendSuccess(() -> Component.literal("Дух отпущен (" + r + "). Вернуть: /spirit summon"), false);
                        return r;
                    }))

                .then(Commands.literal("persona")
                    .executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException();
                        var cur = dev.redzumi.spirit.ai.SpiritPersonas.of(player.getUUID());
                        ctx.getSource().sendSuccess(() -> Component.literal(
                            "Твой дух: " + cur.title() + " (" + cur.id() + "). Варианты: "
                                + String.join(", ", dev.redzumi.spirit.ai.SpiritPersonas.ALL.keySet())), false);
                        return 1;
                    })
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests((c, b) -> {
                            dev.redzumi.spirit.ai.SpiritPersonas.ALL.keySet().forEach(b::suggest);
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            var player = ctx.getSource().getPlayerOrException();
                            String id = StringArgumentType.getString(ctx, "id");
                            var p = dev.redzumi.spirit.ai.SpiritPersonas.ALL.get(id);
                            if (p == null) {
                                ctx.getSource().sendFailure(Component.literal("Нет такой персоны: " + id));
                                return 0;
                            }
                            dev.redzumi.spirit.ai.SpiritPersonas.set(player.getUUID(), id);
                            dev.redzumi.spirit.SpiritManager.refreshAppearance(player);
                            dev.redzumi.spirit.SpiritBubbles.speak(player, p.joinLine().replaceFirst("^✦\\s*", ""));
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                "Дух сменил облик: " + p.title() + ". " + p.joinLine()), false);
                            return 1;
                        })))

                .then(Commands.literal("quest")
                    .executes(SpiritCommand::quest)
                    .then(Commands.literal("turnin")
                        .executes(ctx -> questAction(ctx, false, null))
                        .then(Commands.argument("questId", StringArgumentType.word())
                            .executes(ctx -> questAction(ctx, false, StringArgumentType.getString(ctx, "questId")))))
                    .then(Commands.literal("claim")
                        .executes(ctx -> questAction(ctx, true, null))
                        .then(Commands.argument("rewardId", StringArgumentType.word())
                            .executes(ctx -> questAction(ctx, true, StringArgumentType.getString(ctx, "rewardId"))))))
                .then(Commands.literal("guide")
                    .executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException();
                        var server = ctx.getSource().getServer();
                        var recent = new java.util.ArrayList<String>();
                        try {
                            var pa = player.getAdvancements();
                            for (var holder : server.getAdvancements().getAllAdvancements()) {
                                if (holder.id().getPath().startsWith("recipes/")) continue;
                                if (pa.getOrStartProgress(holder).isDone()) {
                                    holder.value().display().ifPresent(d -> recent.add(d.title().getString()));
                                }
                            }
                        } catch (Exception ignored) {}
                        var last = recent.stream().skip(Math.max(0, recent.size() - 6)).toList();
                        dev.redzumi.spirit.ai.SpiritAi.narrate(server, player,
                            "Игрок просит совета-проводника. Некоторые его выполненные достижения: %s. Подскажи одной-двумя короткими фразами, куда двигаться дальше по духу сборки."
                                .formatted(String.join(", ", last)));
                        ctx.getSource().sendSuccess(() -> Component.literal("Дух думает над советом…"), false);
                        return 1;
                    }))
                .then(Commands.literal("admin")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("providers")
                        .executes(ctx -> {
                            var presets = dev.redzumi.spirit.ai.llm.LlmProviders.presets();
                            var active = dev.redzumi.spirit.ai.llm.LlmProviders.active();
                            String list = String.join(", ", presets.keySet());
                            String act = active == null ? "нет" : active.id();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                "Настроены: [" + list + "]; активен: " + act + " (SPIRIT_PROVIDER)"), false);
                            return 1;
                        }))
                    .then(Commands.literal("test")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String text = StringArgumentType.getString(ctx, "text");
                                ctx.getSource().sendSuccess(() -> Component.literal("…дух думает…"), false);
                                dev.redzumi.spirit.ai.SpiritAi.adminTest(
                                    ctx.getSource().getServer(),
                                    answer -> ctx.getSource().sendSuccess(() -> Component.literal("✦ Дух: " + answer), false),
                                    text);
                                return 1;
                            }))))));
    }

    private static int quest(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = ctx.getSource().getPlayerOrException();
        try {
            SpiritQuests.tickProgress(player);
            var cur = SpiritQuests.active(player.getUUID());
            if (cur != null) {
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "Задание: " + cur.describe() + "\n" + cur.instructions()), false);
                return 1;
            }
            var q = SpiritQuests.issue(player.getUUID());
            if (q == null) return 0;
            ctx.getSource().sendSuccess(() -> Component.literal(
                "Новое задание от духа: " + q.describe() + "\n" + q.instructions()
                    + "\nКогда условия выполнены: /spirit quest turnin. Затем /spirit quest claim."), false);
            dev.redzumi.spirit.ai.SpiritAi.narrate(ctx.getSource().getServer(), player,
                "Ты даёшь игроку задание: «%s». Объяви его одной короткой фразой в своей манере."
                    .formatted(q.describe()));
            return 1;
        } catch (IllegalStateException e) {
            RedzSpirit.LOGGER.warn("[redz-spirit] quest command unavailable: {}", e.toString());
            ctx.getSource().sendFailure(Component.literal("Не удалось прочитать или сохранить задания. Данные сохранены для восстановления; сообщи администратору."));
            return 0;
        }
    }

    private static int questAction(CommandContext<CommandSourceStack> ctx, boolean claim, String suppliedId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = ctx.getSource().getPlayerOrException();
        try {
            var current = SpiritQuests.active(player.getUUID());
            if (current == null) {
                ctx.getSource().sendFailure(Component.literal("Нет активного задания или ожидающей награды. Получить задание: /spirit quest"));
                return 0;
            }
            var id = suppliedId == null ? (claim ? current.rewardId() : current.id())
                : java.util.UUID.fromString(suppliedId);
            var result = claim ? SpiritQuests.claim(player, id) : SpiritQuests.turnIn(player, id);
            if (result.success()) ctx.getSource().sendSuccess(() -> Component.literal(result.message()), false);
            else ctx.getSource().sendFailure(Component.literal(result.message()));
            return result.success() ? 1 : 0;
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Неверный ID действия. Актуальный ID: /spirit quest"));
            return 0;
        } catch (IllegalStateException e) {
            RedzSpirit.LOGGER.warn("[redz-spirit] quest action unavailable: {}", e.toString());
            ctx.getSource().sendFailure(Component.literal("Действие не выполнено: данные задания недоступны. Сообщи администратору."));
            return 0;
        }
    }
}
