package baritone.mining;

import baritone.client.BaritoneConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MiningController {
    private static final double DEBUG_RENDER_RADIUS = 64.0D;
    private static final double DEBUG_RENDER_RADIUS_SQR = DEBUG_RENDER_RADIUS * DEBUG_RENDER_RADIUS;
    private static final int MAX_RENDER_ORES = 96;
    private static final int MAX_RENDER_PATH_SEGMENTS = 48;

    private boolean active;
    private List<Block> targets = List.of();
    private final OreScanner scanner = new OreScanner();
    private final AStarPathfinder pathfinder = new AStarPathfinder();
    private BlockPos targetPosition;
    private List<BlockPos> discoveredTargets = List.of();
    private List<OreScanner.OreEntry> discoveredOres = List.of();
    private List<BlockPos> path = List.of();
    private int pathIndex;
    private BlockPos breakingPosition;
    private BlockPos clearancePosition;
    private int clearanceTicks;
    private BlockPos placementPosition;
    private int placementTicks;
    private OreScanner.ScanSession scanSession;
    private int noTargetCooldown;
    private int pathFailureTicks;
    private final Set<BlockPos> routeAvoidNodes = new HashSet<>();
    private boolean automationKeysActive;
    private Vec3 lastPosition;
    private int stuckTicks;
    private float yawVelocity;
    private float pitchVelocity;
    private BlockPos aimLockPosition;
    private Boolean previousViewBob;

    public void start(Minecraft client, List<Block> newTargets) {
        releaseAutomationKeys(client);
        if (previousViewBob == null) {
            previousViewBob = client.options.bobView().get();
        }
        client.options.bobView().set(false);
        targets = List.copyOf(newTargets);
        active = true;
        targetPosition = null;
        discoveredTargets = List.of();
        discoveredOres = List.of();
        path = List.of();
        pathIndex = 0;
        breakingPosition = null;
        aimLockPosition = null;
        clearancePosition = null;
        clearanceTicks = 0;
        placementPosition = null;
        placementTicks = 0;
        scanSession = null;
        noTargetCooldown = 0;
        pathFailureTicks = 0;
        yawVelocity = 0.0F;
        pitchVelocity = 0.0F;
        routeAvoidNodes.clear();
        automationKeysActive = false;
        lastPosition = client.player == null ? null : client.player.position();
        stuckTicks = 0;
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(
                    "[Baritone] Добыча включена: " + targets.size() + " целей."));
        }
    }

    public void stop(Minecraft client) {
        if (previousViewBob != null) {
            client.options.bobView().set(previousViewBob);
            previousViewBob = null;
        }
        active = false;
        targets = List.of();
        targetPosition = null;
        discoveredTargets = List.of();
        discoveredOres = List.of();
        path = List.of();
        pathIndex = 0;
        breakingPosition = null;
        aimLockPosition = null;
        clearancePosition = null;
        clearanceTicks = 0;
        placementPosition = null;
        placementTicks = 0;
        scanSession = null;
        pathFailureTicks = 0;
        yawVelocity = 0.0F;
        pitchVelocity = 0.0F;
        routeAvoidNodes.clear();
        releaseAutomationKeys(client);
        lastPosition = null;
        stuckTicks = 0;
        if (client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[Baritone] Добыча выключена."));
        }
    }

    public boolean isActive() {
        return active;
    }

    public List<Block> targets() {
        return targets;
    }

    public void tick(Minecraft client) {
        if (!active || client.player == null || client.level == null || client.gameMode == null) return;
        if (client.isPaused()) {
            releaseAutomationKeys(client);
            client.gameMode.stopDestroyBlock();
            breakingPosition = null;
            aimLockPosition = null;
            yawVelocity = 0.0F;
            pitchVelocity = 0.0F;
            return;
        }
        ClientLevel level = client.level;

        if (automationKeysActive && lastPosition != null) {
            stuckTicks = client.player.position().distanceToSqr(lastPosition) < 0.0009D ? stuckTicks + 1 : 0;
        }
        lastPosition = client.player.position();
        if (stuckTicks > 20 && recoverFromStuck(client)) return;

        if (targetPosition != null) {
            if (scanSession == null && noTargetCooldown-- <= 0) {
                noTargetCooldown = 100;
                scanSession = scanner.begin(level, client.player.blockPosition(), targets.get(0));
            }
            if (scanSession != null) {
                OreScanner.ScanResult background = scanSession.step(level, 1500);
                mergeScanResult(background);
                if (background.complete()) scanSession = null;
            }
        }

        if (targetPosition != null && !level.getBlockState(targetPosition).is(targets.get(0))) {
            client.gameMode.stopDestroyBlock();
            discoveredTargets = discoveredTargets.stream()
                    .filter(pos -> !pos.equals(targetPosition))
                    .toList();
            discoveredOres = discoveredOres.stream()
                    .filter(entry -> !entry.position().equals(targetPosition))
                    .toList();
            targetPosition = null;
            scanSession = null;
            path = List.of();
            pathIndex = 0;
            breakingPosition = null;
            aimLockPosition = null;
            clearancePosition = null;
            clearanceTicks = 0;
            placementPosition = null;
            placementTicks = 0;
            routeAvoidNodes.clear();
        }
        if (targetPosition == null) {
            targetPosition = takeNextKnownTarget(level, client.player.blockPosition());
            if (targetPosition != null) {
                routeAvoidNodes.clear();
            }
        }
        if (targetPosition == null) {
            if (scanSession == null && noTargetCooldown-- <= 0) {
                noTargetCooldown = 40;
                scanSession = scanner.begin(level, client.player.blockPosition(), targets.get(0));
            }
            if (scanSession != null) {
                OreScanner.ScanResult result = scanSession.step(level, 5000);
                if (result.firstChunkComplete() || result.complete()) {
                    mergeScanResult(result);
                    sendOreIndex(client, discoveredOres);
                    targetPosition = takeNextKnownTarget(level, client.player.blockPosition());
                    if (targetPosition != null) {
                        path = List.of();
                        pathIndex = 0;
                        routeAvoidNodes.clear();
                        client.player.sendSystemMessage(Component.literal(
                                "[Baritone] Цель найдена: " + targetPosition.toShortString()));
                    } else if (result.complete()) {
                        scanSession = null;
                        client.player.sendSystemMessage(Component.literal(
                                "[Baritone] В загруженных чанках цель не найдена."));
                    }
                }
            }
            if (targetPosition == null) return;
        }

        if (path.isEmpty() || (pathIndex < path.size() && nextPathNodeIsUnsafe(level))) {
            path = pathfinder.findPath(level, client.player.blockPosition(), targetPosition, routeAvoidNodes);
            pathIndex = 0;
            pathFailureTicks = path.isEmpty() ? pathFailureTicks + 1 : 0;
        }

        if (path.isEmpty()) {
            releaseAutomationKeys(client);
            if (pathFailureTicks > 40) {
                discoveredTargets = discoveredTargets.stream()
                        .filter(pos -> !pos.equals(targetPosition))
                        .toList();
                targetPosition = null;
                pathFailureTicks = 0;
            }
            return;
        }

        while (pathIndex < path.size()
                && client.player.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(path.get(pathIndex))) < 0.58D
                && pathNodeIsClear(client, path.get(pathIndex))) {
            pathIndex++;
            clearancePosition = null;
        }
        if (pathIndex >= path.size()) {
            mineTarget(client, targetPosition);
        } else {
            moveOrDig(client, path.get(pathIndex));
        }
    }

    private BlockPos takeNextKnownTarget(ClientLevel level, BlockPos origin) {
        if (discoveredTargets.isEmpty()) return null;
        List<BlockPos> remaining = discoveredTargets.stream()
                .filter(pos -> level.getBlockState(pos).is(targets.get(0)))
                .sorted(java.util.Comparator.comparingDouble(pos -> pos.distSqr(origin)))
                .toList();
        if (remaining.isEmpty()) {
            discoveredTargets = List.of();
            return null;
        }
        BlockPos next = remaining.get(0);
        discoveredTargets = remaining.stream().filter(pos -> !pos.equals(next)).toList();
        return next;
    }

    private void mergeScanResult(OreScanner.ScanResult result) {
        Map<BlockPos, OreScanner.OreEntry> mergedOres = new HashMap<>();
        for (OreScanner.OreEntry entry : discoveredOres) mergedOres.put(entry.position(), entry);
        for (OreScanner.OreEntry entry : result.ores()) mergedOres.put(entry.position(), entry);
        discoveredOres = List.copyOf(mergedOres.values());

        Set<BlockPos> mergedTargets = new HashSet<>(discoveredTargets);
        for (OreScanner.OreEntry entry : result.ores()) {
            if (entry.block().equals(targets.get(0))
                    && (targetPosition == null || !entry.position().equals(targetPosition))) {
                mergedTargets.add(entry.position());
            }
        }
        discoveredTargets = List.copyOf(mergedTargets);
    }

    private void moveOrDig(Minecraft client, BlockPos next) {
        BlockPos current = client.player.blockPosition();
        if (hasGravelHazard(client, current, next)) {
            releaseAutomationKeys(client);
            client.gameMode.stopDestroyBlock();
            breakingPosition = null;
            aimLockPosition = null;
            clearancePosition = null;
            clearanceTicks = 0;
            path = List.of();
            pathIndex = 0;
            return;
        }
        BlockPos blocker = findClearanceBlock(client, current, next);
        if (blocker != null) {
            if (!attemptClearance(client, blocker)) {
                return;
            }
            if (isSolid(client.level, blocker)) return;
        }
        clearancePosition = null;
        clearanceTicks = 0;
        if (client.player.isInWater()) {
            moveInWater(client, next);
            return;
        }
        float yawError = face(client, net.minecraft.world.phys.Vec3.atCenterOf(next));
        boolean aligned = Math.abs(yawError) < 18.0F;
        boolean jump = aligned && next.getY() > client.player.blockPosition().getY();
        setMovementKeys(client, aligned, jump);
    }

    private void moveInWater(Minecraft client, BlockPos next) {
        float yawError = face(client, Vec3.atCenterOf(next));
        boolean aligned = Math.abs(yawError) < 18.0F;
        int verticalDelta = next.getY() - client.player.blockPosition().getY();
        boolean rise = aligned && verticalDelta > 0;
        boolean sink = aligned && verticalDelta < 0;
        setMovementKeys(client, aligned, rise, sink, false);
    }

    private boolean recoverFromStuck(Minecraft client) {
        stuckTicks = 0;
        releaseAutomationKeys(client);
        BlockPos feet = client.player.blockPosition();
        BlockPos next = pathIndex < path.size() ? path.get(pathIndex) : null;

        BlockPos[] blockers = next == null
                ? new BlockPos[]{feet.above()}
                : new BlockPos[]{feet.above(), next.above(), next};
        for (BlockPos blocker : blockers) {
            if (isSolid(client.level, blocker) && !isLava(client.level, blocker)) {
                return attemptClearance(client, blocker);
            }
        }
        if (next != null && next.getY() > feet.getY()
                && targetPosition != null && targetPosition.getY() > feet.getY()
                && placeSupport(client)) {
            return true;
        }
        clearancePosition = null;
        clearanceTicks = 0;
        if (next != null) {
            routeAvoidNodes.add(next.immutable());
            if (routeAvoidNodes.size() > 12) {
                routeAvoidNodes.remove(routeAvoidNodes.iterator().next());
            }
        }
        path = List.of();
        pathIndex = 0;
        return false;
    }

    private void mineTarget(Minecraft client, BlockPos target) {
        BlockPos overhead = client.player.blockPosition().above();
        if (isSolid(client.level, overhead) && !isLava(client.level, overhead)) {
            attemptClearance(client, overhead);
            return;
        }
        if (canReachTarget(client, target)) {
            placementPosition = null;
            placementTicks = 0;
            mineBlock(client, target, true);
            return;
        }
        if (placeSupport(client)) return;
        mineBlock(client, target, true);
    }

    private boolean canReachTarget(Minecraft client, BlockPos target) {
        return client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= 20.25D;
    }

    private boolean placeSupport(Minecraft client) {
        BlockPos feet = client.player.blockPosition();
        BlockPos placePosition = feet;
        BlockPos support = feet.below();

        if (placementPosition != null && isSolid(client.level, placementPosition)) {
            placementTicks++;
            setMovementKeys(client, false, true);
            if (placementTicks <= 40) return true;
            placementPosition = null;
            placementTicks = 0;
            releaseAutomationKeys(client);
            return false;
        }
        if (!client.level.getBlockState(placePosition).getCollisionShape(client.level, placePosition).isEmpty()) {
            return false;
        }
        if (!isSolid(client.level, support) || isLava(client.level, support)) {
            return false;
        }
        int slot = findPlaceableBlockSlot(client);
        if (slot < 0) return false;

        client.gameMode.stopDestroyBlock();
        breakingPosition = null;
        releaseAutomationKeys(client);
        client.player.getInventory().setSelectedSlot(slot);
        float yawError = face(client, Vec3.atCenterOf(support).add(0.0D, 0.5D, 0.0D));
        if (Math.abs(yawError) > 24.0F) return true;

        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(support).add(0.0D, 0.5D, 0.0D),
                Direction.UP,
                support,
                false
        );
        InteractionResult result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            placementPosition = placePosition.immutable();
            placementTicks = 0;
            setMovementKeys(client, false, true);
        }
        return true;
    }

    private int findPlaceableBlockSlot(Minecraft client) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof BlockItem blockItem) || stack.isEmpty()) continue;
            BlockState state = blockItem.getBlock().defaultBlockState();
            if (!state.isAir() && !state.liquid() && !state.getCollisionShape(client.level, BlockPos.ZERO).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private BlockPos findClearanceBlock(Minecraft client, BlockPos current, BlockPos next) {
        BlockPos overhead = current.above();
        if (isSolid(client.level, overhead) && !isLava(client.level, overhead)) {
            return overhead;
        }
        BlockPos nextHead = next.above();
        if (isSolid(client.level, nextHead) && !isLava(client.level, nextHead)) {
            return nextHead;
        }
        if (isSolid(client.level, next) && !isLava(client.level, next)) {
            return next;
        }
        return null;
    }

    private boolean pathNodeIsClear(Minecraft client, BlockPos node) {
        return !isGravel(client.level, node)
                && !isGravel(client.level, node.above())
                && !isSolid(client.level, node)
                && !isSolid(client.level, node.above());
    }

    private boolean hasGravelHazard(Minecraft client, BlockPos current, BlockPos next) {
        return isGravel(client.level, current.above())
                || isGravel(client.level, next)
                || isGravel(client.level, next.above());
    }

    private boolean attemptClearance(Minecraft client, BlockPos blocker) {
        if (!blocker.equals(clearancePosition)) {
            clearancePosition = blocker.immutable();
            clearanceTicks = 0;
            breakingPosition = null;
            aimLockPosition = null;
            client.gameMode.stopDestroyBlock();
        }
        clearanceTicks++;
        setMovementKeys(client, false, false);
        mineBlock(client, blocker);

        if (!isSolid(client.level, blocker)) {
            clearancePosition = null;
            clearanceTicks = 0;
            return true;
        }
        if (clearanceTicks <= 40) return true;
        routeAvoidNodes.add(blocker.immutable());
        if (routeAvoidNodes.size() > 12) {
            routeAvoidNodes.remove(routeAvoidNodes.iterator().next());
        }
        client.gameMode.stopDestroyBlock();
        breakingPosition = null;
        aimLockPosition = null;
        clearancePosition = null;
        clearanceTicks = 0;
        path = List.of();
        pathIndex = 0;
        return false;
    }

    private void mineBlock(Minecraft client, BlockPos pos) {
        mineBlock(client, pos, false);
    }

    private void mineBlock(Minecraft client, BlockPos pos, boolean requireEyeRay) {
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir()) {
            breakingPosition = null;
            aimLockPosition = null;
            client.gameMode.stopDestroyBlock();
            return;
        }
        if (isLava(client.level, pos)) {
            stop(client);
            return;
        }
        if (isGravel(client.level, pos)) {
            client.gameMode.stopDestroyBlock();
            breakingPosition = null;
            aimLockPosition = null;
            return;
        }
        selectBestTool(client, state);
        boolean keepAim = pos.equals(breakingPosition) && pos.equals(aimLockPosition);
        float yawError = keepAim ? 0.0F : face(client, Vec3.atCenterOf(pos));
        if (Math.abs(yawError) > 24.0F) {
            releaseAutomationKeys(client);
            return;
        }
        BlockHitResult eyeHit = requireEyeRay ? rayTraceFromEyes(client, pos) : null;
        if (requireEyeRay && eyeHit == null) {
            releaseAutomationKeys(client);
            return;
        }
        Direction hitFace = requireEyeRay
                ? eyeHit.getDirection()
                : Direction.getNearest(client.player.blockPosition().subtract(pos), Direction.UP);
        if (!pos.equals(breakingPosition)) {
            if (client.gameMode.startDestroyBlock(pos, hitFace)) {
                breakingPosition = pos.immutable();
                aimLockPosition = pos.immutable();
            } else {
                breakingPosition = null;
                aimLockPosition = null;
                client.gameMode.stopDestroyBlock();
            }
        } else {
            client.gameMode.continueDestroyBlock(pos, hitFace);
        }
        releaseAutomationKeys(client);
    }

    private BlockHitResult rayTraceFromEyes(Minecraft client, BlockPos target) {
        Vec3 from = client.player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);
        Vec3[] samples = {
                center,
                new Vec3(center.x + 0.35D, center.y, center.z),
                new Vec3(center.x - 0.35D, center.y, center.z),
                new Vec3(center.x, center.y + 0.35D, center.z),
                new Vec3(center.x, center.y - 0.35D, center.z),
                new Vec3(center.x, center.y, center.z + 0.35D),
                new Vec3(center.x, center.y, center.z - 0.35D)
        };
        for (Vec3 to : samples) {
            BlockHitResult hit = client.level.clip(new ClipContext(
                    from,
                    to,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    client.player
            ));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) return hit;
        }
        return null;
    }

    private void selectBestTool(Minecraft client, BlockState state) {
        int currentSlot = client.player.getInventory().getSelectedSlot();
        int bestSlot = currentSlot;
        float bestSpeed = client.player.getInventory().getItem(currentSlot).getDestroySpeed(state);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            float speed = stack.getDestroySpeed(state);
            if (stack.isCorrectToolForDrops(state) && speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        if (bestSlot != currentSlot) {
            client.player.getInventory().setSelectedSlot(bestSlot);
        }
    }

    private float face(Minecraft client, net.minecraft.world.phys.Vec3 point) {
        double dx = point.x - client.player.getX();
        double dy = point.y - client.player.getEyeY();
        double dz = point.z - client.player.getZ();
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float yawError = net.minecraft.util.Mth.wrapDegrees(desiredYaw - client.player.getYRot());
        float yawTargetVelocity = Math.max(-8.5F, Math.min(8.5F, yawError * 0.30F));
        yawVelocity += (yawTargetVelocity - yawVelocity) * 0.28F;
        float yawStep = smoothVelocityStep(yawError, yawVelocity);
        client.player.yRotO = client.player.getYRot();
        client.player.xRotO = client.player.getXRot();
        client.player.yHeadRotO = client.player.getYHeadRot();
        float newYaw = client.player.getYRot() + yawStep;
        client.player.setYRot(newYaw);
        client.player.setYHeadRot(newYaw);
        float pitchError = desiredPitch - client.player.getXRot();
        float pitchTargetVelocity = Math.max(-5.5F, Math.min(5.5F, pitchError * 0.28F));
        pitchVelocity += (pitchTargetVelocity - pitchVelocity) * 0.28F;
        client.player.setXRot(client.player.getXRot() + smoothVelocityStep(pitchError, pitchVelocity));
        return yawError;
    }

    private static float smoothVelocityStep(float error, float velocity) {
        if (Math.abs(error) < 0.75F) return 0.0F;
        if (Math.signum(error) != Math.signum(velocity)) return 0.0F;
        return Math.max(-Math.abs(error), Math.min(Math.abs(error), velocity));
    }

    private void setMovementKeys(Minecraft client, boolean forward, boolean jump) {
        setMovementKeys(client, forward, jump, false, forward);
    }

    private void setMovementKeys(Minecraft client, boolean forward, boolean jump, boolean descend) {
        setMovementKeys(client, forward, jump, descend, forward);
    }

    private void setMovementKeys(Minecraft client, boolean forward, boolean jump, boolean descend, boolean sprint) {
        if (!forward && !jump && !descend) {
            releaseAutomationKeys(client);
            return;
        }
        KeyMapping.set(client.options.keyUp.getDefaultKey(), forward);
        KeyMapping.set(client.options.keyDown.getDefaultKey(), false);
        KeyMapping.set(client.options.keyLeft.getDefaultKey(), false);
        KeyMapping.set(client.options.keyRight.getDefaultKey(), false);
        KeyMapping.set(client.options.keyJump.getDefaultKey(), jump);
        KeyMapping.set(client.options.keyShift.getDefaultKey(), descend);
        KeyMapping.set(client.options.keySprint.getDefaultKey(), sprint);
        automationKeysActive = true;
    }

    private void releaseAutomationKeys(Minecraft client) {
        if (!automationKeysActive) return;
        KeyMapping.set(client.options.keyUp.getDefaultKey(), false);
        KeyMapping.set(client.options.keyDown.getDefaultKey(), false);
        KeyMapping.set(client.options.keyLeft.getDefaultKey(), false);
        KeyMapping.set(client.options.keyRight.getDefaultKey(), false);
        KeyMapping.set(client.options.keyJump.getDefaultKey(), false);
        KeyMapping.set(client.options.keyShift.getDefaultKey(), false);
        KeyMapping.set(client.options.keySprint.getDefaultKey(), false);
        automationKeysActive = false;
    }

    private boolean nextPathNodeIsUnsafe(ClientLevel level) {
        if (pathIndex >= path.size()) return true;
        BlockPos next = path.get(pathIndex);
        return isLava(level, next) || isLava(level, next.above()) || isLava(level, next.below());
    }

    private static boolean isSolid(ClientLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isGravel(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.GRAVEL);
    }

    private void sendOreIndex(Minecraft client, List<OreScanner.OreEntry> ores) {
        if (client.player == null) return;
        List<String> names = ores.stream()
                .map(OreScanner.OreEntry::id)
                .distinct()
                .sorted()
                .limit(16)
                .toList();
        String suffix = names.isEmpty() ? "нет руд" : String.join(", ", names);
        client.player.sendSystemMessage(Component.literal(
                "[Baritone] Чанк просканирован сверху вниз: " + ores.size() + " руд. Имена: " + suffix));
    }

    public void renderDebug() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        BlockPos viewer = client.player.blockPosition();
        BaritoneConfig config = BaritoneConfig.get();

        if (config.oreHighlights() && targetPosition != null) {
            renderBox(targetPosition, 0xFFFFC857, 0x357A4DFF, 2.0F, viewer);
        }
        Set<BlockPos> renderedOrePositions = new HashSet<>();
        List<OreScanner.OreEntry> visibleOres = config.oreHighlights()
                ? discoveredOres.stream()
                .filter(ore -> !ore.position().equals(targetPosition))
                .filter(ore -> withinRenderRange(ore.position(), viewer))
                .sorted(Comparator.comparingDouble(ore -> ore.position().distSqr(viewer)))
                .limit(MAX_RENDER_ORES)
                .toList()
                : List.of();
        for (OreScanner.OreEntry ore : visibleOres) {
            int color = oreColor(ore.id());
            int fill = (color & 0x00FFFFFF) | 0x1C000000;
            renderBox(ore.position(), color, fill, 1.0F, viewer);
            renderedOrePositions.add(ore.position());
        }
        List<BlockPos> visibleTargets = config.oreHighlights()
                ? discoveredTargets.stream()
                .filter(target -> !target.equals(targetPosition))
                .filter(target -> !renderedOrePositions.contains(target))
                .filter(target -> withinRenderRange(target, viewer))
                .sorted(Comparator.comparingDouble(target -> target.distSqr(viewer)))
                .limit(48)
                .toList()
                : List.of();
        for (BlockPos target : visibleTargets) {
            renderBox(target, 0xFFFFB52E, 0x182E9BFF, 1.0F, viewer);
        }
        if (config.pathHighlights()) renderPath(viewer);
    }

    private void renderBox(BlockPos position, int stroke, int fill, float width, BlockPos viewer) {
        if (!withinRenderRange(position, viewer)) return;
        Gizmos.cuboid(position, GizmoStyle.strokeAndFill(stroke, width, fill))
                .setAlwaysOnTop();
    }

    private void renderPath(BlockPos viewer) {
        if (path.isEmpty()) return;
        int start = Math.min(Math.max(pathIndex, 0), path.size() - 1);
        BlockPos previous = start > 0 ? path.get(start - 1) : viewer;
        BlockPos runStart = previous;
        Direction runDirection = null;
        int renderedSegments = 0;

        for (int i = start; i < path.size(); i++) {
            BlockPos node = path.get(i);
            if (!withinRenderRange(node, viewer)) break;
            Direction direction = directionBetween(previous, node);
            if (direction == null) {
                previous = node;
                continue;
            }
            if (runDirection != null && direction != runDirection) {
                drawPathSegment(runStart, previous);
                if (++renderedSegments >= MAX_RENDER_PATH_SEGMENTS) return;
                runStart = previous;
            }
            if (runDirection == null || direction != runDirection) runDirection = direction;
            previous = node;
        }
        if (runDirection != null && renderedSegments < MAX_RENDER_PATH_SEGMENTS) {
            drawPathSegment(runStart, previous);
        }
    }

    private static void drawPathSegment(BlockPos from, BlockPos to) {
        Vec3 start = Vec3.atCenterOf(from).add(0.0D, 0.16D, 0.0D);
        Vec3 end = Vec3.atCenterOf(to).add(0.0D, 0.16D, 0.0D);
        Gizmos.line(start, end, 0xFF62D9FF, 2.2F)
                .setAlwaysOnTop();
    }

    private static Direction directionBetween(BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX(), from.getX());
        int dy = Integer.compare(to.getY(), from.getY());
        int dz = Integer.compare(to.getZ(), from.getZ());
        if (dx != 0) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (dy != 0) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (dz != 0) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        return null;
    }

    private static boolean withinRenderRange(BlockPos position, BlockPos viewer) {
        double dx = position.getX() - viewer.getX();
        double dy = position.getY() - viewer.getY();
        double dz = position.getZ() - viewer.getZ();
        return dx * dx + dy * dy + dz * dz <= DEBUG_RENDER_RADIUS_SQR;
    }

    private static int oreColor(String id) {
        if (id.contains("diamond")) return 0xFF66E8FF;
        if (id.contains("emerald")) return 0xFF55F28A;
        if (id.contains("gold")) return 0xFFFFD34E;
        if (id.contains("redstone")) return 0xFFFF5B63;
        if (id.contains("lapis")) return 0xFF628CFF;
        if (id.contains("copper")) return 0xFFFF9A62;
        if (id.contains("ancient_debris")) return 0xFFFF8D52;
        return 0xFFD4D9E8;
    }

    private static boolean isLava(ClientLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(net.minecraft.world.level.material.Fluids.LAVA)
                || level.getFluidState(pos).is(net.minecraft.world.level.material.Fluids.FLOWING_LAVA);
    }
}
