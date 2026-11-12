package dev.aerogel.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.aerogel.server.ProxyStreamer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla polices a client's movement server-side and teleports the client back when a move looks
 * impossible: "moved too quickly", "moved wrongly", and a move into a block that was not there before. Every
 * one of those is a rubber-band, and every one is wrong when the intent is fast flight.
 *
 * <p>The speed allowance is a fixed 10 blocks per server tick with no elapsed-time term, and the positions
 * are compared against the server's position from the start of the tick. A generation stall therefore
 * presents the whole stall's movement as one tick: after a stall of {@code S} seconds anything faster than
 * {@code 10/S} blocks per second is rejected, so a 100 blocks/s flier bounces after a 100 ms stall and even
 * a walking player bounces after a 2.5 s one. The ridden-vehicle path has no packet-count scaling at all,
 * so a broom is stricter still, and {@code handleMovePlayer}'s {@code i > 5 -> i = 1} clamp makes the worst
 * case stricter again. A stall of a second or two is enough to reject a walking player.</p>
 *
 * <p>{@code stream.trustClient} (default false) / {@code /aerogel switch trustClient on} turns that
 * policing off for the whole server: the server accepts the position the client reports. That is a
 * deliberate speed-hack and flight-hack hole, which is why it is opt-in and documented as such. What is
 * disabled, and where:</p>
 *
 * <ul>
 *   <li>{@code handleMovePlayer}: "moved too quickly" (the {@code isSingleplayerOwner} guard);</li>
 *   <li>{@code handleMovePlayer}: "moved wrongly" and the "collides with something new" reject (the
 *       {@code isCreative} arm that computes the flag, and the collision arm of the accept condition);</li>
 *   <li>{@code handleMoveVehicle}: "moved too quickly", plus the reject-and-resync at the end (gated on the
 *       pre-move {@code noCollision} result);</li>
 *   <li>the "flying is not enabled on this server" kick, for the player and for the ridden vehicle (the
 *       {@code getMaximumFlyingTicks} cap).</li>
 * </ul>
 *
 * <p>Nothing here changes the position a client reports or skips vanilla's own {@code player.move}: the
 * server still moves the entity, moves chunk tickets, applies fall damage and updates the known movement.
 * It only stops the corrections. Injection targets verified against
 * {@code build/moddev/artifacts/neoforge-21.1.252-merged.jar}.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    /** players: "moved too quickly". */
    @ModifyExpressionValue(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isSingleplayerOwner()Z"))
    private boolean aerogel$exemptPlayerSpeed(boolean original) {
        return original || ProxyStreamer.trustClient();
    }

    /** players: "moved wrongly", which also skips the warn. */
    @ModifyExpressionValue(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;isCreative()Z"))
    private boolean aerogel$notMovedWrongly(boolean original) {
        return original || ProxyStreamer.trustClient();
    }

    /** players: the other reject arm, a move into a block that was not there before. */
    @ModifyExpressionValue(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isPlayerCollidingWithAnythingNew(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/phys/AABB;DDD)Z"))
    private boolean aerogel$allowNewCollision(boolean original) {
        return original && !ProxyStreamer.trustClient();
    }

    /** ridden vehicles (brooms, boats, seats): "moved too quickly". */
    @ModifyExpressionValue(
        method = "handleMoveVehicle",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isSingleplayerOwner()Z"))
    private boolean aerogel$exemptVehicleSpeed(boolean original) {
        return original || ProxyStreamer.trustClient();
    }

    /** ridden vehicles: the reject-and-resync, gated on {@code flag} (the first of the two noCollision calls). */
    @ModifyExpressionValue(
        method = "handleMoveVehicle",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z",
            ordinal = 0))
    private boolean aerogel$acceptVehicleMove(boolean original) {
        return original && !ProxyStreamer.trustClient();
    }

    /** "flying is not enabled on this server": the tick cap behind the kick, player and vehicle. */
    @ModifyExpressionValue(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;getMaximumFlyingTicks(Lnet/minecraft/world/entity/Entity;)I"))
    private int aerogel$noFlyingKick(int original) {
        return ProxyStreamer.trustClient() ? Integer.MAX_VALUE : original;
    }
}
