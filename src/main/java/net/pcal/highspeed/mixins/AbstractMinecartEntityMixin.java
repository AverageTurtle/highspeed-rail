package net.pcal.highspeed.mixins;

import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.RailShape;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.pcal.highspeed.HighspeedClientService;
import net.pcal.highspeed.HighspeedService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(AbstractMinecartEntity.class)
public abstract class AbstractMinecartEntityMixin extends Entity {

    private static final double VANILLA_MAX_SPEED = 8.0 / 20.0;
    private static final double SQRT_TWO = 1.414213;

    private BlockPos lastPos = null;
    private double maxSpeed = VANILLA_MAX_SPEED;
    private double lastMaxSpeed = VANILLA_MAX_SPEED;
    private Vec3d lastSpeedPos = null;
    private long lastSpeedTime = 0;
    private final AbstractMinecartEntity minecart = (AbstractMinecartEntity) (Object) this;

    public AbstractMinecartEntityMixin(EntityType<?> type, World world) {
        super(type, world);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    public void tick(CallbackInfo ci) {
        updateSpeedometer();
        clampVelocity();
    }

    @Inject(
            method = "moveOnRail",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/vehicle/AbstractMinecartEntity;applySlowdown()V", shift = At.Shift.BEFORE),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    public void fixVelocityLoss(BlockPos previousPos, BlockState state, CallbackInfo ci, double d, double e, double f, Vec3d vec3d, boolean bl, boolean bl2, double g, Vec3d previousVelocity, RailShape prevPosRailShape) {
        if (minecart.getBlockPos().equals(previousPos)) {
            return;
        }
        boolean hasHitWall = false;
        Vec3d velocity = minecart.getVelocity();
        if (velocity.x == 0 && Math.abs(previousVelocity.x) > 0.5) {
            velocity = velocity.withAxis(Direction.Axis.X, previousVelocity.x * this.getVelocityMultiplier());
            hasHitWall = true;
        }
        if (velocity.z == 0 && Math.abs(previousVelocity.z) > 0.5) {
            velocity = velocity.withAxis(Direction.Axis.Z, previousVelocity.z * this.getVelocityMultiplier());
            hasHitWall = true;
        }
        if (!hasHitWall) {
            return;
        }
        BlockState blockState = minecart.getWorld().getBlockState(minecart.getBlockPos());
        if (blockState.isOf(Blocks.RAIL)) {
            minecart.setVelocity(velocity);
        }
    }

    @Redirect(method = "moveOnRail", at = @At(value = "INVOKE", ordinal = 0, target = "java/lang/Math.min(DD)D"))
    public double speedClamp(double d1, double d2) {
        final double maxSpeed = getModifiedMaxSpeed();
        return maxSpeed == VANILLA_MAX_SPEED ? Math.min(d1, d2) // i.e. preserve vanilla behavior
                : Math.min(maxSpeed * SQRT_TWO, d2);
    }

    @Inject(method = "getMaxSpeed", at = @At("HEAD"), cancellable = true)
    protected void getMaxSpeed(CallbackInfoReturnable<Double> cir) {
        final double maxSpeed = getModifiedMaxSpeed();
        if (maxSpeed != VANILLA_MAX_SPEED) {
            cir.setReturnValue(maxSpeed);
        }
    }

    //final static int maxDepth = 2;
    private double getModifiedMaxSpeed() {
        final BlockPos currentPos = minecart.getBlockPos();
        if (currentPos.equals(lastPos)) return maxSpeed;
        lastPos = currentPos;

        BlockPos railPos = currentPos;
        boolean isRail = minecart.getWorld().getBlockState(railPos).getBlock() instanceof AbstractRailBlock;
        if (!isRail) {
            railPos = currentPos.down();
            isRail = minecart.getWorld().getBlockState(railPos).getBlock() instanceof AbstractRailBlock;
        }
        if(isRail) {
            final BlockState blockUnder = minecart.getWorld().getBlockState(railPos.down());
            final Identifier blockUnderId = Registries.BLOCK.getId(blockUnder.getBlock());
            final Integer cartSpeedBps = HighspeedService.getInstance().getCartSpeed(blockUnderId);
            if (cartSpeedBps != null)
                return maxSpeed = cartSpeedBps / 20.0;
            else
                return maxSpeed = VANILLA_MAX_SPEED;
        } else
            return maxSpeed = VANILLA_MAX_SPEED;
        /* final BlockPos currentPos = minecart.getBlockPos();
        if (currentPos.equals(lastPos)) return maxSpeed;
        lastPos = currentPos;
        // look at the *next* block the cart is going to hit
        final Vec3d v = minecart.getVelocity();
        final BlockPos nextPos = new BlockPos(
                currentPos.getX() + MathHelper.sign(v.getX()),
                currentPos.getY(),
                currentPos.getZ() + MathHelper.sign(v.getZ())
        );
        final BlockState nextState = minecart.getWorld().getBlockState(nextPos);
        if (nextState.getBlock() instanceof AbstractRailBlock rail) {
            //final RailShape shape = nextState.get(rail.getShapeProperty());
            //if (shape == RailShape.NORTH_EAST || shape == RailShape.NORTH_WEST || shape == RailShape.SOUTH_EAST || shape == RailShape.SOUTH_WEST) {
            //    return maxSpeed = VANILLA_MAX_SPEED;
            //} else {

            final BlockState underState = minecart.getWorld().getBlockState(currentPos.down());
            final Identifier underBlockId = Registries.BLOCK.getId(underState.getBlock());
            final Integer cartSpeedBps = HighspeedService.getInstance().getCartSpeed(underBlockId);
            if (cartSpeedBps != null) {
                return maxSpeed = cartSpeedBps / 20.0;
            } else {
                return maxSpeed = VANILLA_MAX_SPEED;
            }
        } else {
            return maxSpeed = VANILLA_MAX_SPEED;
        } */
    }

    private void clampVelocity() {
        if (getModifiedMaxSpeed() != lastMaxSpeed) {
            double smaller = Math.min(getModifiedMaxSpeed(), lastMaxSpeed);
            final Vec3d vel = minecart.getVelocity();
            minecart.setVelocity(new Vec3d(MathHelper.clamp(vel.x, -smaller, smaller), 0.0,
                    MathHelper.clamp(vel.z, -smaller, smaller)));
        }
        lastMaxSpeed = maxSpeed;
    }

    private void updateSpeedometer() {
        final HighspeedService service = HighspeedService.getInstance();
        if (!service.isSpeedometerEnabled()) return;
        final AbstractMinecartEntity minecart = (AbstractMinecartEntity) (Object) this;
        if (!minecart.getWorld().isClient) return;
        final HighspeedClientService client = service.getClientService();
        if (!client.isPlayerRiding(minecart)) return;
        final double override = getModifiedMaxSpeed();
        final Vec3d vel = minecart.getVelocity();
        final Vec3d nominalVelocity = new Vec3d(MathHelper.clamp(vel.x, -override, override), 0.0, MathHelper.clamp(vel.z, -override, override));
        final Double nominalSpeed = (nominalVelocity.horizontalLength() * 20);
        final String display;
        if (!HighspeedService.getInstance().isSpeedometerTrueSpeedEnabled()) {
            display = String.format("| %.2f bps |", nominalSpeed);
        } else {
            final double trueSpeed;
            if (this.lastSpeedPos == null) {
                trueSpeed = 0.0;
                lastSpeedPos = client.getPlayerPos();
                lastSpeedTime = System.currentTimeMillis();
            } else {
                final long now = System.currentTimeMillis();
                final Vec3d playerPos = client.getPlayerPos();
                final Vec3d vector = playerPos.subtract(this.lastSpeedPos);
                trueSpeed = vector.horizontalLength() * 1000 / ((now - lastSpeedTime));
                this.lastSpeedPos = playerPos;
                lastSpeedTime = now;
            }
            display = String.format("| %.2f bps %.2f  |", nominalSpeed, trueSpeed);
        }
        client.sendPlayerMessage(display);
    }
}

