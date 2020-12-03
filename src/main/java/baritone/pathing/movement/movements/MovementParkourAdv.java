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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.*;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.MobEffects;
import net.minecraft.potion.Potion;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.text.DecimalFormat;
import java.util.*;

/**
 * Allows baritone to make jumps at different angles (diagonally)
 *
 * @author Zephreo
 */
public class MovementParkourAdv extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    private static final EnumMap<EnumFacing, HashMap<Vec3i, JumpType>> ALL_VALID_DIR = new EnumMap<>(EnumFacing.class);
    private static final HashMap<Vec3i, JumpType> SOUTH_VALID = new HashMap<>();
    private static final HashMap<Vec3i, JumpType> WEST_VALID = new HashMap<>();
    private static final HashMap<Vec3i, JumpType> NORTH_VALID = new HashMap<>();
    private static final HashMap<Vec3i, JumpType> EAST_VALID = new HashMap<>();

    private static final HashMap<Vec3i, Double> DISTANCE_CACHE = new HashMap<>();

    private static final double ASCEND_DIST_PER_BLOCK = 0.6;
    private static final double DESCEND_DIST_PER_BLOCK = -0.2; // its easier to descend
    private static final double TURN_COST_PER_RADIAN = 0.3;

    private static final double PREP_OFFSET = 0.2215; // The default prep location for a jump

    private static final double MAX_JUMP_MOMENTUM = 5.3; // We can't make 1bm momentum jumps greater than this distance
    private static final double MAX_JUMP_SPRINT = 4.6; // We can't make flat sprint jumps greater than this distance
    private static final double MAX_JUMP_WALK = 3.48; // We can make the jump without sprinting below this distance

    private static final double LAND_COST = 1; // time taken to land and cancel momentum
    private static final double JUMP_IN_WATER_COST = 5;

    // Calculated using MovementPrediction (add 1.6 ((Player hitbox width + block center) * 2) to get values similar to MAX_JUMP_...), These are flat jumps (12 ticks)
    private static final double WALK_JUMP_DISTANCE = 1.45574 / 12; // (12 ticks) 1.455740609867238, 1 down (14 ticks) = 1.7933772690401462
    private static final double SPRINT_JUMP_DISTANCE = 2.87582 / 12; // (12 ticks) 2.8758288311841866, 1 down (14 ticks) = 3.38866786230939
    // 2.87582 / 12 = 0.23965, 0.23965 * (14 - 12) = 0.4793, 3.38866 - 2.87582 = 0.51284, 0.51284 - 0.4793 = 0.03354 (Accurate enough), Average movement per tick used to extrapolate longer jumps vs Actual distance

    // Not 100% accurate
    private static final double MOMENTUM_JUMP_DISTANCE = (MAX_JUMP_MOMENTUM - 1.6) / 12;

    private static final boolean TEST_LOG = false;
    private static final DecimalFormat df = new DecimalFormat("#.##");

    enum JumpType {
        NORMAL(MAX_JUMP_WALK, MAX_JUMP_SPRINT, 7), // Normal run and jump
        NORMAL_CRAMPED(MAX_JUMP_WALK, MAX_JUMP_SPRINT, 8), // normal jumps with low room for adjustments or side movements
        NORMAL_STRAIGHT_DESCEND(MAX_JUMP_WALK, MAX_JUMP_SPRINT, 7), // A type that will use the normal jump on descends only (Since MovementParkour doesn't do descends)

        EDGE(3, MAX_JUMP_SPRINT, 7), // No run up (for higher angle jumps)
        EDGE_NEO(-1, 4, 7), // Around the pillar

        MOMENTUM(-1, MAX_JUMP_MOMENTUM, 12 + 6), // An extra momentum jump 1bm
        MOMENTUM_BLOCK(-1, MAX_JUMP_MOMENTUM, 12 + 3), // momentum jump with block behind the player
        MOMENTUM_NO_BLOCK(-1, MAX_JUMP_MOMENTUM, 12 + 6); // momentum jump with no block behind the player

        final double prepCost;
        final double maxJumpNoSprint;
        final double maxJumpSprint;

        JumpType(double maxJumpNoSprint, double maxJumpSprint, double prepCost) {
            this.maxJumpNoSprint = maxJumpNoSprint;
            this.maxJumpSprint = maxJumpSprint;
            this.prepCost = prepCost;
        }
    }

    static {
        // The jumps that are valid (forward amount + 1, horizontal amount, the technique to use (defaults to NORMAL))
        int[][] validQuadrant = {{2, 0, JumpType.NORMAL_STRAIGHT_DESCEND.ordinal()}, {3, 0, JumpType.NORMAL_STRAIGHT_DESCEND.ordinal()}, {4, 0, JumpType.NORMAL_STRAIGHT_DESCEND.ordinal()}, {5, 0, JumpType.MOMENTUM.ordinal()},
                {1, 1}, {2, 1, JumpType.NORMAL_CRAMPED.ordinal()}, {3, 1}, {4, 1}, {5, 1, JumpType.MOMENTUM.ordinal()},
                {0, 2, JumpType.EDGE_NEO.ordinal()}, {1, 2, JumpType.EDGE.ordinal()}, {2, 2, JumpType.EDGE.ordinal()}, {3, 2, JumpType.EDGE.ordinal()}, {4, 2, JumpType.MOMENTUM.ordinal()},
                {1, 3, JumpType.EDGE.ordinal()}, {2, 3, JumpType.EDGE.ordinal()}};
        for (int[] jump : validQuadrant) {
            int z = jump[0]; // south is in positive z direction
            for (int neg = -1; neg <= 1; neg += 2) { // -1 and 1
                int x = neg * jump[1];
                Vec3i southVec = new Vec3i(x, 0, z);
                JumpType type = JumpType.NORMAL;
                if (jump.length > 2) {
                    type = JumpType.values()[jump[2]];
                }
                SOUTH_VALID.put(southVec, type);
                WEST_VALID.put(rotateAroundY(southVec, 1), type);
                NORTH_VALID.put(rotateAroundY(southVec, 2), type);
                EAST_VALID.put(rotateAroundY(southVec, 3), type);
            }
        }

        ALL_VALID_DIR.put(EnumFacing.SOUTH, SOUTH_VALID);
        ALL_VALID_DIR.put(EnumFacing.WEST, WEST_VALID);
        ALL_VALID_DIR.put(EnumFacing.NORTH, NORTH_VALID);
        ALL_VALID_DIR.put(EnumFacing.EAST, EAST_VALID);

        for (HashMap<Vec3i, JumpType> posbJumpsForDir : ALL_VALID_DIR.values()) {
            for (Vec3i vec : posbJumpsForDir.keySet()) {
                DISTANCE_CACHE.put(vec, vec.getDistance(0, 0, 0));
            }
        }
    }

    private final double moveDist;
    private final double distanceXZ;
    private final int ascendAmount;
    /**
     * The vector that points from src to destination
     */
    private final Vec3i jump;
    private final EnumFacing jumpDirection;
    private final EnumFacing destDirection;
    private final JumpType type;

    private final float jumpAngle;

    private boolean inStartingPosition = false;
    private int ticksAtDest = 0;
    private int ticksSinceJump = -1;
    private int jumpTime; // initialised in prepare to get potion effects as late as possible

    private MovementParkourAdv(CalculationContext context, BetterBlockPos src, BetterBlockPos dest, EnumFacing jumpDirection, JumpType type) {
        super(context.baritone, src, dest, EMPTY, dest.down());
        this.jump = VecUtils.subtract(dest, src);
        this.moveDist = calcMoveDist(context, src.x, src.y, src.z, jump.getX(), jump.getY(), jump.getZ(), MovementHelper.isBottomSlab(context.get(src.down())) ? 0.5 : 0, jumpDirection);
        this.ascendAmount = dest.y - src.y;
        this.jumpDirection = jumpDirection;
        this.type = type;
        this.distanceXZ = getDistance(new Vec3i(dest.x - src.x, 0, dest.z - src.z), jumpDirection);
        this.jumpAngle = (float) (getSignedAngle(jumpDirection.getXOffset(), jumpDirection.getZOffset(), jump.getX(), jump.getZ()));
        if (jumpAngle < -5) {
            this.destDirection = jumpDirection.rotateYCCW();
        } else if (jumpAngle > 5) {
            this.destDirection = jumpDirection.rotateY();
        } else {
            this.destDirection = jumpDirection;
        }
    }

    /**
     * angle from v1 to v2 (signed, clockwise is positive)
     *
     * @param x1 x of v1
     * @param z1 z of v1
     * @param x2 x of v2
     * @param z2 z of v2
     * @return angle from [-180, 180]
     */
    private static double getSignedAngle(double x1, double z1, double x2, double z2) {
        return Math.atan2(x1 * z2 - z1 * x2, x1 * x2 + z1 * z2) * RotationUtils.RAD_TO_DEG;
    }

    private static double getSignedAngle(Vec3d v1, Vec3d v2) {
        return getSignedAngle(v1.x, v1.z, v2.x, v2.z);
    }

    private static double getDistance(Vec3i vec, EnumFacing offset) {
        return getDistance(new Vec3i(vec.getX() - offset.getXOffset(), vec.getY(), vec.getZ() - offset.getZOffset())) + 1;
    }

    private static double getDistance(Vec3i vec) {
        if (DISTANCE_CACHE.containsKey(vec)) {
            return DISTANCE_CACHE.get(vec);
        } else {
            double distance = vec.getDistance(0, 0, 0);
            DISTANCE_CACHE.put(vec, distance);
            return distance;
        }
    }

    /**
     * Rotates the vector clockwise around the y axis, by a multiple of 90 degrees.
     *
     * @param input the input vector to rotate
     * @param rotations amount of 90 degree rotations to apply
     * @return The rotated vector
     */
    private static Vec3i rotateAroundY(Vec3i input, int rotations) {
        int x;
        int z;
        switch (rotations % 4) {
            case 0:
                return input;
            case 1:
                x = -input.getZ();
                z = input.getX();
                break;
            case 2:
                x = -input.getX();
                z = -input.getZ();
                break;
            case 3:
                x = input.getZ();
                z = -input.getX();
                break;
            default:
                return null;
        }
        return new Vec3i(x, input.getY(), z);
    }

    /**
     * Normalizes an integer vector to a double vector.
     *
     * @param vec The integer vector to normalise
     * @return The normalised double vector
     */
    private static Vec3d normalize(Vec3i vec) {
        double length = getDistance(vec);
        double x = vec.getX() / length;
        double y = vec.getY() / length;
        double z = vec.getZ() / length;
        return new Vec3d(x, y, z);
    }

    /**
     * overlap, accPerBlock, vec
     */
    static final HashMap<Double, HashMap<Double, HashMap<Vec3i, Set<Vec3i>>>> lineApproxCache = new HashMap<>();

    // cache to reduce object allocations?
    private static Set<Vec3i> getLineApprox(Vec3i vec, double overlap, double accPerBlock) {
        HashMap<Double, HashMap<Vec3i, Set<Vec3i>>> lineApproxCache1 = lineApproxCache.get(overlap);
        HashMap<Vec3i, Set<Vec3i>> lineApproxCache2;
        Set<Vec3i> out;
        if (lineApproxCache1 == null) {
            lineApproxCache1 = new HashMap<>();
            lineApproxCache2 = null;
        } else {
            lineApproxCache2 = lineApproxCache1.get(accPerBlock);
        }
        if (lineApproxCache2 == null) {
            lineApproxCache2 = new HashMap<>();
            out = null;
        } else {
            out = lineApproxCache2.get(vec);
        }
        if (!lineApproxCache2.containsKey(vec)) {
            out = approxBlocks(getLine(vec, accPerBlock), overlap);
            lineApproxCache2.put(vec, out);
        }
        if (!lineApproxCache1.containsKey(accPerBlock)) {
            lineApproxCache1.put(accPerBlock, lineApproxCache2);
        }
        if (!lineApproxCache.containsKey(overlap)) {
            lineApproxCache.put(overlap, lineApproxCache1);
        }
        return out;
    }

    public static List<Vec3d> getLine(Vec3i vector, double accPerBlock) {
        double length = getDistance(vector);
        ArrayList<Vec3d> line = new ArrayList<>();
        Vec3d vec = normalize(vector);
        for (double i = 1 / accPerBlock; i < length - (1 / accPerBlock); i += (1 / accPerBlock)) {
            line.add(vec.scale(i).add(0.5, 0.5, 0.5));
        }
        return line;
    }

    /**
     * Checks if each vector is pointing to a location close to the edge of a block. If so also returns the block next to that edge.
     *
     * @param vectors The vectors to approximate
     * @param overlap The size of the edge
     * @return An ordered set with the blocks that the vectors approximately lie in
     */
    public static Set<Vec3i> approxBlocks(Collection<Vec3d> vectors, double overlap) {
        LinkedHashSet<Vec3i> output = new LinkedHashSet<>();
        for (Vec3d vector : vectors) {
            output.addAll(approxBlock(vector, overlap));
        }
        return output;
    }

    /**
     * When the vector is pointing to a location close to the edge of a block also returns the block next to that edge.
     *
     * @param vector  The vector
     * @param overlap The size of the edge
     * @return The set of vectors that correspond to blocks at the approximate location of the input vector
     */
    public static Set<Vec3i> approxBlock(Vec3d vector, double overlap) {
        HashSet<Vec3i> output = new HashSet<>();
        for (int x = (int) Math.floor(vector.x - overlap); x <= vector.x + overlap; x++) {
            for (int y = (int) Math.floor(vector.y - overlap); y <= vector.y + overlap; y++) {
                for (int z = (int) Math.floor(vector.z - overlap); z <= vector.z + overlap; z++) {
                    output.add(new Vec3i(x, y, z));
                }
            }
        }
        return output;
    }

    /**
     * When the vector is pointing to a location close to an XZ edge of a block also returns the block next to that XZ edge.
     *
     * @param vector  The vector
     * @param overlap The size of the edge in the XZ directions
     * @return The set of vectors that correspond to blocks at the approximate location of the input vector
     */
    public static Set<Vec3i> approxBlockXZ(Vec3d vector, double overlap) {
        HashSet<Vec3i> output = new HashSet<>();
        for (int x = (int) (vector.x - overlap); x <= vector.x + overlap; x++) {
            for (int z = (int) (vector.z - overlap); z <= vector.z + overlap; z++) {
                output.add(new Vec3i(x, vector.y, z));
            }
        }
        return output;
    }

    @Override
    public Set<BetterBlockPos> calculateValidPositions() {
        HashSet<BetterBlockPos> out = new HashSet<>();
        for (Vec3i vec : getLineApprox(jump, 1, 2)) {
            BetterBlockPos pos = new BetterBlockPos(src.add(vec));
            out.add(pos); // Jumping from blocks
            out.add(pos.up()); // Jumping into blocks
        }
        out.add(dest);
        out.add(src);
        return out;
    }

    private static final double PLAYER_HEIGHT = 1.8;

    // true if blocks are in the way
    private static boolean checkBlocksInWay(CalculationContext context, int srcX, int srcY, int srcZ, Vec3i jump, int extraAscend, EnumFacing jumpDirection, JumpType type, boolean sprint) {
        if (!MovementHelper.fullyPassable(context, srcX + jump.getX(), srcY + jump.getY() + extraAscend, srcZ + jump.getZ()) || !MovementHelper.fullyPassable(context, srcX + jump.getX(), srcY + jump.getY() + extraAscend + 1, srcZ + jump.getZ())) {
            return true; // Destination is blocked
        }
        Vec3i endPoint;
        if (type == JumpType.EDGE_NEO) {
            endPoint = VecUtils.add(jump, 0, extraAscend, 0); // jumpDir is added later
        } else {
            endPoint = VecUtils.add(jump, -jumpDirection.getXOffset(), extraAscend, -jumpDirection.getZOffset());
        }
        Set<Vec3i> jumpLine = getLineApprox(endPoint, 0.25, 1);

        int jumpBoost = getPotionEffectAmplifier(context.getBaritone().getPlayerContext(), MobEffects.JUMP_BOOST);
        double stepSize = sprint ? SPRINT_JUMP_DISTANCE : WALK_JUMP_DISTANCE; // estimates

        if (type == JumpType.MOMENTUM) {
            jumpLine.add(new Vec3i(-jumpDirection.getXOffset(), 2, -jumpDirection.getZOffset())); // The block above the src is entered during a momentum jump (check block above head)
            stepSize = MOMENTUM_JUMP_DISTANCE;
        }
        Iterator<Vec3i> jumpItr = jumpLine.iterator();

        double prevHeight = 0;
        int prevTick = 0;
        for (int i = 0; i < jumpLine.size(); i++) {
            Vec3i jumpVec = jumpItr.next();
            double distance = getDistance(jumpVec, jumpDirection);
            int tick = (int) (distance / stepSize) + 1;
            if (tick == prevTick + 1) {
                prevHeight += calcFallVelocity(tick, true, jumpBoost); // common faster
                prevTick = tick;
            } else if (tick != prevTick) {
                prevHeight = calcFallPosition(tick, true, jumpBoost); // less common slower
                prevTick = tick;
            }
            for (int j = (int) prevHeight; j <= Math.ceil(PLAYER_HEIGHT + prevHeight); j++) { // Checks feet, head, for each block. (can double check some blocks on ascends/descends)
                // jumpDirection is subtracted at the beginning (re-added here)
                if (!MovementHelper.fullyPassable(context, jumpVec.getX() + srcX + jumpDirection.getXOffset(), jumpVec.getY() + srcY + j, jumpVec.getZ() + srcZ + jumpDirection.getZOffset())) {
                    if (TEST_LOG) {
                        System.out.println("Blocks in the way, block = " + VecUtils.add(jumpVec, srcX + jumpDirection.getXOffset(), srcY + j, srcZ + jumpDirection.getZOffset()) + " jump = " + new Vec3d(srcX, srcY, srcZ) + " -> " + new Vec3d(srcX + jump.getX(), srcY + jump.getY(), srcZ + jump.getZ()) + ", prevHeight = " + prevHeight);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Recalculates the cost for this jump making sure the cost has not changed since initial calculations.
     *
     * Assumes the current jump is still the best jump.
     */
    @Override
    public double calculateCost(CalculationContext context) {
        double cost = COST_INF;
        JumpType tempType;

        if (type == JumpType.MOMENTUM_BLOCK || type == JumpType.MOMENTUM_NO_BLOCK) {
            tempType = JumpType.MOMENTUM;
        } else {
            tempType = type;
        }

        double extraAscend = 0;
        IBlockState standingOn = context.get(src.x, src.y - 1, src.z);
        if (MovementHelper.isBottomSlab(standingOn)) {
            if (!Baritone.settings().allowWalkOnBottomSlab.value) {
                return COST_INF;
            }
            extraAscend += 0.5;
        }
        double moveDis = calcMoveDist(context, src.x, src.y, src.z, jump.getX(), jump.getY(), jump.getZ(), extraAscend, jumpDirection);

        if ((tempType == JumpType.MOMENTUM || tempType == JumpType.EDGE_NEO) && !context.allowParkourMomentumOrNeo) {
            return COST_INF;
        }

        final double maxJump;
        if (context.canSprint) {
            maxJump = type.maxJumpSprint;
        } else {
            maxJump = type.maxJumpNoSprint;
        }

        if (moveDis <= maxJump &&
                !checkBlocksInWay(context, src.x, src.y, src.z, jump, 0, jumpDirection, tempType, moveDis > type.maxJumpNoSprint)) { // no blocks in way
            cost = costFromJump(context, src.x, src.y, src.z, jump.getX(), jump.getY(), jump.getZ(), extraAscend, jumpDirection, tempType);
        } else if (TEST_LOG) {
            logDebug(moveDis + " <= " + maxJump);
        }

        return cost;
    }

    public static Collection<Movement> cost(CalculationContext context, BetterBlockPos src, EnumFacing jumpDirection) {
        MutableMoveResult res = new MutableMoveResult();
        Collection<Movement> out = new ArrayList<>();
        cost(context, src.x, src.y, src.z, res, jumpDirection);
        while (res != null) {
            JumpType type = ALL_VALID_DIR.get(jumpDirection).get(new Vec3i(res.x - src.x, 0, res.z - src.z));

            if (type == JumpType.MOMENTUM) {
                if (MovementHelper.fullyPassable(context, src.x - jumpDirection.getXOffset(), src.y, src.z - jumpDirection.getZOffset())) {
                    // System.out.println("no block found " + new Vec3i(res.x - jumpDirection.getXOffset(), res.y, res.z - jumpDirection.getZOffset()));
                    type = JumpType.MOMENTUM_NO_BLOCK;
                } else {
                    type = JumpType.MOMENTUM_BLOCK;
                }
            }
            out.add(new MovementParkourAdv(context, src, new BetterBlockPos(res.x, res.y, res.z), jumpDirection, type));
            // System.out.println("type = " + type + ", jump = " + new Vec3i(res.x - src.x, 0, res.z - src.z) + ", dir = " + jumpDirection + ", cost = " + res.cost);
            res = res.getNext();
        }
        return out;
    }

    public static void cost(CalculationContext context, int srcX, int srcY, int srcZ, MutableMoveResult res, EnumFacing jumpDirection) {
        if (!context.allowParkour || !context.allowParkourAdv) {
            return;
        }

        if (srcY == 256 && !context.allowJumpAt256) {
            return;
        }

        int xDiff = jumpDirection.getXOffset();
        int zDiff = jumpDirection.getZOffset();
        double extraAscend = 0;

        if (!MovementHelper.fullyPassable(context, srcX + xDiff, srcY, srcZ + zDiff)) {
            return; // block in foot in directly adjacent block
        }
        IBlockState adj = context.get(srcX + xDiff, srcY - 1, srcZ + zDiff);
        if (MovementHelper.canWalkOn(context.bsi, srcX + xDiff, srcY - 1, srcZ + zDiff, adj)) {
            return; // don't parkour if we could just traverse
        }
        if (MovementHelper.avoidWalkingInto(adj.getBlock()) && adj.getBlock() != Blocks.WATER && adj.getBlock() != Blocks.FLOWING_WATER) {
            return; // mostly for lava (we shouldn't jump with lava at our feet as we could take damage)
        }
        if (!MovementHelper.fullyPassable(context, srcX + xDiff, srcY + 1, srcZ + zDiff)) {
            return; // common case (makes all jumps in this direction invalid), block in head in directly adjacent block
        }
        if (!MovementHelper.fullyPassable(context, srcX + xDiff, srcY + 2, srcZ + zDiff)) {
            return; // common case (makes all jumps in this direction invalid), block above head in directly adjacent block
        }

        if (!MovementHelper.fullyPassable(context, srcX, srcY + 2, srcZ)) {
            return; // common case (makes all jumps in this direction invalid), block above head
        }

        IBlockState standingOn = context.get(srcX, srcY - 1, srcZ);
        if (standingOn.getBlock() == Blocks.VINE || standingOn.getBlock() == Blocks.LADDER || standingOn.getBlock() instanceof BlockLiquid) {
            return; // Can't parkour from these blocks.
        }

        if (standingOn.getBlock() instanceof BlockStairs && standingOn.getValue(BlockStairs.HALF) == BlockStairs.EnumHalf.BOTTOM && standingOn.getValue(BlockStairs.FACING) == jumpDirection.getOpposite()) {
            return; // we can't jump if the lower part of the stair is where we need to jump (we'll fall off)
        }

        if (MovementHelper.isBottomSlab(standingOn)) {
            if (!Baritone.settings().allowWalkOnBottomSlab.value) {
                return;
            }
            extraAscend += 0.5;
        }

        MutableMoveResult root = res;
        firstResult = true;

        for (Vec3i posbJump : ALL_VALID_DIR.get(jumpDirection).keySet()) {
            JumpType type = ALL_VALID_DIR.get(jumpDirection).get(posbJump);

            if ((type == JumpType.MOMENTUM || type == JumpType.EDGE_NEO) && !context.allowParkourMomentumOrNeo) {
                continue;
            }

            int destX = srcX + posbJump.getX();
            int destY = srcY;
            int destZ = srcZ + posbJump.getZ();

            final double maxJump;
            if (context.canSprint) {
                maxJump = type.maxJumpSprint;
            } else {
                maxJump = type.maxJumpNoSprint;
            }

            double moveDis;
            IBlockState destInto = context.bsi.get0(destX, destY, destZ);
            // Must ascend here as foot has block, && no block in head at destination (if ascend)
            if (!MovementHelper.fullyPassable(context.bsi.access, context.bsi.isPassableBlockPos.setPos(destX, destY, destZ), destInto) && type != JumpType.NORMAL_STRAIGHT_DESCEND) {
                moveDis = calcMoveDist(context, srcX, srcY, srcZ, posbJump.getX(), posbJump.getY() + 1, posbJump.getZ(), extraAscend, jumpDirection);

                if (moveDis > maxJump) {
                    continue; // jump is too long (recalculated with new posbJump)
                }

                if (context.allowParkourAscend && MovementHelper.canWalkOn(context.bsi, destX, destY, destZ, destInto)) {
                    destY += 1;

                    if (checkBlocksInWay(context, srcX, srcY, srcZ, posbJump, 1, jumpDirection, type, moveDis > type.maxJumpNoSprint)) {
                        continue; // Blocks are in the way
                    }
                    getMoveResult(context, srcX, srcY, srcZ, destX, destY, destZ, extraAscend, posbJump, jumpDirection, type, 0, res);
                }
                continue;
            }

            moveDis = calcMoveDist(context, srcX, srcY, srcZ, posbJump.getX(), posbJump.getY(), posbJump.getZ(), extraAscend, jumpDirection);
            if (moveDis > maxJump) {
                continue; // jump is too long (usually due to ascending (slab) or no sprint)
            }

            for (int descendAmount = type == JumpType.NORMAL_STRAIGHT_DESCEND ? 1 : 0; descendAmount < context.maxFallHeightNoWater; descendAmount++) {
                IBlockState landingOn = context.bsi.get0(destX, destY - descendAmount - 1, destZ);

                // farmland needs to be canWalkOn otherwise farm can never work at all, but we want to specifically disallow ending a jump on farmland
                if (landingOn.getBlock() != Blocks.FARMLAND && (MovementHelper.canWalkOn(context.bsi, destX, destY - descendAmount - 1, destZ, landingOn) /* || landingOn.getBlock() == Blocks.WATER */)) {
                    if (checkBlocksInWay(context, srcX, srcY, srcZ, posbJump, -descendAmount, jumpDirection, type, (moveDis + descendAmount * DESCEND_DIST_PER_BLOCK) > type.maxJumpNoSprint)) {
                        continue; // Blocks are in the way
                    }
                    getMoveResult(context, srcX, srcY, srcZ, destX, destY - descendAmount, destZ, extraAscend - descendAmount, posbJump, jumpDirection, type, 0, res);
                }
            }

            // No block to land on, we now test for a parkour place

            if (!context.allowParkourPlace || type == JumpType.NORMAL_STRAIGHT_DESCEND) {
                continue; // Settings don't allow a parkour place
            }

            IBlockState toReplace = context.get(destX, destY - 1, destZ);
            double placeCost = context.costOfPlacingAt(destX, destY - 1, destZ, toReplace);
            if (placeCost >= COST_INF) {
                continue; // Not allowed to place here
            }
            if (!MovementHelper.isReplaceable(destX, destY - 1, destZ, toReplace, context.bsi)) {
                continue; // Not allowed to place here
            }

            xDiff += posbJump.getX();
            zDiff += posbJump.getZ();
            double jumpLength = Math.sqrt(xDiff * xDiff + zDiff * zDiff);
            boolean blocksCheckedYet = false;
            boolean blocksInWay = true;

            // Check if a block side is available/visible to place on
            for (int j = 0; j < 5; j++) {
                int againstX = HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getXOffset();
                int againstY = HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getYOffset();
                int againstZ = HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getZOffset();
                if (MovementHelper.canPlaceAgainst(context.bsi, destX + againstX, destY - 1 + againstY, destZ + againstZ)) {
                    double angle = Math.acos((againstX * xDiff + againstZ * zDiff) / jumpLength) * RotationUtils.RAD_TO_DEG;
                    // System.out.println(new Vec3i(srcX, srcY, srcZ) + " -> " + new Vec3i(destX, destY, destZ) + ", Dir = " + jumpDirection + ", angle = " + angle + ", against = " + new Vec3i(againstX, againstY, againstZ));
                    if (angle <= 90) { // we can't turn around that fast
                        if (!blocksCheckedYet) { // reduce expensive block checking
                            blocksInWay = checkBlocksInWay(context, srcX, srcY, srcZ, posbJump, 0, jumpDirection, type, moveDis > type.maxJumpNoSprint);
                            blocksCheckedYet = true;
                        }
                        if (!blocksInWay) {
                            getMoveResult(context, srcX, srcY, srcZ, destX, destY, destZ, extraAscend, posbJump, jumpDirection, type, placeCost, res);
                        }
                    }
                }
            }
        }
        res = root;
    }

    private static boolean firstResult;

    private static void getMoveResult(CalculationContext context, int srcX, int srcY, int srcZ, int destX, int destY, int destZ, double extraAscend, Vec3i jump, EnumFacing jumpDirection, JumpType type, double costModifiers, MutableMoveResult res) {
        double cost = costFromJump(context, srcX, srcY, srcZ, jump.getX(), destY - srcY, jump.getZ(), extraAscend, jumpDirection, type) + costModifiers;
        // System.out.println("cost = " + cost);
        if (cost < COST_INF) {
            if (firstResult) {
                firstResult = false;
            } else {
                res = res.nextPotentialDestination();
            }
            res.x = destX;
            res.y = destY;
            res.z = destZ;
            res.cost = cost;
            if (TEST_LOG && res.cost < COST_INF) {
                Vec3i jumpVec = new Vec3i(res.x - srcX, res.y - srcY, res.z - srcZ);
                System.out.println(new Vec3i(srcX, srcY, srcZ) + " -> " + new Vec3i(res.x, res.y, res.z) + ", Dir = " + jumpDirection + ", Cost: " + res.cost + ", Distance: " + getDistance(jumpVec, jumpDirection) + ", MoveDis: " + calcMoveDist(context, srcX, srcY, srcZ, jumpVec.getX(), jumpVec.getY(), jumpVec.getZ(), extraAscend, jumpDirection));
            }
        }
    }

    // used to determine if we should sprint or not
    private static double calcMoveDist(CalculationContext context, int srcX, int srcY, int srcZ, int jumpX, int jumpY, int jumpZ, double extraAscend, EnumFacing jumpDirection) {
        double ascendAmount = jumpY + extraAscend;

        // Accounting for slab height
        IBlockState destBlock = context.get(jumpX + srcX, jumpY + srcY - 1, jumpZ + srcZ);
        if (MovementHelper.isBottomSlab(destBlock)) {
            ascendAmount -= 0.5;
        }

        int x = jumpX - jumpDirection.getXOffset();
        int z = jumpZ - jumpDirection.getZOffset();
        double distance = Math.sqrt(x * x + z * z);

        // Calculating angle between vectors
        double angle = Math.acos((x * jumpDirection.getXOffset() + z * jumpDirection.getZOffset()) / distance); // in radians acos(dot_product(xz, jumpDir))
        distance += TURN_COST_PER_RADIAN * angle + 1;

        // Modifying distance so that ascends have larger distances while descends have smaller
        if (ascendAmount > 0) {
            if (ascendAmount > calcMaxJumpHeight(true, getPotionEffectAmplifier(context.baritone.getPlayerContext(), MobEffects.JUMP_BOOST))) {
                return COST_INF; // any value > the highest sprint jump distance (about 5)
            }
            distance += ascendAmount * ASCEND_DIST_PER_BLOCK;
        } else {
            distance += ascendAmount * -DESCEND_DIST_PER_BLOCK; // ascendAmount is negative
        }

        // reduce distance to approach a minimum value of 0.559 (prevents negatives/or very low distances)
        if (distance < 1.74) { // 1.74 = 3.48 [MAX_JUMP_WALK] / 2
            distance = Math.pow(1.1, distance) + 0.559; // 1.1^1.74 + 0.559 = 1.739
        }

        // This distance is unitless as it contains: modifiers on ascends/descends, and the slowdowns in changing directions midair (angle)
        return distance;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // possibly safe if ctx.player().motion is low enough
        return state.getStatus() != MovementStatus.RUNNING;
    }

    private static double costFromJump(CalculationContext context, int srcX, int srcY, int srcZ, int jumpX, int jumpY, int jumpZ, double extraAscend, EnumFacing jumpDirection, JumpType type) {
        double costMod = 0;
        IBlockState landingOn = context.bsi.get0(srcX + jumpX, srcY + jumpY - 1, srcZ + jumpZ);
        if (landingOn.getBlock() == Blocks.WATER) {
            costMod = JUMP_IN_WATER_COST;
        }
        switch (type) {
            case MOMENTUM:
                if (MovementHelper.fullyPassable(context, srcX - jumpDirection.getXOffset(), srcY, srcZ - jumpDirection.getZOffset()) &&
                        MovementHelper.fullyPassable(context, srcX - jumpDirection.getXOffset(), srcY + 1, srcZ - jumpDirection.getZOffset())) {
                    type = JumpType.MOMENTUM_NO_BLOCK;
                    if (jumpX != 0 && jumpZ != 0 && jumpY >= 0) {
                        if (TEST_LOG) {
                            System.out.println("Discarding angled MOMENTUM_NO_BLOCK.");
                        }
                        return COST_INF; // unsafe
                    }
                    if (MovementHelper.canWalkOn(context.bsi, srcX - jumpDirection.getXOffset(), srcY - 1, srcZ - jumpDirection.getZOffset())) {
                        // type = JumpType.MOMENTUM_2BM?
                        if (TEST_LOG) {
                            System.out.println("Discarding 2bm MOMENTUM_NO_BLOCK. (Not yet implemented/Overlaps with other jump)");
                        }
                        return COST_INF; // unsafe
                    }
                } else {
                    type = JumpType.MOMENTUM_BLOCK;
                }
                break;
            case EDGE_NEO:
                jumpX = Integer.compare(jumpX, 0);
                jumpZ = Integer.compare(jumpZ, 0);
                if (MovementHelper.fullyPassable(context, srcX + jumpX, srcY + 1, srcZ + jumpZ)) {
                    if (TEST_LOG) {
                        System.out.println("Discarding EDGE_NEO. src = " + new Vec3i(srcX, srcY, srcZ) + ", dir = " + jumpDirection + ", since " + new Vec3i(srcX + jumpX, srcY + 1, srcZ + jumpZ) + " is not a block");
                    }
                    return COST_INF; // don't neo if you can just do a normal jump
                }
                break;
        }
        return type.prepCost + calcJumpTime(jumpY + extraAscend, true, context.baritone.getPlayerContext()) + costMod + LAND_COST;
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (inStartingPosition || state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        ticksSinceJump++;
        Vec3d offset;
        double accuracy;
        switch (type) {
            case NORMAL:
            case NORMAL_STRAIGHT_DESCEND:
                offset = new Vec3d(jumpDirection.getOpposite().getDirectionVec()).scale(PREP_OFFSET);
                accuracy = 0.1;
                break;
            case NORMAL_CRAMPED:
                offset = new Vec3d(jumpDirection.getOpposite().getDirectionVec()).scale(PREP_OFFSET).rotateYaw(-jumpAngle);
                accuracy = 0.05;
                break;
            case MOMENTUM_BLOCK:
                offset = new Vec3d(jumpDirection.getOpposite().getDirectionVec()).scale(PREP_OFFSET);
                accuracy = 0.025; // Basically as small as you can get
                break;
            case MOMENTUM_NO_BLOCK:
                offset = new Vec3d(jumpDirection.getOpposite().getDirectionVec()).scale(0.8);
                accuracy = 0.025;
                break;
            case EDGE:
            case EDGE_NEO:
                offset = new Vec3d(jumpDirection.getDirectionVec()).scale(0.8).add(new Vec3d(destDirection.getOpposite().getDirectionVec()).scale(0.2));
                accuracy = 0.025;
                break;
            default:
                throw new UnsupportedOperationException("Add the new JumpType to this switch.");
        }
        Vec3d preJumpPos = offset.add(src.x + 0.5, ctx.player().posY, src.z + 0.5);
        double distance = preJumpPos.distanceTo(ctx.playerFeetAsVec());
        // System.out.println("Distance to prepLoc = " + distance);
        boolean prepLocPassable = MovementHelper.fullyPassable(ctx, src.add(new BlockPos(offset.add(offset.normalize().scale(0.4))))); // Checking 0.4 blocks in the direction of offset for a block (0.3 is the player hitbox width)
        if (((distance > accuracy && prepLocPassable) || (distance > (PREP_OFFSET - (0.2 - accuracy)) && !prepLocPassable)) &&
                (ticksAtDest < 8 || accuracy >= 0.1)) { // Accuracies under 0.1 will require additional wait ticks to reduce excess momentum
            if (ticksAtDest < 6) {
                MovementHelper.moveTowards(ctx, state, offset.add(VecUtils.getBlockPosCenter(src)));
            }
            if (distance < 0.25) {
                state.setInput(Input.SNEAK, true);
                if (distance < accuracy) {
                    ticksAtDest++;
                } else {
                    ticksAtDest--;
                }
            } else {
                ticksAtDest = 0;
            }
            if (ctx.playerFeet().y < src.y) {
                // we have fallen
                logDebug("Fallen during the prep phase");
                state.setStatus(MovementStatus.UNREACHABLE);
                return true; // to bypass prepare phase
            }
            return false;
        } else {
            logDebug("Achieved '" + df.format(distance) + "' blocks of accuracy to preploc. Time = " + ticksSinceJump + ", Jump Direction = " + jumpDirection + ", Remaining Motion = " + df.format(new Vec3d(ctx.player().motionX, ctx.player().motionY, ctx.player().motionZ).length()) + ", Using technique '" + type + "'");
            inStartingPosition = true;
            ticksAtDest = 0;
            ticksSinceJump = -1;
            jumpTime = calcJumpTime(ascendAmount, true, ctx);
            return true;
        }
    }

    /**
     * Calculates the fall velocity for a regular jump. (no glitches)
     * Does not account for blocks in the way.
     * Can be used to predict the next ticks location to possibly map out a jump tick by tick.
     *
     * @param ticksFromStart Ticks past since the jump began
     * @param jump           If jump is a jump and not a fall.
     * @param jumpBoostLvl   The level of jump boost on the player.
     * @return The (y-direction) velocity in blocks per tick
     */
    private static double calcFallVelocity(int ticksFromStart, boolean jump, int jumpBoostLvl) {
        if (ticksFromStart <= 0) {
            return 0;
        }
        double init = 0.42 + 0.1 * jumpBoostLvl;
        if (!jump) {
            init = -0.0784;
        }
        // V0 = 0
        // V1 = 0.42 (if jumping) OR -0.0784 (if falling)
        // VN+1 = (VN - 0.08) * 0.98
        // If |VN+1| < 0.003, it is 0 instead
        double vel = init * Math.pow(0.98, ticksFromStart - 1) + 4 * Math.pow(0.98, ticksFromStart) - 3.92;
        if (vel < -3.92) {
            vel = -3.92; // TERMINAL_VELOCITY
        }
        if (Math.abs(vel) < 0.003) {
            vel = 0; // MINIMUM_VELOCITY
        }
        return vel;
    }

    /**
     * Calculates the velocity the player would have a specified amount of ticks after a jump
     * Jump at tick 1
     *
     * @param ticksFromStart The amount of ticks since jumping
     * @param ctx            Player context (for jump boost)
     * @return The y velocity calculated (+ is up)
     */
    private static double calcFallVelocity(int ticksFromStart, boolean jump, IPlayerContext ctx) {
        return calcFallVelocity(ticksFromStart, jump, getPotionEffectAmplifier(ctx, MobEffects.JUMP_BOOST));
    }

    /**
     * Calculates the y position of the player relative to the jump y position
     * Jump at tick 1
     *
     * @param ticksFromStart The amount of ticks that have passed since jumping
     * @param jump           If the jump is a jump and not a fall
     * @param jumpBoostLvl   The level of jump boost active on the player
     * @return The relative y position of the player
     */
    private static double calcFallPosition(int ticksFromStart, boolean jump, int jumpBoostLvl) {
        int yPos = 0;
        for (int i = 1; i <= ticksFromStart; i++) {
            yPos += calcFallVelocity(i, jump, jumpBoostLvl);
        }
        return yPos;
    }

    /**
     * Calculates the y position of the player relative to the jump y position
     * Jump at tick 1
     *
     * @param ticksFromStart The amount of ticks that have passed since jumping
     * @param jump           If the jump is a jump and not a fall
     * @param ctx            The player context (for jump boost)
     * @return The relative y position of the player
     */
    private static double calcFallPosition(int ticksFromStart, boolean jump, IPlayerContext ctx) {
        return calcFallPosition(ticksFromStart, jump, getPotionEffectAmplifier(ctx, MobEffects.JUMP_BOOST));

    }

    /**
     * Calculates the time in ticks spent in the air after jumping
     *
     * @param ascendAmount` The y differance of the landing position (can be negative)
     * @param jump          If the jump is a jump and not a fall
     * @param jumpBoostLvl  Tne level of jump boost active on the player
     * @return The jump time in ticks
     */
    private static int calcJumpTime(double ascendAmount, boolean jump, int jumpBoostLvl) {
        if (ascendAmount == 0 && jumpBoostLvl == 0 && jump) {
            return 12; // Most common case
        }
        double maxJumpHeight = calcMaxJumpHeight(jump, jumpBoostLvl);
        if (ascendAmount > maxJumpHeight) {
            return -1; // Jump not possible
        }
        int ticks = 0;
        double prevHeight = -1;
        double newHeight = 0;
        while (prevHeight < newHeight || // True when moving upwards (You can only land on a block when you are moving down)
                newHeight > ascendAmount) { // True when you are above the landing block
            ticks++;
            prevHeight = newHeight;
            newHeight += calcFallVelocity(ticks, jump, jumpBoostLvl);
        }
        return ticks - 1;
    }

    /**
     * Calculates the time in ticks spent in the air after jumping
     *
     * @param height The height we are landing at (relative to the jump height)
     * @param jump   If the jump is a jump and not a fall
     * @param ctx    The player context (for jump boost)
     * @return The jump time in ticks
     */
    private static int calcJumpTime(double height, boolean jump, IPlayerContext ctx) {
        return calcJumpTime(height, jump, getPotionEffectAmplifier(ctx, MobEffects.JUMP_BOOST));
    }

    /**
     * Gets the maximum jump height for a given jump boost lvl
     *
     * @param jump         If the jump is a jump and not a fall
     * @param jumpBoostLvl What level of jump boost is active on the player
     * @return The relative jump height
     */
    private static double calcMaxJumpHeight(boolean jump, int jumpBoostLvl) {
        if (!jump) {
            return 0; // you only move up when you press the jump key
        }
        int ticks = 1;
        double prevHeight = -1;
        double newHeight = 0;
        while (prevHeight < newHeight) {
            prevHeight = newHeight;
            newHeight += calcFallVelocity(ticks, jump, jumpBoostLvl);
            ticks++;
        }
        return prevHeight;
    }

    /**
     * 0 is not active (or not relevant), 1 is level 1 (amplifier of 0)
     *
     * @param ctx Player context
     * @param effect The effect to find
     * @return The amplifier of the given effect
     */
    private static int getPotionEffectAmplifier(IPlayerContext ctx, Potion effect) {
        if (Baritone.settings().considerPotionEffects.value && ctx.player().isPotionActive(effect)) {
            return ctx.player().getActivePotionEffect(effect).getAmplifier() + 1;
        } else {
            return 0;
        }
    }

    /**
     * Move in the direction of moveDirYaw while facing towards playerYaw
     * (excluding MOVE_BACK)
     * angleDiff negative is anti-clockwise (left)
     *
     * @param angleDiff  moveDirYaw - playerYaw
     * @return The input required
     */
    private static Input sideMove(double angleDiff) {
        if (angleDiff > 180) {
            angleDiff = 180 - angleDiff; // account for values close to the -180 to 180 flip
        }
        if (angleDiff < -180) {
            angleDiff = -180 - angleDiff;
        }
        // System.out.println("Side move diff of " + angleDiff);
        if (angleDiff >= 20) {
            return Input.MOVE_RIGHT;
        }
        if (angleDiff <= -20) {
            return Input.MOVE_LEFT;
        }
        if (Math.abs(angleDiff) < 20) {
            return Input.MOVE_FORWARD;
        }
        return null; // Not possible
    }

    /**
     * Move in the direction of moveDirYaw while facing towards playerYaw
     * (excluding MOVE_BACK)
     *
     * @param playerYaw  The yaw we are facing towards
     * @param moveDirYaw The yaw (angle) to move towards
     * @return The input required
     */
    private static Input sideMove(double playerYaw, double moveDirYaw) {
        return sideMove(moveDirYaw - playerYaw);
    }

    /**
     * Move in the direction of moveDirYaw while facing towards playerLookDest
     * (excluding MOVE_BACK)
     *
     * @param src            The block we are at
     * @param playerLookDest The block we are facing towards
     * @param moveDest       The block to move towards
     * @return The input required
     */
    private static Input sideMove(Vec3i src, Vec3i playerLookDest, Vec3i moveDest) {
        // Make vectors relative
        playerLookDest = VecUtils.subtract(playerLookDest, src);
        moveDest = VecUtils.subtract(moveDest, src);
        return sideMove(Math.atan2(playerLookDest.getX(), -playerLookDest.getZ()) * RotationUtils.RAD_TO_DEG,
                Math.atan2(moveDest.getX(), -moveDest.getZ()) * RotationUtils.RAD_TO_DEG);
    }

    /**
     * Simulates the states:
     * - Sprinting
     * - Walking
     * - Sneaking
     * - Stopping
     * and finds which one is the best to take for this tick
     * It will then change the state to make sure we land on a block.
     *
     * @param ctx   Player context (for xz pos)
     * @param src   The block we are landing on (for overshooting)
     * @param state The movement state to change
     * @param ticksRemaining The ticks until the expected landing time
     * @param inwards whether we are moving inwards (towards src) or outwards (away from src)
     */
    private void landHere(IPlayerContext ctx, BlockPos src, MovementState state, int ticksRemaining, boolean inwards) {
        if (!inwards && getDistanceToEdgeNeg(ctx.player().posX, ctx.player().posZ, src, 0.8) < 0) {
            // if we are moving outwards and we are already over the edge.
            logDebug("Fallen?");
            return;
        }

        // set state to walk only
        state.getInputStates().remove(Input.SPRINT);
        state.getInputStates().remove(Input.SNEAK);
        state.setInput(Input.MOVE_FORWARD, true);

        MovementPrediction.PredictionResult future = MovementPrediction.getFutureLocation(ctx.player(), state, ticksRemaining);

        // does walking get us to our target?
        if ((future.collidedVertically && !inwards) || (!future.collidedVertically && inwards)) {
            // try if faster movement also works
            state.setInput(Input.SPRINT, true);
            future = future.recalculate(state);
            if (!future.collidedVertically && !inwards) {
                // faster didn't work walking is the best option
                state.getInputStates().remove(Input.SPRINT);
            } else if (!future.collidedVertically) {
                // (inwards) we can't make it when sprinting
                logDebug("Too far can't make jump?");
            }
        } else {
            // walking doesn't work try slower
            state.setInput(Input.SNEAK, true); // sneaking
            future = future.recalculate(state);
            if ((!future.collidedVertically && !inwards) || (future.collidedVertically && inwards)) {
                // sneaking didn't work try slower
                state.getInputStates().remove(Input.MOVE_FORWARD);
                state.getInputStates().remove(Input.SNEAK);
                future = future.recalculate(state);
                if (!inwards && !future.collidedVertically) {
                    // oh no. this is as slow as we can go
                    logDebug("We've overshot..");
                    MovementHelper.moveTowards(ctx, state, src);
                } else if (inwards && !future.collidedVertically) {
                    state.setInput(Input.SNEAK, true);
                    state.setInput(Input.MOVE_FORWARD, true);
                }
            } else if (inwards) {
                // && !future.collidedVertically
                state.getInputStates().remove(Input.SNEAK);
            }
        }
    }

    /**
     * Returns the Chebyshev distance to the edge of the given block
     *
     * @param posX      X position of the player
     * @param posZ      Z position of the player
     * @param feetPos   The block to find the distance to
     * @param edgeSize  The distance to add to the block centre
     * @return          The distance
     */
    private static double getDistanceToEdge(double posX, double posZ, Vec3i feetPos, double edgeSize) {
        return Math.min(Math.abs(edgeSize - Math.abs(feetPos.getX() + 0.5 - posX)), Math.abs(edgeSize - Math.abs(feetPos.getZ() + 0.5 - posZ)));
    }

    /**
     * Returns the Chebyshev distance to the edge of the given block (when positive). Negative if not on block.
     * Negative values show the distance along the farthest axis instead and therefore should only be used for it's sign.
     */
    private static double getDistanceToEdgeNeg(double posX, double posZ, Vec3i feetPos, double edgeSize) {
        return Math.min(edgeSize - Math.abs(feetPos.getX() + 0.5 - posX), edgeSize - Math.abs(feetPos.getZ() + 0.5 - posZ));
    }

    /**
     * Raytraces momentum until the edge of the given block.
     * Although it doesn't actually use any raytracing techniques it produces the same result mathematically.
     *
     * @param posX X position of the player
     * @param posZ Z position of the player
     * @param feetPos The position of the block
     * @param momentum The momentum Vec to raytrace
     * @param edgeSize The Chebyshev distance from the block centre to raytrace until
     * @return The euclidean distance
     */
    private static double getDistanceToEdge(double posX, double posZ, Vec3i feetPos, Vec3d momentum, double edgeSize) {
        double relX = posX - feetPos.getX() - 0.5; // position relative to block centre
        double relZ = posZ - feetPos.getZ() - 0.5;

        relX = momentum.x > 0 ? edgeSize - relX : -edgeSize - relX; // position relative to edge
        relZ = momentum.z > 0 ? edgeSize - relZ : -edgeSize - relZ;

        // dividing by very low numbers leads to floating point inaccuracy
        if (Math.abs(momentum.z) < 0.01) {
            return Math.abs(relX);
        }
        if (Math.abs(momentum.x) < 0.01) {
            return Math.abs(relZ);
        }

        double disX = Math.abs(relZ * momentum.x / momentum.z); // distance to edge in direction of momentum
        double disZ = Math.abs(relX * momentum.z / momentum.x);

        double x = disX < disZ ? disX : disZ * momentum.x / momentum.z; // smallest distance to edge in direction of momentum
        double z = disX > disZ ? disZ : disX * momentum.z / momentum.x;

        return Math.sqrt(x * x + z * z); // euclidean distance to edge in direction of momentum
    }

    double prevDistance = 2; // starting value >= root(2)
    boolean initialLanding = true;

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (ticksSinceJump > 40) { // Should generally have <= 12 (exceptions are descending jumps)
            logDebug("jump timed out");
            return state.setStatus(MovementStatus.FAILED);
        }

        ticksSinceJump++;

        if (ctx.player().posY < (src.y + Math.min(ascendAmount, 0) - 0.5)) {
            // we have fallen
            logDebug("Fallen during jump phase");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        if (moveDist > type.maxJumpNoSprint) {
            state.setInput(Input.SPRINT, true);
        }

        double jumpMod = 0.18; // Amount to shift the jump location by (towards the destination) (0.2 is max if block is present)
        double jumpModX = jumpMod * destDirection.getXOffset(); // perpendicular to offset
        double jumpModZ = jumpMod * destDirection.getZOffset();

        final double JUMP_OFFSET = 0.33;
        final double jumpExtension = 2; // to prevent sudden turns extend the look location

        Vec3d jumpLoc = new Vec3d(src.getX() + 0.5 + jumpModX + (jumpDirection.getXOffset() * (0.5 + JUMP_OFFSET)), src.getY(),
                src.getZ() + 0.5 + jumpModZ + (jumpDirection.getZOffset() * (0.5 + JUMP_OFFSET)));
        Vec3d jumpLocExt = new Vec3d(src.getX() + 0.5 + (jumpModX + (jumpDirection.getXOffset() * (0.5 + JUMP_OFFSET))) * jumpExtension, src.getY(),
                src.getZ() + 0.5 + (jumpModZ + (jumpDirection.getZOffset() * (0.5 + JUMP_OFFSET))) * jumpExtension);
        Vec3d startLoc = new Vec3d(src.getX() + 0.5 - (jumpDirection.getXOffset() * 0.3), src.getY(),
                src.getZ() + 0.5 - (jumpDirection.getZOffset() * 0.3));
        Vec3d destVec = new Vec3d(dest.getX() + 0.5 - ctx.player().posX, dest.getY() - ctx.player().posY, dest.getZ() + 0.5 - ctx.player().posZ); // The vector pointing from the players location to the destination

        // the angle we are looking at relative to the direction of the jump
        float lookAngle = (float) (getSignedAngle(jumpDirection.getXOffset(), jumpDirection.getZOffset(), ctx.player().getLookVec().x, ctx.player().getLookVec().z));

        double curDist = Math.sqrt(ctx.playerFeetAsVec().squareDistanceTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5));
        double distToJumpXZ = ctx.playerFeetAsVec().distanceTo(jumpLoc.subtract(0, jumpLoc.y - ctx.playerFeetAsVec().y, 0));
        double distFromStart = ctx.playerFeetAsVec().distanceTo(startLoc);
        double distFromStartXZ = ctx.playerFeetAsVec().distanceTo(startLoc.subtract(0, startLoc.y - ctx.playerFeetAsVec().y, 0));

        MovementHelper.moveTowards(ctx, state, dest); // set initial look direction (for prediction)
        MovementPrediction.PredictionResult future = MovementPrediction.getFutureLocation(ctx.player(), state, 1); // The predicted location 1 tick in the future
        Vec3d motionVecPred = future.getPosition().subtract(ctx.playerFeetAsVec());

        Vec3d curDest = null; // The current destination (position we are moving towards)

        if (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.up()) || (distToJumpXZ < 0.5 && distFromStartXZ < 1.2) ||
                (type == JumpType.MOMENTUM_NO_BLOCK && distFromStartXZ < 0.8)) {
            // logDebug("Moving to jump, on src = " + ctx.playerFeet().equals(src) + ", or above = " + ctx.playerFeet().equals(src.up()));

            switch (type) {
                case NORMAL:
                case NORMAL_CRAMPED:
                case NORMAL_STRAIGHT_DESCEND:
                    MovementHelper.moveTowards(ctx, state, jumpLocExt);
                    break;
                case MOMENTUM_BLOCK:
                    if (ticksSinceJump == 0) {
                        logDebug("Momentum jump");
                        state.setInput(Input.JUMP, true);
                        state.getInputStates().remove(Input.MOVE_FORWARD);
                    } else if (ticksSinceJump > 0) {
                        if (ticksSinceJump >= 10) {
                            MovementHelper.moveTowards(ctx, state, dest);
                            landHere(ctx, src, state, calcJumpTime(0, true, ctx) - ticksSinceJump, false);
                        } else if (ticksSinceJump <= 1 || ticksSinceJump == 3) {
                            state.getInputStates().remove(Input.SPRINT); // not sprinting for a few ticks
                            MovementHelper.moveTowards(ctx, state, src.offset(jumpDirection));
                        } else {
                            MovementHelper.moveTowards(ctx, state, src.offset(jumpDirection, 2));
                        }
                    } else {
                        state.getInputStates().remove(Input.MOVE_FORWARD); // don't move for a few ticks
                    }
                    break;
                case MOMENTUM_NO_BLOCK:
                    if (ticksSinceJump >= 6) {
                        // logDebug("m 1, " + ticksFromJump);
                        MovementHelper.moveTowards(ctx, state, dest);
                        landHere(ctx, src, state, calcJumpTime(0, true, ctx) - ticksSinceJump, false);
                    } else if (ticksSinceJump != 0) {
                        // logDebug("m 2");
                        state.setTarget(new MovementState.MovementTarget(
                                new Rotation(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                                        VecUtils.getBlockPosCenter(src.offset(jumpDirection, 2)),
                                        ctx.playerRotations()).getYaw(), ctx.player().rotationPitch),
                                false
                        ));
                        state.getInputStates().remove(Input.MOVE_FORWARD);
                    } else {
                        // logDebug("m 3");
                        MovementHelper.moveTowards(ctx, state, src.offset(jumpDirection, 2));
                        state.getInputStates().remove(Input.SPRINT);
                    }
                    if (ticksSinceJump == 0) {
                        logDebug("Momentum jump");
                        MovementHelper.moveTowards(ctx, state, src.offset(jumpDirection, 2));
                        state.setInput(Input.SPRINT, true);
                        state.setInput(Input.JUMP, true);
                    }
                    break;
                case EDGE:
                    MovementHelper.moveTowards(ctx, state, dest);
                    break;
                case EDGE_NEO:
                    MovementHelper.moveTowards(ctx, state, new Vec3d(jumpDirection.getDirectionVec()).scale(0.3).add(VecUtils.getBlockPosCenter(dest)));
                    state.setInput(sideMove(src, dest, src.offset(jumpDirection)), true);
                    break;
                default:
                    throw new UnsupportedOperationException("Add new movement to this switch.");
            }
        } else if (curDist < 0.5 && ctx.player().onGround) {
            IBlockState landingOn = ctx.world().getBlockState(dest.down());
            double remMotion = motionVecPred.length();
            double distance;
            if (remMotion < 0.08) {
                distance = prevDistance; // we can still fall off with slow cancelled momentum (e.g. on edge of block)
            } else {
                distance = getDistanceToEdge(ctx.player().posX, ctx.player().posZ, dest, motionVecPred,0.5);
            }
            double slipMod = 0;
            if (initialLanding && landingOn.getBlock().slipperiness > 0.61) {
                slipMod = remMotion * 2.9; // lower values of remaining motion while on slippery blocks can still be 0 tick cancels
                initialLanding = false;
            } else if (landingOn.getBlock().slipperiness > 0.61) {
                slipMod = remMotion * 4 + 0.5; // slippery surfaces retain a lot of momentum after landing
            }
            // logDebug("Cancelling momentum for " + atDestTicks + " ticks, distance = " + df.format(curDist) + ", distance to edge = " + df.format(distance) + ", remaining motion = " + df.format(remMotion) + ", " + df.format(remMotion + slipMod));
            if (remMotion + slipMod < distance || (distance < 0.5 && distance > prevDistance)) {
                logDebug("Canceled momentum for " + ticksAtDest + " ticks, distance = " + df.format(curDist) + ", distance to edge = " + df.format(distance) + ", remaining motion = " + df.format(remMotion) + ", time since jump = " + ticksSinceJump);
                return state.setStatus(MovementStatus.SUCCESS);
            } else {
                ticksAtDest++;
                MovementHelper.moveTowards(ctx, state, dest);
                state.getInputStates().remove(Input.MOVE_FORWARD);
                state.setInput(Input.SNEAK, true);
            }
            prevDistance = distance;
        } else {
            MovementHelper.moveTowards(ctx, state, dest);
            curDest = VecUtils.getBlockPosCenter(dest);
        }

        if (ctx.playerFeet().equals(dest)) {
            Block d = BlockStateInterface.getBlock(ctx, dest);
            if (d == Blocks.VINE || d == Blocks.LADDER) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
        } else if (!ctx.playerFeet().equals(src)) {  // Don't jump on the src block (too early)
            if ((((Math.abs(future.posX - (src.x + 0.5)) > 0.85 || Math.abs(future.posZ - (src.z + 0.5)) > 0.85) && distFromStart < 1.2) || // Centre 0.5 + Player hitbox 0.3 = 0.8, if we are this distance from src, jump
                    ((type == JumpType.MOMENTUM_BLOCK || type == JumpType.MOMENTUM_NO_BLOCK) && distToJumpXZ < 0.6) || // During a momentum jump the momentum jump will position us so just jump whenever possible (i.e. as soon as we land)
                    ((type == JumpType.EDGE || type == JumpType.EDGE_NEO) && distFromStart < 1.2))  // The prepLoc of an edge jump is on the edge of the block so just jump straight away
                    && ctx.player().onGround) { // To only log Jumping when we can actually jump
                if (type == JumpType.MOMENTUM_BLOCK || type == JumpType.MOMENTUM_NO_BLOCK) {
                    MovementHelper.moveTowards(ctx, state, dest); // make sure we are looking at the target when we jump for sprint jump bonuses
                    state.setInput(Input.SPRINT, true);
                }
                state.setInput(Input.JUMP, true);
                logDebug("Jumping " + ticksSinceJump + " pos = " + ctx.playerFeetAsVec());
                ticksSinceJump = 0; // Reset ticks from momentum/run-up phase
            }
            if (!MovementHelper.canWalkOn(ctx, dest.down()) && !ctx.player().onGround && MovementHelper.attemptToPlaceABlock(state, baritone, dest.down(), true, false) == PlaceResult.READY_TO_PLACE) {
                state.setInput(Input.CLICK_RIGHT, true);
            }
        }

        if (curDest != null &&
                type != JumpType.EDGE_NEO && (type != JumpType.MOMENTUM_BLOCK && type != JumpType.MOMENTUM_NO_BLOCK)) { // EDGE_NEO and MOMENTUM jumps are completed with very low room for error, dodging an obstacle will lead to missing the jump due to the slight decrease in speed
            // This is called after movement to also factor in key presses and look direction
            int ticksRemaining = jumpTime - ticksSinceJump;
            MovementPrediction.PredictionResult future5 = MovementPrediction.getFutureLocation(ctx.player(), state, Math.min(4, ticksRemaining)); // The predicted location a few ticks in the future

            // adjust movement to attempt to dodge obstacles
            if (future5.collidedHorizontally && future.posY > dest.getY() && type != JumpType.NORMAL_CRAMPED) {
                double angleDiff = getSignedAngle(destVec.subtract(future5.getPosition()), ctx.player().getLookVec());
                if(Math.abs(angleDiff) > 20) {
                    logDebug("Adjusting movement to dodge an obstacle. Predicted collision location = " + future5.getPosition() + " tick, " + ticksSinceJump + " -> " + (ticksSinceJump + Math.min(4, ticksRemaining)));
                    state.setInput(sideMove(angleDiff), true);
                }
            }

            // logDebug("JumpAngle = " + jumpAngle)
            if (type == JumpType.NORMAL_CRAMPED) {
                // shift lookAngle away from jumpAngle

                if (ticksRemaining >= 3) {
                    // first half of jump
                    state.getTarget().rotation = new Rotation(jumpDirection.getHorizontalAngle(), state.getTarget().rotation.getPitch());
                } else {
                    // second half of jump
                    float angleToAdd = 15 + (Math.abs(jumpAngle) - Math.abs(lookAngle)) * 0.1F;
                    if (jumpAngle > 1) {
                        // jump is to the right
                        state.getTarget().rotation = new Rotation(state.getTarget().rotation.getYaw() - angleToAdd, state.getTarget().rotation.getPitch());
                        if (ticksRemaining <= 1) {
                            state.setInput(Input.MOVE_RIGHT, true);
                        }
                    } else if (jumpAngle < 1) {
                        // jump is to the left
                        state.getTarget().rotation = new Rotation(state.getTarget().rotation.getYaw() + angleToAdd, state.getTarget().rotation.getPitch());
                        if (ticksRemaining <= 1) {
                            state.setInput(Input.MOVE_LEFT, true);
                        }
                    }
                }
                // logDebug("pos = " + ctx.playerFeetAsVec() + ", angle = " + angleToAdd + ", actAngle = " + ctx.playerRotations().getYaw() + ", ticksRemaining = " + ticksRemaining);
            }

            Vec3d future5Pos = new Vec3d(future5.posX, startLoc.y, future5.posZ);

            if (distanceXZ < future5Pos.distanceTo(startLoc) && // overshot (in the future)
                    distanceXZ > distFromStartXZ) { // haven't overshot yet
                logDebug("Adjusting movement to prevent overshoot. " + ticksSinceJump);
                state.getInputStates().remove(Input.MOVE_FORWARD);
            }
        }

        return state;
    }
}
