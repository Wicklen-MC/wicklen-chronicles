package dev.redzumi.spirit.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Дух-компаньон: летающий неуязвимый светлячок, кружит вокруг владельца.
 * Скелет v0.1: полёт-орбита вокруг владельца, владелец по имени, чат-триггеры далее.
 */
public class SpiritEntity extends PathfinderMob {
    private static final double ORBIT_RADIUS = 1.8;
    private static final double ORBIT_ANGULAR = 0.06; // рад/тик

    private double orbitAngle = Math.random() * Math.PI * 2;
    private double targetYOffset = 1.6;

    /** Владелец по имени игрока (v0.1; позже — UUID + attachment). */
    private String ownerName = "";

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
    }

    public String ownerName() {
        return ownerName;
    }

    /** UUID владельца (по текущему списку игроков); null, если не в игре. */
    public java.util.UUID getOwnerUuid() {
        if (!(level() instanceof ServerLevel sl)) return null;
        var p = sl.getServer().getPlayerList().getPlayerByName(ownerName);
        return p == null ? null : p.getUUID();
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() || ownerName.isEmpty()) return;
        if (!(level() instanceof ServerLevel serverLevel)) return;

        Player owner = serverLevel.getServer().getPlayerList().getPlayerByName(ownerName);
        if (owner == null) return; // владелец не в игре — стоим

        // орбита вокруг владельца + лёгкий боббинг по Y
        orbitAngle = (orbitAngle + ORBIT_ANGULAR) % (Math.PI * 2);
        double bobbing = Math.sin((tickCount + getId()) * 0.08) * 0.25;
        double tx = owner.getX() + Math.cos(orbitAngle) * ORBIT_RADIUS;
        double ty = owner.getY() + targetYOffset + bobbing;
        double tz = owner.getZ() + Math.sin(orbitAngle) * ORBIT_RADIUS;

        // цель − позиция, сглаживание + кламп скорости (идея flySpirit из Spirit Companions)
        double vx = clamp((tx - getX()) * 0.15, 0.35);
        double vy = clamp((ty - getY()) * 0.10, 0.35);
        double vz = clamp((tz - getZ()) * 0.15, 0.35);
        setDeltaMovement(vx, vy, vz);
        if (vx * vx + vz * vz > 1.0e-4) {
            setYRot((float) (Math.atan2(vz, vx) * 180.0 / Math.PI) - 90.0f);
        }
    }

    private static double clamp(double v, double max) {
        return Math.max(-max, Math.min(max, v));
    }

    // --- дух неуязвим и не взаимодействует ---

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
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput in) {
        super.readAdditionalSaveData(in);
        ownerName = in.getStringOr("Owner", "");
    }
}
