package at.vystorm.winter.world;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core.blocks.BlocksEditedEvent;
import Vystorm.vystorm_core.blocks.EditCause;
import Vystorm.vystorm_core.blocks.EditedChunk;
import Vystorm.vystorm_core.blocks.StateTable;
import at.vystorm.winter.storage.TrackedBlocks;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/**
 * Bulk edits (1.27.0): Vystorm Core 0.69.0 announces cells that an edit tool wrote without block events with one
 * {@code BlocksEditedEvent} per chunk. This is the only class that names Core's block write types; it is loaded only
 * when Core has the capability {@code blocks-edited-event} ({@code WinterPlugin.editAware}), so with an older Core
 * nothing changes.
 */
public final class EditAware {
    private EditAware() { }

    /** Registers the handler at OBSERVE: the cells are already written; the chunk is loaded and its record in memory. */
    public static void register(Plugin plugin, API core, WinterTerrain terrain) {
        StateTable states = core.blocks().states();
        core.priority().stages().handle(plugin, BlocksEditedEvent.class, EventStage.OBSERVE,
                (BlocksEditedEvent event) -> terrain.edited(cells(event.chunk(), states)));
    }

    /** The event's cells as the records see them; only ids are read in the loop. */
    static TrackedBlocks.EditedCells cells(EditedChunk chunk, StateTable states) {
        boolean undo = chunk.cause() == EditCause.UNDO;
        UUID world = chunk.world().getUID();
        return new TrackedBlocks.EditedCells() {
            @Override public UUID world() { return world; }
            @Override public int chunkX() { return chunk.chunkX(); }
            @Override public int chunkZ() { return chunk.chunkZ(); }
            @Override public boolean undo() { return undo; }
            @Override public UUID edit() { return chunk.editId(); }
            @Override public int size() { return chunk.size(); }
            @Override public int x(int i) { return chunk.x(i); }
            @Override public int y(int i) { return chunk.y(i); }
            @Override public int z(int i) { return chunk.z(i); }
            @Override public int afterId(int i) { return chunk.afterId(i); }
            @Override public String text(int stateId) { return states.text(stateId); }
        };
    }
}
