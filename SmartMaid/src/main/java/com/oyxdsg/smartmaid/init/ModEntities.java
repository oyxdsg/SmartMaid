package com.oyxdsg.smartmaid.init;

import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {

    public static EntityType<SmartMaidEntity> SMART_MAID;

    private ModEntities() {
    }

    public static void register() {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "smart_maid"));
        SMART_MAID = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                EntityType.Builder.of(SmartMaidEntity::new, MobCategory.CREATURE)
                        .sized(0.6F, 1.8F)
                        .noSave()
                        .build(key));
        FabricDefaultAttributeRegistry.register(SMART_MAID, SmartMaidEntity.createAttributes());
    }
}
