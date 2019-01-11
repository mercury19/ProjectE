package moze_intel.projecte.gameObjs.items;

import moze_intel.projecte.PECore;
import moze_intel.projecte.api.item.IItemCharge;
import moze_intel.projecte.api.item.IModeChanger;
import moze_intel.projecte.api.item.IPedestalItem;
import moze_intel.projecte.config.ProjectEConfig;
import moze_intel.projecte.gameObjs.tiles.DMPedestalTile;
import moze_intel.projecte.utils.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFlowingFluid;
import net.minecraft.block.IGrowable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.Tag;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.IPlantable;

import javax.annotation.Nonnull;
import java.util.*;

// todo 1.13 @Optional.Interface(iface = "baubles.api.IBauble", modid = "baubles")
public class TimeWatch extends ItemPE implements IModeChanger, IPedestalItem, IItemCharge
{
	private static final Set<ResourceLocation> internalBlacklist = new HashSet<>();
	private static final ResourceLocation BLOCK_BLACKLIST_TAG = new ResourceLocation(PECore.MODID, "time_watch_blacklist");

	public TimeWatch(Builder builder)
	{
		super(builder);
		this.addPropertyOverride(ACTIVE_NAME, ACTIVE_GETTER);
	}

	@Nonnull
	@Override
	public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand)
	{
		ItemStack stack = player.getHeldItem(hand);
		if (!world.isRemote)
		{
			if (!ProjectEConfig.items.enableTimeWatch)
			{
				player.sendMessage(new TextComponentTranslation("pe.timewatch.disabled"));
				return ActionResult.newResult(EnumActionResult.FAIL, stack);
			}

			byte current = getTimeBoost(stack);

			setTimeBoost(stack, (byte) (current == 2 ? 0 : current + 1));

			player.sendMessage(new TextComponentTranslation("pe.timewatch.mode_switch", new TextComponentTranslation(getTimeName(stack)).getUnformattedComponentText()));
		}

		return ActionResult.newResult(EnumActionResult.SUCCESS, stack);
	}

	@Override
	public void inventoryTick(ItemStack stack, World world, Entity entity, int invSlot, boolean isHeld)
	{
		super.inventoryTick(stack, world, entity, invSlot, isHeld);
		
		if (!(entity instanceof EntityPlayer) || invSlot > 8)
		{
			return;
		}

		if (!ProjectEConfig.items.enableTimeWatch)
		{
			return;
		}

		byte timeControl = getTimeBoost(stack);

		if (world.getGameRules().getBoolean("doDaylightCycle")) {
			if (timeControl == 1)
            {
                if (world.getDayTime() + ((getCharge(stack) + 1) * 4) > Long.MAX_VALUE)
                {
                    world.setDayTime(Long.MAX_VALUE);
                }
                else
                {
                    world.setDayTime((world.getDayTime() + ((getCharge(stack) + 1) * 4)));
                }
            }
            else if (timeControl == 2)
            {
                if (world.getDayTime() - ((getCharge(stack) + 1) * 4) < 0)
                {
                    world.setDayTime(0);
                }
                else
                {
                    world.setDayTime((world.getDayTime() - ((getCharge(stack) + 1) * 4)));
                }
            }
		}

        if (world.isRemote || !stack.getOrCreateTag().getBoolean(TAG_ACTIVE))
		{
			return;
		}

		EntityPlayer player = (EntityPlayer) entity;
		double reqEmc = getEmcPerTick(this.getCharge(stack));
		
		if (!consumeFuel(player, stack, reqEmc, true))
		{
			return;
		}
		
		int charge = this.getCharge(stack);
		int bonusTicks;
		float mobSlowdown;
		
		if (charge == 0)
		{
			bonusTicks = 8;
			mobSlowdown = 0.25F;
		}
		else if (charge == 1)
		{
			bonusTicks = 12;
			mobSlowdown = 0.16F;
		}
		else
		{
			bonusTicks = 16;
			mobSlowdown = 0.12F;
		}
			
		AxisAlignedBB bBox = player.getBoundingBox().grow(8);

		speedUpTileEntities(world, bonusTicks, bBox);
		speedUpRandomTicks(world, bonusTicks, bBox);
		slowMobs(world, bBox, mobSlowdown);
	}

	private void slowMobs(World world, AxisAlignedBB bBox, double mobSlowdown)
	{
		if (bBox == null) // Sanity check for chunk unload weirdness
		{
			return;
		}
		for (Object obj : world.getEntitiesWithinAABB(EntityLiving.class, bBox))
		{
			Entity ent = (Entity) obj;

			if (ent.motionX != 0)
			{
				ent.motionX *= mobSlowdown;
			}

			if (ent.motionZ != 0)
			{
				ent.motionZ *= mobSlowdown;
			}
		}
	}

	private void speedUpTileEntities(World world, int bonusTicks, AxisAlignedBB bBox)
	{
		if (bBox == null || bonusTicks == 0) // Sanity check the box for chunk unload weirdness
		{
			return;
		}

		Set<String> blacklist = ProjectEConfig.effects.timeWatchTEBlacklist;
		List<TileEntity> list = WorldHelper.getTileEntitiesWithinAABB(world, bBox);
		for (int i = 0; i < bonusTicks; i++)
		{
			for (TileEntity tile : list)
			{
				if (!tile.isRemoved() && tile instanceof ITickable
						&& !internalBlacklist.contains(tile.getType().getRegistryName())
						&& !blacklist.contains(tile.getType().getRegistryName().toString()))
				{
					((ITickable) tile).tick();
				}
			}
		}
	}

	private void speedUpRandomTicks(World world, int bonusTicks, AxisAlignedBB bBox)
	{
		if (bBox == null || bonusTicks == 0) // Sanity check the box for chunk unload weirdness
		{
			return;
		}

		Tag<Block> blacklist = BlockTags.getCollection().getOrCreate(BLOCK_BLACKLIST_TAG);
		for (BlockPos pos : WorldHelper.getPositionsFromBox(bBox))
		{
			for (int i = 0; i < bonusTicks; i++)
			{
				IBlockState state = world.getBlockState(pos);
				Block block = state.getBlock();
				if (state.needsRandomTick()
						&& !blacklist.contains(block)
						&& !(block instanceof BlockFlowingFluid) // Don't speed vanilla non-source blocks - dupe issues
						// todo 1.13 && !(block instanceof BlockFluidBase) // Don't speed Forge fluids - just in case of dupes as well
						&& !(block instanceof IGrowable)
						&& !(block instanceof IPlantable)) // All plants should be sped using Harvest Goddess
				{
					state.randomTick(world, pos, random);
				}
			}
		}
	}

	private String getTimeName(ItemStack stack)
	{
		byte mode = getTimeBoost(stack);
		switch (mode)
		{
			case 0:
				return "pe.timewatch.off";
			case 1:
				return "pe.timewatch.ff";
			case 2:
				return "pe.timewatch.rw";
			default:
				return "ERROR_INVALID_MODE";
		}
	}

	private byte getTimeBoost(ItemStack stack)
	{
        return stack.getOrCreateTag().getByte("TimeMode");
	}

	private void setTimeBoost(ItemStack stack, byte time)
	{
        stack.getOrCreateTag().putByte("TimeMode", (byte) MathHelper.clamp(time, 0, 2));
	}

	public double getEmcPerTick(int charge)
	{
		int actualCharge = charge + 1;
		return (10.0D * actualCharge) / 20.0D;
	}

	@Override
	public byte getMode(@Nonnull ItemStack stack)
	{
        return stack.getOrCreateTag().getBoolean(TAG_ACTIVE) ? (byte) 1 : 0;
	}

	@Override
	public boolean changeMode(@Nonnull EntityPlayer player, @Nonnull ItemStack stack, EnumHand hand)
	{
        NBTTagCompound tag = stack.getOrCreateTag();
		tag.putBoolean(TAG_ACTIVE, !tag.getBoolean(TAG_ACTIVE));
		return true;
	}


	@Override
	@OnlyIn(Dist.CLIENT)
	public void addInformation(ItemStack stack, World world, List<ITextComponent> list, ITooltipFlag flags)
	{
		list.add(new TextComponentTranslation("pe.timewatch.tooltip1"));
		list.add(new TextComponentTranslation("pe.timewatch.tooltip2"));

		if (stack.hasTag())
		{
			list.add(new TextComponentTranslation("pe.timewatch.mode").appendSibling(new TextComponentTranslation(getTimeName(stack))));
		}
	}

	/* todo 1.13
	@Override
	@Optional.Method(modid = "baubles")
	public baubles.api.BaubleType getBaubleType(ItemStack itemstack)
	{
		return BaubleType.BELT;
	}

	@Override
	@Optional.Method(modid = "baubles")
	public void onWornTick(ItemStack stack, EntityLivingBase player) 
	{
		this.onUpdate(stack, player.getEntityWorld(), player, 0, false);
	}

	@Override
	@Optional.Method(modid = "baubles")
	public void onEquipped(ItemStack itemstack, EntityLivingBase player) {}

	@Override
	@Optional.Method(modid = "baubles")
	public void onUnequipped(ItemStack itemstack, EntityLivingBase player) {}

	@Override
	@Optional.Method(modid = "baubles")
	public boolean canEquip(ItemStack itemstack, EntityLivingBase player) 
	{
		return true;
	}

	@Override
	@Optional.Method(modid = "baubles")
	public boolean canUnequip(ItemStack itemstack, EntityLivingBase player) 
	{
		return true;
	}*/

	@Override
	public void updateInPedestal(@Nonnull World world, @Nonnull BlockPos pos)
	{
		// Change from old EE2 behaviour (universally increased tickrate) for safety and impl reasons.

		if (!world.isRemote && ProjectEConfig.items.enableTimeWatch)
		{
			TileEntity te = world.getTileEntity(pos);
			if (te instanceof DMPedestalTile)
			{
				AxisAlignedBB bBox = ((DMPedestalTile) te).getEffectBounds();
				if (ProjectEConfig.effects.timePedBonus > 0) {
					speedUpTileEntities(world, ProjectEConfig.effects.timePedBonus, bBox);
					speedUpRandomTicks(world, ProjectEConfig.effects.timePedBonus, bBox);
				}

				if (ProjectEConfig.effects.timePedMobSlowness < 1.0F) {
					slowMobs(world, bBox, ProjectEConfig.effects.timePedMobSlowness);
				}
			}
		}
	}

	@Nonnull
	@OnlyIn(Dist.CLIENT)
	@Override
	public List<String> getPedestalDescription()
	{
		List<String> list = new ArrayList<>();
		if (ProjectEConfig.effects.timePedBonus > 0) {
			list.add(TextFormatting.BLUE + I18n.format("pe.timewatch.pedestal1", ProjectEConfig.effects.timePedBonus));
		}
		if (ProjectEConfig.effects.timePedMobSlowness < 1.0F)
		{
			list.add(TextFormatting.BLUE + I18n.format("pe.timewatch.pedestal2", ProjectEConfig.effects.timePedMobSlowness));
		}
		return list;
	}

	public static void blacklist(ResourceLocation id)
	{
		internalBlacklist.add(id);
	}

	@Override
	public int getNumCharges(@Nonnull ItemStack stack)
	{
		return 2;
	}

	@Override
	public boolean showDurabilityBar(ItemStack stack)
	{
		return true;
	}

	@Override
	public double getDurabilityForDisplay(ItemStack stack)
	{
		return 1.0D - (double) getCharge(stack) / getNumCharges(stack);
	}
}
