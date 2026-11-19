package at.vystorm.winter.world;

import at.vystorm.winter.storage.TrackedBlocks;
import at.vystorm.winter.storage.TrackedBlocks.ChunkId;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;

/**
 * Keeps each chunk's winter record in the chunk's own persistent data (1.24.2), so it is saved together with the
 * chunk's blocks. Only touches loaded chunks; never loads one.
 */
public final class ChunkRecordStorage implements TrackedBlocks.Storage {
    private final NamespacedKey key;
    /** The chunk of a running load or unload event, which may not count as loaded yet or any more. */
    private Chunk current;

    public ChunkRecordStorage(NamespacedKey key) {
        this.key = key;
    }
    void current(Chunk chunk) {
        current = chunk;
    }
    private Chunk chunk(ChunkId id) {
        Chunk u = current;
        if (u != null && u.getX() == id.x() && u.getZ() == id.z() && u.getWorld().getUID().equals(id.world())) return u;
        World world = Bukkit.getWorld(id.world());
        if (world == null || !world.isChunkLoaded(id.x(), id.z())) return null;
        return world.getChunkAt(id.x(), id.z());
    }
    @Override public byte[] read(ChunkId id) {
        Chunk chunk = chunk(id);
        return chunk == null ? null : chunk.getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
    }
    @Override public void write(ChunkId id, byte[] data) {
        Chunk chunk = chunk(id);
        if (chunk == null) throw new IllegalStateException("Winter record of chunk " + id.x() + "," + id.z() + " changed while the chunk is not loaded");
        if (data == null) chunk.getPersistentDataContainer().remove(key);
        else chunk.getPersistentDataContainer().set(key, PersistentDataType.BYTE_ARRAY, data);
    }
}
