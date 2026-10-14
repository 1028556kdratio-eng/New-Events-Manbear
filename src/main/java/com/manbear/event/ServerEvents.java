package com.manbear.event;

import com.manbear.ManbearConfig;
import com.manbear.ai.CoverFinder;
import com.manbear.ai.FleeManbearGoal;
import com.manbear.command.ManbearCommand;
import com.manbear.data.ManbearData;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.Stance;
import com.manbear.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.VanillaGameEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ServerEvents {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.overworld().getGameTime();
        if (now % 10 == 0) sampleProfiles(server, now);
        if (now > 0 && now % ManbearConfig.SPAWN_INTERVAL_TICKS.get() == 0) spawnRoll(server, now);
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        ManbearCommand.register(e.getDispatcher());
    }

    /** every animal learns to be afraid of him */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent e) {
        if (!e.getLevel().isClientSide() && e.getEntity() instanceof Animal a) {
            a.goalSelector.addGoal(0, new FleeManbearGoal(a));
        }
    }

    /** vibrations (like the Warden hears) can wake a sleeping Manbear */
    @SubscribeEvent
    public void onGameEvent(VanillaGameEvent ev) {
        ManbearEntity b = ManbearEntity.current();
        if (b == null || !b.isAsleep() || ev.getLevel() != b.level() || ev.getCause() == b) return;
        double d = ev.getEventPosition().distanceTo(b.position());
        GameEvent ge = ev.getVanillaEvent();
        float amt = 0F;
        if (ge == GameEvent.STEP) amt = d < 16 ? 0.8F * (1F - (float) d / 16F) : 0F;
        else if (ge == GameEvent.HIT_GROUND) amt = d < 24 ? 2.0F * (1F - (float) d / 24F) : 0F;
        else if (ge == GameEvent.BLOCK_DESTROY || ge == GameEvent.BLOCK_PLACE) amt = d < 24 ? 3.0F * (1F - (float) d / 24F) : 0F;
        else if (ge == GameEvent.EXPLODE) amt = d < 64 ? 30F : 0F;
        else if (ge == GameEvent.PROJECTILE_LAND || ge == GameEvent.SPLASH) amt = d < 20 ? 1.5F * (1F - (float) d / 20F) : 0F;
        else if (ge == GameEvent.ENTITY_DAMAGE) amt = d < 24 ? 4.0F * (1F - (float) d / 24F) : 0F;
        if (amt > 0F) b.hearNoise(amt);
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && e.getSource().getEntity() instanceof ManbearEntity m) {
            m.onKilledPlayer(p);
        }
    }

    // ------------------------------------------------------------------ profiles
    private void sampleProfiles(MinecraftServer server, long now) {
        ManbearData data = ManbearData.get(server);
        Entity bear = findActive(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            data.profile(p.getUUID()).sample(p, now, bear);
        }
        if (now % 600 == 0) data.setDirty();
    }

    public static ManbearEntity findActive(MinecraftServer server) {
        UUID id = ManbearData.get(server).activeId();
        if (id == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e instanceof ManbearEntity m) return m;
        }
        return null;
    }

    // ------------------------------------------------------------------ spawning (1/25 per minute, only one ever)
    private void spawnRoll(MinecraftServer server, long now) {
        ManbearData data = ManbearData.get(server);
        if (data.isActive(now) || data.onCooldown(now)) return;
        if (server.overworld().getDifficulty() == Difficulty.PEACEFUL) return;
        RandomSource rnd = server.overworld().random;
        if (rnd.nextInt(ManbearConfig.SPAWN_CHANCE_DENOMINATOR.get()) != 0) return;

        List<? extends String> dims = ManbearConfig.DIMENSIONS.get();
        List<ServerPlayer> candidates = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            if (!dims.contains(p.level().dimension().location().toString())) continue;
            candidates.add(p);
        }
        if (candidates.isEmpty()) return;
        spawnNear(server, candidates.get(rnd.nextInt(candidates.size())));
    }

    public static ManbearEntity spawnNear(MinecraftServer server, ServerPlayer p) {
        return spawnNear(server, p, null);
    }

    /** Spawns him out of sight. kind (optional, for commands): chaser | faker | trapper | sleeper. */
    public static ManbearEntity spawnNear(MinecraftServer server, ServerPlayer p, String kind) {
        ServerLevel level = p.serverLevel();
        RandomSource rnd = level.random;
        ManbearEntity e = ModEntities.MANBEAR.get().create(level);
        if (e == null) return null;
        if (kind != null) e.applyKind(kind);
        int min = ManbearConfig.SPAWN_MIN_DIST.get();
        int max = Math.max(min + 1, ManbearConfig.SPAWN_MAX_DIST.get());
        if (e.isSleeper()) { min = 22; max = 40; }   // closer, so you can actually stumble on him
        if (e.isBehind()) {                      // directly behind the player, out of their view
            Vec3 look = p.getLookAngle();
            double base = Math.atan2(-look.z, -look.x);
            for (int i = 0; i < 40; i++) {
                double ang = base + (rnd.nextDouble() - 0.5) * 0.9;
                double d = 11 + rnd.nextDouble() * 5;
                int x = (int) Math.floor(p.getX() + Math.cos(ang) * d);
                int z = (int) Math.floor(p.getZ() + Math.sin(ang) * d);
                if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;
                BlockPos g = CoverFinder.groundNear(level, x + 0.5, z + 0.5, p.getBlockY(), 5);
                if (g == null || Math.abs(g.getY() - p.getBlockY()) > 3) continue;
                Vec3 v = Vec3.atBottomCenterOf(g);
                Vec3 to = new Vec3(v.x - p.getX(), 0, v.z - p.getZ()).normalize();
                if (new Vec3(look.x, 0, look.z).normalize().dot(to) > -0.3) continue; // must not be in front of them
                if (!level.noCollision(Stance.STAND.dims.makeBoundingBox(v))) continue;
                e.moveTo(v.x, v.y, v.z, 0F, 0F);
                level.addFreshEntity(e);
                ManbearData.get(server).claim(e.getUUID(), server.overworld().getGameTime());
                return e;
            }
            return null;
        }
        for (int i = 0; i < 60; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = min + rnd.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * d);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;

            BlockPos g;
            if (level.canSeeSky(p.blockPosition())) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                g = new BlockPos(x, y, z);
                if (!CoverFinder.standable(level, g, e.isSleeper() ? 2 : 5)) continue;
            } else {
                g = CoverFinder.groundNear(level, x + 0.5, z + 0.5, p.getBlockY(), e.isSleeper() ? 2 : 5);
                if (g == null) continue;
            }
            Vec3 v = Vec3.atBottomCenterOf(g);
            if (seenByAnyPlayer(level, v)) continue;
            if (!level.noCollision((e.isSleeper() ? Stance.SLEEP : Stance.STAND).dims.makeBoundingBox(v))) continue;

            e.moveTo(v.x, v.y, v.z, rnd.nextFloat() * 360F, 0F);
            level.addFreshEntity(e);
            ManbearData.get(server).claim(e.getUUID(), server.overworld().getGameTime());
            return e;
        }
        return null;
    }

    private static boolean seenByAnyPlayer(ServerLevel level, Vec3 v) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(v) < 60 * 60 && CoverFinder.lineClear(level, p.getEyePosition(), v.add(0, 1.5, 0), p)) return true;
        }
        return false;
    }
}
