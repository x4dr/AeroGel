package dev.aerogel.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.aerogel.server.ProxyStreamer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's move handler rejects any move with {@code d10 - d9 > 100} (≈10 blocks/packet) and teleports the
 * player back to the server position; it only exempts the singleplayer owner. On a dedicated server this
 * rubber-bands every fast-flight packet (~3×/s at high speed) — the "invisible wall every chunk".
 *
 * <p>We exempt fast creative flight and elytra when {@link ProxyStreamer#bypassMoveCheck()} is on. This
 * piggybacks on the existing {@code !isSingleplayerOwner()} guard, so the redirect only affects the two
 * movement checks in {@code handleMovePlayer}.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    @ModifyExpressionValue(
        method = "handleMovePlayer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isSingleplayerOwner()Z"))
    private boolean aerogel$exemptFastFlight(boolean original) {
        if (original || !ProxyStreamer.bypassMoveCheck()) {
            return original;
        }
        return this.player.isFallFlying()
            || (this.player.getAbilities().flying && this.player.getAbilities().getFlyingSpeed() > 0.1F);
    }
}
