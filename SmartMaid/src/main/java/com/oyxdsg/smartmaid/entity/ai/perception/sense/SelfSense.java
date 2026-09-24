package com.oyxdsg.smartmaid.entity.ai.perception.sense;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionUtil;
import com.oyxdsg.smartmaid.entity.ai.perception.Sense;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

/**
 * 自身感知通道：女仆自己的状态、装备、属性、安全、AI 状态与当前动作。
 *
 * <p>interval 5（0.25s）。区块 {@code self}：</p>
 * <ul>
 *     <li>基础：pos / health / max_health / air / fire_ticks / on_ground / velocity / yaw / pitch / pose /
 *         armor_value / absorption / effects / sprinting / swimming / sleeping / blocking</li>
 *     <li>safety：in_water / in_lava / on_fire / under_water / feet(分类+id) / below(分类+id)</li>
 *     <li>equipment：mainhand / offhand / head / chest / legs / feet（物品摘要）</li>
 *     <li>attributes：movement_speed / attack_damage / follow_range</li>
 *     <li>status：ai_busy / task_id / sitting / target(type+pos)</li>
 *     <li>action：moving / jumping</li>
 * </ul>
 */
public class SelfSense implements Sense {

    @Override
    public int intervalTicks() {
        return 5;
    }

    @Override
    public String sectionName() {
        return "self";
    }

    @Override
    public void collect(SmartMaidEntity maid, MaidPerception data) {
        JsonObject self = data.self();
        PerceptionUtil.pos(self, maid.position());
        self.addProperty("health", maid.getHealth());
        self.addProperty("max_health", maid.getMaxHealth());
        self.addProperty("air", maid.getAirSupply());
        self.addProperty("fire_ticks", maid.getRemainingFireTicks());
        self.addProperty("on_ground", maid.onGround());
        JsonObject velocity = new JsonObject();
        PerceptionUtil.pos(velocity, maid.getDeltaMovement());
        self.add("velocity", velocity);
        self.addProperty("yaw", Math.round(maid.getYRot() * 10.0F) / 10.0F);
        self.addProperty("pitch", Math.round(maid.getXRot() * 10.0F) / 10.0F);
        self.addProperty("pose", maid.getPose().name().toLowerCase());
        self.addProperty("armor_value", maid.getArmorValue());
        self.addProperty("absorption", maid.getAbsorptionAmount());
        self.addProperty("sprinting", maid.isSprinting());
        self.addProperty("swimming", maid.isSwimming());
        self.addProperty("sleeping", maid.isSleeping());
        self.addProperty("blocking", maid.isBlocking());

        JsonArray effects = new JsonArray();
        PerceptionUtil.writeEffects(effects, maid.getActiveEffects());
        self.add("effects", effects);

        // 安全感知
        JsonObject safety = new JsonObject();
        safety.addProperty("in_water", maid.isInWater());
        safety.addProperty("in_lava", maid.isInLava());
        safety.addProperty("on_fire", maid.isOnFire());
        safety.addProperty("under_water", maid.isUnderWater());
        BlockPos feetPos = maid.blockPosition();
        safety.addProperty("feet", PerceptionUtil.classifyBlock(maid.level(), feetPos));
        safety.addProperty("feet_id", PerceptionUtil.blockId(maid.level().getBlockState(feetPos)));
        safety.addProperty("below", PerceptionUtil.classifyBlock(maid.level(), feetPos.below()));
        safety.addProperty("below_id", PerceptionUtil.blockId(maid.level().getBlockState(feetPos.below())));
        self.add("safety", safety);

        // 装备（女仆=玩家：主手=热键0，副手任意）
        JsonObject equipment = new JsonObject();
        PerceptionUtil.writeItem(equipmentEntry(equipment, "mainhand"), maid.getMainHandItem(), true);
        PerceptionUtil.writeItem(equipmentEntry(equipment, "offhand"), maid.getOffhandItem(), true);
        PerceptionUtil.writeItem(equipmentEntry(equipment, "head"), maid.getItemBySlot(EquipmentSlot.HEAD), true);
        PerceptionUtil.writeItem(equipmentEntry(equipment, "chest"), maid.getItemBySlot(EquipmentSlot.CHEST), true);
        PerceptionUtil.writeItem(equipmentEntry(equipment, "legs"), maid.getItemBySlot(EquipmentSlot.LEGS), true);
        PerceptionUtil.writeItem(equipmentEntry(equipment, "feet"), maid.getItemBySlot(EquipmentSlot.FEET), true);
        self.add("equipment", equipment);

        // 属性
        JsonObject attributes = new JsonObject();
        attributes.addProperty("movement_speed", round(maid.getAttributeValue(Attributes.MOVEMENT_SPEED)));
        attributes.addProperty("attack_damage", round(maid.getAttributeValue(Attributes.ATTACK_DAMAGE)));
        attributes.addProperty("follow_range", round(maid.getAttributeValue(Attributes.FOLLOW_RANGE)));
        self.add("attributes", attributes);

        // AI 状态
        JsonObject status = new JsonObject();
        status.addProperty("ai_busy", maid.isAiBusy());
        status.addProperty("combat", maid.isCombatActive());
        status.addProperty("task_id", maid.getMaidTaskManager().currentTask() == null
                ? null : maid.getMaidTaskManager().currentTask().taskId());
        status.addProperty("sitting", maid.isOrderedToSit());
        LivingEntity target = maid.getTarget();
        if (target != null) {
            JsonObject t = new JsonObject();
            t.addProperty("type", target.getType().toShortString());
            PerceptionUtil.blockPos(t, target.blockPosition());
            status.add("target", t);
        }
        self.add("status", status);

        // 当前动作
        JsonObject action = new JsonObject();
        action.addProperty("moving", maid.getNavigation().isInProgress());
        action.addProperty("jumping", !maid.onGround() && maid.getDeltaMovement().y > 0.05D
                && !maid.isInWater() && !maid.isInLava());
        self.add("action", action);
    }

    private static JsonObject equipmentEntry(JsonObject equipment, String key) {
        JsonObject o = new JsonObject();
        equipment.add(key, o);
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }
}
