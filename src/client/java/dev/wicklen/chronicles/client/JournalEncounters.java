package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.net.EncounterRequestPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.function.Consumer;

/** Preparation and explicit start are distinct actions; absence of Gateway is a visible disabled state. */
final class JournalEncounters {
    private final SpiritDialogScreen screen;
    private final JournalPages pages;
    private int selectedCourt;
    private int selectedEncounter;
    private boolean preparing;
    private long seenRevision = -1;
    private String actionError="";
    private long actionErrorUntil;

    JournalEncounters(SpiritDialogScreen screen, JournalPages pages) {
        this.screen = screen;
        this.pages = pages;
    }

    void refresh() { EncounterClientState.request(EncounterRequestPayload.Action.READ, ""); }

    void init(SpiritDialogScreen.Layout box, Consumer<Button> add) {
        var data = EncounterClientState.data();
        if (data == null) return;
        if (seenRevision != EncounterClientState.revision()) {
            var response=EncounterClientState.response();
            if(response!=null && response.error() && !response.feedback().isBlank()) {
                actionError=response.feedback(); actionErrorUntil=System.currentTimeMillis()+6000;
            } else if(response!=null && !response.error() && EncounterClientState.lastAction()!=EncounterRequestPayload.Action.READ) actionError="";
            if (!EncounterClientState.busy() && EncounterClientState.response() != null
                    && !EncounterClientState.response().error()
                    && (EncounterClientState.lastAction() == EncounterRequestPayload.Action.PREPARE_NORMAL
                        || EncounterClientState.lastAction() == EncounterRequestPayload.Action.PREPARE_EMPOWERED))
                preparing = false;
            seenRevision = EncounterClientState.revision();
        }
        if (data.encounters().isEmpty()) preparing = true;
        int footer = box.top() + box.height() - 38;
        int actionLeft=box.width()<260?12:92;
        int actionWidth=(box.width()-actionLeft-16)/2;
        if (preparing) {
            selectedCourt = Math.clamp(selectedCourt, 0, Math.max(0, data.courtyards().size() - 1));
            if (data.courtyards().size() > 1) selector(box, add, data.courtyards().size(), true);
            String id = data.courtyards().isEmpty() ? "" : data.courtyards().get(selectedCourt).id();
            var normal = JournalButton.colored(JournalTexts.tr("encounter.normal"), button ->
                EncounterClientState.request(EncounterRequestPayload.Action.PREPARE_NORMAL, id))
                .bounds(box.left() + actionLeft, footer, actionWidth, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(JournalTexts.tr("encounter.adaptive_hint"))).build();
            var empowered = JournalButton.colored(JournalTexts.tr("encounter.empowered"), button ->
                EncounterClientState.request(EncounterRequestPayload.Action.PREPARE_EMPOWERED, id))
                .bounds(box.left() + box.width() - actionWidth - 12, footer, actionWidth, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(JournalTexts.tr("encounter.adaptive_hint"))).build();
            normal.active = empowered.active = data.gatewayAvailable() && !id.isEmpty() && !EncounterClientState.busy();
            add.accept(normal);
            add.accept(empowered);
            if (!data.encounters().isEmpty()) add.accept(JournalButton.colored(JournalTexts.tr("encounter.own"), button -> {
                preparing = false;
                pages.resetDetailScroll();
                screen.changed();
            }).bounds(box.left() + box.width() - 118, box.contentTop() - 3, 106, 18).build());
            return;
        }
        selectedEncounter = Math.clamp(selectedEncounter, 0, data.encounters().size() - 1);
        if (data.encounters().size() > 1) selector(box, add, data.encounters().size(), false);
        var row = data.encounters().get(selectedEncounter);
        add.accept(JournalButton.colored(JournalTexts.tr("encounter.new"), button -> {
            preparing = true;
            pages.resetDetailScroll();
            screen.changed();
        }).bounds(box.left() + box.width() - 118, box.contentTop() - 3, 106, 18).build());
        if (row.status().equals("LOBBY") && row.leader()) {
            var start = JournalButton.colored(JournalTexts.tr("encounter.start"), button ->
                EncounterClientState.request(EncounterRequestPayload.Action.START, row.id().toString()))
                .bounds(box.left() + box.width() - actionWidth - 12, footer, actionWidth, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(JournalTexts.tr("encounter.adaptive_hint"))).build();
            start.active = data.gatewayAvailable() && !EncounterClientState.busy();
            add.accept(start);
        }
        if (row.status().equals("LOBBY") || row.status().equals("ACTIVE")) {
            var leave = JournalButton.colored(JournalTexts.tr("encounter.leave"), button ->
                EncounterClientState.request(EncounterRequestPayload.Action.LEAVE, row.id().toString()))
                .bounds(box.left() + actionLeft, footer, actionWidth, 18).build();
            leave.active = !EncounterClientState.busy();
            add.accept(leave);
        }
        var reward = row.rewards().stream().filter(value -> !value.claimed()).findFirst().orElse(null);
        if (reward != null) {
            var claim = JournalButton.colored(JournalTexts.tr("claim"), button ->
                EncounterClientState.request(EncounterRequestPayload.Action.CLAIM, reward.id().toString()))
                .bounds(box.left() + box.width() - 170, footer, 158, 18).build();
            claim.active = !EncounterClientState.busy();
            add.accept(claim);
        }
    }

