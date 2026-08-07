package baritone.mining;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OreScanner {
    private static final int CHUNK_RADIUS = 1;
    public ScanSession begin(ClientLevel level, BlockPos origin, Block target) {
        int centerX = origin.getX() >> 4;
        int centerZ = origin.getZ() >> 4;
        List<ChunkColumn> columns = new ArrayList<>();
        for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
            for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
                columns.add(new ChunkColumn(centerX + dx, centerZ + dz));
            }
        }
        columns.sort(Comparator.comparingInt(column ->
                Math.abs(column.x() - centerX) + Math.abs(column.z() - centerZ)));
        return new ScanSession(origin, target, columns, level.getMinY(), level.getMaxY() - 1);
    }

    public record OreEntry(BlockPos position, Block block, String id) {}

    public record ScanResult(List<OreEntry> ores, boolean firstChunkComplete, boolean complete) {}

    private record ChunkColumn(int x, int z) {}

    public static final class ScanSession {
        private final BlockPos origin;
        private final List<ChunkColumn> columns;
        private final int minY;
        private final int maxY;
        private final List<OreEntry> found = new ArrayList<>();
        private int columnIndex;
        private int localX;
        private int localZ;
        private int y;

        private ScanSession(BlockPos origin, Block target, List<ChunkColumn> columns, int minY, int maxY) {
            this.origin = origin;
            this.columns = columns;
            this.minY = minY;
            this.maxY = maxY;
            this.y = maxY;
        }

        public ScanResult step(ClientLevel level, int budget) {
            int inspected = 0;
            boolean firstChunkComplete = false;
            while (inspected++ < budget && columnIndex < columns.size()) {
                ChunkColumn column = columns.get(columnIndex);
                if (!level.hasChunk(column.x(), column.z())) {
                    columnIndex++;
                    resetColumnCursor();
                    if (columnIndex == 1) firstChunkComplete = true;
                    continue;
                }

                BlockPos pos = new BlockPos((column.x() << 4) + localX, y, (column.z() << 4) + localZ);
                Block block = level.getBlockState(pos).getBlock();
                if (isOre(block)) remember(pos.immutable(), block);
                if (--y >= minY) continue;
                y = maxY;
                if (++localZ < 16) continue;
                localZ = 0;
                if (++localX < 16) continue;
                localX = 0;
                columnIndex++;
                resetColumnCursor();
                if (columnIndex == 1) firstChunkComplete = true;
            }
            return new ScanResult(List.copyOf(found), firstChunkComplete, columnIndex >= columns.size());
        }

        private void resetColumnCursor() {
            localX = 0;
            localZ = 0;
            y = maxY;
        }

        private void remember(BlockPos pos, Block block) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            found.add(new OreEntry(pos, block, id == null ? "unknown" : id.toString()));
        }

        private static boolean isOre(Block block) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) return false;
            String path = id.getPath();
            return path.endsWith("_ore") || path.equals("ancient_debris");
        }
    }
}
