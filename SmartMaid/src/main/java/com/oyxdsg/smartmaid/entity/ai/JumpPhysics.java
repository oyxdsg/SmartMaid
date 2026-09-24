package com.oyxdsg.smartmaid.entity.ai;

/**
 * 女仆移动/跳跃物理计算——采用 prismarine-physics（mineflayer 官方物理库，复刻 MC 原版）权威公式。
 *
 * <p><b>垂直顺序（关键）</b>：MC 每 tick 先位移后重力 —— y += vy; vy = (vy - 0.08) × 0.98，
 * 因此 vy0=0.42 的最大抬升 ≈1.25 格（可跳上 1 格高台），飞行 ≈12 tick。</p>
 *
 * <p><b>地面</b>：inertia = 0.6×0.91 = 0.546，加速度 = 速度属性，稳定位移速度 = 属性/0.454 ≈ 2.2×属性；
 * 起跳继承的"摩擦后 vel" = 属性×1.203。玩家 0.1 → 走 4.41 m/s、跑 5.73 m/s（实测一致）。</p>
 *
 * <p><b>空中</b>：水平 v = v×0.91 + 0.02（持续前进输入维持速度），落点由抛物线决定。</p>
 */
public final class JumpPhysics {

    public static final double GRAVITY = 0.08D;
    public static final double VERTICAL_DRAG = 0.98D;
    public static final double AIR_INERTIA = 0.91D;
    /** 空中持续前进输入的加速度（prismarine-physics airborneAcceleration） */
    public static final double AIR_ACCELERATION = 0.02D;
    public static final double JUMP_VY0 = 0.42D;

    /** 疾跑跳跃的水平冲量加成（jumpFromGround isSprinting 分支） */
    public static final double SPRINT_JUMP_BOOST = 0.2D;

    /** 与玩家一致的速度体系（26.2：玩家 MOVEMENT_SPEED=0.1） */
    public static final double PLAYER_MOVEMENT_SPEED = 0.1D;
    public static final double SPRINT_MULTIPLIER = 1.3D;

    /** 地面位移速度系数 1/(1-inertia) = 1/0.454 */
    public static final double GROUND_SPEED_FACTOR = 1.0D / (1.0D - 0.6D * AIR_INERTIA);
    /** 起跳继承的"摩擦后 vel"系数 inertia/(1-inertia) ≈ 1.203 */
    public static final double GROUND_VEL_FACTOR = (0.6D * AIR_INERTIA) / (1.0D - 0.6D * AIR_INERTIA);

    /**
     * 固定跳跃 takeoff 速度（blocks/tick）——能力与玩家一致，无需助跑。
     * 由 tools/gen_jump_table.js 离线标定：走 0.30 覆盖跨 1~2 沟；跑 0.55 覆盖跨 3 沟 + 上 1 格。
     */
    public static final double TAKEOFF_WALK = 0.30D;
    public static final double TAKEOFF_RUN = 0.55D;

    /** 每 tick 轨迹点：{水平位移, 垂直抬升} */
    public static final class TrajPoint {
        public final double x;
        public final double y;
        public final int tick;

        TrajPoint(double x, double y, int tick) {
            this.x = x;
            this.y = y;
            this.tick = tick;
        }
    }

    private static final int FLIGHT_TICKS = computeFlightTicks();

    private JumpPhysics() {
    }

    /** 起跳时继承的水平 vel（摩擦后）：1.203 × 属性（含疾跑） */
    public static double groundVelocity(double attributeValue, boolean sprint) {
        double v = attributeValue * (sprint ? SPRINT_MULTIPLIER : 1.0D);
        return v * GROUND_VEL_FACTOR;
    }

    /**
     * 起跳瞬间水平速度 = 固定档位值（走/跑），与地面速度/助跑无关。
     * 走 0.30 覆盖跨 1~2 格沟；跑 0.55 覆盖跨 3 格沟 + 上 1 格（离线解表标定）。
     */
    public static double takeoffSpeed(double attributeValue, boolean sprint) {
        return sprint ? TAKEOFF_RUN : TAKEOFF_WALK;
    }

    /** 起跳后在空中飞行的 tick 数（落地瞬间，y 回起跳高度） */
    public static int flightTicks() {
        return FLIGHT_TICKS;
    }

    /**
     * 前向模拟跳跃轨迹：从起跳点（相对位移 0,0）以水平速度 takeoffSpeed 抛出。
     * 垂直顺序与 MC 一致（位移先于重力）。空中可选持续前进输入（+0.02/tick 维持速度）。
     *
     * @return 每 tick 的 {水平位移, 垂直抬升}，到落地或 maxTicks 为止
     */
    public static TrajPoint[] simulateTrajectory(double takeoffSpeed, boolean airInput, int maxTicks) {
        double v = takeoffSpeed;
        double x = 0.0D;
        double y = 0.0D;
        double vy = JUMP_VY0;
        java.util.List<TrajPoint> points = new java.util.ArrayList<>();
        for (int t = 0; t < maxTicks; t++) {
            x += v;
            y += vy;
            points.add(new TrajPoint(x, y, t + 1));
            v = v * AIR_INERTIA + (airInput ? AIR_ACCELERATION : 0.0D);
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            if (y <= 0.0D && t > 0) {
                break;
            }
        }
        return points.toArray(new TrajPoint[0]);
    }

    private static int computeFlightTicks() {
        double y = 0.0D;
        double vy = JUMP_VY0;
        int t = 0;
        while (t < 200) {
            y += vy;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            t++;
            if (y <= 0.0D) {
                return t;
            }
        }
        return 12;
    }
}
