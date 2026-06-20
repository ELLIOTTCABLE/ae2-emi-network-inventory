package io.ell.ae2emibackport;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config gating the network-inventory mixin. Mirrors AE2's upstream
 * {@code provideNetworkInventoryToEmi} option (PR #8215), but defaults on.
 */
public final class Ae2EmiBackportConfig {
   public static final ModConfigSpec SPEC;
   private static final ModConfigSpec.BooleanValue EXPOSE_NETWORK_INVENTORY_TO_EMI;

   static {
      var builder = new ModConfigSpec.Builder();
      EXPOSE_NETWORK_INVENTORY_TO_EMI = builder
            .comment(
                  "Expose the full ME network inventory to EMI, so synthetic favorites and",
                  "crafting-tree nodes for items stored in the network resolve as present.",
                  "May cause performance problems on very large networks; turn off to fall",
                  "back to player-inventory-only (vanilla EMI behaviour).")
            .define("provideNetworkInventoryToEmi", true);
      SPEC = builder.build();
   }

   private Ae2EmiBackportConfig() {
   }

   public static boolean exposeNetworkInventoryToEmi() {
      return EXPOSE_NETWORK_INVENTORY_TO_EMI.getAsBoolean();
   }
}
