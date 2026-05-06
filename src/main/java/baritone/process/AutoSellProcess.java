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
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Monitors the player's main inventory while #autosell is enabled. Once {@link Baritone#settings().autoSellInventoryThreshold}
 * of the 36 main inventory slots are non-empty, this process takes over (with REQUEST_PAUSE so #farm pauses) and runs
 * a state machine that:
 * <ol>
 *     <li>Sends the configured slash command (default {@code /seller}).</li>
 *     <li>Waits for the category menu to open and clicks the first slot containing a {@link Items#CACTUS}
 *         (the "Фермер" category on the target server).</li>
 *     <li>Waits for the shop menu to open and right-clicks the first slot containing a {@link Items#MELON}
 *         (which sells the entire stack at the configured price).</li>
 *     <li>Closes the menu and enters a brief cooldown before resuming.</li>
 * </ol>
 * Failures (timeouts, unexpected menu state) are counted; after {@link Baritone#settings().autoSellMaxFailures}
 * consecutive failures the process disables itself so the player can investigate.
 */
public final class AutoSellProcess extends BaritoneProcessHelper {

    private static final Item CATEGORY_ITEM = Items.CACTUS;
    private static final Item SELL_ITEM = Items.MELON;

    private enum State {
        IDLE,
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
            if (inventoryFull()) {
                enterState(State.SEND_COMMAND);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        // every active state pauses farming
        switch (state) {
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
     * Externally requested manual trigger (e.g. {@code #autosell now}). Forces the state machine to start a sell
     * cycle even if the inventory hasn't crossed the threshold yet.
     */
    public void triggerNow() {
        if (!isActive()) {
            return;
        }
        if (state == State.IDLE) {
            enterState(State.SEND_COMMAND);
        }
    }

    public void reset() {
        enterState(State.IDLE);
        consecutiveFailures = 0;
    }

    @Override
    public void onLostControl() {
        // since we're temporary, this is only called when isActive() returns false (i.e. user disabled autosell)
        reset();
    }

    @Override
    public String displayName0() {
        return "AutoSell (" + state.name() + ")";
    }

    // ---------------- state implementations ----------------

    private PathingCommand tickSendCommand() {
        LocalPlayer player = ctx.player();
        if (player == null || player.connection == null) {
            return failure("no connection");
        }
        // safety: if a foreign menu is already open, close it first and try again later
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
            // menu opened but cactus not yet present this tick -- keep waiting
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
            // try to find it again in case slot moved
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
            // menu was closed; treat as failure
            return failure("shop menu closed unexpectedly");
        }
        // wait until containerId changes (server replaced the menu) AND a melon slot is present
        if (menu.containerId == categoryContainerId) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        int slot = findShopSlot(menu, player.getInventory(), SELL_ITEM);
        if (slot < 0) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        categoryContainerId = menu.containerId; // remember for safety, reused as "current" id
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
        if (sellSlotIndex < 0 || sellSlotIndex >= menu.slots.size()
                || menu.slots.get(sellSlotIndex).getItem().getItem() != SELL_ITEM) {
            int slot = findShopSlot(menu, player.getInventory(), SELL_ITEM);
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
        consecutiveFailures = 0; // success!
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

    private PathingCommand failure(String reason) {
        consecutiveFailures++;
        int max = Math.max(1, Baritone.settings().autoSellMaxFailures.value);
        logDirect("AutoSell failure (" + consecutiveFailures + "/" + max + "): " + reason);
        // try to close any leftover menu so we don't soft-lock
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

    private boolean inventoryFull() {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return false;
        }
        // a foreign menu being open means we shouldn't trigger another sell
        if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
            return false;
        }
        NonNullList<ItemStack> inv = player.getInventory().getNonEquipmentItems();
        int total = Math.min(36, inv.size()); // 27 main + 9 hotbar
        int filled = 0;
        for (int i = 0; i < total; i++) {
            if (!inv.get(i).isEmpty()) {
                filled++;
            }
        }
        double threshold = Baritone.settings().autoSellInventoryThreshold.value;
        if (threshold <= 0.0) {
            threshold = 0.9;
        }
        if (threshold > 1.0) {
            threshold = 1.0;
        }
        int needed = (int) Math.ceil(total * threshold);
        return filled >= needed;
    }

    /**
     * Find the index of the first slot in {@code menu} whose item is {@code target}, skipping the player's own
     * inventory portion (so we don't accidentally click the player's own cactus/melon stacks).
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
