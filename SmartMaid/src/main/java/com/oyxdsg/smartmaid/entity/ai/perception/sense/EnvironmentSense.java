package com.oyxdsg.smartmaid.entity.ai.perception.sense;

import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception;
import com.oyxdsg.smartmaid.entity.ai.perception.Sense;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 环境感知通道：维度/生物群系/昼夜/天气/光照/区块加载状态。
 *
 * <p>interval 40（2s）。区块 {@code env}。昼夜用 26.2 新时钟系统：
 * {@code getOverworldClockTime() % 24000}（0=日出 / 6000=正午 / 12000=日落 / 18000=午夜，
 * {@code is_day = timeOfDay < 13000}）。</p>
 */
public class EnvironmentSense implements Sense {

    @Override
    public int intervalTicks() {
        return 40;
    }

    @Override
    public String sectionName() {
        return "env";
    }

    @Override
    public void collect(SmartMaidEntity maid, MaidPerception data) {
        Level level = maid.level();
        JsonObject env = data.env();
        BlockPos pos = maid.blockPosition();

        env.addProperty("biome", level.getBiome(pos).unwrapKey()
                .map(k -> k.identifier().toString()).orElse("?"));

        // 昼夜（26.2 新时钟系统）
        long dayTime = Math.floorMod(level.getOverworldClockTime(), 24000L);
        env.addProperty("time_of_day", dayTime);
        env.addProperty("is_day", dayTime < 13000L);

        // 天气
        String weather = level.isThundering() ? "thunder" : (level.isRaining() ? "rain" : "clear");
        env.addProperty("weather", weather);
        env.addProperty("rain_level", Math.round(level.getRainLevel(1.0F) * 100.0F) / 100.0F);
        env.addProperty("thunder_level", Math.round(level.getThunderLevel(1.0F) * 100.0F) / 100.0F);

        env.addProperty("light_level", level.getMaxLocalRawBrightness(pos));
        env.addProperty("height", level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()));
        env.addProperty("min_y", level.getMinY());
        env.addProperty("max_y", level.getMaxY());

        // 区块
        JsonObject chunk = new JsonObject();
        ChunkPos cp = level.getChunkAt(pos).getPos();
        chunk.addProperty("cx", cp.x());
        chunk.addProperty("cz", cp.z());
        chunk.addProperty("loaded", level.hasChunkAt(pos));
        env.add("chunk", chunk);

        if (level instanceof ServerLevel serverLevel) {
            env.addProperty("chunks_loaded", serverLevel.getChunkSource().getLoadedChunksCount());
        }
    }
}
