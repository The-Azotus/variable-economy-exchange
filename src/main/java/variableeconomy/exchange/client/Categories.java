package variableeconomy.exchange.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import variableeconomy.exchange.ExchangeSettings;
import variableeconomy.exchange.network.ExchangePayloads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Browse tab's categories, as the server's {@code browse.categories} names them. An item is in a category when it's
 * in that category's item tag (tags reach the client with the world). An item in none of them takes the category its
 * creative tab stands for ({@code browse.creative_tab_fallback}), and an item in neither is Miscellaneous. An item may
 * be in several categories.
 */
final class Categories {
    /** One category: its config entry, its tag (none for Miscellaneous), and its name. */
    record Category(String id, Optional<TagKey<Item>> tag, Component name) {
        boolean miscellaneous() {
            return id.equals(ExchangeSettings.MISCELLANEOUS);
        }
    }

    private final List<Category> categories = new ArrayList<>();
    /** Creative tab ID to the categories it stands for. */
    private final Map<ResourceLocation, List<String>> fallback = new HashMap<>();
    /** Each item's categories, worked out once. */
    private final Map<Item, Set<String>> cache = new HashMap<>();
    /** Each item's creative tabs, read once the tabs are built. */
    private Map<Item, Set<ResourceLocation>> creativeTabs;
    private final ExchangePayloads.Categories source;

    Categories(ExchangePayloads.Categories source) {
        this.source = source;
        for (String id : source.categories()) {
            if (categories.stream().anyMatch(c -> c.id().equals(id))) {
                continue;
            }
            if (id.equals(ExchangeSettings.MISCELLANEOUS)) {
                categories.add(new Category(id, Optional.empty(), Component.translatable("screen.variable_economy_exchange.category." + id)));
                continue;
            }
            ExchangeSettings.categoryTag(id).ifPresent(tag -> categories.add(new Category(id, Optional.of(TagKey.create(Registries.ITEM, tag)), name(id, tag))));
        }
        for (String entry : source.creativeTabFallback()) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            ResourceLocation tab = ResourceLocation.tryParse(entry.substring(eq + 1).trim());
            if (tab != null) {
                fallback.computeIfAbsent(tab, t -> new ArrayList<>()).add(entry.substring(0, eq).trim());
            }
        }
    }

    /** Whether these were built from {@code other}. */
    boolean from(ExchangePayloads.Categories other) {
        return source.equals(other);
    }

    List<Category> all() {
        return categories;
    }

    /** The categories {@code item} is in. */
    Set<String> of(Item item) {
        return cache.computeIfAbsent(item, this::compute);
    }

    boolean contains(Category category, Item item) {
        return of(item).contains(category.id());
    }

    private Set<String> compute(Item item) {
        Set<String> found = new LinkedHashSet<>();
        ItemStack stack = new ItemStack(item);
        for (Category category : categories) {
            if (category.tag().isPresent() && stack.is(category.tag().get())) {
                found.add(category.id());
            }
        }
        if (found.isEmpty()) {
            for (ResourceLocation tab : creativeTabs().getOrDefault(item, Set.of())) {
                for (String id : fallback.getOrDefault(tab, List.of())) {
                    if (categories.stream().anyMatch(c -> c.id().equals(id))) {
                        found.add(id);
                    }
                }
            }
        }
        found.remove(ExchangeSettings.MISCELLANEOUS);
        if (found.isEmpty()) {
            found.add(ExchangeSettings.MISCELLANEOUS);
        }
        return found;
    }

    /**
     * Each item's creative tabs. The tabs are built the way the creative inventory builds them, once, the first time an
     * item in no category tag is looked up.
     */
    private Map<Item, Set<ResourceLocation>> creativeTabs() {
        if (creativeTabs != null) {
            return creativeTabs;
        }
        creativeTabs = new HashMap<>();
        if (fallback.isEmpty()) {
            return creativeTabs;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.level != null) {
            CreativeModeTabs.tryRebuildTabContents(minecraft.player.connection.enabledFeatures(),
                    minecraft.options.operatorItemsTab().get() && minecraft.player.canUseGameMasterBlocks(), minecraft.level.registryAccess());
        }
        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            ResourceLocation id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (id == null || !fallback.containsKey(id)) {
                continue;
            }
            for (ItemStack stack : tab.getDisplayItems()) {
                creativeTabs.computeIfAbsent(stack.getItem(), i -> new HashSet<>()).add(id);
            }
        }
        return creativeTabs;
    }

    /**
     * A category's name: the mod's own translation for a plain name, a full ID's tag translation as mods name their
     * tags, or else its last word, capitalised.
     */
    private static Component name(String id, ResourceLocation tag) {
        String key = id.contains(":") ? "tag.item." + tag.getNamespace() + "." + tag.getPath().replace('/', '.')
                : "screen.variable_economy_exchange.category." + id;
        if (I18n.exists(key)) {
            return Component.translatable(key);
        }
        String last = tag.getPath().substring(tag.getPath().lastIndexOf('/') + 1).replace('_', ' ');
        return Component.literal(last.isEmpty() ? id : last.substring(0, 1).toUpperCase(Locale.ROOT) + last.substring(1));
    }
}
