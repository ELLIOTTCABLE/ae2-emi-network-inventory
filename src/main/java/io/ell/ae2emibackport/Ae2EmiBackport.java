package io.ell.ae2emibackport;

import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;

import org.slf4j.Logger;

/**
 * Entry point. The behaviour lives entirely in the client-side mixin
 * {@code io.ell.ae2emibackport.mixin.AbstractRecipeHandlerMixin} (see IMPLEMENTATION_PLAN.md);
 * this class exists only so FML loads the mod, its config, and its mixin config.
 */
@Mod(Ae2EmiBackport.MODID)
public final class Ae2EmiBackport {
   public static final String MODID = "ae2emibackport";
   public static final Logger LOGGER = LogUtils.getLogger();
   // DEBUG one-shot: lets the mixin log a single network-repo sample per launch, not every frame.
   public static volatile boolean DEBUG_DUMPED = false;

   public Ae2EmiBackport(IEventBus modBus, ModContainer container) {
      if (FMLEnvironment.dist == Dist.CLIENT) {
         ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Ae2EmiBackportConfig.SPEC);
         LOGGER.info("[{}] loaded; EMI network-inventory mixin active on the client", MODID);
      }
   }
}
