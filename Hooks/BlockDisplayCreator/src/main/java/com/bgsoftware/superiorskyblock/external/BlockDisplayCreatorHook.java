package com.bgsoftware.superiorskyblock.external;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.key.CustomKeyParser;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.key.KeyMap;
import com.bgsoftware.superiorskyblock.api.service.world.WorldRecordFlags;
import com.bgsoftware.superiorskyblock.api.service.world.WorldRecordService;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import com.bgsoftware.superiorskyblock.core.LazyReference;
import com.bgsoftware.superiorskyblock.core.ObjectsPools;
import com.bgsoftware.superiorskyblock.core.key.KeyIndicator;
import com.bgsoftware.superiorskyblock.core.key.Keys;
import com.bgsoftware.superiorskyblock.core.key.map.KeyMaps;
import com.bgsoftware.superiorskyblock.external.blocks.ICustomBlocksProvider;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.custom.block.AbstractCustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockService;
import me.general_breddok.blockdisplaycreator.event.custom.block.CustomBlockBreakEvent;
import me.general_breddok.blockdisplaycreator.event.custom.block.CustomBlockPlaceEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom blocks of BlockDisplayCreator count in the island: each placed block is the key BDC:&lt;NAME&gt; (its file
 * name in capitals), valued in levels.yml / worth.yml like any block. Placements and breaks are recorded when they
 * happen, and a recalculation reads the blocks stored in each chunk of the island.
 */
public class BlockDisplayCreatorHook {

    private static final String BDC_PREFIX = "BDC";
    private static final Key PARSER_KEY = Keys.of(BDC_PREFIX, null, KeyIndicator.CUSTOM);

    private static final LazyReference<WorldRecordService> worldRecordService = new LazyReference<WorldRecordService>() {
        @Override
        protected WorldRecordService create() {
            return plugin.getServices().getService(WorldRecordService.class);
        }
    };

    /** Block names in capitals -> names as BlockDisplayCreator knows them, for the item shown in menus. */
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();

    private static boolean registered = false;

    private static SuperiorSkyblockPlugin plugin;

    public static void register(SuperiorSkyblockPlugin plugin) {
        BlockDisplayCreatorHook.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(new ListenerImpl(), plugin);
        if (!registered) {
            registered = true;
            plugin.getProviders().registerCustomBlocksProvider(new CustomBlocksProviderImpl());
            plugin.getBlockValues().registerKeyParser(new KeyParserImpl(), PARSER_KEY);
        }
    }

    private static Key keyOf(String name) {
        return Keys.of(BDC_PREFIX, name.toUpperCase(Locale.ENGLISH), KeyIndicator.CUSTOM);
    }

    private static CustomBlockService service() {
        return BlockDisplayCreator.getInstance().getCustomBlockService();
    }

    private static class CustomBlocksProviderImpl implements ICustomBlocksProvider {

        @Nullable
        @Override
        public KeyMap<Integer> getBlockCountsForChunk(ChunkPosition chunkPosition) {
            // The blocks are read from the chunk itself: only on the main thread, the calculation asks again there.
            if (!Bukkit.isPrimaryThread())
                return null;

            World world = chunkPosition.getWorld();
            if (!world.isChunkLoaded(chunkPosition.getX(), chunkPosition.getZ()))
                return KeyMaps.createEmptyMap();

            KeyMap<Integer> blockCounts = KeyMaps.createHashMap(KeyIndicator.CUSTOM);
            for (String name : service().getCustomBlockNames(world.getChunkAt(chunkPosition.getX(), chunkPosition.getZ()))) {
                Key blockKey = keyOf(name);
                blockCounts.put(blockKey, blockCounts.getRaw(blockKey, 0) + 1);
            }
            return blockCounts;
        }

    }

    private static class ListenerImpl implements Listener {

        @WorldRecordFlags
        private static final int REGULAR_RECORD_FLAGS = WorldRecordFlags.SAVE_BLOCK_COUNT | WorldRecordFlags.DIRTY_CHUNKS;

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onCustomBlockPlace(CustomBlockPlaceEvent e) {
            CustomBlock block = e.getCustomBlock();
            try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                Location location = wrapper.getHandle();
                copy(block.getLocation(), location);
                worldRecordService.get().recordBlockPlace(keyOf(block.getName()), location, 1, null, REGULAR_RECORD_FLAGS);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onCustomBlockBreak(CustomBlockBreakEvent e) {
            CustomBlock block = e.getCustomBlock();
            try (ObjectsPools.Wrapper<Location> wrapper = ObjectsPools.LOCATION.obtain()) {
                Location location = wrapper.getHandle();
                copy(block.getLocation(), location);
                worldRecordService.get().recordBlockBreak(keyOf(block.getName()), location, 1, REGULAR_RECORD_FLAGS);
            }
        }

        private static void copy(Location from, Location to) {
            to.setWorld(from.getWorld());
            to.setX(from.getBlockX());
            to.setY(from.getBlockY());
            to.setZ(from.getBlockZ());
        }

    }

    private static class KeyParserImpl implements CustomKeyParser {

        @Override
        public boolean isCustomKey(Key key) {
            return key.getGlobalKey().equals(BDC_PREFIX) && key.getSubKey() != null && !key.getSubKey().isEmpty();
        }

        @Override
        @Nullable
        public ItemStack getCustomKeyItem(Key key) {
            String name = NAMES.get(key.getSubKey());
            if (name == null) {
                for (String known : service().getStorage().getNames())
                    NAMES.put(known.toUpperCase(Locale.ENGLISH), known);
                name = NAMES.get(key.getSubKey());
            }
            AbstractCustomBlock block = name == null ? null : service().getStorage().getAbstractCustomBlock(name);
            return block == null ? null : block.getItem();
        }

    }

}
