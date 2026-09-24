package com.oyxdsg.smartmaid.entity.ai.perception.sense;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionUtil;
import com.oyxdsg.smartmaid.entity.ai.perception.Sense;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

import java.util.Comparator;
import java.util.List;

/**
 * 实体感知通道：女仆近程（16 格）内的敌对生物 / 动物 / 玩家 / 掉落物。
 *
 * <p>interval 10（0.5s）。区块 {@code nearby}，每类按距离排序并截断
 * {@value #MAX_PER_CATEGORY} 个，避免快照过大。</p>
 *
 * <ul>
 *     <li>hostiles[]：敌对生物（复用 Monster+canAttack 判定），含 targeting_me（是否正攻击女仆）/ 视线</li>
 *     <li>animals[]：可繁殖/友好生物（AgeableMob）</li>
 *     <li>players[]：其他玩家（不含主人）</li>
 *     <li>items[]：掉落物（物品摘要）</li>
 * </ul>
 */
public class EntitySense implements Sense {

    /** 每类实体截断数量 */
    private static final int MAX_PER_CATEGORY = 8;
    /** 近程感知范围（格） */
    private static final int RANGE = 16;

    @Override
    public int intervalTicks() {
        return 10;
    }

    @Override
    public String sectionName() {
        return "nearby";
    }

    @Override
    public void collect(SmartMaidEntity maid, MaidPerception data) {
        JsonObject nearby = data.nearby();

        // 一次查询所有活体实体，再按类型分类（避免多次 AABB 扫描）
        List<LivingEntity> living = MaidActions.findEntities(maid, LivingEntity.class, RANGE,
                e -> e.isAlive() && !(e instanceof SmartMaidEntity));
        living.sort(Comparator.comparingDouble(maid::distanceToSqr));

        JsonArray hostiles = new JsonArray();
        JsonArray animals = new JsonArray();
        JsonArray players = new JsonArray();
        Player owner = maid.getOwner() instanceof Player op ? op : null;
        int[] counters = {0, 0, 0};
        for (LivingEntity e : living) {
            if (e instanceof Monster && counters[0] < MAX_PER_CATEGORY) {
                hostiles.add(hostileEntry(maid, e));
                counters[0]++;
            } else if (e instanceof AgeableMob && counters[1] < MAX_PER_CATEGORY) {
                animals.add(animalEntry(maid, e));
                counters[1]++;
            } else if (e instanceof Player p && p != owner && counters[2] < MAX_PER_CATEGORY) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getName().getString());
                PerceptionUtil.pos(o, p.position());
                o.addProperty("dist", round(maid.distanceTo(p)));
                players.add(o);
                counters[2]++;
            }
        }
        nearby.add("hostiles", hostiles);
        nearby.add("animals", animals);
        nearby.add("players", players);

        // 掉落物单独查询
        JsonArray items = new JsonArray();
        List<ItemEntity> itemList = MaidActions.findEntities(maid, ItemEntity.class, RANGE, Entity::isAlive);
        itemList.sort(Comparator.comparingDouble(maid::distanceToSqr));
        for (int i = 0; i < Math.min(MAX_PER_CATEGORY, itemList.size()); i++) {
            ItemEntity e = itemList.get(i);
            JsonObject o = new JsonObject();
            PerceptionUtil.writeItem(o, e.getItem(), false);
            PerceptionUtil.pos(o, e.position());
            o.addProperty("dist", round(maid.distanceTo(e)));
            items.add(o);
        }
        nearby.add("items", items);
    }

    private static JsonObject hostileEntry(SmartMaidEntity maid, LivingEntity e) {
        JsonObject o = new JsonObject();
        o.addProperty("type", e.getType().toShortString());
        PerceptionUtil.pos(o, e.position());
        o.addProperty("dist", round(maid.distanceTo(e)));
        o.addProperty("health", e.getHealth());
        o.addProperty("max_health", e.getMaxHealth());
        o.addProperty("targeting_me", e instanceof Mob mob && mob.getTarget() == maid);
        o.addProperty("has_los", maid.hasLineOfSight(e));
        return o;
    }

    private static JsonObject animalEntry(SmartMaidEntity maid, LivingEntity e) {
        JsonObject o = new JsonObject();
        o.addProperty("type", e.getType().toShortString());
        PerceptionUtil.pos(o, e.position());
        o.addProperty("dist", round(maid.distanceTo(e)));
        o.addProperty("health", e.getHealth());
        o.addProperty("baby", e.isBaby());
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 10.0D) / 10.0D;
    }
}
