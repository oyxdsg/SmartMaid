package com.oyxdsg.smartmaid.entity.ai.combat;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 战斗威胁检测（盾牌触发用，见 DEVELOPMENT_COMBAT.md §5.4）。
 *
 * <ul>
 *     <li><b>箭矢飞来</b>：扫描附近箭矢，按当前速度外推 ~1.5s，判断是否命中女仆包围盒</li>
 *     <li><b>苦力怕将爆</b>：4 格内、正在膨胀且膨胀进度 &gt; 0.6</li>
 * </ul>
 *
 * <p>检测每 tick 执行（廉价，范围小）；举盾与否由 {@link MaidShieldSkill} 决定。</p>
 */
public final class MaidThreatDetector {

    private static final double ARROW_SCAN_RANGE = 16.0D;
    private static final int ARROW_PREDICT_TICKS = 30;
    private static final double ARROW_HITBOX_INFLATE = 0.8D;
    private static final double CREEPER_RANGE = 4.0D;
    private static final float CREEPER_SWELL_THRESHOLD = 0.6F;

    private MaidThreatDetector() {
    }

    /** 是否存在近在眼前的威胁（箭矢将命中 / 苦力怕将爆）。 */
    public static boolean threatened(SmartMaidEntity maid) {
        return !threatLabel(maid).isEmpty();
    }

    /** 当前威胁类型标签（无威胁返回空串）：{@code arrow} / {@code creeper}，盾牌日志用。 */
    public static String threatLabel(SmartMaidEntity maid) {
        if (arrowIncoming(maid)) {
            return "arrow";
        }
        if (creeperAboutToExplode(maid)) {
            return "creeper";
        }
        return "";
    }

    /** 是否有箭矢将命中女仆（排除自己射出的箭）。 */
    public static boolean arrowIncoming(SmartMaidEntity maid) {
        AABB box = maid.getBoundingBox().inflate(ARROW_HITBOX_INFLATE);
        for (AbstractArrow arrow : scanArrows(maid)) {
            if (firstHitTick(box, arrow) > 0) {
                return true;
            }
        }
        return false;
    }

    /** 女仆周围非自身射出的箭矢（诊断/威胁共用）。 */
    private static List<AbstractArrow> scanArrows(SmartMaidEntity maid) {
        return MaidActions.findEntities(maid, AbstractArrow.class, ARROW_SCAN_RANGE,
                a -> a.isAlive() && a.getOwner() != maid);
    }

    /** 按当前速度外推，返回多少 tick 后进入包围盒；不会命中返回 -1。 */
    private static int firstHitTick(AABB box, AbstractArrow arrow) {
        Vec3 pos = arrow.position();
        Vec3 vel = arrow.getDeltaMovement();
        if (vel.lengthSqr() < 1.0E-4D) {
            return -1;
        }
        for (int t = 1; t <= ARROW_PREDICT_TICKS; t++) {
            if (box.contains(pos.add(vel.scale(t)))) {
                return t;
            }
        }
        return -1;
    }

    /**
     * 战斗盾牌诊断（打骷髅排查用，每 10 tick 节流打印一次）：
     * 女仆朝向 / 举盾状态 + 周围每个箭矢实体的位置、速度、距女仆距离、射手与预测命中 tick。
     */
    public static void debug(SmartMaidEntity maid) {
        if (!MaidDebug.enabled() || maid.tickCount % 10 != 0) {
            return;
        }
        AABB box = maid.getBoundingBox().inflate(ARROW_HITBOX_INFLATE);
        List<AbstractArrow> arrows = scanArrows(maid);
        if (arrows.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("CombatShield threat=").append(threatLabel(maid).isEmpty() ? "-" : threatLabel(maid))
                .append(" hasShield=").append(MaidShieldSkill.hasShield(maid))
                .append(" blocking=").append(maid.isBlocking())
                .append(" yaw=").append(fmt(maid.getYRot()))
                .append(" headRot=").append(fmt(maid.getYHeadRot()));
        for (AbstractArrow a : arrows) {
            Vec3 p = a.position();
            Vec3 v = a.getDeltaMovement();
            Entity owner = a.getOwner();
            int hitIn = firstHitTick(box, a);
            sb.append(" | arrow#").append(a.getId())
                    .append(" type=").append(a.getType().toShortString())
                    .append(" pos=(").append(a.blockPosition().getX()).append(',')
                    .append(a.blockPosition().getY()).append(',')
                    .append(a.blockPosition().getZ()).append(')')
                    .append(" vel=(").append(fmt(v.x)).append(',').append(fmt(v.y)).append(',').append(fmt(v.z)).append(')')
                    .append(" dist=").append(fmt(a.distanceTo(maid)))
                    .append(" owner=").append(owner == null ? "?" : owner.getType().toShortString())
                    .append(" hitIn=").append(hitIn > 0 ? hitIn + "t" : "-");
        }
        MaidDebug.log(sb.toString());
    }

    /** 附近是否有即将爆炸的苦力怕。 */
    public static boolean creeperAboutToExplode(SmartMaidEntity maid) {
        return !MaidActions.findEntities(maid, Creeper.class, CREEPER_RANGE,
                c -> c.isAlive() && c.getSwellDir() > 0 && c.getSwelling(1.0F) > CREEPER_SWELL_THRESHOLD)
                .isEmpty();
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
