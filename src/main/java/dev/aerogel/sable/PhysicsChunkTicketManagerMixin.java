package dev.aerogel.sable;

import dev.aerogel.server.PromotionManager;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * #9: Sable's per-tick chunk ticketing reads every terrain section around a physics body (ship, coaster, any
 * sub-level or physics object) once, through {@code level.getChunk(x, z)}, and bakes it into the Rapier
 * world. For a proxy that read is the air placeholder, and the air stays baked in while the body sits there.
 * Before that first read, a stub chunk is promoted when the section is near its surface and the section is
 * left unregistered (a ticket outside Sable's map is returned), so Sable reads it again next tick and gets
 * the real terrain once generated. Applied only when Sable is installed ({@code requiredMods} in
 * neoforge.mods.toml).
 */
@Mixin(PhysicsChunkTicketManager.class)
public class PhysicsChunkTicketManagerMixin {

    @Inject(method = "addTicket", cancellable = true, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private void aerogel$bodyReachesProxy(Level level, PhysicsPipeline pipeline, SectionPos sectionPos, int x, int y,
        int z, int index, long gameTime, CallbackInfoReturnable<PhysicsChunkTicket> cir) {
        if (level instanceof ServerLevel server && PromotionManager.onBodySection(server, ChunkPos.asLong(x, z), y)) {
            cir.setReturnValue(new PhysicsChunkTicket(sectionPos, gameTime, null));
        }
    }
}
