package dev.wicklen.chronicles.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.particles.DustParticleOptions;
import java.util.UUID;

/**
 * Дух-компаньон: летающий неуязвимый светлячок, кружит вокруг владельца.
 * Медленная орбита вокруг владельца с восстановлением после телепорта.
 */
public class SpiritEntity extends PathfinderMob {
    public static final double ORBIT_RADIUS = 3.1;
    private static final double ORBIT_ANGULAR = 0.012; // one slow circle in ~26 seconds
    private static final EntityDataAccessor<Integer> COLOR =
        SynchedEntityData.defineId(SpiritEntity.class, EntityDataSerializers.INT);

    private double orbitAngle = Math.random() * Math.PI * 2;
    private double targetYOffset = 1.6;

    /** Name is kept for migrating older saves; UUID is the owner's identity. */
    private String ownerName = "";
    private UUID ownerId;

    public SpiritEntity(EntityType<? extends SpiritEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setPersistenceRequired();
        this.noPhysics = true; // сквозь стены
        this.setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.3);
    }

    public void setOwner(Player player) {
        this.ownerName = player.getScoreboardName();
        this.ownerId = player.getUUID();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(COLOR, 0xFFE97F);
    }

    public int spiritColor() { return entityData.get(COLOR); }
    public void setSpiritColor(int color) { entityData.set(COLOR, color & 0xFFFFFF); }

    @Override
    protected net.minecraft.network.chat.Component getTypeName() {
        // A companion loaded through the legacy entity adapter still uses the
        // current translation, without keeping obsolete resource keys alive.
        return net.minecraft.network.chat.Component.translatable("entity.wicklen_chronicles.spirit");
    }

    public String ownerName() {
        return ownerName;
    }

    /** UUID владельца (по текущему списку игроков); null, если не в игре. */
    public java.util.UUID getOwnerUuid() {
        if (ownerId != null) return ownerId;
        if (!(level() instanceof ServerLevel sl)) return null;
        var p = sl.getServer().getPlayerList().getPlayerByName(ownerName);
        if (p != null) ownerId = p.getUUID(); // migrate old name-only saves
        return ownerId;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            if (tickCount % 5 == 0) {
                level().addParticle(new DustParticleOptions(spiritColor(), 0.65f),
                    getX() + (random.nextDouble() - 0.5) * 0.3, getY() + 0.15,
                    getZ() + (random.nextDouble() - 0.5) * 0.3, 0, 0.01, 0);
            }
            return;
        }
        if (!(level() instanceof ServerLevel serverLevel)) return;

        UUID id = getOwnerUuid();
        Player owner = id == null ? null : serverLevel.getServer().getPlayerList().getPlayer(id);
        if (owner == null || owner.level() != level()
                || dev.wicklen.chronicles.ai.SpiritPrefs.isHidden(id)) {
            discard();
            return;
        }

        // орбита вокруг владельца + лёгкий боббинг по Y
        orbitAngle = (orbitAngle + ORBIT_ANGULAR) % (Math.PI * 2);
        double bobbing = Math.sin((tickCount + getId()) * 0.08) * 0.25;
        double tx = owner.getX() + Math.cos(orbitAngle) * ORBIT_RADIUS;
        double ty = owner.getY() + targetYOffset + bobbing;
        double tz = owner.getZ() + Math.sin(orbitAngle) * ORBIT_RADIUS;

        if (distanceToSqr(owner) > 16 * 16) {
            snapTo(tx, ty, tz);
            setDeltaMovement(0, 0, 0);
            return;
        }

        // цель − позиция, сглаживание + кламп скорости (идея flySpirit из Spirit Companions)
        double vx = clamp((tx - getX()) * 0.15, 0.6);
        double vy = clamp((ty - getY()) * 0.10, 0.6);
        double vz = clamp((tz - getZ()) * 0.15, 0.6);
        setDeltaMovement(vx, vy, vz);
        if (vx * vx + vz * vz > 1.0e-4) {
            setYRot((float) (Math.atan2(vz, vx) * 180.0 / Math.PI) - 90.0f);
            setYBodyRot(getYRot());
            setYHeadRot(getYRot());
        }
    }

    private static double clamp(double v, double max) {
        return Math.max(-max, Math.min(max, v));
    }

    // --- дух неуязвим и не взаимодействует ---

    @Override
    public boolean isSilent() {
        return true;
    }

    @Override
    protected MovementEmission getMovementEmission() {
        return MovementEmission.NONE;
    }

    @Override
    protected boolean isAffectedByBlocks() {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return true;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean hurtClient(DamageSource source) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {}

    @Override
    public void checkDespawn() {} // никогда не деспавнится

    // --- сохранение ---

    @Override
    protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putString("Owner", ownerName);
        if (ownerId != null) out.putString("OwnerUuid", ownerId.toString());
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput in) {
        super.readAdditionalSaveData(in);
        ownerName = in.getStringOr("Owner", "");
        try { ownerId = UUID.fromString(in.getStringOr("OwnerUuid", "")); }
        catch (IllegalArgumentException ignored) { ownerId = null; }
    }
}
