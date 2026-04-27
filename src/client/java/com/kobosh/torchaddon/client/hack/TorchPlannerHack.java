package com.kobosh.torchaddon.client.hack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.CommonColors;
import net.minecraft.world.entity.EntityType;
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
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"torch", "mob spawn", "lighting", "overlay"})
public final class TorchPlannerHack extends Hack
    implements UpdateListener, RenderListener, GUIRenderListener
{
    private static final int TORCH_LIGHT_RADIUS = 13;
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
    private ArrayList<CandidateCoverage> calcCandidates = new ArrayList<>();
    private HashSet<Long> calcUncovered = new HashSet<>();
    private int calcTotalSpawnable;
    private int calcCoveredSpawnable;
    private int calcCandidateIndex;
    private CandidateCoverage calcCurrentCandidate;
    private int calcCurrentSpotIndex;
    private int calcCurrentCoverage;
    private CandidateCoverage calcBestCandidate;
    private int calcBestCoverage;
    private long calcRoundChecksDone;
    private long calcRoundChecksTotal;
    private CompletableFuture<SolveResult> solveFuture;
    private final AtomicInteger asyncPercent = new AtomicInteger(0);
    private final Map<Long, HashSet<Long>> coverageTrueCache = new HashMap<>();
    private final Map<Long, HashSet<Long>> coverageFalseCache =
        new HashMap<>();

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
    public void onRenderGUI(GuiGraphics context, float partialTicks)
    {
        if(step == Step.DONE)
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
        context.drawString(tr, message, msgX1 + 2, msgY1 + 1,
            CommonColors.WHITE, false);
    }

    private void handlePositionSelection()
    {
        if(step.pos != null
            && InputConstants.isKeyDown(MC.getWindow(), GLFW.GLFW_KEY_ENTER))
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

    private void calculateSuggestions()
    {
        if(calcPhase == CalcPhase.IDLE)
        {
            selection = new Selection(Step.START_POS.pos, Step.END_POS.pos);
            Step.START_POS.pos = null;
            Step.END_POS.pos = null;

            if(selection.volume() > MAX_SELECTION_VOLUME)
            {
                ChatUtils.error("Selection is too large. Please select up to "
                    + MAX_SELECTION_VOLUME + " blocks.");
                setEnabled(false);
                return;
            }

            List<BlockPos> spawnableSpots = collectSpawnableSpots(selection);
            if(spawnableSpots.isEmpty())
            {
                suggestedTorches.clear();
                uncoveredSpawnableCount = 0;
                step = Step.DONE;
                ChatUtils.message(
                    "TorchPlanner: No hostile spawn spots found in selection.");
                return;
            }

            Map<Long, Integer> spawnBuckets = buildSpawnBuckets(spawnableSpots);
            ArrayList<BlockPos> candidates =
                collectTorchCandidates(selection, spawnBuckets);
            if(candidates.isEmpty())
            {
                suggestedTorches.clear();
                uncoveredSpawnableCount = spawnableSpots.size();
                step = Step.DONE;
                ChatUtils.error(
                    "TorchPlanner: No valid torch placement blocks found in selection.");
                return;
            }

            suggestedTorches.clear();
            calcCandidates = buildCandidateCoverages(candidates, spawnableSpots);
            if(calcCandidates.isEmpty())
            {
                uncoveredSpawnableCount = spawnableSpots.size();
                step = Step.DONE;
                ChatUtils.error(
                    "TorchPlanner: No candidate can cover spawn spots in this selection.");
                return;
            }

            calcUncovered.clear();
            for(BlockPos spot : spawnableSpots)
                calcUncovered.add(spot.asLong());

            coverageTrueCache.clear();
            coverageFalseCache.clear();
            calcTotalSpawnable = spawnableSpots.size();
            calcCoveredSpawnable = 0;

            ArrayList<CandidateCoverage> workerCandidates =
                new ArrayList<>(calcCandidates);
            HashSet<Long> workerUncovered = new HashSet<>(calcUncovered);
            asyncPercent.set(0);
            solveFuture = CompletableFuture.supplyAsync(() ->
                solveGreedyWorker(workerCandidates, workerUncovered,
                    calcTotalSpawnable, asyncPercent));
            calcPhase = CalcPhase.ASYNC_RUNNING;
            return;
        }
    }

    private void pollAsyncSolve()
    {
        if(solveFuture == null || !solveFuture.isDone())
            return;

        SolveResult result;
        try
        {
            result = solveFuture.join();

        }catch(RuntimeException e)
        {
            ChatUtils.error("TorchPlanner: Solve failed.");
            resetCalculationState();
            setEnabled(false);
            return;
        }

        suggestedTorches.clear();
        suggestedTorches.addAll(result.positions());
        uncoveredSpawnableCount = result.uncoveredCount();
        calcCoveredSpawnable = calcTotalSpawnable - uncoveredSpawnableCount;
        step = Step.DONE;
        ChatUtils.message("TorchPlanner: Suggested " + suggestedTorches.size()
            + " torch positions for " + calcTotalSpawnable + " spawnable spots."
            + (uncoveredSpawnableCount > 0
                ? " " + uncoveredSpawnableCount + " spots remain uncovered."
                : ""));
        resetCalculationState();
    }

    private static SolveResult solveGreedyWorker(
        ArrayList<CandidateCoverage> candidates, HashSet<Long> uncovered,
        int totalSpawnable, AtomicInteger progress)
    {
        ArrayList<BlockPos> chosen = new ArrayList<>();

        while(!uncovered.isEmpty() && !candidates.isEmpty())
        {
            CandidateScore best = candidates.parallelStream()
                .map(c -> new CandidateScore(c, countCoverage(c, uncovered)))
                .max(Comparator.comparingInt(CandidateScore::coverage)).orElse(null);

            if(best == null || best.coverage() <= 0)
                break;

            CandidateCoverage bestCandidate = best.candidate();
            chosen.add(bestCandidate.pos());
            candidates.remove(bestCandidate);

            for(long spot : bestCandidate.spotLongs())
                uncovered.remove(spot);

            if(totalSpawnable > 0)
            {
                int covered = totalSpawnable - uncovered.size();
                int percent = (int)Math.floor((double)covered * 100.0 / totalSpawnable);
                progress.set(Math.min(99, Math.max(0, percent)));
            }
        }

        progress.set(100);
        return new SolveResult(chosen, uncovered.size());
    }

    private static int countCoverage(CandidateCoverage candidate,
        HashSet<Long> uncovered)
    {
        int count = 0;
        for(long spot : candidate.spotLongs())
            if(uncovered.contains(spot))
                count++;
        return count;
    }

    private List<BlockPos> collectSpawnableSpots(Selection selected)
    {
        ArrayList<BlockPos> spawnableSpots = new ArrayList<>();

        for(BlockPos pos : BlockUtils.getAllInBox(selected.min(), selected.max()))
        {
            if(!SpawnPlacements.isSpawnPositionOk(EntityType.CREEPER, MC.level, pos))
                continue;

            spawnableSpots.add(pos.immutable());
        }

        return spawnableSpots;
    }

    private ArrayList<BlockPos> collectTorchCandidates(Selection selected,
        Map<Long, Integer> spawnBuckets)
    {
        ArrayList<BlockPos> candidates = new ArrayList<>();

        for(BlockPos pos : BlockUtils.getAllInBox(selected.min(), selected.max()))
        {
            BlockState state = MC.level.getBlockState(pos);
            if(!state.isAir() || !MC.level.getFluidState(pos).isEmpty())
                continue;

            BlockPos below = pos.below();
            BlockState belowState = MC.level.getBlockState(below);
            boolean hasFloorSupport =
                belowState.isFaceSturdy(MC.level, below, Direction.UP);
            boolean hasWallSupport = false;

            for(Direction direction : Direction.Plane.HORIZONTAL)
            {
                BlockPos sidePos = pos.relative(direction);
                BlockState sideState = MC.level.getBlockState(sidePos);
                if(!sideState.isFaceSturdy(MC.level, sidePos,
                    direction.getOpposite()))
                    continue;

                hasWallSupport = true;
                break;
            }

            if(!hasFloorSupport && !hasWallSupport)
                continue;

            if(!hasNearbySpawnBucket(pos, spawnBuckets))
                continue;

            candidates.add(pos.immutable());
        }

        return candidates;
    }

    private Map<Long, Integer> buildSpawnBuckets(List<BlockPos> spawnableSpots)
    {
        HashMap<Long, Integer> buckets = new HashMap<>();

        for(BlockPos spot : spawnableSpots)
        {
            long key = bucketKey(spot.getX(), spot.getY(), spot.getZ());
            buckets.merge(key, 1, Integer::sum);
        }

        return buckets;
    }

    private Map<Long, ArrayList<BlockPos>> buildSpawnSpotBuckets(
        List<BlockPos> spawnableSpots)
    {
        HashMap<Long, ArrayList<BlockPos>> buckets = new HashMap<>();

        for(BlockPos spot : spawnableSpots)
        {
            long key = bucketKey(spot.getX(), spot.getY(), spot.getZ());
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(spot);
        }

        return buckets;
    }

    private ArrayList<CandidateCoverage> buildCandidateCoverages(
        ArrayList<BlockPos> candidates, List<BlockPos> spawnableSpots)
    {
        Map<Long, ArrayList<BlockPos>> spawnBuckets =
            buildSpawnSpotBuckets(spawnableSpots);
        ArrayList<CandidateCoverage> result = new ArrayList<>(candidates.size());

        for(BlockPos candidate : candidates)
        {
            ArrayList<Long> covered = getCoveredSpawnSpots(candidate, spawnBuckets);
            if(covered.isEmpty())
                continue;

            result.add(new CandidateCoverage(candidate, toLongArray(covered)));
        }

        return result;
    }

    private ArrayList<Long> getCoveredSpawnSpots(BlockPos candidate,
        Map<Long, ArrayList<BlockPos>> spawnBuckets)
    {
        ArrayList<Long> covered = new ArrayList<>();

        int bx = floorDiv(candidate.getX(), 8);
        int by = floorDiv(candidate.getY(), 8);
        int bz = floorDiv(candidate.getZ(), 8);

        for(int y = by - 2; y <= by + 2; y++)
            for(int z = bz - 2; z <= bz + 2; z++)
                for(int x = bx - 2; x <= bx + 2; x++)
                {
                    ArrayList<BlockPos> spots =
                        spawnBuckets.get(bucketKeyFromBuckets(x, y, z));
                    if(spots == null)
                        continue;

                    for(BlockPos spot : spots)
                        if(isCoveredByTorch(spot, candidate))
                            covered.add(spot.asLong());
                }

        return covered;
    }

    private static long[] toLongArray(ArrayList<Long> values)
    {
        long[] arr = new long[values.size()];
        for(int i = 0; i < values.size(); i++)
            arr[i] = values.get(i);
        return arr;
    }

    private boolean hasNearbySpawnBucket(BlockPos pos,
        Map<Long, Integer> spawnBuckets)
    {
        int bx = floorDiv(pos.getX(), 8);
        int by = floorDiv(pos.getY(), 8);
        int bz = floorDiv(pos.getZ(), 8);

        // Radius 13 can reach into buckets up to +/-2 in each axis.
        for(int y = by - 2; y <= by + 2; y++)
            for(int z = bz - 2; z <= bz + 2; z++)
                for(int x = bx - 2; x <= bx + 2; x++)
                    if(spawnBuckets.containsKey(bucketKeyFromBuckets(x, y, z)))
                        return true;

        return false;
    }

    private static long bucketKey(int x, int y, int z)
    {
        return bucketKeyFromBuckets(floorDiv(x, 8), floorDiv(y, 8),
            floorDiv(z, 8));
    }

    private static long bucketKeyFromBuckets(int bx, int by, int bz)
    {
        long lx = ((long)bx) & 0x1FFFFFL;
        long ly = ((long)by) & 0x1FFFFFL;
        long lz = ((long)bz) & 0x1FFFFFL;
        return (lx << 42) | (ly << 21) | lz;
    }

    private static int floorDiv(int value, int div)
    {
        int result = value / div;
        if((value ^ div) < 0 && result * div != value)
            result--;
        return result;
    }

    private void startBestCandidateSearchRound()
    {
        calcCandidateIndex = 0;
        calcCurrentCandidate = null;
        calcCurrentSpotIndex = 0;
        calcCurrentCoverage = 0;
        calcBestCandidate = null;
        calcBestCoverage = 0;
        calcRoundChecksDone = 0;

        long totalChecks = 0;
        for(CandidateCoverage candidate : calcCandidates)
            totalChecks += candidate.spotLongs().length;
        calcRoundChecksTotal = totalChecks;
    }

    private void processBestCandidateSearch()
    {
        int checksLeft = checksPerTick.getValueI();

        while(checksLeft > 0 && calcCandidateIndex < calcCandidates.size())
        {
            if(calcCurrentCandidate == null)
            {
                calcCurrentCandidate = calcCandidates.get(calcCandidateIndex);
                calcCurrentSpotIndex = 0;
                calcCurrentCoverage = 0;

                if(calcCurrentCandidate.spotLongs().length <= calcBestCoverage)
                {
                    calcCandidateIndex++;
                    calcCurrentCandidate = null;
                    continue;
                }
            }

            long[] spots = calcCurrentCandidate.spotLongs();

            while(checksLeft > 0 && calcCurrentSpotIndex < spots.length)
            {
                int remainingSpots = spots.length - calcCurrentSpotIndex;
                if(calcCurrentCoverage + remainingSpots <= calcBestCoverage)
                {
                    // Branch-and-bound: this candidate cannot beat the current
                    // best even if all remaining spots were covered.
                    calcRoundChecksDone += remainingSpots;
                    calcCurrentSpotIndex = spots.length;
                    break;
                }

                if(calcUncovered.contains(spots[calcCurrentSpotIndex]))
                    calcCurrentCoverage++;

                calcCurrentSpotIndex++;
                calcRoundChecksDone++;
                checksLeft--;
            }

            if(calcCurrentSpotIndex >= spots.length)
            {
                if(calcCurrentCoverage > calcBestCoverage)
                {
                    calcBestCoverage = calcCurrentCoverage;
                    calcBestCandidate = calcCurrentCandidate;
                }

                if(calcCurrentCoverage == 0)
                    calcCandidates.remove(calcCandidateIndex);
                else
                    calcCandidateIndex++;

                calcCurrentCandidate = null;
                calcCurrentSpotIndex = 0;
                calcCurrentCoverage = 0;
            }
        }

        if(calcCandidateIndex >= calcCandidates.size())
        {
            if(calcBestCandidate == null || calcBestCoverage == 0)
            {
                finalizeCalculation();
                return;
            }

            calcPhase = CalcPhase.APPLY_BEST;
        }
    }

    private void applyBestCandidate()
    {
        suggestedTorches.add(calcBestCandidate.pos());
        calcCandidates.remove(calcBestCandidate);

        int removedThisRound = 0;
        for(long spotLong : calcBestCandidate.spotLongs())
        {
            if(calcUncovered.remove(spotLong))
                removedThisRound++;
        }

        calcCoveredSpawnable += removedThisRound;

        if(calcUncovered.isEmpty())
        {
            finalizeCalculation();
            return;
        }

        if(calcCandidates.isEmpty())
        {
            finalizeCalculation();
            return;
        }

        startBestCandidateSearchRound();
        calcPhase = CalcPhase.SEARCH_BEST;
    }

    private void finalizeCalculation()
    {
        uncoveredSpawnableCount = calcUncovered.size();
        step = Step.DONE;
        ChatUtils.message("TorchPlanner: Suggested " + suggestedTorches.size()
            + " torch positions for " + calcTotalSpawnable + " spawnable spots."
            + (uncoveredSpawnableCount > 0
                ? " " + uncoveredSpawnableCount + " spots remain uncovered."
                : ""));
        resetCalculationState();
    }

    private void resetCalculationState()
    {
        if(solveFuture != null && !solveFuture.isDone())
            solveFuture.cancel(true);

        calcPhase = CalcPhase.IDLE;
        calcCandidates.clear();
        calcUncovered.clear();
        calcTotalSpawnable = 0;
        calcCoveredSpawnable = 0;
        calcCandidateIndex = 0;
        calcCurrentCandidate = null;
        calcCurrentSpotIndex = 0;
        calcCurrentCoverage = 0;
        calcBestCandidate = null;
        calcBestCoverage = 0;
        calcRoundChecksDone = 0;
        calcRoundChecksTotal = 0;
        solveFuture = null;
        asyncPercent.set(0);
        coverageTrueCache.clear();
        coverageFalseCache.clear();
    }

    private int getCalculationPercent()
    {
        if(calcTotalSpawnable <= 0)
            return 0;

        if(calcPhase == CalcPhase.ASYNC_RUNNING)
            return Math.min(99, Math.max(0, asyncPercent.get()));

        double coveredRatio = (double)calcCoveredSpawnable / calcTotalSpawnable;
        int basePercent = (int)Math.floor(coveredRatio * 100.0);

        if(calcPhase == CalcPhase.SEARCH_BEST && calcRoundChecksTotal > 0)
        {
            double roundRatio =
                Math.min(1.0, (double)calcRoundChecksDone / calcRoundChecksTotal);
            int blended =
                basePercent + (int)Math.floor((100 - basePercent) * roundRatio * 0.25);
            return Math.min(99, Math.max(0, blended));
        }

        if(calcPhase == CalcPhase.APPLY_BEST)
            return Math.min(99, Math.max(0, basePercent));

        return Math.min(100, Math.max(0, basePercent));
    }

    private int getRemainingSuggestionCount()
    {
        int remaining = 0;

        for(BlockPos pos : suggestedTorches)
            if(!isSuggestionCompleted(pos))
                remaining++;

        return remaining;
    }

    private boolean isSuggestionCompleted(BlockPos pos)
    {
        BlockState state = MC.level.getBlockState(pos);
        return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
            || state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
            || state.is(Blocks.REDSTONE_TORCH)
            || state.is(Blocks.REDSTONE_WALL_TORCH);
    }

    private boolean isCoveredByTorch(BlockPos spawnableSpot, BlockPos torchPos)
    {
        int dx = Math.abs(spawnableSpot.getX() - torchPos.getX());
        int dy = Math.abs(spawnableSpot.getY() - torchPos.getY());
        int dz = Math.abs(spawnableSpot.getZ() - torchPos.getZ());
        if(dx + dy + dz > TORCH_LIGHT_RADIUS)
            return false;

        long torchPosLong = torchPos.asLong();
        long spawnPosLong = spawnableSpot.asLong();
        HashSet<Long> coveredSpots = coverageTrueCache.get(torchPosLong);
        if(coveredSpots != null && coveredSpots.contains(spawnPosLong))
            return true;

        HashSet<Long> blockedSpots = coverageFalseCache.get(torchPosLong);
        if(blockedSpots != null && blockedSpots.contains(spawnPosLong))
            return false;

        // Prevent counting spots that are only reachable through walls.
        Vec3 torchCenter = new Vec3(torchPos.getX() + 0.5, torchPos.getY() + 0.6,
            torchPos.getZ() + 0.5);
        Vec3 spawnCenter = new Vec3(spawnableSpot.getX() + 0.5,
            spawnableSpot.getY() + 1.0, spawnableSpot.getZ() + 0.5);
        boolean covered = BlockUtils.hasLineOfSight(torchCenter, spawnCenter);

        if(covered)
            coverageTrueCache.computeIfAbsent(torchPosLong, k -> new HashSet<>())
                .add(spawnPosLong);
        else
            coverageFalseCache.computeIfAbsent(torchPosLong, k -> new HashSet<>())
                .add(spawnPosLong);

        return covered;
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
        SEARCH_BEST,
        APPLY_BEST,
        ASYNC_RUNNING
    }

    private static record Selection(BlockPos min, BlockPos max, AABB box, int volume)
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
                (Math.abs(pos1.getX() - pos2.getX()) + 1)
                    * (Math.abs(pos1.getY() - pos2.getY()) + 1)
                    * (Math.abs(pos1.getZ() - pos2.getZ()) + 1));
        }
    }

    private static record CandidateCoverage(BlockPos pos, long[] spotLongs)
    {
    }

    private static record CandidateScore(CandidateCoverage candidate,
        int coverage)
    {
    }

    private static record SolveResult(ArrayList<BlockPos> positions,
        int uncoveredCount)
    {
    }
}
