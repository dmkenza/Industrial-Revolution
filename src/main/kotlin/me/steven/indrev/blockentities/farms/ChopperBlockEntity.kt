package me.steven.indrev.blockentities.farms

import me.steven.indrev.blockentities.crafters.UpgradeProvider
import me.steven.indrev.components.InventoryController
import me.steven.indrev.components.TemperatureController
import me.steven.indrev.inventories.DefaultSidedInventory
import me.steven.indrev.items.CoolerItem
import me.steven.indrev.items.rechargeable.RechargeableItem
import me.steven.indrev.items.upgrade.Upgrade
import me.steven.indrev.items.upgrade.UpgradeItem
import me.steven.indrev.registry.MachineRegistry
import me.steven.indrev.utils.FakePlayerEntity
import me.steven.indrev.utils.Tier
import me.steven.indrev.utils.toIntArray
import net.minecraft.block.BlockState
import net.minecraft.block.LeavesBlock
import net.minecraft.block.LogBlock
import net.minecraft.block.SaplingBlock
import net.minecraft.item.*
import net.minecraft.loot.context.LootContext
import net.minecraft.loot.context.LootContextParameters
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Hand
import net.minecraft.util.ItemScatterer
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import team.reborn.energy.EnergySide

class ChopperBlockEntity(tier: Tier) : AOEMachineBlockEntity(tier, MachineRegistry.CHOPPER_REGISTRY), UpgradeProvider {
    init {
        this.inventoryController = InventoryController({ this }) {
            DefaultSidedInventory(19, (2..5).toIntArray(), (6 until 15).toIntArray()) { slot, stack ->
                val item = stack?.item
                when {
                    item is UpgradeItem -> getUpgradeSlots().contains(slot)
                    item is RechargeableItem && item.canOutput -> slot == 0
                    item is CoolerItem -> slot == 1
                    slot in 2..5 -> item is AxeItem || item is BoneMealItem || (item is BlockItem && item.block is SaplingBlock)
                    slot in 6 until 15 -> true
                    else -> false
                }
            }
        }
        this.temperatureController = TemperatureController({ this }, 0.02, 350..430, 600.0)
    }

    private var scheduledBlocks = mutableListOf<BlockPos>().iterator()
    var cooldown = 0

    override fun tick() {
        super.tick()
        if (world?.isClient == true) return
        val inventory = inventoryController?.getInventory() ?: return
        if (cooldown > 0) {
            cooldown--
            return
        } else if (!takeEnergy(Upgrade.ENERGY.apply(this, inventory)))
            return
        val axeStack = inventory.inputSlots.map { slot -> inventory.getInvStack(slot) }
            .firstOrNull { stack -> stack.item is AxeItem }
        if (!scheduledBlocks.hasNext()) {
            val list = mutableListOf<BlockPos>()
            val area = getWorkingArea()
            for (y in area.y1.toInt() until area.y2.toInt()) {
                for (x in area.x1.toInt() until area.x2.toInt()) {
                    for (z in area.z1.toInt() until area.z2.toInt()) {
                        list.add(BlockPos(x, y, z))
                    }
                }
            }
            scheduledBlocks = list.iterator()
        } else {
            var performedAction = false
            outer@ while (scheduledBlocks.hasNext()) {
                val pos = scheduledBlocks.next()
                val blockState = world?.getBlockState(pos) ?: continue
                if (axeStack != null && !axeStack.isEmpty && tryChop(axeStack, pos, blockState, inventory)) {
                    performedAction = true
                    break
                }
                for (slot in inventory.inputSlots) {
                    val stack = inventory.getInvStack(slot)
                    if ((axeStack != null && stack.isItemEqual(axeStack)) || stack.isEmpty || !tryUse(stack, world!!, pos)) continue
                    performedAction = true
                    break@outer
                }
            }
            temperatureController?.tick(performedAction)
        }
        cooldown += 6 - (Upgrade.SPEED.apply(this, inventory).toInt() / 4)
    }

    private fun tryChop(axeStack: ItemStack, blockPos: BlockPos, blockState: BlockState, inventory: DefaultSidedInventory): Boolean {
        when (blockState.block) {
            is LogBlock -> {
                world?.breakBlock(blockPos, false)
                axeStack.damage(1, world?.random, null)
            }
            is LeavesBlock -> world?.breakBlock(blockPos, false)
            else -> return false
        }
        val droppedStacks = blockState.getDroppedStacks(
            LootContext.Builder(world as ServerWorld).setRandom(world?.random)
                .put(LootContextParameters.POSITION, blockPos)
                .put(LootContextParameters.TOOL, axeStack)
        )
        droppedStacks.map { inventory.add(it) }.filterNot(ItemStack::isEmpty).forEach {
            ItemScatterer.spawn(world, blockPos.x.toDouble(), blockPos.y.toDouble(), blockPos.z.toDouble(), it)
        }
        return true
    }

    private fun tryUse(itemStack: ItemStack, world: World, pos: BlockPos): Boolean {
        val fakePlayer = FakePlayerEntity(world, pos)
        fakePlayer.setStackInHand(Hand.MAIN_HAND, itemStack)
        return itemStack.useOnBlock(ItemUsageContext(fakePlayer, Hand.MAIN_HAND, BlockHitResult(Vec3d(pos), Direction.UP, pos, false))).isAccepted
    }

    override fun getUpgradeSlots(): IntArray = intArrayOf(15, 16, 17, 18)

    override fun getAvailableUpgrades(): Array<Upgrade> = Upgrade.ALL

    override fun getBaseValue(upgrade: Upgrade): Double =
        when (upgrade) {
            Upgrade.ENERGY -> 2.0 * Upgrade.SPEED.apply(this, inventoryController!!.getInventory())
            Upgrade.SPEED -> if (temperatureController!!.temperature.toInt() in temperatureController!!.optimalRange) 4.0 else 3.0
            Upgrade.BUFFER -> baseBuffer
        }

    override fun getMaxOutput(side: EnergySide?): Double = 0.0

    override fun getWorkingArea(): Box {
        val box = Box(pos)
        if (this.hasWorld()) {
            val range = getRange()
            return box.expand(range.x, 0.0, range.z).stretch(0.0, range.y, 0.0)
        }
        return box
    }

    private fun getRange() =
        when (tier) {
            Tier.MK1 -> Vec3d(3.0, 6.0, 3.0)
            Tier.MK2 -> Vec3d(4.0, 7.0, 4.0)
            Tier.MK3 -> Vec3d(5.0, 8.0, 5.0)
            Tier.MK4 -> Vec3d(6.0, 9.0, 6.0)
        }

}