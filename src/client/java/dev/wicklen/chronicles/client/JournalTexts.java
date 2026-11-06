package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.discovery.DiscoveryEntry;
import dev.wicklen.chronicles.core.quest.QuestInstance;
import dev.wicklen.chronicles.core.quest.QuestDefinition;
import dev.wicklen.chronicles.core.quest.QuestType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

final class JournalTexts {
    static Component tr(String suffix, Object... args) {
        return Component.translatable("journal.wicklen_chronicles." + suffix, args);
    }
    static Component target(QuestInstance quest) {
        return target(quest.definition());
    }
    static Component target(QuestDefinition quest) {
        return quest.type() == QuestType.COLLECT
            ? item(quest.target()) : label(quest.type() == QuestType.SLAY ? "entity" : "biome", quest.target());
    }
    static Component item(String id) {
        var identifier = Identifier.tryParse(id);
        if (identifier == null) return Component.literal(id);
        return BuiltInRegistries.ITEM.get(identifier).<Component>map(ref -> new net.minecraft.world.item.ItemStack(ref.value()).getHoverName()).orElseGet(() -> label("item", id));
    }
    static Component label(String type, String id) {
        var identifier = Identifier.tryParse(id);
        if (identifier == null) return Component.literal(id);
        return Component.translatableWithFallback(type + "." + identifier.getNamespace() + "." + identifier.getPath().replace('/', '.'),
            identifier.getPath().replace('_', ' '));
    }
    static Component placeTitle(String title) {
        for(var variant:dev.wicklen.chronicles.station.StationVariant.values())
            if(variant.title().equals(title))
                return Component.translatableWithFallback("station.wicklen_chronicles."+variant.name().toLowerCase(java.util.Locale.ROOT)+".title",title);
        return Component.literal(title);
    }
    static Component discovery(DiscoveryEntry entry) {
        var annotation=AnnotationClientState.lookup(entry.key());
        return annotation!=null&&!annotation.label().isBlank()?Component.literal(annotation.label()):canonicalDiscovery(entry);
    }
    static Component canonicalDiscovery(DiscoveryEntry entry) {
        return switch (entry.key().kind()) {
            case CREATURE -> label("entity", entry.key().id());
            case BIOME -> label("biome", entry.key().id());
            case PLACE -> Component.translatableWithFallback("journal.wicklen_chronicles.place." + entry.key().id().replace(':', '.').replace('/', '.'), placeTitle(entry.title()).getString());
        };
    }
    static Component quest(QuestInstance quest) {
        return quest(quest.definition());
    }
    static Component quest(QuestDefinition quest) {
        return tr("quest." + quest.type().name().toLowerCase(java.util.Locale.ROOT), target(quest), quest.required());
    }
    private JournalTexts() {}
}
