package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.quest.QuestType;
import dev.wicklen.chronicles.net.StoryRequestPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.function.Consumer;

/** Preview a consequence first, then explicitly select the corresponding server-owned plan. */
final class JournalStories {
    private final SpiritDialogScreen screen;
    private final JournalPages pages;
    private int selected;

    JournalStories(SpiritDialogScreen screen, JournalPages pages) {
        this.screen = screen;
        this.pages = pages;
    }

    void read() { StoryClientState.request(StoryRequestPayload.Action.READ, "", ""); }

    void init(SpiritDialogScreen.Layout box, Consumer<Button> add) {
        var snapshot = StoryClientState.story();
        var plan = snapshot == null ? null : snapshot.pending();
        int footer = box.top() + box.height() - 38;
        if (plan == null) {
            var view = JournalClientState.view();
            var request = Button.builder(JournalTexts.tr("story_request"), button ->
                StoryClientState.request(StoryRequestPayload.Action.REQUEST, "", ""))
                .bounds(box.left() + box.width() - 196, footer, 184, 18).build();
            request.active = !StoryClientState.busy() && view != null
                && view.totalDiscoveries() > 0 && view.activeQuest() == null;
            add.accept(request);
            return;
        }
        selected = Math.clamp(selected, 0, 1);
        int width = (box.width() - 30) / 2;
        for (int i = 0; i < 2; i++) {
            final int index = i;
            var choice = plan.choices().get(i);
            var label = Component.literal(screen.getFont().plainSubstrByWidth(choice.label(), width - 16));
            var button = Button.builder(label, ignored -> {
                selected = index;
                pages.resetDetailScroll();
                screen.changed();
            }).bounds(box.left() + 12 + i * (width + 6), box.contentTop() + 22, width, 18)
                .tooltip(Tooltip.create(Component.literal(choice.label() + "\n" + choice.consequencePreview())))
                .build();
            button.active = i != selected;
            add.accept(button);
        }
        var choice = plan.choices().get(selected);
        var choose = Button.builder(JournalTexts.tr("story_choose"), button ->
            StoryClientState.request(StoryRequestPayload.Action.CHOOSE, plan.id().toString(), choice.id()))
            .bounds(box.left() + box.width() - 196, footer, 184, 18).build();
        choose.active = !StoryClientState.busy();
        add.accept(choose);
    }

    void render(GuiGraphicsExtractor graphics, SpiritDialogScreen.Layout box) {
        var snapshot = StoryClientState.story();
        var plan = snapshot == null ? null : snapshot.pending();
        var lines = new ArrayList<Component>();
        graphics.text(screen.getFont(), JournalTexts.tr("story_heading"),
            box.left() + 14, box.contentTop() + 2, SpiritDialogScreen.INK, false);
        if (plan != null) {
            var choice = plan.choices().get(Math.clamp(selected, 0, 1));
            lines.add(Component.literal(plan.title()));
            lines.add(Component.literal(choice.label()));
            lines.add(JournalTexts.tr("consequence", Component.literal(choice.consequencePreview())));
            var quest = choice.quest();
            lines.add(JournalTexts.quest(quest));
            lines.add(JournalTexts.tr("reward", quest.reward().count(), JournalTexts.item(quest.reward().itemId())));
            if (quest.type() == QuestType.COLLECT)
                lines.add(JournalTexts.tr("collection_cost", quest.required(), JournalTexts.target(quest)));
            lines.add(Component.literal(plan.motive()));
            pages.renderLines(graphics, box, box.left() + 14, box.contentTop() + 47, box.width() - 28, lines);
            return;
        }
        var view = JournalClientState.view();
        lines.add(JournalTexts.tr(view == null || view.totalDiscoveries() == 0 ? "story_need_discovery"
            : view.activeQuest() != null ? "story_finish_task" : "story_empty"));
        if (StoryClientState.busy()) lines.add(JournalTexts.tr("story_thinking"));
        if (!StoryClientState.feedback().isBlank()) lines.add(Component.literal(StoryClientState.feedback()));
        if (snapshot != null && !snapshot.history().isEmpty()) {
            lines.add(JournalTexts.tr("story_past"));
            for (var outcome : snapshot.history()) lines.add(Component.literal(outcome.consequence()));
        }
        pages.renderLines(graphics, box, box.left() + 14, box.contentTop() + 27, box.width() - 28, lines);
    }
}
