package com.kobosh.torchaddon;

import java.util.*;
import java.util.function.BooleanSupplier;

/** World-independent light propagation and set-cover solver. No client/world access on workers. */
public final class LightPlanner
{
    private LightPlanner() {}

    public static boolean needsLighting(boolean spawnable, int blockLight)
    {
        return spawnable && blockLight == 0;
    }

    public static long selectionVolume(Point a, Point b)
    {
        try
        {
            return Math.multiplyExact(Math.multiplyExact(
                Math.abs((long)a.x() - b.x()) + 1,
                Math.abs((long)a.y() - b.y()) + 1),
                Math.abs((long)a.z() - b.z()) + 1);
        }
        catch(ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    public record Point(int x, int y, int z)
    {
        public Point offset(int dx, int dy, int dz)
        {
            return new Point(x + dx, y + dy, z + dz);
        }
    }

    public static final int[][] DIRECTIONS =
        {{1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}};

    /** Returns attenuation into the destination, or >=16 for an opaque/unloaded edge. */
    public interface LightWorld
    {
        int loss(Point from, Point to);
    }

    /** Incremental positive-light flood fill, including paths around corners. */
    public static final class Spread
    {
        private record Node(Point pos, int light) {}
        private final Map<Point, Integer> levels = new HashMap<>();
        private final ArrayDeque<Node> queue = new ArrayDeque<>();

        public Spread(Point source)
        {
            levels.put(source, 14);
            queue.add(new Node(source, 14));
        }

        public boolean advance(LightWorld world, int maxChecks)
        {
            for(int checks = 0; checks < maxChecks && !queue.isEmpty(); checks += 6)
            {
                Node node = queue.removeFirst();
                if(levels.getOrDefault(node.pos(), 0) != node.light() || node.light() <= 1)
                    continue;
                for(int[] d : DIRECTIONS)
                {
                    Point next = node.pos().offset(d[0], d[1], d[2]);
                    int light = node.light() - Math.max(1, world.loss(node.pos(), next));
                    if(light <= levels.getOrDefault(next, 0))
                        continue;
                    levels.put(next, light);
                    queue.addLast(new Node(next, light));
                }
            }
            return queue.isEmpty();
        }

        public int lightAt(Point pos) { return levels.getOrDefault(pos, 0); }
    }

    public record Candidate(Point pos, Set<Point> coverage)
    {
        public Candidate { coverage = Set.copyOf(coverage); }
    }
    public record Result(List<Point> positions, int uncovered)
    {
        public Result { positions = List.copyOf(positions); }
    }

    /** Stable greedy maximum new coverage, then remove every redundant chosen torch. */
    public static Result solve(List<Candidate> input, Set<Point> targets,
        BooleanSupplier cancelled)
    {
        Set<Point> uncovered = new HashSet<>(targets);
        List<Candidate> candidates = new ArrayList<>(input);
        List<Candidate> chosen = new ArrayList<>();
        while(!uncovered.isEmpty())
        {
            if(cancelled.getAsBoolean())
                throw new java.util.concurrent.CancellationException();
            Candidate best = null;
            int bestScore = 0;
            for(Candidate candidate : candidates)
            {
                if(cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                int score = 0;
                for(Point point : candidate.coverage())
                    if(uncovered.contains(point)) score++;
                if(score > bestScore)
                {
                    best = candidate;
                    bestScore = score;
                }
            }
            if(best == null) break;
            chosen.add(best);
            candidates.remove(best);
            uncovered.removeAll(best.coverage());
        }

        Map<Point, Integer> counts = new HashMap<>();
        for(Candidate candidate : chosen)
            for(Point point : candidate.coverage())
                if(targets.contains(point)) counts.merge(point, 1, Integer::sum);
        for(int i = chosen.size() - 1; i >= 0; i--)
        {
            if(cancelled.getAsBoolean())
                throw new java.util.concurrent.CancellationException();
            Candidate candidate = chosen.get(i);
            boolean redundant = candidate.coverage().stream()
                .filter(targets::contains).allMatch(p -> counts.get(p) > 1);
            if(!redundant) continue;
            chosen.remove(i);
            for(Point point : candidate.coverage())
                if(targets.contains(point)) counts.computeIfPresent(point, (p, n) -> n - 1);
        }
        return new Result(chosen.stream().map(Candidate::pos).toList(), uncovered.size());
    }
}
