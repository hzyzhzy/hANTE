package cn.zbx1425.mtrsteamloco.path;

import cn.zbx1425.mtrsteamloco.util.ModExecutors;
import mtr.path.PathData;
import mtr.data.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public class BetterPathFinder {
    private static final int MAX_AIRPLANE_TURN_ARC = 128;

    public static int findPath(List<PathData> path, Map<BlockPos, Map<BlockPos, Rail>> rails, List<SavedRailBase> savedRailBases, int stopIndexOffset, int cruisingAltitude, boolean useFastSpeed) {
        // pl("Entering findPath (public)");
        path.clear();
        if (savedRailBases.size() < 2) {
            // pl("savedRailBases.size() < 2, returning 0");
            return 0;
        }

        var tasks = (CompletableFuture<List<PathData>>[]) new CompletableFuture[savedRailBases.size() - 1];

        for (int i = 0; i < savedRailBases.size() - 1; i++) {
            // pl("Processing saved rail base pair " + i);
            final SavedRailBase savedRailBaseStart = savedRailBases.get(i);
            final SavedRailBase savedRailBaseEnd = savedRailBases.get(i + 1);

            final Set<BlockPos> runways = new HashSet<>();
            if (savedRailBaseStart.transportMode == TransportMode.AIRPLANE) {
                // pl("TransportMode is AIRPLANE, collecting runways");
                rails.forEach((startPos, railMap) -> {
                    if (railMap.size() == 1 && railMap.values().stream().allMatch(rail -> rail.railType == RailType.RUNWAY)) {
                        runways.add(startPos);
                    }
                });
            }

            final int currentI = i;
            tasks[i] = CompletableFuture.supplyAsync(() -> findPath(rails, runways, savedRailBaseStart, savedRailBaseEnd, currentI + stopIndexOffset, cruisingAltitude, useFastSpeed), ModExecutors.POOL);
        }

        try {
            for (var i = 0; i < savedRailBases.size() - 1; i++) {
                List<PathData> partialPath = tasks[i].get();

                if (partialPath.isEmpty()) {
                    // pl("Partial path is empty, clearing path and returning " + (i + 1));
                    path.clear();
                    return i + 1;
                }

                appendPath(path, partialPath);
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }

        // pl("Successfully processed all saved rail bases, returning " + savedRailBases.size());
        return savedRailBases.size();
    }

    public static void appendPath(List<PathData> path, List<PathData> partialPath) {
        // pl("Entering appendPath");
        if (partialPath.isEmpty()) {
            // pl("partialPath is empty, clearing path");
            path.clear();
        } else {
            // pl("partialPath not empty, checking sameFirstRail");
            final boolean sameFirstRail = !path.isEmpty() && path.get(path.size() - 1).isSameRail(partialPath.get(0));
            for (int j = 0; j < partialPath.size(); j++) {
                if (!(j == 0 && sameFirstRail)) {
                    // pl("Adding element from partialPath at index " + j);
                    path.add(partialPath.get(j));
                } else {
                    // pl("Skipping first element due to sameFirstRail");
                }
            }
        }
    }

    private static List<PathData> findPath(Map<BlockPos, Map<BlockPos, Rail>> rails, Set<BlockPos> runways, SavedRailBase savedRailBaseStart, SavedRailBase savedRailBaseEnd, int stopIndex, int cruisingAltitude, boolean useFastSpeed) {
        List<List<PathData>> options = new ArrayList<>();

        // pl("Entering findPath (private)");
        final BlockPos savedRailBaseEndMidPos = savedRailBaseEnd.getMidPos();
        final Function<Map<BlockPos, Rail>, Comparator<BlockPos>> comparator = newConnections -> (pos1, pos2) -> {
            if (pos1 == pos2) {
                return 0;
            } else {
                final Rail connection1 = newConnections.get(pos1);
                final Rail connection2 = newConnections.get(pos2);

                if (connection1 == null || connection2 == null || connection1.railType.speedLimit == connection2.railType.speedLimit) {
                    return pos1.distSqr(savedRailBaseEndMidPos) > pos2.distSqr(savedRailBaseEndMidPos) ? 1 : -1;
                } else {
                    return connection2.railType.speedLimit - connection1.railType.speedLimit;
                }
            }
        };

        var visited = new HashMap<VisitedSegment, Integer>();

        for (int i = 0; i < 2; i++) {
            // pl("Starting iteration " + i + " of path finding loop");
            final List<PathPart> path = new ArrayList<>();
            final Set<BlockPos> turnBacks = new HashSet<>();
            final List<BlockPos> startPositions = savedRailBaseStart.getOrderedPositions(savedRailBaseEndMidPos, i == 0);
            // pl("startPositions: " + startPositions);
            path.add(new PathPart(null, startPositions.get(0), new ArrayList<>()));
            addPathPart(rails, runways, startPositions.get(1), startPositions.get(0), path, turnBacks, comparator);

            while (path.size() >= 2) {
                // pl("While loop with path size " + path.size());
                final PathPart lastPathPart = path.get(path.size() - 1);
                final var segment = new VisitedSegment(path.get(path.size() - 2).pos, lastPathPart.pos);

                if (visited.getOrDefault(segment, Integer.MAX_VALUE) <= path.size()) {
                    path.remove(lastPathPart);
                    continue;
                }

                if (lastPathPart.otherOptions.isEmpty()) {
                    // pl("Removing lastPathPart as otherOptions is empty");
                    visited.put(segment, path.size());
                    path.remove(lastPathPart);
                } else {
                    // pl("Processing otherOptions for lastPathPart");
                    final BlockPos newPos = lastPathPart.otherOptions.remove(0);
                    addPathPart(rails, runways, newPos, lastPathPart.pos, path, turnBacks, comparator);

                    if (savedRailBaseEnd.containsPos(newPos)) {
                        // pl("Found end position at newPos: " + newPos);
                        final List<PathData> railPath = new ArrayList<>();
                        for (int j = 0; j < path.size() - 1; j++) {
                            // pl("Processing path segment " + j);
                            final PathPart pathPart1 = path.get(j);
                            final PathPart pathPart2 = path.get(j + 1);
                            final BlockPos pos1 = pathPart1.pos;
                            final BlockPos pos2 = pathPart2.pos;
                            final Rail rail = DataCache.tryGet(rails, pos1, pos2);

                            if (rail == null) {
                                // pl("Rail is null, checking runways");
                                if (runways.isEmpty()) {
                                    // pl("Runways is empty, returning empty list");
//                                    return new ArrayList<>();
                                } else {
                                    // pl("Adding airplane dummy path");
                                    final int heightDifference1 = cruisingAltitude - pos1.getY();
                                    final int heightDifference2 = cruisingAltitude - pos2.getY();
                                    final BlockPos cruisingPos1 = RailwayData.offsetBlockPos(pos1, pathPart1.direction.cos * Math.abs(heightDifference1) * 4, heightDifference1, pathPart1.direction.sin * Math.abs(heightDifference1) * 4);
                                    final BlockPos cruisingPos4 = RailwayData.offsetBlockPos(pos2, -pathPart2.direction.cos * Math.abs(heightDifference2) * 4, heightDifference2, -pathPart2.direction.sin * Math.abs(heightDifference2) * 4);
                                    final int turnArc = Math.min(MAX_AIRPLANE_TURN_ARC, cruisingPos1.distManhattan(cruisingPos4) / 8);
                                    final RailType dummyRailType = useFastSpeed ? RailType.AIRPLANE_DUMMY : RailType.RUNWAY;

                                    railPath.add(new PathData(new Rail(pos1, pathPart1.direction, cruisingPos1, pathPart1.direction.getOpposite(), dummyRailType, TransportMode.AIRPLANE), 0, 0, pos1, cruisingPos1, stopIndex));

                                    final RailAngle expectedAngle = RailAngle.fromAngle((float) Math.toDegrees(Math.atan2(cruisingPos4.getZ() - cruisingPos1.getZ(), cruisingPos4.getX() - cruisingPos1.getX())));
                                    final BlockPos cruisingPos2 = addAirplanePath(pathPart1.direction, cruisingPos1, expectedAngle, turnArc, railPath, dummyRailType, stopIndex, false);
                                    final List<PathData> tempRailData = new ArrayList<>();
                                    final BlockPos cruisingPos3 = addAirplanePath(pathPart2.direction.getOpposite(), cruisingPos4, expectedAngle.getOpposite(), turnArc, tempRailData, dummyRailType, stopIndex, true);

                                    railPath.add(new PathData(new Rail(cruisingPos2, expectedAngle, cruisingPos3, expectedAngle.getOpposite(), dummyRailType, TransportMode.AIRPLANE), 0, 0, cruisingPos2, cruisingPos3, stopIndex));
                                    railPath.addAll(tempRailData);

                                    railPath.add(new PathData(new Rail(cruisingPos4, pathPart2.direction, pos2, pathPart2.direction.getOpposite(), dummyRailType, TransportMode.AIRPLANE), 0, 0, cruisingPos4, pos2, stopIndex));
                                }
                            } else {
                                final boolean turningBack = rail.railType == RailType.TURN_BACK && j < path.size() - 2 && path.get(j + 2).pos.equals(pos1);
                                // pl("Adding rail to path, turningBack: " + turningBack);
                                railPath.add(new PathData(rail, j == 0 ? savedRailBaseStart.id : 0, turningBack ? 1 : 0, pos1, pos2, stopIndex));
                            }
                        }

                        final BlockPos endPos = savedRailBaseEnd.getOtherPosition(newPos);
                        // pl("Processing endPos: " + endPos);
                        final Rail rail = DataCache.tryGet(rails, newPos, endPos);
                        if (rail == null) {
                            // pl("End rail is null, returning empty list");
//                            return new ArrayList<>();
                        } else {
                            // pl("Adding end rail with dwell time: " + savedRailBaseEnd.getDwellTime());
                            railPath.add(new PathData(rail, savedRailBaseEnd.id, savedRailBaseEnd instanceof Platform ? savedRailBaseEnd.getDwellTime() : 0, newPos, endPos, stopIndex + 1));
                            options.add(railPath);
                        }
                    }
                }
            }
        }

        // pl("No path found, returning empty list");
        return options.stream()
                .min(Comparator.comparingDouble(path ->
                    path.stream().reduce(0.0, (acc, data) -> acc + (data.rail.getLength() / data.rail.railType.maxBlocksPerTick), Double::sum)
                ))
                .orElseGet(ArrayList::new);
    }

    private static BlockPos addAirplanePath(RailAngle startAngle, BlockPos startPos, RailAngle expectedAngle, int turnArc, List<PathData> tempRailPath, RailType railType, int stopIndex, boolean reverse) {
        // pl("Entering addAirplanePath: reverse=" + reverse);
        final RailAngle angleDifference = expectedAngle.sub(startAngle);
        final boolean turnRight = angleDifference.angleRadians > 0;
        // pl("turnRight: " + turnRight);
        RailAngle tempAngle = startAngle;
        BlockPos tempPos = startPos;

        for (int i = 0; i < RailAngle.values().length; i++) {
            // pl("Processing angle loop " + i);
            if (tempAngle == expectedAngle) {
                // pl("Reached expected angle, breaking loop");
                break;
            }

            final RailAngle oldTempAngle = tempAngle;
            final BlockPos oldTempPos = tempPos;
            final RailAngle rotateAngle = turnRight ? RailAngle.SEE : RailAngle.NEE;
            tempAngle = tempAngle.add(rotateAngle);
            final Vec3 posOffset = new Vec3(turnArc, 0, 0).yRot((float) -oldTempAngle.angleRadians - (float) rotateAngle.angleRadians / 2);
            tempPos = RailwayData.offsetBlockPos(oldTempPos, posOffset.x, posOffset.y, posOffset.z);

            if (reverse) {
                // pl("Adding reverse path data at tempPos: " + tempPos);
                tempRailPath.add(0, new PathData(new Rail(tempPos, tempAngle.getOpposite(), oldTempPos, oldTempAngle, railType, TransportMode.AIRPLANE), 0, 0, tempPos, oldTempPos, stopIndex));
            } else {
                // pl("Adding forward path data from oldTempPos: " + oldTempPos);
                tempRailPath.add(new PathData(new Rail(oldTempPos, oldTempAngle, tempPos, tempAngle.getOpposite(), railType, TransportMode.AIRPLANE), 0, 0, oldTempPos, tempPos, stopIndex));
            }
        }

        // pl("Returning tempPos: " + tempPos);
        return tempPos;
    }

    private static void addPathPart(Map<BlockPos, Map<BlockPos, Rail>> rails, Set<BlockPos> runways,
                                   BlockPos newPos, BlockPos lastPos, List<PathPart> path,
                                   Set<BlockPos> turnBacks, Function<Map<BlockPos, Rail>,
                                   Comparator<BlockPos>> comparator) {
        final Map<BlockPos, Rail> newConnections = rails.get(newPos);
        final Rail oldRail = rails.get(lastPos).get(newPos);

        if (oldRail == null && runways.isEmpty()) return;

        // 获取当前路径部分的访问记录
        PathPart currentPart = path.get(path.size() - 1);

        final RailAngle newDirection = calculateNewDirection(oldRail, newConnections);
        final List<BlockPos> otherOptions = new ArrayList<>();

        if (newConnections != null) {
            final boolean canTurnBack = checkTurnBackCondition(oldRail, turnBacks, newPos);

            newConnections.forEach((connectedPos, rail) -> {
                if (isValidConnection(rail, newDirection, path, newPos, canTurnBack)) {
                    otherOptions.add(connectedPos);
                    if (canTurnBack) {
                        turnBacks.add(newPos);
                    }
                }
            });
        }

        if (!otherOptions.isEmpty()) {
            otherOptions.sort(comparator.apply(newConnections));
            PathPart newPart = new PathPart(newDirection, newPos, otherOptions);
            path.add(newPart);
        }
    }

    private static RailAngle calculateNewDirection(Rail oldRail, Map<BlockPos, Rail> newConnections) {
        return oldRail == null ?
            newConnections.values().stream()
                .map(rail -> rail.facingStart)
                .findFirst()
                .orElse(RailAngle.E) :
            oldRail.facingEnd.getOpposite();
    }

    private static boolean checkTurnBackCondition(Rail oldRail, Set<BlockPos> turnBacks, BlockPos newPos) {
        return oldRail != null &&
               oldRail.railType == RailType.TURN_BACK &&
               !turnBacks.contains(newPos);
    }

    private static boolean isValidConnection(Rail rail, RailAngle newDirection,
                                            List<PathPart> path, BlockPos newPos,
                                            boolean canTurnBack) {
        return rail.railType != RailType.NONE &&
               (canTurnBack || !_equals(rail.facingStart, newDirection.getOpposite())) &&
               path.stream().noneMatch(p -> p.isSame(newPos, newDirection));
    }

    // private static void pl(String s) {
        // System.out.println("[PathFinder] " + s);
    // }

    private static final float ANGLE_EQUALITY_THRESHOLD = 0.01745f * 45; // 1 degree in radians

    private static boolean _equals(RailAngle a, RailAngle b) {
        if (a == null || b == null) return false;

        // 处理角度周期性并计算最小差值
        double normalizedA = (a.angleRadians % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
        double normalizedB = (b.angleRadians % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
        double diff = Math.abs(normalizedA - normalizedB);

        return Math.min(diff, 2 * Math.PI - diff) < ANGLE_EQUALITY_THRESHOLD;
    }

    private static class PathPart {
        private final RailAngle direction;
        private final BlockPos pos;
        private final List<BlockPos> otherOptions;

        private PathPart(RailAngle direction, BlockPos pos, List<BlockPos> otherOptions) {
            this.direction = direction;
            this.pos = pos;
            this.otherOptions = new ArrayList<>(otherOptions);
        }

        private boolean isSame(BlockPos newPos, RailAngle newDirection) {
            return newPos.equals(pos) && _equals(direction, newDirection);
        }
    }

    private static class VisitedSegment {
        private final BlockPos from;
        private final BlockPos to;

        public VisitedSegment(BlockPos from, BlockPos to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            VisitedSegment that = (VisitedSegment) o;
            return Objects.equals(from, that.from) && Objects.equals(to, that.to);
        }

        @Override
        public int hashCode() {
            return Objects.hash(from, to);
        }
    }
}