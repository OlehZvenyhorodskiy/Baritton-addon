/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.process.IFarmProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class FarmProcess extends BaritoneProcessHelper implements IFarmProcess {

    private boolean active;

    private List<BlockPos> locations;
    private int tickCount;
    private int breakCooldown;
    private BlockPos currentBreakTarget;

    /** Per-tick counter used by the right-click throttle. Incremented at the start of onTick(). */
    private int rightClickTickCounter;
    /** Tick value of the most recent right-click action. Used with farmRightClickDelay. */
    private int lastRightClickTick = Integer.MIN_VALUE / 2;

    /** Tick at which the last /feed or /heal command was sent, in units of {@link #rightClickTickCounter}. */
    private int lastFeedHealTick;
    /** True if the next auto-feed/heal command should be {@code /feed}; otherwise {@code /heal}. */
    private boolean nextFeedHealIsFeed = true;

    private int range;
    private BlockPos center;

    private static final List<Item> FARMLAND_PLANTABLE = Arrays.asList(
            Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS,
            Items.WHEAT_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.POTATO,
            Items.CARROT
    );

    private static final List<Item> PICKUP_DROPPED = Arrays.asList(
            Items.BEETROOT_SEEDS,
            Items.BEETROOT,
            Items.MELON_SEEDS,
            Items.MELON_SLICE,
            Blocks.MELON.asItem(),
            Items.WHEAT_SEEDS,
            Items.WHEAT,
            Items.PUMPKIN_SEEDS,
            Blocks.PUMPKIN.asItem(),
            Items.POTATO,
            Items.CARROT,
            Items.NETHER_WART,
            Items.COCOA_BEANS,
            Blocks.SUGAR_CANE.asItem(),
            Blocks.BAMBOO.asItem(),
            Blocks.CACTUS.asItem()
    );

    public FarmProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void farm(int range, BlockPos pos) {
        if (pos == null) {
            center = baritone.getPlayerContext().playerFeet();
        } else {
            center = pos;
        }
        this.range = range;
        active = true;
        locations = null;
        breakCooldown = 0;
        currentBreakTarget = null;
        // reset the auto-feed/heal cycle so a fresh #farm starts at /feed in `interval` ticks
        lastFeedHealTick = rightClickTickCounter;
        nextFeedHealIsFeed = true;
    }

    private enum Harvest {
        WHEAT((CropBlock) Blocks.WHEAT),
        CARROTS((CropBlock) Blocks.CARROTS),
        POTATOES((CropBlock) Blocks.POTATOES),
        BEETROOT((CropBlock) Blocks.BEETROOTS),
        PUMPKIN(Blocks.PUMPKIN, state -> true),
        MELON(Blocks.MELON, state -> true),
        NETHERWART(Blocks.NETHER_WART, state -> state.getValue(NetherWartBlock.AGE) >= 3),
        COCOA(Blocks.COCOA, state -> state.getValue(CocoaBlock.AGE) >= 2),
        SUGARCANE(Blocks.SUGAR_CANE, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof SugarCaneBlock;
                }
                return true;
            }
        },
        BAMBOO(Blocks.BAMBOO, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof BambooStalkBlock;
                }
                return true;
            }
        },
        CACTUS(Blocks.CACTUS, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof CactusBlock;
                }
                return true;
            }
        };
        public final Block block;
        public final Predicate<BlockState> readyToHarvest;

        Harvest(CropBlock blockCrops) {
            this(blockCrops, blockCrops::isMaxAge);
            // max age is 7 for wheat, carrots, and potatoes, but 3 for beetroot
        }

        Harvest(Block block, Predicate<BlockState> readyToHarvest) {
            this.block = block;
            this.readyToHarvest = readyToHarvest;
        }

        public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
            return readyToHarvest.test(state);
        }
    }

    private boolean readyForHarvest(Level world, BlockPos pos, BlockState state) {
        for (Harvest harvest : Harvest.values()) {
            if (harvest.block == state.getBlock()) {
                return harvest.readyToHarvest(world, pos, state);
            }
        }
        return false;
    }

    private boolean isPlantable(ItemStack stack) {
        return FARMLAND_PLANTABLE.contains(stack.getItem());
    }

    private boolean isBoneMeal(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.BONE_MEAL);
    }

    private boolean isNetherWart(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.NETHER_WART);
    }

    private boolean isCocoa(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.COCOA_BEANS);
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        rightClickTickCounter++;
        tickAutoFeedHeal();
        // Farm-specific rescan interval: defaults to 40 ticks (≈ 2 s) so the bot picks up freshly grown crops without
        // waiting for the shared mineGoalUpdateInterval. Set farmScanIntervalTicks=0 to fall back to the mine default.
        int farmInterval = Baritone.settings().farmScanIntervalTicks.value;
        int scanInterval = farmInterval > 0 ? farmInterval : Baritone.settings().mineGoalUpdateInterval.value;
        if (scanInterval != 0 && tickCount++ % scanInterval == 0) {
            ArrayList<Block> scan = new ArrayList<>();
            for (Harvest harvest : Harvest.values()) {
                scan.add(harvest.block);
            }
            if (Baritone.settings().replantCrops.value) {
                scan.add(Blocks.FARMLAND);
                scan.add(Blocks.JUNGLE_LOG);
                if (Baritone.settings().replantNetherWart.value) {
                    scan.add(Blocks.SOUL_SAND);
                }
            }

            int blockRadius = Baritone.settings().farmScanBlockRadius.value;
            int chunkRadius = blockRadius > 0
                    ? Math.max(1, (blockRadius + 15) / 16) // ceil(blockRadius / 16)
                    : Math.max(1, Baritone.settings().farmScanChunkRadius.value);
            Baritone.getExecutor().execute(() -> locations = BaritoneAPI.getProvider().getWorldScanner()
                    .scanChunkRadius(ctx, scan, Baritone.settings().farmMaxScanSize.value, chunkRadius, chunkRadius));
        }
        if (locations == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        updateBreakCooldown();
        boolean waitingForBreakCooldown = breakCooldown > 0;
        if (breakCooldown > 0) {
            breakCooldown--;
        }
        List<BlockPos> toBreak = new ArrayList<>();
        List<BlockPos> openFarmland = new ArrayList<>();
        List<BlockPos> bonemealable = new ArrayList<>();
        List<BlockPos> openSoulsand = new ArrayList<>();
        List<BlockPos> openLog = new ArrayList<>();
        for (BlockPos pos : locations) {
            //check if the target block is out of range.
            if (range != 0 && pos.distSqr(center) > range * range) {
                continue;
            }

            BlockState state = ctx.world().getBlockState(pos);
            boolean airAbove = ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock;
            if (state.getBlock() == Blocks.FARMLAND) {
                if (airAbove) {
                    openFarmland.add(pos);
                }
                continue;
            }
            if (state.getBlock() == Blocks.SOUL_SAND) {
                if (airAbove) {
                    openSoulsand.add(pos);
                }
                continue;
            }
            if (state.getBlock() == Blocks.JUNGLE_LOG) {
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (ctx.world().getBlockState(pos.relative(direction)).getBlock() instanceof AirBlock) {
                        openLog.add(pos);
                        break;
                    }
                }
                continue;
            }
            if (readyForHarvest(ctx.world(), pos, state)) {
                toBreak.add(pos);
                continue;
            }
            if (state.getBlock() instanceof BonemealableBlock) {
                BonemealableBlock ig = (BonemealableBlock) state.getBlock();
                if (ig.isValidBonemealTarget(ctx.world(), pos, state) && ig.isBonemealSuccess(ctx.world(), ctx.world().random, pos, state)) {
                    bonemealable.add(pos);
                }
            }
        }

        baritone.getInputOverrideHandler().clearAllKeys();
        BetterBlockPos playerPos = ctx.playerFeet();
        double blockReachDistance = ctx.playerController().getBlockReachDistance();
        for (BlockPos pos : toBreak) {
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent() && isSafeToCancel) {
                updateFarmLookTarget(rot.get());
                MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                if (!waitingForBreakCooldown && ctx.isLookingAt(pos)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    currentBreakTarget = pos;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        ArrayList<BlockPos> both = new ArrayList<>(openFarmland);
        both.addAll(openSoulsand);
        for (BlockPos pos : both) {
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            boolean soulsand = openSoulsand.contains(pos);
            Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), blockReachDistance, false);
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, soulsand ? this::isNetherWart : this::isPlantable)) {
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), blockReachDistance);
                if (result instanceof BlockHitResult && ((BlockHitResult) result).getDirection() == Direction.UP) {
                    updateFarmLookTarget(rot.get());
                    if (isLookingAt(pos, Direction.UP) && canRightClick()) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        markRightClick();
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }
        for (BlockPos pos : openLog) {
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (!(ctx.world().getBlockState(pos.relative(dir)).getBlock() instanceof AirBlock)) {
                    continue;
                }
                Vec3 faceCenter = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(dir.getUnitVec3i()).scale(0.5));
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, faceCenter, blockReachDistance, false);
                if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isCocoa)) {
                    HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), blockReachDistance);
                    if (result instanceof BlockHitResult && ((BlockHitResult) result).getDirection() == dir) {
                        updateFarmLookTarget(rot.get());
                        if (isLookingAt(pos, dir) && canRightClick()) {
                            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                            markRightClick();
                        }
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                }
            }
        }
        for (BlockPos pos : bonemealable) {
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isBoneMeal)) {
                updateFarmLookTarget(rot.get());
                if (ctx.isLookingAt(pos) && canRightClick()) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    markRightClick();
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (calcFailed) {
            logDirect("Farm failed");
            if (Baritone.settings().notificationOnFarmFail.value) {
                logNotification("Farm failed", true);
            }
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        List<Goal> goalz = new ArrayList<>();
        for (BlockPos pos : toBreak) {
            goalz.add(new BuilderProcess.GoalBreak(pos));
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isPlantable)) {
            for (BlockPos pos : openFarmland) {
                goalz.add(new GoalBlock(pos.above()));
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isNetherWart)) {
            for (BlockPos pos : openSoulsand) {
                goalz.add(new GoalBlock(pos.above()));
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isCocoa)) {
            for (BlockPos pos : openLog) {
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (ctx.world().getBlockState(pos.relative(direction)).getBlock() instanceof AirBlock) {
                        goalz.add(new GoalGetToBlock(pos.relative(direction)));
                    }
                }
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isBoneMeal)) {
            for (BlockPos pos : bonemealable) {
                goalz.add(new GoalBlock(pos));
            }
        }
        for (Entity entity : ctx.entities()) {
            if (entity instanceof ItemEntity && entity.onGround()) {
                ItemEntity ei = (ItemEntity) entity;
                if (PICKUP_DROPPED.contains(ei.getItem().getItem())) {
                    // +0.1 because of farmland's 0.9375 dummy height lol
                    goalz.add(new GoalBlock(new BetterBlockPos(entity.position().x, entity.position().y + 0.1, entity.position().z)));
                }
            }
        }
        if (goalz.isEmpty()) {
            logDirect("Farm failed");
            if (Baritone.settings().notificationOnFarmFail.value) {
                logNotification("Farm failed", true);
            }
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalComposite(goalz.toArray(new Goal[0])), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private boolean canRightClick() {
        int delay = Math.max(0, Baritone.settings().farmRightClickDelay.value);
        return rightClickTickCounter - lastRightClickTick >= delay;
    }

    private void markRightClick() {
        lastRightClickTick = rightClickTickCounter;
    }

    /**
     * Sends {@code /feed} and {@code /heal} alternately while the bot is farming. Cadence is controlled by
     * {@link baritone.api.Settings#farmAutoFeedHealIntervalTicks}; default is 3 minutes (3600 ticks). The first
     * command goes out at {@code start + interval} (i.e. 3 minutes after #farm starts), then alternates.
     */
    private void tickAutoFeedHeal() {
        if (!Baritone.settings().farmAutoFeedHealEnabled.value) {
            return;
        }
        int interval = Baritone.settings().farmAutoFeedHealIntervalTicks.value;
        if (interval <= 0) {
            return;
        }
        if (rightClickTickCounter - lastFeedHealTick < interval) {
            return;
        }
        LocalPlayer player = ctx.player();
        if (player == null || player.connection == null) {
            return;
        }
        String cmd = nextFeedHealIsFeed ? "feed" : "heal";
        player.connection.sendCommand(cmd);
        logDirect("AutoFeedHeal: /" + cmd);
        nextFeedHealIsFeed = !nextFeedHealIsFeed;
        lastFeedHealTick = rightClickTickCounter;
    }

    private void updateBreakCooldown() {
        if (currentBreakTarget == null) {
            return;
        }

        BlockState state = ctx.world().getBlockState(currentBreakTarget);
        if (readyForHarvest(ctx.world(), currentBreakTarget, state)) {
            return;
        }

        breakCooldown = Math.max(breakCooldown, Math.max(0, Baritone.settings().farmBreakDelay.value));
        currentBreakTarget = null;
    }

    private void updateFarmLookTarget(Rotation target) {
        baritone.getLookBehavior().updateTarget(smoothFarmRotation(target), true, Baritone.settings().farmForceClientLook.value);
    }

    private Rotation smoothFarmRotation(Rotation target) {
        float maxYawChange = Baritone.settings().farmMaxYawChange.value;
        float maxPitchChange = Baritone.settings().farmMaxPitchChange.value;
        if (maxYawChange < 1.0F && maxPitchChange < 1.0F) {
            return target;
        }

        Rotation current = ctx.playerRotations();
        float yawDelta = Rotation.normalizeYaw(target.getYaw() - current.getYaw());
        float pitchDelta = target.getPitch() - current.getPitch();
        float yawStep = maxYawChange < 1.0F ? yawDelta : clamp(yawDelta, -maxYawChange, maxYawChange);
        float pitchStep = maxPitchChange < 1.0F ? pitchDelta : clamp(pitchDelta, -maxPitchChange, maxPitchChange);
        return new Rotation(current.getYaw() + yawStep, current.getPitch() + pitchStep).normalizeAndClamp();
    }

    private boolean isLookingAt(BlockPos pos, Direction direction) {
        HitResult result = ctx.objectMouseOver();
        return result instanceof BlockHitResult
                && ((BlockHitResult) result).getBlockPos().equals(pos)
                && ((BlockHitResult) result).getDirection() == direction;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onLostControl() {
        active = false;
        breakCooldown = 0;
        currentBreakTarget = null;
    }

    @Override
    public String displayName0() {
        return "Farming";
    }
}