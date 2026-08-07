package baritone.mining;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class AStarPathfinder {
    private static final int PRIMARY_GOAL_COUNT = 3;
    private static final int MAX_VISITED_NODES = 5000;
    private static final Direction[] DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN
    };

    public List<BlockPos> findPath(ClientLevel level, BlockPos start, BlockPos target) {
        return findPath(level, start, target, Set.of());
    }

    public List<BlockPos> findPath(ClientLevel level, BlockPos start, BlockPos target, Set<BlockPos> avoidNodes) {
        List<BlockPos> goals = new ArrayList<>();
        for (Direction direction : DIRECTIONS) {
            BlockPos candidate = target.relative(direction);
            if (safeNode(level, candidate) && !avoidNodes.contains(candidate)) goals.add(candidate);
        }
        goals.sort(Comparator.comparingDouble(pos -> pos.distSqr(start)));

        List<BlockPos> bestDirect = List.of();
        double bestDirectScore = Double.MAX_VALUE;
        for (BlockPos goal : goals) {
            List<BlockPos> direct = directPath(level, start, goal, avoidNodes);
            if (direct.isEmpty()) continue;
            double score = pathScore(level, direct);
            if (score < bestDirectScore) {
                bestDirectScore = score;
                bestDirect = direct;
            }
        }
        if (!bestDirect.isEmpty()) return bestDirect;

        List<BlockPos> bestPath = List.of();
        double bestScore = Double.MAX_VALUE;
        int primaryCount = Math.min(PRIMARY_GOAL_COUNT, goals.size());
        for (int pass = 0; pass < 2; pass++) {
            int from = pass == 0 ? 0 : primaryCount;
            int to = pass == 0 ? primaryCount : goals.size();
            if (from >= to || (pass == 1 && !bestPath.isEmpty())) continue;
            for (int i = from; i < to; i++) {
                List<BlockPos> path = search(level, start, goals.get(i), avoidNodes);
                if (path.isEmpty()) continue;
                double score = pathScore(level, path);
                if (score < bestScore) {
                    bestScore = score;
                    bestPath = path;
                }
            }
        }
        return bestPath;
    }

    private List<BlockPos> directPath(ClientLevel level, BlockPos start, BlockPos goal, Set<BlockPos> avoidNodes) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos current = start;
        result.add(current);
        while (!current.equals(goal) && result.size() <= 256) {
            int dx = goal.getX() - current.getX();
            int dy = goal.getY() - current.getY();
            int dz = goal.getZ() - current.getZ();
            Direction direction;
            if (Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz)) {
                direction = dx > 0 ? Direction.EAST : Direction.WEST;
            } else if (Math.abs(dy) >= Math.abs(dz)) {
                direction = dy > 0 ? Direction.UP : Direction.DOWN;
            } else {
                direction = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            }
            current = current.relative(direction);
            if (!safeNode(level, current) || avoidNodes.contains(current)) return List.of();
            result.add(current);
        }
        return current.equals(goal) ? List.copyOf(result) : List.of();
    }

    private double pathScore(ClientLevel level, List<BlockPos> path) {
        double score = path.size();
        for (int i = 1; i < path.size(); i++) {
            BlockPos pos = path.get(i);
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty()) score += 8.0D;
            if (path.get(i - 1).getY() != pos.getY()) score += 1.5D;
        }
        return score;
    }

    private List<BlockPos> search(ClientLevel level, BlockPos start, BlockPos goal, Set<BlockPos> avoidNodes) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::priority));
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> cost = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        open.add(new Node(start, 0, heuristic(start, goal)));
        cost.put(start, 0D);

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_VISITED_NODES) {
            Node current = open.poll();
            if (!closed.add(current.pos())) continue;
            if (current.pos().equals(goal)) return rebuild(cameFrom, current.pos());

            for (Direction direction : DIRECTIONS) {
                BlockPos next = current.pos().relative(direction);
                if (!safeNode(level, next) || avoidNodes.contains(next) || closed.contains(next)) continue;
                double nextCost = current.cost() + movementCost(level, next);
                if (nextCost >= cost.getOrDefault(next, Double.MAX_VALUE)) continue;
                cost.put(next, nextCost);
                cameFrom.put(next, current.pos());
                open.add(new Node(next, nextCost, nextCost + heuristic(next, goal)));
            }
        }
        return List.of();
    }

    private static List<BlockPos> rebuild(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        ArrayDeque<BlockPos> result = new ArrayDeque<>();
        result.addFirst(end);
        BlockPos current = end;
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            result.addFirst(current);
        }
        return List.copyOf(result);
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    private static double movementCost(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        double cost = state.getCollisionShape(level, pos).isEmpty() ? 1D : 9.0D;
        return cost + (pos.getY() == 0 ? 0D : 0.15D);
    }

    private static boolean safeNode(ClientLevel level, BlockPos pos) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        if (isLava(level, pos) || isLava(level, pos.above()) || isLava(level, pos.below())) return false;
        if (isGravel(level, pos) || isGravel(level, pos.above()) || isGravel(level, pos.below())) return false;
        if (level.getBlockState(pos).is(Blocks.BEDROCK)) return false;
        if (level.getBlockState(pos.above()).is(Blocks.BEDROCK)) return false;
        return true;
    }

    private static boolean isGravel(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.GRAVEL);
    }

    private static boolean isLava(ClientLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(net.minecraft.world.level.material.Fluids.LAVA)
                || level.getFluidState(pos).is(net.minecraft.world.level.material.Fluids.FLOWING_LAVA);
    }

    private record Node(BlockPos pos, double cost, double priority) {}
}
