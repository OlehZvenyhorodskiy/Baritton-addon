/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Monitors the player's main inventory while #autosell is enabled. Once at least
 * {@link Baritone#settings().autoSellTriggerSlots} slots in the main inventory contain pumpkin blocks
 * ({@link Items#PUMPKIN}), this process takes over (with REQUEST_PAUSE so #farm pauses) and runs a state
 * machine that:
 * <ol>
 *     <li>(if {@link Baritone#settings().autoSellCraftPumpkins} is true) crafts every pumpkin block in the
 *         player's main inventory + hotbar into pumpkin seeds via the player's 2x2 crafting grid using the
 *         vanilla recipe {@code 1 pumpkin -> 4 pumpkin_seeds}.</li>
 *     <li>Sends the configured slash command (default {@code /seller}).</li>
 *     <li>Waits for the category menu to open and clicks the first slot containing a {@link Items#CACTUS}
 *         (the "Фермер" category on the target server).</li>
 *     <li>Waits for the shop menu to open and right-clicks the first slot containing
 *         {@link Items#PUMPKIN_SEEDS} (which sells the entire stack at the configured price).</li>
 *     <li>Closes the menu and enters a brief cooldown before resuming.</li>
 * </ol>
 * Failures (timeouts, unexpected menu state) are counted; after {@link Baritone#settings().autoSellMaxFailures}
 * consecutive failures the process disables itself so the player can investigate.
 */
public final class AutoSellProcess extends BaritoneProcessHelper {

    /** First-menu slot we click on to enter the "Фермер" category. */
    private static final Item CATEGORY_ITEM = Items.CACTUS;
    /** Item that triggers the autosell cycle when accumulated in inventory. */
    private static final Item SOURCE_ITEM = Items.PUMPKIN;
    /** Item we right-click in the seller menu to sell. After crafting, the inventory is full of these. */
    private static final Item SELL_ITEM = Items.PUMPKIN_SEEDS;

    /** Slot index in the player's inventoryMenu of the crafting result slot. */
    private static final int CRAFT_RESULT_SLOT = 0;
    /** Slot index in the player's inventoryMenu of the first crafting input slot (we use this one). */
    private static final int CRAFT_INPUT_SLOT = 1;
    /** First main-inventory slot index in inventoryMenu (slots 9..35 = main, 36..44 = hotbar). */
    private static final int MAIN_INV_FIRST = 9;
    /** Last hotbar slot index in inventoryMenu. */
    private static final int MAIN_INV_LAST = 44;

    private enum State {
        IDLE,
        CRAFT_PICK_STACK,
        CRAFT_PUT_IN_GRID,
        CRAFT_WAIT_RESULT,
        CRAFT_SHIFT_RESULT,
        SEND_COMMAND,
        WAIT_CATEGORY_MENU,
        CLICK_CATEGORY,
        WAIT_SHOP_MENU,
        RIGHT_CLICK_SELL,
        WAIT_SELL,
        CLOSE_MENU,
        COOLDOWN
    }

    private State state = State.IDLE;
    private int stateStartTick;
    private int tickCounter;
    private int categoryContainerId = -1;
    private int categorySlotIndex = -1;
    private int sellSlotIndex = -1;
    private int consecutiveFailures;

    // crafting bookkeeping
    private int lastCraftClickTick = -100;
    private int craftClicksThisCycle;

    public AutoSellProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (!Baritone.settings().autoSellEnabled.value) {
            return false;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public double priority() {
        return DEFAULT_PRIORITY + 0.5; // ahead of #farm but behind pause/resume (DEFAULT_PRIORITY + 1)
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        tickCounter++;

        if (state == State.IDLE) {
            if (sourceItemSlotsCount() >= Math.max(1, Baritone.settings().autoSellTriggerSlots.value)) {
                startCycle();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        switch (state) {
            case CRAFT_PICK_STACK:
                return tickCraftPickStack();
            case CRAFT_PUT_IN_GRID:
                return tickCraftPutInGrid();
            case CRAFT_WAIT_RESULT:
                return tickCraftWaitResult();
            case CRAFT_SHIFT_RESULT:
                return tickCraftShiftResult();
            case SEND_COMMAND:
                return tickSendCommand();
            case WAIT_CATEGORY_MENU:
                return tickWaitCategoryMenu();
            case CLICK_CATEGORY:
                return tickClickCategory();
            case WAIT_SHOP_MENU:
                return tickWaitShopMenu();
            case RIGHT_CLICK_SELL:
                return tickRightClickSell();
            case WAIT_SELL:
                return tickWaitSell();
            case CLOSE_MENU:
                return tickCloseMenu();
            case COOLDOWN:
                return tickCooldown();
            default:
                enterState(State.IDLE);
                return new PathingCommand(null, PathingCommandType.DEFER);
        }
    }

    /**
     * Externally requested manual trigger (e.g. {@code #autosell now}).
     */
    public void triggerNow() {
        if (!isActive()) {
            return;
        }
        if (state == State.IDLE) {
            startCycle();
        }
    }

    public void reset() {
        enterState(State.IDLE);
        consecutiveFailures = 0;
    }

    @Override
    public void onLostControl() {
        reset();
    }

    @Override
    public String displayName0() {
        return "AutoSell (" + state.name() + ")";
    }

    // ---------------- cycle entry ----------------

    private void startCycle() {
        craftClicksThisCycle = 0;
        if (Baritone.settings().autoSellCraftPumpkins.value) {
            enterState(State.CRAFT_PICK_STACK);
        } else {
            enterState(State.SEND_COMMAND);
        }
    }

    // ---------------- crafting phase ----------------

    private PathingCommand tickCraftPickStack() {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return failure("player is null in CRAFT_PICK_STACK");
        }
        // safety: only craft when no foreign menu is open (we use player.inventoryMenu directly)
        if (player.containerMenu != player.inventoryMenu) {
            // wait for menu to close
            if (timedOut()) {
                return failure("foreign menu was open during craft phase");
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        // throttle clicks
        if (tickCounter - lastCraftClickTick < clickDelay()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        AbstractContainerMenu menu = player.inventoryMenu;
        int sourceSlot = findInventorySlot(menu, SOURCE_ITEM);
        ItemStack craftInput = slotItem(menu, CRAFT_INPUT_SLOT);

        if (sourceSlot < 0 && craftInput.isEmpty()) {
            // no more pumpkins anywhere, move on to selling
            enterState(State.SEND_COMMAND);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (sourceSlot < 0) {
            // no pumpkins in main inv but craft slot still has some -> drain it
            enterState(State.CRAFT_SHIFT_RESULT);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        // pickup the stack
        ctx.playerController().windowClick(menu.containerId, sourceSlot, 0, ClickType.PICKUP, player);
        lastCraftClickTick = tickCounter;
        enterState(State.CRAFT_PUT_IN_GRID);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickCraftPutInGrid() {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return failure("player is null in CRAFT_PUT_IN_GRID");
        }
        if (tickCounter - lastCraftClickTick < clickDelay()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        AbstractContainerMenu menu = player.inventoryMenu;
        if (player.containerMenu != menu) {
            return failure("foreign menu opened during craft put");
        }
        // place cursor (which holds pumpkins) into craft input slot 1
        ctx.playerController().windowClick(menu.containerId, CRAFT_INPUT_SLOT, 0, ClickType.PICKUP, player);
        lastCraftClickTick = tickCounter;
        enterState(State.CRAFT_WAIT_RESULT);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickCraftWaitResult() {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return failure("player is null in CRAFT_WAIT_RESULT");
        }
        if (player.containerMenu != player.inventoryMenu) {
            return failure("foreign menu opened during craft wait");
        }
        AbstractContainerMenu menu = player.inventoryMenu;
        ItemStack result = slotItem(menu, CRAFT_RESULT_SLOT);
        if (!result.isEmpty()) {
            enterState(State.CRAFT_SHIFT_RESULT);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        // grace period: if the result slot never appears, the recipe doesn't exist on this server.
        // Abort the cycle - we deliberately do NOT fall back to selling raw pumpkins because the
        // target shop only buys pumpkin seeds.
        if (tickCounter - stateStartTick > 40) { // 2 seconds
            return failure("pumpkin->seeds recipe not detected (server has no such recipe?)");
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickCraftShiftResult() {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return failure("player is null in CRAFT_SHIFT_RESULT");
        }
        if (player.containerMenu != player.inventoryMenu) {
            return failure("foreign menu opened during craft shift");
        }
        if (craftClicksThisCycle >= Math.max(1, Baritone.settings().autoSellCraftMaxClicks.value)) {
            return failure("crafting safety limit hit (" + craftClicksThisCycle + " clicks)");
        }
        if (tickCounter - lastCraftClickTick < clickDelay()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        AbstractContainerMenu menu = player.inventoryMenu;
        ItemStack input = slotItem(menu, CRAFT_INPUT_SLOT);
        ItemStack result = slotItem(menu, CRAFT_RESULT_SLOT);

        if (input.isEmpty() && result.isEmpty()) {
            // batch done, look for next pumpkin stack in inventory
            enterState(State.CRAFT_PICK_STACK);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (result.isEmpty() && !input.isEmpty()) {
            // server hasn't repopulated yet (or recipe blocked) -- short wait
            if (tickCounter - lastCraftClickTick > 20) {
                // give up after 1 sec: re-check from PICK
                enterState(State.CRAFT_PICK_STACK);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        // result has seeds: shift-click to take and craft
        ctx.playerController().windowClick(menu.containerId, CRAFT_RESULT_SLOT, 0, ClickType.QUICK_MOVE, player);
        lastCraftClickTick = tickCounter;
        craftClicksThisCycle++;
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // ---------------- sell phase ----------------

    private PathingCommand tickSendCommand() {
        LocalPlayer player = ctx.player();
        if (player == null || player.connection == null) {
            return failure("no connection");
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
            enterState(State.COOLDOWN);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        String command = Baritone.settings().autoSellCommand.value;
        if (command == null || command.isEmpty()) {
            return failure("autoSellCommand is empty");
        }
        logDirect("AutoSell: running /" + command);
        player.connection.sendCommand(command);
        enterState(State.WAIT_CATEGORY_MENU);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickWaitCategoryMenu() {
        if (timedOut()) {
            return failure("category menu did not open in time");
        }
        LocalPlayer player = ctx.player();
        if (player == null) {
            return failure("player is null");
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        int slot = findShopSlot(menu, player.getInventory(), CATEGORY_ITEM);
        if (slot < 0) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        categoryContainerId = menu.containerId;
        categorySlotIndex = slot;
        enterState(State.CLICK_CATEGORY);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickClickCategory() {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return failure("player is null");
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu || menu.containerId != categoryContainerId) {
            return failure("category menu vanished before click");
        }
        if (categorySlotIndex < 0 || categorySlotIndex >= menu.slots.size()
                || menu.slots.get(categorySlotIndex).getItem().getItem() != CATEGORY_ITEM) {
            int slot = findShopSlot(menu, player.getInventory(), CATEGORY_ITEM);
            if (slot < 0) {
                return failure("category slot disappeared");
            }
            categorySlotIndex = slot;
        }
        ctx.playerController().windowClick(categoryContainerId, categorySlotIndex, 0, ClickType.PICKUP, player);
        enterState(State.WAIT_SHOP_MENU);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickWaitShopMenu() {
        if (timedOut()) {
            return failure("shop menu did not open in time");
        }
        LocalPlayer player = ctx.player();
        if (player == null) {
            return failure("player is null");
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu) {
            return failure("shop menu closed unexpectedly");
        }
        if (menu.containerId == categoryContainerId) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        Item target = effectiveSellItem();
        int slot = findShopSlot(menu, player.getInventory(), target);
        if (slot < 0) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        categoryContainerId = menu.containerId;
        sellSlotIndex = slot;
        enterState(State.RIGHT_CLICK_SELL);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickRightClickSell() {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return failure("player is null");
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu || menu.containerId != categoryContainerId) {
            return failure("shop menu vanished before sell click");
        }
        Item target = effectiveSellItem();
        if (sellSlotIndex < 0 || sellSlotIndex >= menu.slots.size()
                || menu.slots.get(sellSlotIndex).getItem().getItem() != target) {
            int slot = findShopSlot(menu, player.getInventory(), target);
            if (slot < 0) {
                return failure("sell slot disappeared");
            }
            sellSlotIndex = slot;
        }
        // mouseButton = 1 -> right click; ClickType.PICKUP with right click triggers the server's "/продать всё" handler
        ctx.playerController().windowClick(menu.containerId, sellSlotIndex, 1, ClickType.PICKUP, player);
        enterState(State.WAIT_SELL);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickWaitSell() {
        int wait = Math.max(1, Baritone.settings().autoSellWaitAfterSellTicks.value);
        if (tickCounter - stateStartTick >= wait) {
            enterState(State.CLOSE_MENU);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickCloseMenu() {
        LocalPlayer player = ctx.player();
        if (player != null && player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        consecutiveFailures = 0;
        enterState(State.COOLDOWN);
        logDirect("AutoSell: sell cycle complete, resuming farm");
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickCooldown() {
        int cd = Math.max(1, Baritone.settings().autoSellCooldownTicks.value);
        if (tickCounter - stateStartTick >= cd) {
            enterState(State.IDLE);
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // ---------------- helpers ----------------

    private void enterState(State next) {
        this.state = next;
        this.stateStartTick = tickCounter;
        if (next == State.IDLE) {
            categoryContainerId = -1;
            categorySlotIndex = -1;
            sellSlotIndex = -1;
        }
    }

    private boolean timedOut() {
        int timeout = Math.max(20, Baritone.settings().autoSellTimeoutTicks.value);
        return tickCounter - stateStartTick >= timeout;
    }

    private int clickDelay() {
        return Math.max(1, Baritone.settings().autoSellCraftClickDelay.value);
    }

    /**
     * If crafting is disabled the bot sells raw pumpkins; otherwise it sells the crafted pumpkin seeds.
     * Note: when crafting is enabled and the recipe doesn't work the cycle aborts via {@link #failure}, so
     * we never reach this method with leftover pumpkins to sell.
     */
    private Item effectiveSellItem() {
        return Baritone.settings().autoSellCraftPumpkins.value ? SELL_ITEM : SOURCE_ITEM;
    }

    private PathingCommand failure(String reason) {
        consecutiveFailures++;
        int max = Math.max(1, Baritone.settings().autoSellMaxFailures.value);
        logDirect("AutoSell failure (" + consecutiveFailures + "/" + max + "): " + reason);
        LocalPlayer player = ctx.player();
        if (player != null && player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        if (consecutiveFailures >= max) {
            logDirect("AutoSell: max failures reached, disabling autosell");
            Baritone.settings().autoSellEnabled.value = false;
            consecutiveFailures = 0;
            enterState(State.IDLE);
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        enterState(State.COOLDOWN);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * Count slots in main inventory + hotbar (slots {@value #MAIN_INV_FIRST}..{@value #MAIN_INV_LAST} of
     * inventoryMenu) that contain the configured {@link #SOURCE_ITEM}.
     */
    private int sourceItemSlotsCount() {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return 0;
        }
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            // foreign menu open, don't trigger
            return 0;
        }
        AbstractContainerMenu menu = player.inventoryMenu;
        int count = 0;
        for (int i = MAIN_INV_FIRST; i <= MAIN_INV_LAST && i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (!stack.isEmpty() && stack.getItem() == SOURCE_ITEM) {
                count++;
            }
        }
        return count;
    }

    /**
     * Return the slot index in {@code inventoryMenu} (range {@value #MAIN_INV_FIRST}..{@value #MAIN_INV_LAST})
     * containing the given target item, or {@code -1} if no such slot exists.
     */
    private static int findInventorySlot(AbstractContainerMenu menu, Item target) {
        for (int i = MAIN_INV_FIRST; i <= MAIN_INV_LAST && i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (!stack.isEmpty() && stack.getItem() == target) {
                return i;
            }
        }
        return -1;
    }

    private static ItemStack slotItem(AbstractContainerMenu menu, int idx) {
        if (idx < 0 || idx >= menu.slots.size()) {
            return ItemStack.EMPTY;
        }
        return menu.slots.get(idx).getItem();
    }

    /**
     * Find the index of the first slot in {@code menu} whose item is {@code target}, skipping the player's own
     * inventory portion (so we don't accidentally click the player's own cactus/seed stacks).
     */
    private static int findShopSlot(AbstractContainerMenu menu, Inventory playerInventory, Item target) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == playerInventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && stack.getItem() == target) {
                return i;
            }
        }
        return -1;
    }
}
