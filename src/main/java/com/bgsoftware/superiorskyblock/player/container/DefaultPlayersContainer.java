package com.bgsoftware.superiorskyblock.player.container;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.superiorskyblock.api.player.container.PlayersContainer;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.superiorskyblock.core.SequentialListBuilder;
import com.bgsoftware.superiorskyblock.core.logging.Log;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultPlayersContainer implements PlayersContainer {

    private final Map<UUID, SuperiorPlayer> players = new ConcurrentHashMap<>();
    private final Map<String, SuperiorPlayer> playersByNames = new ConcurrentHashMap<>();

    // Each inconsistency is reported once, so the console names the code that caused it without flooding.
    private final Set<UUID> reportedReplacedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> reportedStaleNames = ConcurrentHashMap.newKeySet();

    @Nullable
    @Override
    public SuperiorPlayer getSuperiorPlayer(String name) {
        String lowerName = name.toLowerCase(Locale.ENGLISH);
        SuperiorPlayer superiorPlayer = this.playersByNames.get(lowerName);

        // The name index must point to the record kept for that uuid. A second record of the same player
        // (left behind by a duplicate creation) has no island, which made every name based command answer
        // "<player> has no island" until the next restart.
        if (superiorPlayer != null && (this.players.get(superiorPlayer.getUniqueId()) != superiorPlayer ||
                !superiorPlayer.getName().equalsIgnoreCase(name))) {
            if (this.reportedStaleNames.add(lowerName)) {
                SuperiorPlayer current = this.players.get(superiorPlayer.getUniqueId());
                Log.warn("Name index of ", name, " pointed to a stale record (", superiorPlayer.getUniqueId(),
                        ", island: ", superiorPlayer.getIsland() != null, ") instead of the current one (",
                        current == null ? "none" : current.getUniqueId() + ", island: " + (current.getIsland() != null),
                        "), fixed.");
            }
            this.playersByNames.remove(lowerName, superiorPlayer);
            superiorPlayer = null;
        }

        if (superiorPlayer == null) {
            superiorPlayer = findByName(name);
            if (superiorPlayer != null)
                this.playersByNames.put(lowerName, superiorPlayer);
        }

        return superiorPlayer;
    }

    @Nullable
    @Override
    public SuperiorPlayer getSuperiorPlayer(UUID uuid) {
        return this.players.get(uuid);
    }

    @Override
    public List<SuperiorPlayer> getAllPlayers() {
        return new SequentialListBuilder<SuperiorPlayer>().build(this.players.values());
    }

    @Override
    public void addPlayer(SuperiorPlayer superiorPlayer) {
        SuperiorPlayer previous = this.players.put(superiorPlayer.getUniqueId(), superiorPlayer);

        if (previous != null && previous != superiorPlayer) {
            this.playersByNames.remove(previous.getName().toLowerCase(Locale.ENGLISH), previous);
            if (this.reportedReplacedPlayers.add(superiorPlayer.getUniqueId())) {
                Log.error(new IllegalStateException("Duplicate player record"), "Record of ", previous.getName(),
                        " (", previous.getUniqueId(), ", island: ", previous.getIsland() != null,
                        ") was replaced by another record (island: ", superiorPlayer.getIsland() != null,
                        ") on thread ", Thread.currentThread().getName(), ":");
            }
        }

        String playerName = superiorPlayer.getName();
        if (!playerName.equals("null"))
            this.playersByNames.put(playerName.toLowerCase(Locale.ENGLISH), superiorPlayer);
    }

    @Override
    public void removePlayer(SuperiorPlayer superiorPlayer) {
        // Removing a stale record must never drop the current record of the same player.
        this.players.remove(superiorPlayer.getUniqueId(), superiorPlayer);
        this.playersByNames.remove(superiorPlayer.getName().toLowerCase(Locale.ENGLISH), superiorPlayer);
    }

    @Nullable
    private SuperiorPlayer findByName(String name) {
        SuperiorPlayer found = null;
        for (SuperiorPlayer player : this.players.values()) {
            if (!player.getName().equalsIgnoreCase(name))
                continue;
            if (found == null || rank(player) > rank(found))
                found = player;
        }
        return found;
    }

    // Several records can share a name (renamed accounts): prefer the online one, then the one with an island.
    private static int rank(SuperiorPlayer superiorPlayer) {
        return (superiorPlayer.isOnline() ? 2 : 0) + (superiorPlayer.getIsland() != null ? 1 : 0);
    }

}
