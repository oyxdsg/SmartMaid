package com.oyxdsg.smartmaid.entity.ai.perception.sense;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionUtil;
import com.oyxdsg.smartmaid.entity.ai.perception.Sense;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 主人感知通道：女仆的主人（玩家）状态——血量/饱食度/经验/buff/装备/游戏模式/活动状态。
 *
 * <p>interval 10（0.5s）。区块 {@code owner}：</p>
 * <ul>
 *     <li>身份：name / uuid / pos / dist（与女仆距离）</li>
 *     <li>生命：health / max_health / armor_value</li>
 *     <li>饥饿：food（饱食度）/ saturation（饱和度）</li>
 *     <li>经验：xp_level / total_xp</li>
 *     <li>buff：effects[]</li>
 *     <li>装备：equipment{}（主/副手+头胸腿脚）</li>
 *     <li>状态：gamemode / on_ground / swimming / sleeping / blocking</li>
 *     <li>救援：hurt_recently（最近是否受伤，用于救援决策）</li>
 * </ul>
 */
public class OwnerSense implements Sense {

    @Override
    public int intervalTicks() {
        return 10;
    }

    @Override
    public String sectionName() {
        return "owner";
    }

    @Override
    public void collect(SmartMaidEntity maid, MaidPerception data) {
        LivingEntity owner = maid.getOwner();
        if (!(owner instanceof Player player)) {
            return; // 无主人 → 保持空对象
        }
        JsonObject ownerObj = data.owner();
        ownerObj.addProperty("name", player.getName().getString());
        ownerObj.addProperty("uuid", player.getUUID().toString());
        // 主人方块坐标：桌宠侧解析「在我脚下放方块 / 到我这儿来」这类相对指令需要，
        // 只有 dist 不足以定位（模组侧 move/place 都要绝对坐标）。
        PerceptionUtil.blockPos(ownerObj, player.blockPosition());
        PerceptionUtil.pos(ownerObj, player.position());
        ownerObj.addProperty("dist", Math.round(maid.distanceTo(player) * 10.0D) / 10.0D);

        ownerObj.addProperty("health", player.getHealth());
        ownerObj.addProperty("max_health", player.getMaxHealth());
        ownerObj.addProperty("armor_value", player.getArmorValue());
        ownerObj.addProperty("food", player.getFoodData().getFoodLevel());
        ownerObj.addProperty("saturation", player.getFoodData().getSaturationLevel());
        ownerObj.addProperty("xp_level", player.experienceLevel);
        ownerObj.addProperty("total_xp", player.totalExperience);

        JsonArray effects = new JsonArray();
        PerceptionUtil.writeEffects(effects, player.getActiveEffects());
        ownerObj.add("effects", effects);

        JsonObject equipment = new JsonObject();
        writeEquip(equipment, "mainhand", player);
        writeEquip(equipment, "offhand", player);
        writeEquip(equipment, "head", player);
        writeEquip(equipment, "chest", player);
        writeEquip(equipment, "legs", player);
        writeEquip(equipment, "feet", player);
        ownerObj.add("equipment", equipment);

        ownerObj.addProperty("gamemode", player.gameMode().getName());
        ownerObj.addProperty("on_ground", player.onGround());
        ownerObj.addProperty("swimming", player.isSwimming());
        ownerObj.addProperty("sleeping", player.isSleeping());
        ownerObj.addProperty("blocking", player.isBlocking());
        ownerObj.addProperty("hurt_recently", player.getLastHurtByMobTimestamp() > 0);
    }

    private static void writeEquip(JsonObject equipment, String key, Player player) {
        JsonObject o = new JsonObject();
        equipment.add(key, o);
        PerceptionUtil.writeItem(o, switch (key) {
            case "mainhand" -> player.getMainHandItem();
            case "offhand" -> player.getOffhandItem();
            case "head" -> player.getItemBySlot(EquipmentSlot.HEAD);
            case "chest" -> player.getItemBySlot(EquipmentSlot.CHEST);
            case "legs" -> player.getItemBySlot(EquipmentSlot.LEGS);
            default -> player.getItemBySlot(EquipmentSlot.FEET);
        }, true);
    }
}
