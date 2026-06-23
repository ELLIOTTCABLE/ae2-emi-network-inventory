package io.ell.backports.ae2_emi_network_inventory;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config gating the network-inventory mixin. Mirrors AE2's upstream
 * {@code provideNetworkInventoryToEmi} option (PR #8215), but defaults on.
 */
public final class Ae2EmiNetworkInventoryConfig {
   public static final ModConfigSpec SPEC;
   private static final ModConfigSpec.BooleanValue EXPOSE_NETWORK_INVENTORY_TO_EMI;
   private static final ModConfigSpec.BooleanValue DEBUG_LOGGING;

   static {
      var builder = new ModConfigSpec.Builder();
      EXPOSE_NETWORK_INVENTORY_TO_EMI = builder
            .comment(
                  "Expose the full ME network inventory to EMI, so synthetic favorites and",
                  "crafting-tree nodes for items stored in the network resolve as present.",
                  "May cause performance problems on very large networks; turn off to fall",
                  "back to player-inventory-only (vanilla EMI behaviour).")
            .define("provideNetworkInventoryToEmi", true);
      DEBUG_LOGGING = builder
            .comment(
                  "Log a one-line summary (once per terminal session) of how many network",
                  "stacks were exposed to EMI. For diagnosing the feature; off for normal play.")
            .define("debugLogging", false);
      SPEC = builder.build();
   }

   private Ae2EmiNetworkInventoryConfig() {
   }

   public static boolean exposeNetworkInventoryToEmi() {
      return EXPOSE_NETWORK_INVENTORY_TO_EMI.getAsBoolean();
   }

   public static boolean debugLogging() {
      return DEBUG_LOGGING.getAsBoolean();
   }
}