    private void selector(SpiritDialogScreen.Layout box, Consumer<Button> add, int count, boolean courtyard) {
        add.accept(JournalButton.colored(Component.literal("‹"), button -> {
            if (courtyard) selectedCourt = Math.floorMod(selectedCourt - 1, count);
            else selectedEncounter = Math.floorMod(selectedEncounter - 1, count);
            pages.resetDetailScroll();
            screen.changed();
        }).bounds(box.left() + 12, box.contentTop() + 20, 20, 18).build());
        add.accept(JournalButton.colored(Component.literal("›"), button -> {
            if (courtyard) selectedCourt = (selectedCourt + 1) % count;
            else selectedEncounter = (selectedEncounter + 1) % count;
            pages.resetDetailScroll();
            screen.changed();
        }).bounds(box.left() + 36, box.contentTop() + 20, 20, 18).build());
    }

    void render(GuiGraphicsExtractor graphics, SpiritDialogScreen.Layout box) {
        graphics.text(screen.getFont(), JournalTexts.tr("encounter.heading"), box.left() + 14,
            box.contentTop() + 2, SpiritDialogScreen.INK, false);
        var data = EncounterClientState.data();
        var lines = new ArrayList<Component>();
        if (data == null) {
            lines.add(JournalTexts.tr(EncounterClientState.busy() ? "loading" : "empty_connection"));
        } else if (preparing) {
            if (!data.gatewayAvailable()) lines.add(JournalTexts.tr("encounter.executor_absent"));
            if (data.courtyards().isEmpty()) lines.add(JournalTexts.tr("encounter.no_courtyard"));
            else {
                var court = data.courtyards().get(Math.clamp(selectedCourt, 0, data.courtyards().size() - 1));
                lines.add(JournalTexts.placeTitle(court.title()));
                lines.add(JournalTexts.tr("recorded_at", court.position().x(), court.position().y(), court.position().z()));
            }
            lines.add(JournalTexts.tr("encounter.prepare_hint"));
            lines.add(JournalTexts.tr("encounter.conditions"));
            lines.add(JournalTexts.tr("encounter.reward"));
        } else if (!data.encounters().isEmpty()) {
            var row = data.encounters().get(Math.clamp(selectedEncounter, 0, data.encounters().size() - 1));
            lines.add(JournalTexts.placeTitle(row.title()));
            lines.add(JournalTexts.tr("encounter.mode", JournalTexts.tr("encounter." + row.mode().toLowerCase(java.util.Locale.ROOT))));
            lines.add(JournalTexts.tr("encounter.status." + row.status().toLowerCase(java.util.Locale.ROOT)));
            lines.add(JournalTexts.tr("encounter.party", row.participants()));
            lines.add(JournalTexts.tr("encounter.reward"));
            if (row.status().equals("LOBBY") || row.status().equals("ACTIVE"))
                lines.add(JournalTexts.tr("encounter.conditions"));
            if (!row.rewards().isEmpty()) lines.add(JournalTexts.tr(
                row.rewards().stream().allMatch(reward -> reward.claimed()) ? "status.rewarded" : "status.reward_pending"));
        }
        var response = EncounterClientState.response();
        if(!actionError.isBlank() && System.currentTimeMillis()<actionErrorUntil)
            lines.addFirst(Component.literal(actionError).withStyle(net.minecraft.ChatFormatting.RED));
        else if (response != null && !response.feedback().isBlank()) lines.add(Component.literal(response.feedback()));
        if (response != null && response.error() && response.feedback().isBlank()) lines.add(JournalTexts.tr("action_failed"));
        pages.renderLines(graphics, box, box.left() + 14, box.contentTop() + (data!=null && (preparing?data.courtyards().size():data.encounters().size())>1?44:24), box.width() - 28, lines);
    }
}
