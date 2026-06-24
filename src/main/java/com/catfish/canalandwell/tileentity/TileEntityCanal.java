package com.catfish.canalandwell.tileentity;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.catfish.canalandwell.CanalAndWell;
import com.catfish.canalandwell.Config;
import com.catfish.canalandwell.block.BlockCanal;

/**
 * 水渠 TileEntity —— 仅存储湿水状态，处理水传播。
 *
 * 连接与形态由 BlockCanal 在渲染时动态计算（参考红石线），
 * TE 不参与连接逻辑，避免状态漂移。
 *
 * 注意：1.7.10 中 TileEntity.canUpdate() 默认 false，
 * 必须重写为 true 才能使 updateEntity() 被调用。
 */
public class TileEntityCanal extends TileEntity {

    private boolean isWet;
    private int tickCounter;

    // ── 关键：让 TE 每 tick 运行 ──
    @Override
    public boolean canUpdate() {
        return true;
    }

    public boolean isWet() { return isWet; }

    public void setWet(boolean wet) {
        if (this.isWet != wet) {
            this.isWet = wet;
            markDirty();
            if (worldObj != null) {
                worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  每 tick 水源检测 + 传播
    // ══════════════════════════════════════════════════════

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) return;

        if (++tickCounter < Config.tickRate) return;
        tickCounter = 0;

        Block block = worldObj.getBlock(xCoord, yCoord, zCoord);
        if (!(block instanceof BlockCanal)) return;

        // ── 1. 检测相邻水源 → 变湿 ──
        if (!isWet && detectWaterSource()) {
            setWet(true);
            if (Config.debugLogging) {
                CanalAndWell.LOG.info("[Canal] Wet at {}, {}, {}", xCoord, yCoord, zCoord);
            }
        }

        // ── 2. 湿润时传播到相邻干燥水渠 + 生成流水 ──
        if (isWet) {
            propagateWetness();
            spawnFlowingWater();
        }
    }

    // ══════════════════════════════════════════════════════
    //  水源检测 —— 检查四个水平方向
    // ══════════════════════════════════════════════════════

    public boolean detectWaterSource() {
        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            if (dir == ForgeDirection.UP || dir == ForgeDirection.DOWN) continue;

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            Block neighbor = worldObj.getBlock(nx, ny, nz);

            // 水源方块 (meta=0)
            if (neighbor == Blocks.water && worldObj.getBlockMetadata(nx, ny, nz) == 0) {
                return true;
            }

            // 已湿润的相邻水渠
            if (neighbor instanceof BlockCanal) {
                TileEntity te = worldObj.getTileEntity(nx, ny, nz);
                if (te instanceof TileEntityCanal && ((TileEntityCanal) te).isWet()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════
    //  传播
    // ══════════════════════════════════════════════════════

    private void propagateWetness() {
        for (ForgeDirection dir : new ForgeDirection[] {
                ForgeDirection.NORTH, ForgeDirection.SOUTH,
                ForgeDirection.WEST,  ForgeDirection.EAST}) {

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            Block neighbor = worldObj.getBlock(nx, ny, nz);
            if (!(neighbor instanceof BlockCanal)) continue;

            TileEntity te = worldObj.getTileEntity(nx, ny, nz);
            if (!(te instanceof TileEntityCanal)) continue;

            TileEntityCanal ct = (TileEntityCanal) te;
            if (!ct.isWet()) {
                ct.setWet(true);
            }
        }
    }

    private void spawnFlowingWater() {
        for (ForgeDirection dir : new ForgeDirection[] {
                ForgeDirection.NORTH, ForgeDirection.SOUTH,
                ForgeDirection.WEST,  ForgeDirection.EAST}) {

            int nx = xCoord + dir.offsetX;
            int ny = yCoord + dir.offsetY;
            int nz = zCoord + dir.offsetZ;

            if (!worldObj.isAirBlock(nx, ny, nz)) continue;

            Block below = worldObj.getBlock(nx, ny - 1, nz);
            if (below instanceof BlockCanal || below == Blocks.water) {
                worldObj.setBlock(nx, ny, nz, Blocks.water, 1, 3);
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  NBT
    // ══════════════════════════════════════════════════════

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        isWet = nbt.getBoolean("isWet");
        tickCounter = nbt.getInteger("tickCounter");
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean("isWet", isWet);
        nbt.setInteger("tickCounter", tickCounter);
    }
}
