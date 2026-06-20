/*
 * Backport of Applied Energistics 2 PR #8215 ("Expose entire network inventory to EMI")
 * to MC 1.20.4 / AE2 17.13.0-beta, the final 1.20.4 AE2 build, which predates the feature.
 *
 * The getInventory() body is adapted from AppliedEnergistics/Applied-Energistics-2,
 * appeng/integration/modules/emi/AbstractRecipeHandler.java (commit 38c8ff34b, PR #8215),
 * which is LGPL-3.0-only. This derivative is therefore also LGPL-3.0-only.
 */
package io.ell.ae2emibackport.mixin;

import java.util.ArrayList;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Mixin;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.EmiStack;

import appeng.api.stacks.GenericStack;
import appeng.integration.modules.emi.EmiStackHelper;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.common.MEStorageMenu;

import io.ell.ae2emibackport.Ae2EmiBackportConfig;

/**
 * Adds a {@code getInventory} override to AE2's EMI recipe handler so items stored in the ME
 * network count as "present" for EMI's synthetic favorites / crafting tree. Covers every terminal
 * whose handler extends AE2's {@code AbstractRecipeHandler} — AE2's crafting/pattern terminals and
 * AE2WTLib's wireless ones (which reuse AE2's handler classes).
 *
 * <p>This lives in our own package and targets AE2's package-private base class by name, NOT in
 * {@code appeng.integration.modules.emi}: NeoForge puts each mod in its own JVM module, so a class
 * in AE2's package split-package-conflicts with AE2's module and aborts loading at boot
 * ({@code java.lang.module.ResolutionException}). Every member we touch is public ({@code EmiStackHelper}
 * included at this tag), so no same-package access is needed.
 *
 * <p>{@code getInputSources}/{@code getCraftingSlots} are called via the {@link StandardRecipeHandler}
 * interface rather than {@code @Shadow}: shadowing them with the narrowed {@code T extends AEBaseMenu}
 * makes javac emit synthetic bridge methods that collide with the target's own at mixin-apply.
 */
@Mixin(targets = "appeng.integration.modules.emi.AbstractRecipeHandler")
public abstract class AbstractRecipeHandlerMixin<T extends AEBaseMenu> implements StandardRecipeHandler<T> {

   @Override
   public EmiPlayerInventory getInventory(AbstractContainerScreen<T> screen) {
      if (!Ae2EmiBackportConfig.exposeNetworkInventoryToEmi()) {
         return StandardRecipeHandler.super.getInventory(screen);
      }

      var menu = screen.getMenu();
      var list = new ArrayList<EmiStack>();

      for (Slot slot : getInputSources(menu)) {
         list.add(EmiStack.of(slot.getItem()));
      }
      // #8215 reached the crafting grid by folding it into getInputSources; we read it directly
      // instead, leaving AE2's getInputSources (and EMI's fill-button source logic) untouched.
      for (Slot slot : getCraftingSlots(menu)) {
         list.add(EmiStack.of(slot.getItem()));
      }

      if (menu instanceof MEStorageMenu meMenu) {
         var repo = meMenu.getClientRepo();
         if (repo != null) {
            for (var entry : repo.getAllEntries()) {
               if (entry.getStoredAmount() <= 0) {
                  continue; // craftable-only entries are tracked with 0 stored; not actually present
               }
               var emiStack = EmiStackHelper.toEmiStack(new GenericStack(entry.getWhat(), entry.getStoredAmount()));
               if (emiStack != null) { // toEmiStack is @Nullable; EMI's inventory ctor NPEs on a null stack
                  list.add(emiStack);
               }
            }
         }
      }

      return new EmiPlayerInventory(list);
   }
}
