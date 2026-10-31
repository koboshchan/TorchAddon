package com.kobosh.torchaddon;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import com.kobosh.torchaddon.LightPlanner.*;

class LightPlannerTest
{
    private static final Point O = new Point(0, 0, 0);
    private Spread spread(LightWorld world)
    {
        Spread result = new Spread(O);
        while(!result.advance(world, 384)) {}
        return result;
    }
    private Candidate candidate(int id, Point... covered)
    {
        return new Candidate(new Point(id, 20, 0), Set.of(covered));
    }

    @Test void targetsOnlyZeroBlockLight()
    {
        assertTrue(LightPlanner.needsLighting(true, 0));
        for(int light = 1; light <= 15; light++)
            assertFalse(LightPlanner.needsLighting(true, light));
        assertFalse(LightPlanner.needsLighting(false, 0));
    }

    @Test void normalTorchHasFourteenAtSource()
    {
        assertEquals(14, spread((a,b) -> 1).lightAt(O));
    }

    @Test void positiveLightStopsAtThirteenSteps()
    {
        Spread s = spread((a,b) -> 1);
        assertEquals(1, s.lightAt(new Point(13, 0, 0)));
        assertEquals(0, s.lightAt(new Point(14, 0, 0)));
        assertEquals(0, s.lightAt(new Point(7, 7, 0)));
    }

    @Test void manhattanFalloffWorksBelowZero()
    {
        assertEquals(5, spread((a,b) -> 1).lightAt(new Point(-3, -3, -3)));
    }

    @Test void opaqueWallBlocksEvenNearbyTargets()
    {
        Spread s = spread((a,b) -> b.x() == 1 ? 16 : 1);
        assertEquals(0, s.lightAt(new Point(2, 0, 0)));
        assertEquals(0, s.lightAt(new Point(1, 0, 0)));
    }

    @Test void lightTravelsAroundCornersWithoutStraightLineOfSight()
    {
        Spread s = spread((a,b) -> b.equals(new Point(1, 0, 0)) ? 16 : 1);
        assertEquals(10, s.lightAt(new Point(2, 0, 0))); // four-step detour
    }

    @Test void waterLikeAttenuationConsumesMoreThanOne()
    {
        Spread s = spread((a,b) -> b.x() > 0 ? 3 : 1);
        assertEquals(11, s.lightAt(new Point(1, 0, 0)));
        assertEquals(8, s.lightAt(new Point(2, 0, 0)));
    }

    @Test void unloadedCellsFailClosed()
    {
        Spread s = spread((a,b) -> Math.abs(b.x()) > 1 ? 16 : 1);
        assertEquals(13, s.lightAt(new Point(1, 0, 0)));
        assertEquals(0, s.lightAt(new Point(2, 0, 0)));
    }

    @Test void incrementalAndOneShotSpreadsMatch()
    {
        LightWorld world = (a,b) -> b.x() == 2 && b.z() < 3 ? 16 : 1;
        Spread one = spread(world), tiny = new Spread(O);
        int slices = 0;
        while(!tiny.advance(world, 6)) slices++;
        assertTrue(slices > 100);
        for(int x = -14; x <= 14; x++)
            for(int z = -14; z <= 14; z++)
                assertEquals(one.lightAt(new Point(x, 0, z)), tiny.lightAt(new Point(x, 0, z)));
    }

    @Test void greedyPrefersMaximumNewDarkCoverage()
    {
        Point a = O, b = new Point(1,0,0), c = new Point(2,0,0);
        Result r = LightPlanner.solve(List.of(candidate(0,a), candidate(1,a,b,c)),
            Set.of(a,b,c), () -> false);
        assertEquals(List.of(new Point(1,20,0)), r.positions());
        assertEquals(0, r.uncovered());
    }

    @Test void duplicateSuggestionsAreNotSelected()
    {
        Result r = LightPlanner.solve(List.of(candidate(0,O), candidate(1,O)),
            Set.of(O), () -> false);
        assertEquals(1, r.positions().size());
    }

    @Test void reportsUnreachableOrUnlitTargetsWithoutInventingCoverage()
    {
        Point dark = new Point(30,0,0);
        Result r = LightPlanner.solve(List.of(candidate(0,O)), Set.of(O,dark), () -> false);
        assertEquals(1, r.uncovered());
    }

    @Test void redundancyPruningRemovesEarlierGreedyChoice()
    {
        Point a=O, b=new Point(1,0,0), c=new Point(2,0,0),
            d=new Point(3,0,0), e=new Point(4,0,0), f=new Point(5,0,0);
        Result r = LightPlanner.solve(List.of(candidate(0,a,b,c,d),
            candidate(1,a,b,e), candidate(2,c,d,f)), Set.of(a,b,c,d,e,f), () -> false);
        assertEquals(List.of(new Point(1,20,0),new Point(2,20,0)), r.positions());
        assertEquals(0, r.uncovered());
    }

    @Test void tiesAreDeterministic()
    {
        for(int i=0; i<20; i++)
            assertEquals(List.of(new Point(2,20,0)),
                LightPlanner.solve(List.of(candidate(2,O),candidate(1,O)),
                    Set.of(O), () -> false).positions());
    }

    @Test void workerCanBeCancelled()
    {
        assertThrows(CancellationException.class, () ->
            LightPlanner.solve(List.of(candidate(0,O)), Set.of(O), () -> true));
    }

    @Test void emptySelectionNeedsNoTorch()
    {
        Result r = LightPlanner.solve(List.of(candidate(0,O)), Set.of(), () -> false);
        assertTrue(r.positions().isEmpty());
        assertEquals(0, r.uncovered());
    }

    @Test void coverageAndResultsAreDefensiveSnapshots()
    {
        HashSet<Point> mutable = new HashSet<>(Set.of(O));
        Candidate c = new Candidate(O, mutable);
        mutable.clear();
        assertEquals(Set.of(O), c.coverage());
        assertThrows(UnsupportedOperationException.class, () -> c.coverage().clear());
    }
    @Test void selectionVolumeDoesNotOverflow()
    {
        assertEquals(27, LightPlanner.selectionVolume(O, new Point(2,2,2)));
        assertEquals(27, LightPlanner.selectionVolume(O, new Point(-2,-2,-2)));
        assertEquals(Long.MAX_VALUE, LightPlanner.selectionVolume(
            new Point(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE),
            new Point(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE)));
    }
}
