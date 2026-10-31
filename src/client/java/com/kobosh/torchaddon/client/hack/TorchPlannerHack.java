package com.kobosh.torchaddon.client.hack;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kobosh.torchaddon.LightPlanner;
import com.kobosh.torchaddon.LightPlanner.Point;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.lighting.LightEngine;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.CommonColors;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"torch", "mob spawn", "lighting", "overlay"})
public final class TorchPlannerHack extends Hack
    implements UpdateListener, RenderListener, GUIRenderListener
{
    private static final int MAX_SELECTION_VOLUME = 524288;

    private final SliderSetting checksPerTick =
        new SliderSetting("Checks per tick", 300000, 100000, 2500000,
            100000, ValueDisplay.INTEGER.withSuffix(" checks/tick"));

    private final SliderSetting renderLimit =
        new SliderSetting("Render limit", 256, 32, 2048, 32,
            ValueDisplay.INTEGER.withSuffix(" boxes"));

    private Step step;
    private BlockPos posLookingAt;
    private Selection selection;

    private final ArrayList<BlockPos> suggestedTorches = new ArrayList<>();
    private int uncoveredSpawnableCount;

    private CalcPhase calcPhase = CalcPhase.IDLE;
    private Iterator<BlockPos> scan;
    private Selection walkBounds;
    private final Set<Point> targets = new HashSet<>();
    private final Set<BlockPos> walkable = new HashSet<>();
    private final Set<BlockPos> reachable = new HashSet<>();
    private final ArrayDeque<BlockPos> reachQueue = new ArrayDeque<>();
    private final ArrayList<BlockPos> placements = new ArrayList<>();
    private final ArrayList<LightPlanner.Candidate> coverages = new ArrayList<>();
    private int candidateIndex;
    private int targetCount;
    private int coverageEntries;
    private LightPlanner.Spread spread;
    private CompletableFuture<LightPlanner.Result> solveFuture;
    private AtomicBoolean cancelled = new AtomicBoolean();

    public TorchPlannerHack()
    {
        super("TorchPlanner");
        setCategory(Category.RENDER);
        addSetting(checksPerTick);
        addSetting(renderLimit);
    }

    @Override
    public String getRenderName()
    {
        if(step == Step.CALCULATE)
            return getName() + " [" + getCalculationPercent() + "%]";

        if(step == Step.DONE)
            return getName() + " [" + getRemainingSuggestionCount() + "]";

        return getName();
    }

    @Override
    protected void onEnable()
    {
        step = Step.START_POS;
        posLookingAt = null;
        selection = null;
        suggestedTorches.clear();
        uncoveredSpawnableCount = 0;
        resetCalculationState();

        EVENTS.add(UpdateListener.class, this);
        EVENTS.add(RenderListener.class, this);
        EVENTS.add(GUIRenderListener.class, this);
    }

    @Override
    protected void onDisable()
    {
        EVENTS.remove(UpdateListener.class, this);
        EVENTS.remove(RenderListener.class, this);
        EVENTS.remove(GUIRenderListener.class, this);

        for(Step currentStep : Step.values())
            currentStep.pos = null;

        step = null;
        posLookingAt = null;
        selection = null;
        suggestedTorches.clear();
        uncoveredSpawnableCount = 0;
        resetCalculationState();
    }

    @Override
    public void onUpdate()
    {
        if(MC.level == null || MC.player == null)
        {
            setEnabled(false);
            return;
        }
        if(step == null) return;
        if(step.selectPos)
            handlePositionSelection();
        else if(step == Step.CALCULATE)
            calculateSuggestions();

        if(calcPhase == CalcPhase.ASYNC_RUNNING)
            pollAsyncSolve();
    }

    @Override
    public void onRender(PoseStack matrixStack, float partialTicks)
    {
        if(MC.level == null || MC.player == null || step == null) return;
        int black = 0x80000000;
        int hovered = 0x26404040;
        int selected = 0x2600FF00;
        int pendingSuggestionFill = 0x50FFD35A;
        int pendingSuggestionLine = 0xC0FFC941;
        int completedSuggestionFill = 0x5000C85A;
        int completedSuggestionLine = 0xC000FF80;

        if(selection == null && step == Step.END_POS && Step.START_POS.pos != null
            && Step.END_POS.pos != null)
        {
            AABB preview = AABB
                .encapsulatingFullBlocks(Step.START_POS.pos, Step.END_POS.pos)
                .deflate(1 / 16.0);
            RenderUtils.drawOutlinedBox(matrixStack, preview, black, true);
        }

        if(selection != null)
            RenderUtils.drawOutlinedBox(matrixStack, selection.box(), black, true);

        ArrayList<AABB> selectedBoxes = new ArrayList<>();
        for(Step currentStep : Step.SELECT_POSITION_STEPS)
            if(currentStep.pos != null)
                selectedBoxes.add(new AABB(currentStep.pos).deflate(1 / 16.0));

        RenderUtils.drawOutlinedBoxes(matrixStack, selectedBoxes, black, false);
        RenderUtils.drawSolidBoxes(matrixStack, selectedBoxes, selected, false);

        if(posLookingAt != null)
        {
            AABB box = new AABB(posLookingAt).deflate(1 / 16.0);
            RenderUtils.drawOutlinedBox(matrixStack, box, black, false);
            RenderUtils.drawSolidBox(matrixStack, box, hovered, false);
        }

        if(!suggestedTorches.isEmpty())
        {
            int maxRendered = Math.min(renderLimit.getValueI(),
                suggestedTorches.size());

            ArrayList<AABB> pendingBoxes = new ArrayList<>(maxRendered);
            ArrayList<AABB> completedBoxes = new ArrayList<>(maxRendered);
            for(int i = 0; i < maxRendered; i++)
            {
                BlockPos pos = suggestedTorches.get(i);
                AABB box = new AABB(pos).deflate(1 / 16.0);
                if(isSuggestionCompleted(pos))
                    completedBoxes.add(box);
                else
                    pendingBoxes.add(box);
            }

            if(!pendingBoxes.isEmpty())
            {
                RenderUtils.drawSolidBoxes(matrixStack, pendingBoxes,
                    pendingSuggestionFill, false);
                RenderUtils.drawOutlinedBoxes(matrixStack, pendingBoxes,
                    pendingSuggestionLine, false);
            }

            if(!completedBoxes.isEmpty())
            {
                RenderUtils.drawSolidBoxes(matrixStack, completedBoxes,
                    completedSuggestionFill, false);
                RenderUtils.drawOutlinedBoxes(matrixStack, completedBoxes,
                    completedSuggestionLine, false);
            }
        }
    }

    @Override
    public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
    {
        if(step == null || step == Step.DONE)
            return;

        String message;
        if(step.selectPos && step.pos != null)
            message = "Press enter to confirm, or select a different position.";
        else
            message = step.message;

        Font tr = MC.font;
        int msgWidth = tr.width(message);

        int msgX1 = context.guiWidth() / 2 - msgWidth / 2;
        int msgX2 = msgX1 + msgWidth + 2;
        int msgY1 = context.guiHeight() / 2 + 1;
        int msgY2 = msgY1 + 10;

        context.fill(msgX1, msgY1, msgX2, msgY2, 0x80000000);
        context.text(tr, message, msgX1 + 2, msgY1 + 1,
            CommonColors.WHITE, false);
    }

    private void handlePositionSelection()
    {
        if(step.pos != null
            && InputConstants.isKeyDown(InputConstants.KEY_RETURN))
        {
            step = Step.values()[step.ordinal() + 1];

            if(!step.selectPos)
                posLookingAt = null;

            return;
        }

        if(MC.hitResult instanceof BlockHitResult hitResult)
        {
            posLookingAt = hitResult.getBlockPos();

            if(MC.options.keyShift.isDown())
                posLookingAt = posLookingAt.relative(hitResult.getDirection());

        }else
            posLookingAt = null;

        if(posLookingAt != null && MC.options.keyUse.isDown())
            step.pos = posLookingAt;
    }

    private static Point point(BlockPos pos)
    {
        return new Point(pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos block(Point p)
    {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private void calculateSuggestions()
    {
        // Both settings retain their old names/ranges, so existing Wurst config survives.
        // No world reads happen on the asynchronous set-cover worker.
        int budget = checksPerTick.getValueI();
        long deadline = System.nanoTime() + 8_000_000L;
        if(calcPhase == CalcPhase.IDLE)
        {
            selection = new Selection(Step.START_POS.pos, Step.END_POS.pos);
            Step.START_POS.pos = null;
            Step.END_POS.pos = null;
            if(selection.volume() > MAX_SELECTION_VOLUME)
            {
                ChatUtils.error("Selection is too large. Select up to "
                    + MAX_SELECTION_VOLUME + " blocks.");
                setEnabled(false);
                return;
            }
            walkBounds = new Selection(selection.min().offset(-5, -2, -5),
                selection.max().offset(5, 2, 5));
            if(walkBounds.volume() > MAX_SELECTION_VOLUME * 4L)
            {
                ChatUtils.error("Selection is too thin/large to scan safely.");
                setEnabled(false);
                return;
            }
            scan = BlockPos.betweenClosed(walkBounds.min(), walkBounds.max()).iterator();
            calcPhase = CalcPhase.SCAN;
        }

        while(budget > 0 && System.nanoTime() < deadline)
        {
            if(calcPhase == CalcPhase.SCAN)
            {
                if(scan.hasNext())
                {
                    BlockPos pos = scan.next().immutable();
                    budget -= 32;
                    if(!MC.level.isLoaded(pos) || !MC.level.isLoaded(pos.above())
                        || !MC.level.isLoaded(pos.below()))
                        continue;
                    if(isWalkable(pos)) walkable.add(pos);
                    if(!contains(selection, pos)) continue;
                    // Creeper placement checks geometry, not darkness. Use measured BLOCK
                    // light so daylight does not hide nighttime risks and existing lights count.
                    if(LightPlanner.needsLighting(
                        SpawnPlacements.isSpawnPositionOk(EntityTypes.CREEPER, MC.level, pos),
                        MC.level.getBrightness(LightLayer.BLOCK, pos)))
                        targets.add(point(pos));
                    if(MC.level.getBlockState(pos).isAir()
                        && MC.level.getFluidState(pos).isEmpty()
                        && !supportDirections(pos).isEmpty())
                        placements.add(pos);
                    continue;
                }
                targetCount = targets.size();
                if(targets.isEmpty())
                {
                    finish(new LightPlanner.Result(List.of(), 0));
                    return;
                }
                BlockPos start = MC.player.blockPosition();
                if(walkable.contains(start))
                {
                    reachable.add(start);
                    reachQueue.add(start);
                }
                calcPhase = CalcPhase.REACH;
            }
            if(calcPhase == CalcPhase.REACH)
            {
                if(!reachQueue.isEmpty())
                {
                    BlockPos from = reachQueue.removeFirst();
                    budget -= 128;
                    for(Direction dir : Direction.Plane.HORIZONTAL)
                        for(int dy = -1; dy <= 1; dy++)
                        {
                            BlockPos to = from.relative(dir).offset(0, dy, 0);
                            if(!walkable.contains(to) || reachable.contains(to)
                                || !canWalkBetween(from, to)) continue;
                            reachable.add(to);
                            reachQueue.add(to);
                        }
                    continue;
                }
                calcPhase = CalcPhase.COVERAGE;
            }
            if(calcPhase == CalcPhase.COVERAGE)
            {
                if(candidateIndex >= placements.size())
                {
                    List<LightPlanner.Candidate> snapshot = List.copyOf(coverages);
                    Set<Point> targetSnapshot = Set.copyOf(targets);
                    AtomicBoolean cancellation = cancelled;
                    solveFuture = CompletableFuture.supplyAsync(() ->
                        LightPlanner.solve(snapshot, targetSnapshot, cancellation::get));
                    calcPhase = CalcPhase.ASYNC_RUNNING;
                    return;
                }
                BlockPos candidate = placements.get(candidateIndex);
                if(spread == null)
                {
                    budget -= 1000;
                    if(!hasReachablePlacement(candidate))
                    {
                        candidateIndex++;
                        continue;
                    }
                    spread = new LightPlanner.Spread(point(candidate));
                }
                // Bounded work slices keep even wall-heavy selections responsive.
                int slice = Math.min(budget, 384);
                budget -= slice;
                if(!spread.advance(this::lightLoss, slice)) continue;
                Set<Point> covered = new HashSet<>();
                // Flood fill is local (radius 13), avoid scanning every selected target.
                Point center = point(candidate);
                for(int dx = -13; dx <= 13; dx++)
                    for(int dy = -13 + Math.abs(dx); dy <= 13 - Math.abs(dx); dy++)
                    {
                        int remaining = 13 - Math.abs(dx) - Math.abs(dy);
                        for(int dz = -remaining; dz <= remaining; dz++)
                        {
                            Point p = center.offset(dx, dy, dz);
                            if(targets.contains(p) && spread.lightAt(p) > 0)
                                covered.add(p);
                        }
                    }
                coverageEntries += covered.size();
                if(coverageEntries > 4_000_000)
                {
                    ChatUtils.error("TorchPlanner: Coverage map is too large. Select a smaller area.");
                    setEnabled(false);
                    return;
                }
                if(!covered.isEmpty())
                    coverages.add(new LightPlanner.Candidate(center, covered));
                spread = null;
                candidateIndex++;
            }
            if(calcPhase == CalcPhase.ASYNC_RUNNING) return;
        }
    }

    private static boolean contains(Selection box, BlockPos pos)
    {
        return pos.getX() >= box.min().getX() && pos.getX() <= box.max().getX()
            && pos.getY() >= box.min().getY() && pos.getY() <= box.max().getY()
            && pos.getZ() >= box.min().getZ() && pos.getZ() <= box.max().getZ();
    }

    private boolean isWalkable(BlockPos feet)
    {
        BlockPos below = feet.below();
        return MC.level.getFluidState(feet).isEmpty()
            && MC.level.getBlockState(below).isFaceSturdy(MC.level, below, Direction.UP)
            && MC.level.noCollision(MC.player, bodyAt(feet));
    }

    private AABB bodyAt(BlockPos feet)
    {
        double half = MC.player.getBbWidth() / 2.0;
        return new AABB(feet.getX() + 0.5 - half, feet.getY(),
            feet.getZ() + 0.5 - half, feet.getX() + 0.5 + half,
            feet.getY() + MC.player.getBbHeight(), feet.getZ() + 0.5 + half);
    }

    private boolean canWalkBetween(BlockPos from, BlockPos to)
    {
        // Test the raised sweep as well as both landing positions, preventing
        // diagonal/corner clipping and jumps through ceilings. No ladders/flying.
        int top = Math.max(from.getY(), to.getY());
        AABB sweep = bodyAt(new BlockPos(from.getX(), top, from.getZ()))
            .minmax(bodyAt(new BlockPos(to.getX(), top, to.getZ())));
        return MC.level.noCollision(MC.player, sweep);
    }

    private List<Direction> supportDirections(BlockPos pos)
    {
        ArrayList<Direction> result = new ArrayList<>();
        if(Blocks.TORCH.defaultBlockState().canSurvive(MC.level, pos))
            result.add(Direction.DOWN);
        for(Direction facing : Direction.Plane.HORIZONTAL)
            if(Blocks.WALL_TORCH.defaultBlockState()
                .setValue(WallTorchBlock.FACING, facing).canSurvive(MC.level, pos))
                result.add(facing.getOpposite());
        return result;
    }

    private boolean hasReachablePlacement(BlockPos pos)
    {
        // A plan is not limited to the player's current reach, but every position
        // needs a reachable stance and a real visible, in-range supporting face.
        double range = Math.min(6, MC.player.blockInteractionRange());
        int radius = (int)Math.ceil(range);
        List<Direction> supports = supportDirections(pos);
        for(int dx = -radius; dx <= radius; dx++)
            for(int dy = -radius - 2; dy <= radius; dy++)
                for(int dz = -radius; dz <= radius; dz++)
                {
                    BlockPos stance = pos.offset(dx, dy, dz);
                    if(!reachable.contains(stance)) continue;
                    Vec3 eye = Vec3.atBottomCenterOf(stance)
                        .add(0, MC.player.getEyeHeight(), 0);
                    for(Direction support : supports)
                    {
                        BlockPos neighbor = pos.relative(support);
                        Direction face = support.getOpposite();
                        Vec3 hit = Vec3.atCenterOf(neighbor).add(
                            face.getStepX() * 0.4999, face.getStepY() * 0.4999,
                            face.getStepZ() * 0.4999);
                        if(eye.distanceToSqr(hit) > range * range) continue;
                        BlockHitResult ray = MC.level.clip(new ClipContext(eye, hit,
                            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
                        if(ray.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                            && ray.getBlockPos().equals(neighbor)
                            && ray.getDirection() == face)
                            return true;
                    }
                }
        return false;
    }

    private int lightLoss(Point from, Point to)
    {
        BlockPos a = block(from), b = block(to);
        if(!MC.level.isLoaded(a) || !MC.level.isLoaded(b)) return 16;
        BlockState source = MC.level.getBlockState(a);
        BlockState dest = MC.level.getBlockState(b);
        Direction direction = Direction.getNearest(to.x() - from.x(),
            to.y() - from.y(), to.z() - from.z(), Direction.UP);
        // Vanilla's own 26.3 dampening and voxel-face occlusion rules.
        return LightEngine.getLightDampeningInto(source, dest, direction,
            Math.max(1, dest.getLightDampening()));
    }

    private void pollAsyncSolve()
    {
        if(solveFuture == null || !solveFuture.isDone()) return;
        try { finish(solveFuture.join()); }
        catch(RuntimeException e)
        {
            ChatUtils.error("TorchPlanner: Solve failed. Select the area again.");
            setEnabled(false);
        }
    }

    private void finish(LightPlanner.Result result)
    {
        suggestedTorches.clear();
        for(Point p : result.positions()) suggestedTorches.add(block(p));
        uncoveredSpawnableCount = result.uncovered();
        step = Step.DONE;
        ChatUtils.message("TorchPlanner: Suggested " + suggestedTorches.size()
            + " normal torches for " + targetCount + " dark spawnable spots."
            + (uncoveredSpawnableCount > 0 ? " " + uncoveredSpawnableCount
                + " remain uncovered (no reachable placement/light path)." : "")
            + " Recalculate after changing terrain or lighting.");
        resetCalculationState();
    }

    private void resetCalculationState()
    {
        cancelled.set(true);
        if(solveFuture != null) solveFuture.cancel(true);
        cancelled = new AtomicBoolean();
        solveFuture = null;
        calcPhase = CalcPhase.IDLE;
        scan = null;
        walkBounds = null;
        targets.clear();
        walkable.clear();
        reachable.clear();
        reachQueue.clear();
        placements.clear();
        coverages.clear();
        spread = null;
        candidateIndex = 0;
        targetCount = 0;
        coverageEntries = 0;
    }

    private int getCalculationPercent()
    {
        if(calcPhase == CalcPhase.COVERAGE)
            return 20 + (int)(79L * candidateIndex / Math.max(1, placements.size()));
        if(calcPhase == CalcPhase.ASYNC_RUNNING) return 99;
        if(calcPhase == CalcPhase.REACH) return 10;
        return 0;
    }

    private int getRemainingSuggestionCount()
    {
        int remaining = 0;
        for(BlockPos pos : suggestedTorches)
            if(!isSuggestionCompleted(pos)) remaining++;
        return remaining;
    }

    private boolean isSuggestionCompleted(BlockPos pos)
    {
        BlockState state = MC.level.getBlockState(pos);
        // Soul/redstone torches emit less than the normal torch used by the plan.
        return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH);
    }

    private static enum Step
    {
        START_POS("Select first corner.", true),

        END_POS("Select opposite corner.", true),

        CALCULATE("Calculating torch suggestions...", false),

        DONE("Planning complete.", false);

        private static final Step[] SELECT_POSITION_STEPS =
            {START_POS, END_POS};

        private final String message;
        private final boolean selectPos;

        private BlockPos pos;

        private Step(String message, boolean selectPos)
        {
            this.message = message;
            this.selectPos = selectPos;
        }
    }

    private static enum CalcPhase
    {
        IDLE,
        SCAN,
        REACH,
        COVERAGE,
        ASYNC_RUNNING
    }

    private static record Selection(BlockPos min, BlockPos max, AABB box, long volume)
    {
        private Selection(BlockPos pos1, BlockPos pos2)
        {
            this(new BlockPos(Math.min(pos1.getX(), pos2.getX()),
                    Math.min(pos1.getY(), pos2.getY()),
                    Math.min(pos1.getZ(), pos2.getZ())),
                new BlockPos(Math.max(pos1.getX(), pos2.getX()),
                    Math.max(pos1.getY(), pos2.getY()),
                    Math.max(pos1.getZ(), pos2.getZ())),
                AABB.encapsulatingFullBlocks(pos1, pos2).deflate(1 / 16.0),
                LightPlanner.selectionVolume(point(pos1), point(pos2)));
        }
    }

}
